import { describe, expect, it, vi } from 'vitest'

import { createVoiceConversationController, type VoiceConversationControllerOptions, type VoiceConversationPhase } from './voice-conversation'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function createController(overrides: Partial<VoiceConversationControllerOptions> = {}) {
  const phases: Array<{ phase: VoiceConversationPhase, message?: string }> = []
  const options: VoiceConversationControllerOptions = {
    recognize: vi.fn().mockResolvedValue({ ok: false, code: 'no_speech' }),
    cancelRecognition: vi.fn(),
    send: vi.fn().mockResolvedValue(true),
    cancelConversation: vi.fn(),
    waitForSpeech: vi.fn().mockResolvedValue(undefined),
    waitAfterSilence: vi.fn().mockResolvedValue(undefined),
    onState: (phase, message) => phases.push({ phase, message }),
    ...overrides,
  }
  const controller = createVoiceConversationController(options)
  return { controller, options, phases }
}

async function settleMicrotasks() {
  await Promise.resolve()
  await Promise.resolve()
  await new Promise(resolve => setImmediate(resolve))
}

describe('long voice conversation controller', () => {
  it('sends each completed transcript once and waits for TTS before listening again', async () => {
    const speech = deferred<void>()
    const secondRecognition = deferred<{ ok: boolean, code?: string }>()
    const recognize = vi.fn()
      .mockResolvedValueOnce({ ok: true, text: '你好，Meguri' })
      .mockImplementationOnce(() => secondRecognition.promise)
    const { controller, options, phases } = createController({
      recognize,
      waitForSpeech: vi.fn(() => speech.promise),
    })

    expect(controller.start()).toBe(true)
    await settleMicrotasks()

    expect(options.send).toHaveBeenCalledOnce()
    expect(options.send).toHaveBeenCalledWith('你好，Meguri')
    expect(options.waitForSpeech).toHaveBeenCalledOnce()
    expect(recognize).toHaveBeenCalledOnce()
    expect(phases.some(entry => entry.phase === 'thinking' && entry.message === '你好，Meguri')).toBe(true)
    expect(controller.getPhase()).toBe('speaking')

    speech.resolve()
    await settleMicrotasks()
    expect(recognize).toHaveBeenCalledTimes(2)
    expect(controller.getPhase()).toBe('listening')

    await controller.stop()
    secondRecognition.resolve({ ok: false, code: 'cancelled' })
    await settleMicrotasks()
    expect(options.cancelRecognition).toHaveBeenCalledOnce()
    expect(controller.getPhase()).toBe('idle')
  })

  it('retries a no-speech recognition cycle without ending the call', async () => {
    const activeRecognition = deferred<{ ok: boolean, code?: string }>()
    const recognize = vi.fn()
      .mockResolvedValueOnce({ ok: false, code: 'no_speech' })
      .mockImplementationOnce(() => activeRecognition.promise)
    const { controller, options } = createController({ recognize })

    controller.start()
    await settleMicrotasks()

    expect(options.waitAfterSilence).toHaveBeenCalledOnce()
    expect(recognize).toHaveBeenCalledTimes(2)
    expect(controller.isActive()).toBe(true)
    expect(controller.getPhase()).toBe('listening')

    await controller.stop()
    activeRecognition.resolve({ ok: false, code: 'cancelled' })
    await settleMicrotasks()
  })

  it('stops and exposes fatal recognizer errors', async () => {
    const { controller, options, phases } = createController({
      recognize: vi.fn().mockResolvedValue({ ok: false, code: 'engine_unavailable', error: '识别引擎不可用' }),
    })

    controller.start()
    await settleMicrotasks()

    expect(controller.isActive()).toBe(false)
    expect(controller.getPhase()).toBe('error')
    expect(phases.at(-1)).toEqual({ phase: 'error', message: '识别引擎不可用' })
    expect(options.send).not.toHaveBeenCalled()
  })

  it('prevents duplicate starts and ignores a late result after hang-up', async () => {
    const recognition = deferred<{ ok: boolean, text?: string }>()
    const { controller, options } = createController({
      recognize: vi.fn(() => recognition.promise),
    })

    expect(controller.start()).toBe(true)
    expect(controller.start()).toBe(false)
    await controller.stop()
    recognition.resolve({ ok: true, text: '迟到的语音' })
    await settleMicrotasks()

    expect(options.cancelRecognition).toHaveBeenCalledOnce()
    expect(options.send).not.toHaveBeenCalled()
    expect(controller.getPhase()).toBe('idle')
  })

  it('cancels the reply and playback when hanging up during a voice turn', async () => {
    const send = deferred<boolean>()
    const { controller, options } = createController({
      recognize: vi.fn().mockResolvedValue({ ok: true, text: '继续说' }),
      send: vi.fn(() => send.promise),
    })

    controller.start()
    await settleMicrotasks()
    expect(controller.getPhase()).toBe('thinking')

    await controller.stop()
    expect(options.cancelConversation).toHaveBeenCalledOnce()
    send.resolve(true)
    await settleMicrotasks()

    expect(options.waitForSpeech).not.toHaveBeenCalled()
    expect(options.recognize).toHaveBeenCalledOnce()
    expect(controller.getPhase()).toBe('idle')
  })

  it('ends the call if the existing conversation flow rejects a transcript', async () => {
    const { controller, options, phases } = createController({
      recognize: vi.fn().mockResolvedValue({ ok: true, text: '需要授权才能发送' }),
      send: vi.fn().mockResolvedValue(false),
      getSendError: () => '请先允许 AI 对话。',
    })

    controller.start()
    await settleMicrotasks()

    expect(options.send).toHaveBeenCalledOnce()
    expect(controller.isActive()).toBe(false)
    expect(phases.at(-1)).toEqual({ phase: 'error', message: '请先允许 AI 对话。' })
    expect(options.waitForSpeech).not.toHaveBeenCalled()
  })
})
