import { EventEmitter } from 'node:events'
import { join } from 'node:path'
import { PassThrough } from 'node:stream'
import { fileURLToPath } from 'node:url'

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

const windowsOnly = process.platform === 'win32' ? it : it.skip

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
      expect.arrayContaining(['-NoProfile', '-NonInteractive', '-Command']),
      expect.objectContaining({
        shell: false,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
        env: expect.objectContaining({
          MEGURI_VOICE_SCRIPT: 'D:\\program\\meguri-pet\\apps\\desktop-meguri\\src\\main\\voice-recognition.ps1',
        }),
      }),
    )
    expect(state.recognizer.isRunning()).toBe(false)
  })

  windowsOnly('loads the PowerShell helper and speech grammar without opening the microphone', async () => {
    const powershellPath = join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
    const scriptPath = fileURLToPath(new URL('./voice-recognition.ps1', import.meta.url))
    const recognizer = createWindowsVoiceRecognizer({
      powershellPath,
      scriptPath,
      timeoutMs: 20_000,
      diagnosticOnly: true,
    } as never)

    const result = await recognizer.start()
    if (!result.ok)
      throw new Error(`PowerShell helper smoke failed: ${result.failureSource ?? 'unknown'}/${result.failureDiagnostic ?? result.code ?? 'unknown'}${result.failureLine ? ` at line ${result.failureLine}` : ''}`)
    expect(result).toEqual({ ok: true, text: 'voice-helper-ready' })
  }, 25_000)

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
