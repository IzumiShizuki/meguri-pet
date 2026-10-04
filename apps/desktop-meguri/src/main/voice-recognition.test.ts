import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'

import { afterEach, describe, expect, it, vi } from 'vitest'

import { createWindowsVoiceRecognizer, voiceRecognitionInternals } from './voice-recognition.mjs'

function childProcess() {
  const child = new EventEmitter() as EventEmitter & {
    stdout: PassThrough
    kill: ReturnType<typeof vi.fn>
  }
  child.stdout = new PassThrough()
  child.kill = vi.fn()
  return child
}

function createRecognizer(child = childProcess(), timeoutMs?: number) {
  const spawnImpl = vi.fn(() => child)
  const recognizer = createWindowsVoiceRecognizer({
    powershellPath: 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe',
    scriptPath: 'D:\\program\\meguri-pet\\apps\\desktop-meguri\\src\\main\\voice-recognition.ps1',
    spawnImpl: spawnImpl as never,
    timeoutMs,
  } as never)
  return { recognizer, child, spawnImpl }
}

afterEach(() => vi.useRealTimers())

describe('Windows voice recognition boundary', () => {
  it('runs only the fixed local script with shell-free PowerShell arguments', async () => {
    const state = createRecognizer()
    const recognition = state.recognizer.start()
    state.child.stdout.write('{"ok":true,"text":"提醒我睡觉"}')
    state.child.emit('close', 0)

    await expect(recognition).resolves.toEqual({ ok: true, text: '提醒我睡觉' })
    expect(state.spawnImpl).toHaveBeenCalledWith(
      'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe',
      expect.arrayContaining(['-NoProfile', '-NonInteractive', '-File', 'D:\\program\\meguri-pet\\apps\\desktop-meguri\\src\\main\\voice-recognition.ps1']),
      expect.objectContaining({ shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] }),
    )
    expect(state.recognizer.isRunning()).toBe(false)
  })

  it('rejects concurrent recognition and cancels the active local process', async () => {
    const state = createRecognizer()
    const recognition = state.recognizer.start()

    await expect(state.recognizer.start()).resolves.toMatchObject({ ok: false, code: 'already_running' })
    expect(state.recognizer.cancel()).toEqual({ ok: true })
    await expect(recognition).resolves.toMatchObject({ ok: false, code: 'cancelled' })
    expect(state.child.kill).toHaveBeenCalledOnce()
    expect(state.recognizer.isRunning()).toBe(false)
  })

  it('bounds output and reports a recognizer failure without exposing process output', async () => {
    const state = createRecognizer()
    const recognition = state.recognizer.start()
    state.child.stdout.write('x'.repeat(voiceRecognitionInternals.MAX_OUTPUT_BYTES + 1))

    await expect(recognition).resolves.toMatchObject({ ok: false, code: 'output_limit' })
    expect(state.child.kill).toHaveBeenCalledOnce()

    const failed = createRecognizer()
    const failedRecognition = failed.recognizer.start()
    failed.child.emit('close', 1)
    await expect(failedRecognition).resolves.toMatchObject({ ok: false, code: 'recognizer_failed' })
  })

  it('terminates a recognizer that exceeds its timeout', async () => {
    vi.useFakeTimers()
    const state = createRecognizer(childProcess(), 1_000)
    const recognition = state.recognizer.start()
    await vi.advanceTimersByTimeAsync(1_000)

    await expect(recognition).resolves.toMatchObject({ ok: false, code: 'timeout' })
    expect(state.child.kill).toHaveBeenCalledOnce()
  })
})
