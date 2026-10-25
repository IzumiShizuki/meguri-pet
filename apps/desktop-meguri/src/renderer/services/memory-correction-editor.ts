import { computed, reactive } from 'vue'
import type { DesktopMemoryCandidate, DesktopMemoryCorrection, DesktopMemoryDetail } from '../../shared/memory-contract'

interface Dependencies {
  load: (signal: AbortSignal) => Promise<DesktopMemoryDetail>
  submit: (body: DesktopMemoryCorrection, requestId: string, signal: AbortSignal) => Promise<DesktopMemoryCandidate>
  requestId?: () => string
}

/** One selected memory/session. Disposal rejects all late UI results. */
export function createMemoryCorrectionEditor(deps: Dependencies) {
  const state = reactive({
    detail: null as DesktopMemoryDetail | null,
    loading: false, submitting: false, editing: false, error: '',
    contentText: '', changeReason: '', submitted: null as DesktopMemoryCandidate | null,
  })
  let disposed = false
  let loadGeneration = 0
  let pendingLoad: AbortController | undefined
  let pendingSubmit: AbortController | undefined
  let lastAttempt: { signature: string, requestId: string } | undefined
  const canSubmit = computed(() => !disposed && state.detail?.can_correct && state.editing
    && !state.loading && !state.submitting && !!state.contentText.trim() && !!state.changeReason.trim()
    && [...state.contentText.trim()].length <= 4000 && [...state.changeReason.trim()].length <= 1000
    && state.contentText.trim() !== state.detail.content_text.trim())

  async function reload() {
    if (disposed || state.submitting) return
    pendingLoad?.abort()
    const controller = new AbortController()
    pendingLoad = controller
    const generation = ++loadGeneration
    state.loading = true
    state.error = ''
    try {
      const detail = await deps.load(controller.signal)
      if (!disposed && generation === loadGeneration) state.detail = detail
    }
    catch (reason) {
      if (!disposed && generation === loadGeneration)
        state.error = reason instanceof Error ? reason.message : '读取记忆失败，请重试。'
    }
    finally {
      if (!disposed && generation === loadGeneration) state.loading = false
    }
  }

  function begin() {
    if (disposed || state.loading || state.submitting || !state.detail?.can_correct) return
    state.contentText = state.detail.content_text
    state.changeReason = ''
    state.editing = true
    state.error = ''
    state.submitted = null
    lastAttempt = undefined
  }

  function cancel() {
    if (state.submitting) return
    state.editing = false
    state.contentText = ''
    state.changeReason = ''
    state.error = ''
    lastAttempt = undefined
  }

  async function save() {
    if (disposed || !canSubmit.value || !state.detail) return null
    const body: DesktopMemoryCorrection = { base_version_id: state.detail.current_version_id,
      content_text: state.contentText.trim(), change_reason: state.changeReason.trim() }
    const signature = JSON.stringify(body)
    if (lastAttempt?.signature !== signature)
      lastAttempt = { signature, requestId: deps.requestId?.() ?? `desktop-correction-${crypto.randomUUID()}` }
    const controller = new AbortController()
    pendingSubmit = controller
    state.submitting = true
    state.error = ''
    try {
      const candidate = await deps.submit(body, lastAttempt.requestId, controller.signal)
      if (disposed) return null
      state.submitted = candidate
      state.editing = false
      state.contentText = ''
      state.changeReason = ''
      return candidate
    }
    catch (reason) {
      if (!disposed) state.error = reason instanceof Error ? reason.message : '提交改正失败，请重试。'
      return null
    }
    finally {
      if (!disposed) state.submitting = false
    }
  }

  function dispose() {
    disposed = true
    loadGeneration++
    pendingLoad?.abort()
    pendingSubmit?.abort()
  }
  return { state, canSubmit, reload, begin, cancel, save, dispose }
}
