export type VoiceConversationPhase = 'idle' | 'listening' | 'thinking' | 'speaking' | 'error'

export interface VoiceRecognitionResult {
  ok: boolean
  text?: string
  code?: string
  error?: string
}

export interface VoiceConversationControllerOptions {
  recognize: () => Promise<VoiceRecognitionResult>
  cancelRecognition: () => Promise<unknown> | unknown
  send: (text: string) => Promise<boolean>
  cancelConversation: () => Promise<unknown> | unknown
  waitForSpeech: () => Promise<unknown>
  getSendError?: () => string
  waitAfterSilence?: (milliseconds: number) => Promise<unknown>
  onState?: (phase: VoiceConversationPhase, message?: string) => void
  silenceRetryDelayMs?: number
}

const DEFAULT_SILENCE_RETRY_DELAY_MS = 150

/** Owns one explicit, half-duplex voice conversation session. */
export function createVoiceConversationController(options: VoiceConversationControllerOptions) {
  let active = false
  let generation = 0
  let phase: VoiceConversationPhase = 'idle'
  let recognitionInFlight = false

  function setPhase(next: VoiceConversationPhase, message?: string) {
    phase = next
    options.onState?.(next, message)
  }

  function isCurrent(session: number) {
    return active && generation === session
  }

  function stopWithError(session: number, message: string) {
    if (!isCurrent(session))
      return
    active = false
    generation++
    recognitionInFlight = false
    setPhase('error', message)
  }

  async function run(session: number) {
    const retryDelay = Number.isFinite(options.silenceRetryDelayMs)
      ? Math.max(0, options.silenceRetryDelayMs!)
      : DEFAULT_SILENCE_RETRY_DELAY_MS
    const waitAfterSilence = options.waitAfterSilence
      ?? (milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)))
    let listeningMessage = ''

    while (isCurrent(session)) {
      setPhase('listening', listeningMessage || undefined)
      listeningMessage = ''
      recognitionInFlight = true
      let result: VoiceRecognitionResult
      try {
        result = await options.recognize()
      }
      catch (error) {
        recognitionInFlight = false
        stopWithError(session, error instanceof Error ? error.message : '语音识别失败，通话已结束。')
        return
      }
      recognitionInFlight = false
      if (!isCurrent(session))
        return

      if (!result.ok) {
        if (result.code === 'no_speech') {
          try {
            await waitAfterSilence(retryDelay)
          }
          catch { /* A failed delay must not end the active call. */ }
          continue
        }
        if (result.code === 'cancelled') {
          active = false
          generation++
          setPhase('idle')
          return
        }
        stopWithError(session, result.error || '语音识别失败，通话已结束。')
        return
      }

      const text = result.text?.trim()
      if (!text) {
        try {
          await waitAfterSilence(retryDelay)
        }
        catch { /* A failed delay must not end the active call. */ }
        continue
      }

      setPhase('thinking', text)
      let sent: boolean
      try {
        sent = await options.send(text)
      }
      catch (error) {
        sent = false
        if (isCurrent(session)) {
          void Promise.resolve().then(() => options.cancelConversation()).catch(() => {})
          stopWithError(session, error instanceof Error ? error.message : '这句话没有发送成功，通话已结束。')
        }
        return
      }
      if (!isCurrent(session))
        return
      if (!sent) {
        void Promise.resolve().then(() => options.cancelConversation()).catch(() => {})
        stopWithError(session, options.getSendError?.() || '这句话没有发送成功，通话已结束。')
        return
      }

      setPhase('speaking', text)
      let playbackNotice = ''
      try {
        const result = await options.waitForSpeech()
        if (typeof result === 'string')
          playbackNotice = result
      }
      catch {
        // TTS failures are shown by the existing playback module; the text turn remains valid.
      }
      if (!isCurrent(session))
        return
      listeningMessage = playbackNotice
    }
  }

  function start() {
    if (active)
      return false
    active = true
    const session = ++generation
    void run(session)
    return true
  }

  async function stop() {
    if (!active)
      return
    const shouldCancelRecognition = recognitionInFlight
    const shouldCancelConversation = phase === 'thinking' || phase === 'speaking'
    active = false
    generation++
    recognitionInFlight = false
    setPhase('idle')

    const cleanup: Promise<unknown>[] = []
    if (shouldCancelRecognition)
      cleanup.push(Promise.resolve().then(() => options.cancelRecognition()))
    if (shouldCancelConversation)
      cleanup.push(Promise.resolve().then(() => options.cancelConversation()))
    await Promise.allSettled(cleanup)
  }

  return {
    start,
    stop,
    isActive: () => active,
    getPhase: () => phase,
  }
}
