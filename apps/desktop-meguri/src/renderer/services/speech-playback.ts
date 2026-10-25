import type { DesktopPreferences } from '../../shared/desktop-contract'
import { reactive } from 'vue'

export const speechPlaybackState = reactive({ speaking: false, busy: false, mouthOpen: 0, error: '' })
let generation = 0
let pending: AbortController | undefined
let audio: HTMLAudioElement | undefined
let objectUrl: string | undefined
let frame: number | undefined
let context: AudioContext | undefined
let speakingListener: ((speaking: boolean) => void) | undefined
let finishPlayback: (() => void) | undefined
let speechPlaybackTask: Promise<void> = Promise.resolve()
let manualPlayback = false

export async function waitForSpeechPlayback() {
  while (true) {
    const observedTask = speechPlaybackTask
    await observedTask
    if (observedTask === speechPlaybackTask && !speechPlaybackState.busy)
      return
  }
}

export function cancelSpeech() {
  generation++
  manualPlayback = false
  pending?.abort()
  pending = undefined
  if (audio) { audio.pause(); audio.src = ''; audio = undefined }
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = undefined
  if (frame) cancelAnimationFrame(frame)
  frame = undefined
  if (context) void context.close().catch(() => {})
  context = undefined
  speechPlaybackState.speaking = false
  speechPlaybackState.busy = false
  speechPlaybackState.mouthOpen = 0
  speakingListener?.(false)
  speakingListener = undefined
  finishPlayback?.()
  finishPlayback = undefined
}

export function speechAllowed(preferences: DesktopPreferences['speech'], surface: 'ambient' | 'galgame') {
  return preferences.ttsEnabled && (surface === 'galgame' ? preferences.galgameEnabled : preferences.ambientEnabled)
}

export function updateSpeechPreferences(preferences: DesktopPreferences['speech'], surface: 'ambient' | 'galgame') {
  if (!manualPlayback && !speechAllowed(preferences, surface)) cancelSpeech()
  else if (audio) audio.volume = Math.max(0, Math.min(1, preferences.volume))
}

/** One speech request at a time; resolves after playback ends or is cancelled. */
export function playSpeech(input: {
  text: string
  voiceStyle: string
  intensity: string
  preferences: DesktopPreferences['speech']
  surface: 'ambient' | 'galgame'
  requestId?: string
  manual?: boolean
  onSpeaking?: (speaking: boolean) => void
}): Promise<void> {
  cancelSpeech()
  speechPlaybackState.error = ''
  if ((!input.manual && !speechAllowed(input.preferences, input.surface)) || !input.text.trim()) {
    speechPlaybackState.busy = false
    speechPlaybackTask = Promise.resolve()
    return speechPlaybackTask
  }
  const current = generation
  manualPlayback = Boolean(input.manual)
  const controller = new AbortController()
  pending = controller
  speechPlaybackState.busy = true
  speakingListener = input.onSpeaking
  const timeout = setTimeout(() => controller.abort(), 30000)
  let resolvePlayback!: () => void
  const playbackFinished = new Promise<void>(resolve => { resolvePlayback = resolve })
  let finished = false
  const finishCurrentPlayback = () => {
    if (finished) return
    finished = true
    if (finishPlayback === finishCurrentPlayback)
      finishPlayback = undefined
    resolvePlayback()
  }
  finishPlayback = finishCurrentPlayback

  speechPlaybackTask = (async () => {
    try {
      const response = await fetch('http://127.0.0.1:9880/tts/synthesize', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
        body: JSON.stringify({ text: input.text.slice(0, 1000), voice_style: input.voiceStyle, expression_intensity: input.intensity, request_id: input.requestId ?? `desktop-${crypto.randomUUID()}`, speed_factor: input.preferences.speed }),
      })
      if (!response.ok) throw new Error('本地语音生成失败；文字回复可继续查看。')
      const blob = await response.blob()
      if (current !== generation) return
      objectUrl = URL.createObjectURL(blob)
      const nextAudio = new Audio(objectUrl)
      audio = nextAudio
      nextAudio.volume = Math.max(0, Math.min(1, input.preferences.volume))
      let requestContext: AudioContext | undefined
      try {
        requestContext = new AudioContext()
        context = requestContext
        const source = requestContext.createMediaElementSource(nextAudio)
        const analyser = requestContext.createAnalyser()
        analyser.fftSize = 256
        source.connect(analyser)
        analyser.connect(context.destination)
        const samples = new Uint8Array(analyser.fftSize)
        function sample() {
          if (current !== generation) return
          analyser.getByteTimeDomainData(samples)
          const rms = Math.sqrt(samples.reduce((sum, value) => sum + ((value - 128) / 128) ** 2, 0) / samples.length)
          speechPlaybackState.mouthOpen = Math.min(1, rms * 5)
          frame = requestAnimationFrame(sample)
        }
        await requestContext.resume()
        if (current !== generation) {
          if (context === requestContext) context = undefined
          void requestContext.close().catch(() => {})
          return
        }
        sample()
      }
      catch {
        if (context === requestContext) context = undefined
        if (requestContext) void requestContext.close().catch(() => {})
      }
      if (current !== generation) return
      const finish = () => { if (current === generation) cancelSpeech() }
      nextAudio.addEventListener('ended', finish, { once: true })
      nextAudio.addEventListener('error', () => {
        if (current !== generation) return
        speechPlaybackState.error = '语音播放失败，请重试。'
        cancelSpeech()
      }, { once: true })
      await nextAudio.play()
      clearTimeout(timeout)
      if (current !== generation) return
      speechPlaybackState.speaking = true
      input.onSpeaking?.(true)
      await playbackFinished
    }
    catch (error) {
      if (current === generation) {
        const wasAborted = controller.signal.aborted
        cancelSpeech()
        speechPlaybackState.error = wasAborted
          ? '本地语音生成超时，请检查语音服务后重试。'
          : error instanceof TypeError ? '无法连接本地语音服务，请启动语音服务后重试。'
            : error instanceof Error ? error.message : '本地语音暂时不可用。'
      }
    }
    finally {
      finishCurrentPlayback()
      clearTimeout(timeout)
      if (pending === controller) pending = undefined
      if (current === generation) speechPlaybackState.busy = false
    }
  })()
  return speechPlaybackTask
}
