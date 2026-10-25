import { afterEach, describe, expect, it, vi } from 'vitest'
import { cancelSpeech, playSpeech, speechAllowed, speechPlaybackState, updateSpeechPreferences, waitForSpeechPlayback } from './speech-playback'

let latestAudio: { volume: number, playCount: number, emit: (event: string) => void } | undefined
const audios: Array<{ volume: number, playCount: number, emit: (event: string) => void }> = []
const pendingResumes: Array<{ resolve: () => void, reject: (error: Error) => void }> = []
const contexts: Array<{ close: ReturnType<typeof vi.fn> }> = []

class FakeAudio {
  volume = 1
  private listeners = new Map<string, () => void>()

  constructor(_source: string) { latestAudio = this; audios.push(this) }
  addEventListener(event: string, listener: () => void) { this.listeners.set(event, listener) }
  playCount = 0
  async play() { this.playCount++ }
  pause() {}
  set src(_value: string) {}
  emit(event: string) { this.listeners.get(event)?.() }
}

const preferences = { ttsEnabled: true, volume: 0.8, speed: 1, ambientEnabled: true, galgameEnabled: true }

afterEach(() => {
  cancelSpeech()
  latestAudio = undefined
  audios.length = 0
  pendingResumes.length = 0
  contexts.length = 0
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('surface speech preferences', () => {
  it('discards synthesized unsolicited audio when activity expires during TTS', async () => {
    let release!: (value: unknown) => void
    const fetch = vi.fn(() => new Promise(resolve => { release = resolve }))
    vi.stubGlobal('fetch', fetch)
    vi.stubGlobal('Audio', FakeAudio)
    let active = true
    const playback = playSpeech({ text: '主动搭话', voiceStyle: 'neutral', intensity: 'calm', preferences,
      surface: 'ambient', canStart: () => active })
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    active = false
    release({ ok: true, blob: async () => new Blob(['audio']) })
    await playback
    expect(audios).toHaveLength(0)
    expect(speechPlaybackState.busy).toBe(false)
    expect(speechPlaybackState.error).toBe('')
  })

  it('rechecks native permission after AudioContext resume immediately before playback', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['audio']) }))
    vi.stubGlobal('Audio', FakeAudio)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:presence-check')
    vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
    class DelayedAudioContext {
      destination = {}
      close = vi.fn().mockResolvedValue(undefined)
      createMediaElementSource() { return { connect() {} } }
      createAnalyser() { return { fftSize: 256, getByteTimeDomainData(values: Uint8Array) { values.fill(128) }, connect() {} } }
      resume() { return new Promise<void>((resolve, reject) => pendingResumes.push({ resolve, reject })) }
    }
    vi.stubGlobal('AudioContext', DelayedAudioContext)
    let active = true
    const playback = playSpeech({ text: '主动新闻', voiceStyle: 'neutral', intensity: 'calm', preferences,
      surface: 'ambient', canStart: async () => active })
    await vi.waitFor(() => expect(pendingResumes).toHaveLength(1))
    active = false
    pendingResumes[0].resolve()
    await playback
    expect(audios[0]?.playCount).toBe(0)
    expect(speechPlaybackState.error).toBe('')
  })

  it('applies independent surface switches and global mute', () => {
    expect(speechAllowed({ ...preferences, speed: 1.4, galgameEnabled: false }, 'ambient')).toBe(true)
    expect(speechAllowed({ ...preferences, speed: 1.4, galgameEnabled: false }, 'galgame')).toBe(false)
    expect(speechAllowed({ ...preferences, ttsEnabled: false }, 'ambient')).toBe(false)
  })

  it('waits until the generated audio has finished playing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['audio']) }))
    vi.stubGlobal('Audio', FakeAudio)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:meguri-voice')

    const playback = playSpeech({ text: '你好', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient', requestId: 'voice-test' })
    await vi.waitFor(() => expect(speechPlaybackState.speaking).toBe(true))
    expect(speechPlaybackState.busy).toBe(true)

    let finished = false
    const waiting = waitForSpeechPlayback().then(() => { finished = true })
    await Promise.resolve()
    expect(finished).toBe(false)

    latestAudio?.emit('ended')
    await Promise.all([playback, waiting])
    expect(finished).toBe(true)
    expect(speechPlaybackState.speaking).toBe(false)
    expect(speechPlaybackState.busy).toBe(false)
  })

  it('resolves its wait when playback is cancelled', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['audio']) }))
    vi.stubGlobal('Audio', FakeAudio)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:meguri-voice')

    const playback = playSpeech({ text: '你好', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient', requestId: 'voice-test-cancel' })
    await vi.waitFor(() => expect(speechPlaybackState.speaking).toBe(true))
    const waiting = waitForSpeechPlayback()
    cancelSpeech()

    await expect(Promise.all([playback, waiting])).resolves.toEqual([undefined, undefined])
    expect(speechPlaybackState.speaking).toBe(false)
    expect(speechPlaybackState.busy).toBe(false)
  })

  it('waits for a replacement speech request after the previous audio is cancelled', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['audio']) })
    vi.stubGlobal('fetch', fetch)
    vi.stubGlobal('Audio', FakeAudio)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:meguri-voice')

    const firstPlayback = playSpeech({ text: '第一句', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient', requestId: 'voice-first' })
    await vi.waitFor(() => expect(speechPlaybackState.speaking).toBe(true))
    const waiting = waitForSpeechPlayback()
    const replacementPlayback = playSpeech({ text: '第二句', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient', requestId: 'voice-second' })
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(speechPlaybackState.speaking).toBe(true))

    let finished = false
    void waiting.then(() => { finished = true })
    await Promise.resolve()
    expect(finished).toBe(false)

    latestAudio?.emit('ended')
    await Promise.all([firstPlayback, replacementPlayback, waiting])
    expect(finished).toBe(true)
  })

  it('does not let a cancelled AudioContext resume close or mutate its replacement', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['audio']) })
    vi.stubGlobal('fetch', fetch)
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
    class DelayedAudioContext {
      destination = {}
      close = vi.fn().mockResolvedValue(undefined)
      constructor() { contexts.push(this) }
      createMediaElementSource() { return { connect() {} } }
      createAnalyser() { return { fftSize: 256, getByteTimeDomainData(values: Uint8Array) { values.fill(128) }, connect() {} } }
      resume() { return new Promise<void>((resolve, reject) => pendingResumes.push({ resolve, reject })) }
    }
    vi.stubGlobal('AudioContext', DelayedAudioContext)

    const first = playSpeech({ text: '旧语音', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient' })
    await vi.waitFor(() => expect(pendingResumes).toHaveLength(1))
    cancelSpeech()
    const replacement = playSpeech({ text: '新语音', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient' })
    await vi.waitFor(() => expect(pendingResumes).toHaveLength(2))

    pendingResumes[0].resolve()
    await Promise.resolve()
    expect(audios[0]?.playCount).toBe(0)
    expect(contexts[1].close).not.toHaveBeenCalled()
    pendingResumes[1].resolve()
    await vi.waitFor(() => expect(speechPlaybackState.speaking).toBe(true))
    expect(contexts[1].close).not.toHaveBeenCalled()
    latestAudio?.emit('ended')
    await Promise.all([first, replacement])
    expect(audios[0]?.playCount).toBe(0)
    expect(audios[1]?.playCount).toBe(1)
  })

  it('does not start stale audio when a cancelled AudioContext resume rejects late', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['audio']) }))
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
    class DelayedAudioContext {
      destination = {}
      close = vi.fn().mockResolvedValue(undefined)
      constructor() { contexts.push(this) }
      createMediaElementSource() { return { connect() {} } }
      createAnalyser() { return { fftSize: 256, getByteTimeDomainData(values: Uint8Array) { values.fill(128) }, connect() {} } }
      resume() { return new Promise<void>((resolve, reject) => pendingResumes.push({ resolve, reject })) }
    }
    vi.stubGlobal('AudioContext', DelayedAudioContext)
    const first = playSpeech({ text: '旧语音', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient' })
    await vi.waitFor(() => expect(pendingResumes).toHaveLength(1))
    cancelSpeech()
    const replacement = playSpeech({ text: '新语音', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient' })
    await vi.waitFor(() => expect(pendingResumes).toHaveLength(2))

    pendingResumes[0].reject(new Error('resume failed after cancellation'))
    await Promise.resolve()
    expect(audios[0]?.playCount).toBe(0)
    expect(contexts[1].close).not.toHaveBeenCalled()
    pendingResumes[1].resolve()
    await vi.waitFor(() => expect(speechPlaybackState.speaking).toBe(true))
    latestAudio?.emit('ended')
    await Promise.all([first, replacement])
    expect(audios[0]?.playCount).toBe(0)
    expect(audios[1]?.playCount).toBe(1)
  })

  it('resolves its wait if synthesis fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, blob: async () => new Blob() }))

    const playback = playSpeech({ text: '你好', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient', requestId: 'voice-test-error' })
    await expect(waitForSpeechPlayback()).resolves.toBeUndefined()
    await expect(playback).resolves.toBeUndefined()
    expect(speechPlaybackState.error).toContain('本地语音生成失败')
    expect(speechPlaybackState.busy).toBe(false)
  })

  it('skips local playback when speech preferences disable it', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)

    await expect(playSpeech({ text: '你好', voiceStyle: 'neutral', intensity: 'calm', preferences: { ...preferences, ttsEnabled: false }, surface: 'ambient' }))
      .resolves.toBeUndefined()

    expect(fetch).not.toHaveBeenCalled()
    await expect(waitForSpeechPlayback()).resolves.toBeUndefined()
    expect(speechPlaybackState.busy).toBe(false)
  })

  it('plays explicit replay while automatic speech is off and survives a chat-layout preference refresh', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['audio']) })
    vi.stubGlobal('fetch', fetch)
    vi.stubGlobal('Audio', FakeAudio)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:manual-replay')
    const automaticOff = { ...preferences, ttsEnabled: false, ambientEnabled: false, volume: 0.4, speed: 1.2 }
    const playback = playSpeech({ text: '晚安。', voiceStyle: 'gentle', intensity: 'calm', preferences: automaticOff, surface: 'ambient', manual: true })
    await vi.waitFor(() => expect(speechPlaybackState.speaking).toBe(true))
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toMatchObject({ text: '晚安。', voice_style: 'gentle', speed_factor: 1.2 })
    expect(latestAudio?.volume).toBe(0.4)
    updateSpeechPreferences({ ...automaticOff, volume: 0.6 }, 'ambient')
    expect(speechPlaybackState.busy).toBe(true)
    expect(latestAudio?.volume).toBe(0.6)
    latestAudio?.emit('ended')
    await playback
    expect(speechPlaybackState.busy).toBe(false)
  })

  it('reports audio playback failures and releases playback state for retry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['audio']) }))
    vi.stubGlobal('Audio', FakeAudio)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:failed-playback')
    const playback = playSpeech({ text: '晚安。', voiceStyle: 'neutral', intensity: 'calm', preferences, surface: 'ambient', manual: true })
    await vi.waitFor(() => expect(speechPlaybackState.speaking).toBe(true))
    latestAudio?.emit('error')
    await playback
    expect(speechPlaybackState.error).toContain('语音播放失败')
    expect(speechPlaybackState.busy).toBe(false)
  })
})
