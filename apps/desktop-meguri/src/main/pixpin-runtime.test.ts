import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { EventEmitter } from 'node:events'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  discoverPixPinExecutable,
  ensurePixPinRunning,
  findPixPinProcessIds,
  pixpinRuntimeInternals,
  validatePixPinExecutable,
} from './pixpin-runtime.mjs'

const roots: string[] = []

function testRoot() {
  const root = mkdtempSync(join(tmpdir(), 'meguri-pixpin-test-'))
  roots.push(root)
  return root
}

afterEach(() => {
  while (roots.length)
    rmSync(roots.pop()!, { recursive: true, force: true })
})

describe('PixPin runtime boundary', () => {
  it('accepts only an absolute regular file named PixPin.exe', () => {
    const root = testRoot()
    const executable = join(root, 'PixPin.exe')
    const other = join(root, 'pixpin.cmd')
    writeFileSync(executable, 'MZ')
    writeFileSync(other, 'echo')

    expect(validatePixPinExecutable(executable)).toMatchObject({ ok: true, executable })
    expect(validatePixPinExecutable('PixPin.exe')).toMatchObject({ ok: false })
    expect(validatePixPinExecutable(other)).toMatchObject({ ok: false, error: expect.stringContaining('PixPin.exe') })
    expect(validatePixPinExecutable(join(root, 'missing', 'PixPin.exe'))).toMatchObject({ ok: false })
    expect(validatePixPinExecutable('\\\\server\\share\\PixPin.exe', {
      lstat: (() => ({ isFile: () => true, isSymbolicLink: () => false })) as never,
    })).toMatchObject({ ok: false, error: expect.stringContaining('网络共享') })
  })

  it('uses a fixed PixPin script and shell-free process argument arrays', () => {
    expect(pixpinRuntimeInternals.captureScript).toBe('pixpin.screenShot(ShotAction.Copy)')
    expect(pixpinRuntimeInternals.clipboardWatcherScript).toContain('GetClipboardSequenceNumber')
    expect(pixpinRuntimeInternals.clipboardWatcherScript).toContain('GetClipboardOwner')
    expect(pixpinRuntimeInternals.clipboardWatcherScript).toContain('GetWindowThreadProcessId')
    expect(pixpinRuntimeInternals.sameWindowsPath('D:/Apps/PixPin.exe', 'd:\\apps\\pixpin.exe')).toBe(true)
  })

  it('discovers a registered executable only after validating its path', async () => {
    const root = testRoot()
    const executable = join(root, 'PixPin.exe')
    writeFileSync(executable, 'MZ')
    const runQuery = vi.fn(async () => [executable])

    await expect(discoverPixPinExecutable({ runQuery })).resolves.toMatchObject({ ok: true, executable })
    expect(runQuery).toHaveBeenCalledOnce()
  })

  it('matches clipboard owner PIDs only to the selected PixPin executable', async () => {
    const executable = 'D:\\Program Files\\PixPin\\PixPin.exe'
    const ids = await findPixPinProcessIds(executable, {
      runQuery: async () => [
        { pid: 100, executablePath: 'D:\\Program Files\\PixPin\\PixPin.exe' },
        { pid: 101, executablePath: 'C:\\Other\\PixPin.exe' },
        { pid: 'bad', executablePath: executable },
      ],
    })
    expect(ids).toEqual([100])
  })

  it('reuses a running instance and starts PixPin only when it is absent', async () => {
    const spawn = vi.fn()
    const running = await ensurePixPinRunning('D:\\Program Files\\PixPin\\PixPin.exe', {
      findProcesses: async () => [900],
      spawnImpl: spawn,
    })
    expect(running.processIds).toEqual([900])
    expect(spawn).not.toHaveBeenCalled()

    const started = await ensurePixPinRunning('D:\\Program Files\\PixPin\\PixPin.exe', {
      findProcesses: vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([901]),
      spawnImpl: (() => {
        const child = new EventEmitter() as EventEmitter & { unref: () => void }
        child.unref = vi.fn()
        queueMicrotask(() => child.emit('spawn'))
        return child as never
      }) as never,
      wait: async () => undefined,
    })
    expect(started.processIds).toEqual([901])
  })
})
