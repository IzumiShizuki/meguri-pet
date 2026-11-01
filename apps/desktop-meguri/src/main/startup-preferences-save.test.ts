import { describe, expect, it, vi } from 'vitest'

import { defaultDesktopPreferences } from './desktop-preferences.mjs'
import { saveStartupPreferences } from './startup-preferences-save.mjs'

describe('startup preference persistence and Windows application', () => {
  it('persists the requested preference before the Windows task is updated', async () => {
    const calls: string[] = []
    const persist = vi.fn((next) => { calls.push(`persist:${next.startup.runAsAdministrator}`) })
    const apply = vi.fn(async () => {
      calls.push('apply')
      return { ok: true, message: 'applied' }
    })

    const result = await saveStartupPreferences({
      currentPreferences: defaultDesktopPreferences,
      patch: { runAsAdministrator: false },
      persist,
      apply,
    })

    expect(calls).toEqual(['persist:false', 'apply'])
    expect(result).toMatchObject({
      ok: true,
      saved: true,
      preferences: { startup: { openAtLogin: true, runAsAdministrator: false } },
      status: { ok: true, pending: false },
    })
  })

  it('retains a saved preference and exposes a pending task when UAC is cancelled', async () => {
    const persisted: Array<{ startup: { openAtLogin: boolean, runAsAdministrator: boolean } }> = []
    const task = { runLevel: 'highest' }
    const persist = vi.fn((next) => { persisted.push(next) })
    const apply = vi.fn(async () => ({
      ok: false,
      message: '管理员授权已取消，Windows 没有应用启动设置。',
    }))

    const result = await saveStartupPreferences({
      currentPreferences: defaultDesktopPreferences,
      patch: { runAsAdministrator: false },
      persist,
      apply,
    })

    expect(result).toMatchObject({
      ok: false,
      saved: true,
      preferences: { startup: { openAtLogin: true, runAsAdministrator: false } },
      status: { ok: false, pending: true, code: 'windows_startup_pending' },
    })
    expect(result.status?.message).toContain('当前任务仍保持此前状态')
    expect(persisted[0].startup.runAsAdministrator).toBe(false)
    expect(task.runLevel).toBe('highest')
  })

  it('keeps the previous preference and skips Windows changes when local storage fails', async () => {
    const persist = vi.fn(() => { throw new Error('access denied') })
    const apply = vi.fn()

    const result = await saveStartupPreferences({
      currentPreferences: defaultDesktopPreferences,
      patch: { runAsAdministrator: false },
      persist,
      apply,
    })

    expect(result).toMatchObject({
      ok: false,
      saved: false,
      preferences: defaultDesktopPreferences,
      status: { ok: false, pending: false, code: 'preference_save_failed' },
    })
    expect(apply).not.toHaveBeenCalled()
    expect(result.status?.message).toContain('Windows 启动任务未更改')
  })

  it('retries the same saved choice and reports applied after Windows accepts it', async () => {
    const persist = vi.fn()
    const apply = vi.fn()
      .mockResolvedValueOnce({ ok: false, message: 'task remains elevated' })
      .mockResolvedValueOnce({ ok: true, message: 'applied' })
    const first = await saveStartupPreferences({
      currentPreferences: defaultDesktopPreferences,
      patch: { runAsAdministrator: false },
      persist,
      apply,
    })
    const second = await saveStartupPreferences({
      currentPreferences: first.preferences!,
      patch: { runAsAdministrator: false },
      persist,
      apply,
    })

    expect(first.status).toMatchObject({ ok: false, pending: true })
    expect(second).toMatchObject({
      ok: true,
      saved: true,
      preferences: { startup: { runAsAdministrator: false } },
      status: { ok: true, pending: false },
    })
    expect(apply).toHaveBeenCalledTimes(2)
  })
})
