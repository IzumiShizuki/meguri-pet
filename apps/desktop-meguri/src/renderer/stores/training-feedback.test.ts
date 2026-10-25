import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PRIVACY_POLICY_VERSION } from '../../shared/experience-contract'
import { parseTrainingComparison, type TrainingReceipt } from '../../shared/training-contract'
import { useDesktopPreferencesStore } from './desktop-preferences'
import { useTrainingFeedbackStore } from './training-feedback'

const { pendingMock, submitMock, revokeMock } = vi.hoisted(() => ({ pendingMock: vi.fn(), submitMock: vi.fn(), revokeMock: vi.fn() }))
vi.mock('../services/training-api', () => ({ pendingTraining: pendingMock, submitTraining: submitMock, revokeTraining: revokeMock }))

const storage = new Map<string, string>()
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => storage.set(key, value),
  removeItem: (key: string) => storage.delete(key),
} })

const comparison = { turn_id: 'turn-a', comparison_id: 'comparison-a', training_mode: true,
  candidates: [
    { candidate_id: 'candidate-a', response: { reply: '第一种回答' } },
    { candidate_id: 'candidate-b', response: { reply: '第二种回答' } },
  ] }
const receipt: TrainingReceipt = { turn_id: 'turn-a', feedback_id: 'feedback-a', status: 'training_candidate_saved',
  selected_candidate_id: 'candidate-b', response: { reply: '第二种回答' } }

function prepared() {
  const store = useTrainingFeedbackStore()
  store.capture('session-a', 'turn-a', comparison)
  store.edit('session-a', 'turn-a', { selectedCandidateId: 'candidate-b', feedbackText: '先解释原因。' })
  return store
}

describe('native training consent, recovery and withdrawal', () => {
  beforeEach(() => {
    storage.clear()
    setActivePinia(createPinia())
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.acceptedPolicyVersion = PRIVACY_POLICY_VERSION
    preferences.value.privacy.remoteChatAllowed = true
    pendingMock.mockReset().mockResolvedValue({ pending: [], receipts: [] })
    submitMock.mockReset().mockResolvedValue(receipt)
    revokeMock.mockReset().mockResolvedValue({ ...receipt, status: 'revoked' })
  })

  it('does not save or imply consent from the training-mode setting', async () => {
    useDesktopPreferencesStore().value.training.enabled = true
    const store = prepared()
    expect(await store.submit('session-a', 'turn-a', false)).toBeUndefined()
    expect(submitMock).not.toHaveBeenCalled()
    expect(store.receipts).toEqual([])
    useDesktopPreferencesStore().value.privacy.remoteChatAllowed = false
    expect(await store.submit('session-a', 'turn-a', true)).toBeUndefined()
    expect(submitMock).not.toHaveBeenCalled()
  })

  it('retains a stable submission key over save failure and renderer restart', async () => {
    const store = prepared()
    submitMock.mockRejectedValueOnce(new Error('未能保存'))
    expect(await store.submit('session-a', 'turn-a', true)).toBeUndefined()
    expect(store.receipts).toHaveLength(0)
    const originalKey = submitMock.mock.calls[0][2]
    expect(store.entry('session-a', 'turn-a')?.feedbackText).toBe('先解释原因。')
    setActivePinia(createPinia())
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.acceptedPolicyVersion = PRIVACY_POLICY_VERSION
    preferences.value.privacy.remoteChatAllowed = true
    const restarted = useTrainingFeedbackStore()
    await restarted.submit('session-a', 'turn-a', true)
    expect(submitMock.mock.calls[1][2]).toBe(originalKey)
    expect(restarted.receipt('session-a', 'turn-a')?.feedback_id).toBe('feedback-a')
    expect(restarted.entries).toEqual([])
  })

  it('changes retry identity only when the user changes the submitted content', async () => {
    const store = prepared()
    submitMock.mockRejectedValueOnce(new Error('未能保存'))
    await store.submit('session-a', 'turn-a', true)
    store.edit('session-a', 'turn-a', { feedbackText: '先说明限制。' })
    await store.submit('session-a', 'turn-a', true)
    expect(submitMock.mock.calls[1][2]).not.toBe(submitMock.mock.calls[0][2])
  })

  it('recovers candidates and saved receipts using each requested session', async () => {
    const store = useTrainingFeedbackStore()
    pendingMock.mockResolvedValueOnce({ pending: [comparison], receipts: [] })
    await store.refresh('session-a')
    expect(store.entry('session-a', 'turn-a')?.candidates).toHaveLength(2)
    expect(pendingMock).toHaveBeenCalledWith('session-a')
    pendingMock.mockResolvedValueOnce({ pending: [], receipts: [receipt] })
    await store.refresh('session-a')
    expect(store.entry('session-a', 'turn-a')).toBeUndefined()
    setActivePinia(createPinia())
    const restarted = useTrainingFeedbackStore()
    await restarted.refresh('session-b')
    await restarted.revoke('session-a', 'turn-a')
    expect(revokeMock).toHaveBeenCalledWith('session-a', 'feedback-a', expect.any(String))
    expect(restarted.receipt('session-a', 'turn-a')?.status).toBe('revoked')
  })

  it('keeps withdrawal retry keys and rejects mismatched success receipts', async () => {
    const store = prepared()
    submitMock.mockResolvedValueOnce({ ...receipt, turn_id: 'foreign-turn' })
    await store.submit('session-a', 'turn-a', true)
    expect(store.receipts).toHaveLength(0)
    expect(store.entry('session-a', 'turn-a')?.error).toContain('不一致')
    await store.submit('session-a', 'turn-a', true)
    revokeMock.mockRejectedValueOnce(new Error('暂时断开'))
    await store.revoke('session-a', 'turn-a')
    await store.revoke('session-a', 'turn-a')
    expect(revokeMock.mock.calls[1][2]).toBe(revokeMock.mock.calls[0][2])
    expect(store.receipt('session-a', 'turn-a')?.status).toBe('revoked')
  })

  it('saves only receipt identifiers when local history is disabled', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.saveHistory = false
    const store = prepared()
    await store.submit('session-a', 'turn-a', true)
    expect(storage.has('meguri.desktop.training-drafts.v1')).toBe(false)
    const saved = storage.get('meguri.desktop.training-receipts.v1') ?? ''
    expect(saved).toContain('feedback-a')
    expect(saved).not.toContain('第二种回答')
    expect(saved).not.toContain('先解释原因')
    expect(saved).not.toContain('response')
  })

  it('aborts in-flight saving when network permission is withdrawn and retracts a late receipt', async () => {
    let complete!: (value: TrainingReceipt) => void
    submitMock.mockImplementationOnce(() => new Promise<TrainingReceipt>(resolve => { complete = resolve }))
    const store = prepared()
    const saving = store.submit('session-a', 'turn-a', true)
    useDesktopPreferencesStore().value.privacy.remoteChatAllowed = false
    expect((submitMock.mock.calls[0][3] as AbortSignal).aborted).toBe(true)
    complete(receipt)
    await saving
    expect(revokeMock).toHaveBeenCalled()
    expect(store.receipt('session-a', 'turn-a')?.status).toBe('revoked')
  })

  it('shows uncertainty after abort rather than claiming the server did not save', async () => {
    submitMock.mockImplementationOnce((_session, _body, _key, signal: AbortSignal) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))
    const store = prepared()
    const saving = store.submit('session-a', 'turn-a', true)
    useDesktopPreferencesStore().value.privacy.remoteChatAllowed = false
    await saving
    expect(store.entry('session-a', 'turn-a')?.uncertain).toBe(true)
    expect(store.entry('session-a', 'turn-a')?.error).toContain('保存结果待核对')
    expect(store.receipts).toHaveLength(0)
    pendingMock.mockResolvedValueOnce({ pending: [], receipts: [receipt] })
    await store.refresh('session-a')
    expect(store.receipt('session-a', 'turn-a')?.feedback_id).toBe('feedback-a')
  })

  it('clears private local drafts while preserving withdrawal receipts', async () => {
    const store = prepared()
    await store.submit('session-a', 'turn-a', true)
    store.capture('session-b', 'turn-b', { ...comparison, turn_id: 'turn-b' })
    store.clearLocalDrafts()
    expect(store.entries).toEqual([])
    expect(storage.has('meguri.desktop.training-drafts.v1')).toBe(false)
    expect(store.receipt('session-a', 'turn-a')?.feedback_id).toBe('feedback-a')
  })

  it('rejects malformed optional comparison contents and duplicate candidate IDs', () => {
    expect(() => parseTrainingComparison({ ...comparison, candidates: [{ candidate_id: 'a', response: { reply: '' } }] })).toThrow()
    expect(() => parseTrainingComparison({ ...comparison, candidates: [comparison.candidates[0], comparison.candidates[0]] })).toThrow()
  })
})
