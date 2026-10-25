import { describe, expect, it, vi } from 'vitest'
import type { DesktopMemoryCandidate, DesktopMemoryDetail } from '../../shared/memory-contract'
import { createMemoryCorrectionEditor } from './memory-correction-editor'

const detail: DesktopMemoryDetail = { memory_id: 'memory-a', memory_type: 'user_preference', status: 'active',
  updated_at: '2026-10-23T00:00:00Z', current_version_id: 'version-a', content_text: '用户喜欢茶', version_no: 1,
  change_reason: '已确认', source: { client_id: null, session_id: null, kind: null }, can_correct: true }
const candidate: DesktopMemoryCandidate = { candidate_id: 'candidate-a', content_text: '用户喜欢咖啡',
  memory_type: 'user_preference', status: 'pending_review', updated_at: detail.updated_at, merge_policy: 'supersede' }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function fixture() {
  const load = vi.fn().mockResolvedValue(detail)
  const submit = vi.fn().mockResolvedValue(candidate)
  let request = 0
  const editor = createMemoryCorrectionEditor({ load, submit, requestId: () => `request-${++request}` })
  return { editor, load, submit }
}
async function edit(f: ReturnType<typeof fixture>) {
  await f.editor.reload()
  f.editor.begin()
  f.editor.state.contentText = ' 用户喜欢咖啡 '
  f.editor.state.changeReason = ' 更新偏好 '
}

describe('selected formal memory correction editor', () => {
  it('requires changed bounded text and reason before proposing, never approves', async () => {
    const f = fixture()
    await f.editor.reload(); f.editor.begin()
    f.editor.state.changeReason = '原因'
    expect(f.editor.canSubmit.value).toBe(false)
    f.editor.state.contentText = 'x'.repeat(4001)
    expect(f.editor.canSubmit.value).toBe(false)
    f.editor.state.contentText = '用户喜欢咖啡'
    f.editor.state.changeReason = ' '
    expect(await f.editor.save()).toBeNull()
    f.editor.state.changeReason = '更新偏好'
    expect(await f.editor.save()).toEqual(candidate)
    expect(f.submit).toHaveBeenCalledWith({ base_version_id: 'version-a', content_text: '用户喜欢咖啡', change_reason: '更新偏好' }, 'request-1', expect.any(AbortSignal))
    expect(f.editor.state.detail?.content_text).toBe('用户喜欢茶')
    expect(f.editor.state.submitted?.status).toBe('pending_review')
    expect(f.editor.state.editing).toBe(false)
  })

  it('keeps drafts and the identical request ID after ambiguous timeout', async () => {
    const f = fixture(); await edit(f)
    f.submit.mockRejectedValueOnce(new Error('网络中断'))
    await f.editor.save()
    expect(f.editor.state.error).toBe('网络中断')
    expect(f.editor.state.contentText).toBe(' 用户喜欢咖啡 ')
    expect(f.editor.state.editing).toBe(true)
    await f.editor.save()
    expect(f.submit.mock.calls.map(call => call[1])).toEqual(['request-1', 'request-1'])
  })

  it('reloads changed base version explicitly while retaining typed correction', async () => {
    const f = fixture(); await edit(f)
    f.submit.mockRejectedValueOnce(new Error('版本已变化'))
    await f.editor.save()
    f.load.mockResolvedValueOnce({ ...detail, current_version_id: 'version-b', version_no: 2, content_text: '用户喜欢乌龙茶' })
    await f.editor.reload()
    expect(f.editor.state.contentText).toBe(' 用户喜欢咖啡 ')
    expect(f.editor.state.detail?.version_no).toBe(2)
    await f.editor.save()
    expect(f.submit.mock.calls[1]?.[0].base_version_id).toBe('version-b')
    expect(f.submit.mock.calls[1]?.[1]).toBe('request-2')
  })

  it('uses a fresh operation after text changes and suppresses duplicate in-flight submits', async () => {
    const f = fixture(); await edit(f)
    f.submit.mockRejectedValueOnce(new Error('重试'))
    await f.editor.save()
    f.editor.state.contentText = '用户喜欢红茶'
    const pending = deferred<DesktopMemoryCandidate>()
    f.submit.mockReturnValueOnce(pending.promise)
    const saving = f.editor.save()
    expect(await f.editor.save()).toBeNull()
    expect(f.submit.mock.calls[1]?.[1]).toBe('request-2')
    pending.resolve(candidate); await saving
    expect(f.submit).toHaveBeenCalledTimes(2)
  })

  it('ignores older detail responses and disposed session results', async () => {
    const f = fixture()
    const old = deferred<DesktopMemoryDetail>()
    f.load.mockReturnValueOnce(old.promise)
    const loading = f.editor.reload()
    await f.editor.reload()
    old.resolve({ ...detail, content_text: 'stale' }); await loading
    expect(f.editor.state.detail?.content_text).toBe('用户喜欢茶')
    f.editor.begin(); f.editor.state.contentText = '咖啡'; f.editor.state.changeReason = '原因'
    const pending = deferred<DesktopMemoryCandidate>()
    f.submit.mockReturnValueOnce(pending.promise)
    const saving = f.editor.save()
    const signal = f.submit.mock.calls[0]?.[2] as AbortSignal
    f.editor.dispose()
    expect(signal.aborted).toBe(true)
    pending.resolve(candidate)
    expect(await saving).toBeNull()
    expect(f.editor.state.submitted).toBeNull()
  })

  it('allows retrying an initial read failure and disables deleted memories', async () => {
    const f = fixture()
    f.load.mockRejectedValueOnce(new Error('读取失败'))
    await f.editor.reload()
    expect(f.editor.state.error).toBe('读取失败')
    expect(f.editor.state.detail).toBeNull()
    f.load.mockResolvedValueOnce({ ...detail, can_correct: false, status: 'deleted' })
    await f.editor.reload(); f.editor.begin()
    expect(f.editor.state.editing).toBe(false)
    expect(f.editor.canSubmit.value).toBe(false)
  })

  it('never starts another save or displays late detail after disposal', async () => {
    const f = fixture(); await edit(f)
    expect(f.editor.canSubmit.value).toBe(true)
    const pending = deferred<DesktopMemoryDetail>()
    f.load.mockReturnValueOnce(pending.promise)
    const loading = f.editor.reload()
    f.editor.dispose()
    pending.resolve({ ...detail, content_text: 'late session data' })
    await loading
    expect(f.editor.state.detail?.content_text).toBe(detail.content_text)
    expect(await f.editor.save()).toBeNull()
    expect(f.submit).not.toHaveBeenCalled()
  })
})
