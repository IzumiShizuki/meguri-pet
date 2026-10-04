import { describe, expect, it, vi } from 'vitest'

import { ensureEverythingIpc } from './everything-runtime.mjs'

const paths = {
  everythingPath: 'D:\\Program Files\\Everything\\everything.exe',
  esPath: 'D:\\Program Files\\Everything\\es.exe',
  databasePath: 'D:\\Program Files\\Everything\\Everything.db',
}

describe('Everything desktop runtime', () => {
  it('does nothing when IPC is already available', async () => {
    const launch = vi.fn()
    const result = await ensureEverythingIpc({
      platform: 'win32',
      paths,
      exists: () => true,
      probe: vi.fn().mockResolvedValue(true),
      launch,
    })

    expect(result).toEqual({ ready: true, started: false, detail: 'Everything IPC 已就绪' })
    expect(launch).not.toHaveBeenCalled()
  })

  it('starts a hidden read-only fallback client when the configured IPC is missing', async () => {
    const child = { once: vi.fn(), unref: vi.fn() }
    const launch = vi.fn(() => child)
    const probe = vi.fn()
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true)
    const result = await ensureEverythingIpc({
      platform: 'win32',
      paths,
      exists: () => true,
      probe,
      launch,
      delay: vi.fn().mockResolvedValue(undefined),
      attempts: 3,
    })

    expect(launch).toHaveBeenCalledWith(paths.everythingPath, [
      '-startup',
      '-first-instance',
      '-noapp-data',
      '-db', paths.databasePath,
      '-read-only',
    ], expect.objectContaining({ detached: true, shell: false, windowsHide: true }))
    expect(child.unref).toHaveBeenCalled()
    expect(result).toEqual({ ready: true, started: true, detail: 'Everything IPC 已由 Meguri 后台唤醒' })
  })

  it('fails softly when local Everything binaries are unavailable', async () => {
    const launch = vi.fn()
    const result = await ensureEverythingIpc({
      platform: 'win32',
      paths,
      exists: () => false,
      probe: vi.fn().mockResolvedValue(false),
      launch,
    })

    expect(result.ready).toBe(false)
    expect(result.detail).toContain('安装不完整')
    expect(launch).not.toHaveBeenCalled()
  })
})
