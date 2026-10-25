import { defineStore } from 'pinia'
import { watch } from 'vue'
import { remoteChatConsented } from '../../shared/experience-contract'
import {
  parseTrainingComparison,
  parseTrainingReceipt,
  type TrainingComparison,
  type TrainingReceipt,
  type TrainingSubmission,
} from '../../shared/training-contract'
import { pendingTraining, revokeTraining, submitTraining } from '../services/training-api'
import { useDesktopPreferencesStore } from './desktop-preferences'

export interface FeedbackEntry extends TrainingComparison {
  session_id: string
  selectedCandidateId: string
  feedbackText: string
  submissionKey: string
  submittedBody?: TrainingSubmission
  busy: boolean
  uncertain: boolean
  error: string
}

export interface SavedFeedback extends TrainingReceipt {
  session_id: string
  revokeKey: string
  busy: boolean
  error: string
}

const draftsKey = 'meguri.desktop.training-drafts.v1'
const receiptsKey = 'meguri.desktop.training-receipts.v1'
const submitControllers = new Map<string, AbortController>()

function entryKey(sessionId: string, turnId: string): string {
  return `${sessionId}:${turnId}`
}

function readStorage(key: string): unknown[] {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? '[]')
    return Array.isArray(value) ? value : []
  }
  catch { return [] }
}

function initialState() {
  const entries: FeedbackEntry[] = []
  const receipts: SavedFeedback[] = []
  for (const item of readStorage(draftsKey).slice(-100)) {
    try {
      const saved = item as FeedbackEntry
      if (typeof saved.session_id !== 'string' || !saved.session_id) continue
      const comparison = parseTrainingComparison(item)
      entries.push({ ...comparison, session_id: saved.session_id,
        selectedCandidateId: comparison.candidates.some(candidate => candidate.candidate_id === saved.selectedCandidateId) ? saved.selectedCandidateId : '',
        feedbackText: typeof saved.feedbackText === 'string' ? saved.feedbackText.slice(0, 2000) : '',
        submissionKey: typeof saved.submissionKey === 'string' ? saved.submissionKey : '',
        ...(saved.submittedBody ? { submittedBody: saved.submittedBody } : {}),
        busy: false, uncertain: saved.busy === true || saved.uncertain === true, error: '' })
    }
    catch { /* Ignore a malformed draft without discarding other records. */ }
  }
  for (const item of readStorage(receiptsKey).slice(-500)) {
    try {
      const saved = item as SavedFeedback
      if (typeof saved.session_id !== 'string' || !saved.session_id) continue
      const receipt = parseTrainingReceipt(item)
      receipts.push({ ...receipt, session_id: saved.session_id,
        revokeKey: typeof saved.revokeKey === 'string' ? saved.revokeKey : '', busy: false, error: '' })
    }
    catch { /* Saved receipt metadata never contains conversation content. */ }
  }
  return { entries, receipts }
}

const feedbackDefinition = defineStore('training-feedback', {
  state: () => ({ ...initialState(), refreshingSessions: [] as string[], refreshError: '', privacySyncInstalled: false }),
  getters: {
    refreshing: state => state.refreshingSessions.length > 0,
    entry: state => (sessionId: string, turnId: string) => state.entries.find(item => item.session_id === sessionId && item.turn_id === turnId),
    receipt: state => (sessionId: string, turnId: string) => state.receipts.find(item => item.session_id === sessionId && item.turn_id === turnId),
  },
  actions: {
    initializePrivacySync() {
      if (this.privacySyncInstalled) return
      this.privacySyncInstalled = true
      const preferences = useDesktopPreferencesStore()
      watch(() => remoteChatConsented(preferences.value.privacy), allowed => {
        if (!allowed) {
          for (const controller of submitControllers.values()) controller.abort()
        }
      }, { flush: 'sync' })
      watch(() => preferences.value.privacy.saveHistory, save => {
        if (!save) localStorage.removeItem(draftsKey)
      }, { flush: 'sync' })
    },
    persist() {
      if (useDesktopPreferencesStore().value.privacy.saveHistory)
        localStorage.setItem(draftsKey, JSON.stringify(this.entries.slice(-100)))
      else localStorage.removeItem(draftsKey)
      // Keep only receipt identifiers after a conversation is cleared. These
      // allow withdrawal from Settings without retaining private reply content.
      localStorage.setItem(receiptsKey, JSON.stringify(this.receipts.slice(-500).map(receipt => ({
        session_id: receipt.session_id, turn_id: receipt.turn_id, feedback_id: receipt.feedback_id,
        status: receipt.status, revokeKey: receipt.revokeKey,
      }))))
    },
    capture(sessionId: string, turnId: string, value: unknown) {
      if (this.receipt(sessionId, turnId)) return
      const comparison = parseTrainingComparison(value, turnId)
      const existing = this.entry(sessionId, turnId)
      if (existing) {
        Object.assign(existing, comparison)
      }
      else this.entries.push({ ...comparison, session_id: sessionId, selectedCandidateId: '', feedbackText: '',
        submissionKey: '', busy: false, uncertain: false, error: '' })
      this.persist()
    },
    edit(sessionId: string, turnId: string, patch: { selectedCandidateId?: string, feedbackText?: string }) {
      const entry = this.entry(sessionId, turnId)
      if (!entry || entry.busy || entry.uncertain || this.receipt(sessionId, turnId)) return
      if (patch.selectedCandidateId !== undefined
        && (patch.selectedCandidateId === '' || entry.candidates.some(candidate => candidate.candidate_id === patch.selectedCandidateId)))
        entry.selectedCandidateId = patch.selectedCandidateId
      if (patch.feedbackText !== undefined) entry.feedbackText = patch.feedbackText.slice(0, 2000)
      entry.error = ''
      this.persist()
    },
    rememberReceipt(sessionId: string, value: TrainingReceipt) {
      const existing = this.receipt(sessionId, value.turn_id)
      if (existing && existing.status !== 'revoked') Object.assign(existing, value)
      else this.receipts.push({ ...value, session_id: sessionId, revokeKey: '', busy: false, error: '' })
      this.entries = this.entries.filter(entry => entry.session_id !== sessionId || entry.turn_id !== value.turn_id)
      this.persist()
    },
    async refresh(sessionId: string) {
      if (this.refreshingSessions.includes(sessionId)) return
      this.refreshingSessions.push(sessionId)
      this.refreshError = ''
      try {
        const result = await pendingTraining(sessionId)
        for (const receipt of result.receipts) this.rememberReceipt(sessionId, receipt)
        const pendingIds = new Set(result.pending.map(entry => entry.turn_id))
        this.entries = this.entries.filter(entry => entry.session_id !== sessionId || entry.busy || pendingIds.has(entry.turn_id))
        for (const comparison of result.pending) {
          this.capture(sessionId, comparison.turn_id, comparison)
          const entry = this.entry(sessionId, comparison.turn_id)
          if (entry && !entry.busy) entry.uncertain = false
        }
        this.persist()
      }
      catch (error) {
        this.refreshError = error instanceof Error ? error.message : '反馈状态读取失败，请稍后刷新。'
      }
      finally { this.refreshingSessions = this.refreshingSessions.filter(session => session !== sessionId) }
    },
    async submit(sessionId: string, turnId: string, consent: boolean): Promise<TrainingReceipt | undefined> {
      const entry = this.entry(sessionId, turnId)
      if (!entry || entry.busy || this.receipt(sessionId, turnId)) return
      if (!consent || !remoteChatConsented(useDesktopPreferencesStore().value.privacy)) {
        entry.error = '请明确同意本次数据用于离线训练；网络对话许可也需要开启。'
        return
      }
      if (entry.uncertain) {
        entry.error = '上次提交结果待核对，请先刷新状态。'
        return
      }
      const body: TrainingSubmission = { turn_id: turnId,
        ...(entry.selectedCandidateId ? { selected_candidate_id: entry.selectedCandidateId } : {}),
        feedback_text: entry.feedbackText.trim(), consent_to_training: true }
      if (!body.selected_candidate_id && !body.feedback_text) {
        entry.error = '请选择更合适的回复，或填写行为纠正。'
        return
      }
      if (!entry.submissionKey || JSON.stringify(entry.submittedBody) !== JSON.stringify(body)) {
        entry.submissionKey = `desktop-feedback-${crypto.randomUUID()}`
        entry.submittedBody = body
      }
      const controller = new AbortController()
      submitControllers.set(entryKey(sessionId, turnId), controller)
      entry.busy = true
      entry.error = ''
      this.persist()
      try {
        const receipt = await submitTraining(sessionId, body, entry.submissionKey, controller.signal)
        if (receipt.turn_id !== turnId || receipt.status !== 'training_candidate_saved')
          throw new Error('反馈回执与本轮不一致，请刷新后查看。')
        this.rememberReceipt(sessionId, receipt)
        if (controller.signal.aborted || !remoteChatConsented(useDesktopPreferencesStore().value.privacy)) {
          // A server may finish before an abort arrives. Withdraw a received
          // receipt instead of treating cancellation as unsaving remote data.
          await this.revoke(sessionId, turnId)
          return this.receipt(sessionId, turnId)
        }
        return receipt
      }
      catch (error) {
        entry.uncertain = controller.signal.aborted
        entry.error = controller.signal.aborted
          ? '许可已撤回，提交已中断；服务端保存结果待核对，请刷新状态后撤回已保存反馈。'
          : error instanceof Error ? error.message : '反馈未能保存，请稍后重试。'
      }
      finally {
        entry.busy = false
        submitControllers.delete(entryKey(sessionId, turnId))
        this.persist()
      }
    },
    async revoke(sessionId: string, turnId: string) {
      const saved = this.receipt(sessionId, turnId)
      if (!saved || saved.busy || saved.status === 'revoked') return
      saved.busy = true
      saved.error = ''
      saved.revokeKey ||= `desktop-feedback-revoke-${crypto.randomUUID()}`
      this.persist()
      try {
        const receipt = await revokeTraining(sessionId, saved.feedback_id, saved.revokeKey)
        if (receipt.turn_id !== turnId || receipt.feedback_id !== saved.feedback_id || receipt.status !== 'revoked')
          throw new Error('撤回回执与这条反馈不一致，请刷新后查看。')
        Object.assign(saved, receipt)
      }
      catch (error) { saved.error = error instanceof Error ? error.message : '反馈撤回失败，请稍后重试。' }
      finally { saved.busy = false; this.persist() }
    },
    clearLocalDrafts() {
      for (const controller of submitControllers.values()) controller.abort()
      this.entries = []
      localStorage.removeItem(draftsKey)
    },
  },
})

export function useTrainingFeedbackStore(...args: Parameters<typeof feedbackDefinition>) {
  const store = feedbackDefinition(...args)
  store.initializePrivacySync()
  return store
}
