import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { compile, createSSRApp, ref } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it, vi } from 'vitest'
import { startupFeedbackTemplate, startupHandlerCode } from '../../../scripts/startup-settings-fixture.mjs'

const preload = readFileSync(new URL('../../preload/index.cjs', import.meta.url), 'utf8')

function fixture(options: { openAtLogin?: boolean, runAsAdministrator?: boolean, pending?: boolean, reject?: boolean } = {}) {
  let bridge: any
  let received: any
  const invoke = vi.fn(async (_channel: string, patch: unknown) => {
    // The real preload must survive the same serialization boundary as IPC.
    if (options.reject) throw new Error('An object could not be cloned.')
    received = structuredClone(patch)
    return {
      ok: !options.pending, saved: true,
      preferences: { startup: received },
      status: { ok: !options.pending, pending: Boolean(options.pending), message: options.pending ? 'Windows 启动任务尚未更新' : 'Windows 启动设置已应用。' },
    }
  })
  runInNewContext(preload, {
    process: { argv: ['--meguri-view=settings'] },
    require: () => ({ contextBridge: { exposeInMainWorld: (_name: string, value: unknown) => { bridge = value } }, ipcRenderer: { invoke }, webUtils: {} }),
  })
  const state = {
    startupDraft: ref({ openAtLogin: options.openAtLogin ?? true, runAsAdministrator: options.runAsAdministrator ?? false }),
    startupLoading: ref(false), startupSaving: ref(false), startupDirty: ref(true),
    startupStatus: ref({ ok: true, message: '初始状态' }), startupFeedback: ref<any>(),
    preferences: { apply: vi.fn() }, window: { meguriDesktop: bridge }, Error, Boolean,
  }
  const save = runInNewContext(`${startupHandlerCode}\nsaveStartupSettings`, state)
  const html = () => renderToString(createSSRApp({
    setup: () => state,
    render: compile(startupFeedbackTemplate),
  }))
  return { state, save, invoke, html, received: () => received }
}

describe('Settings startup save path', () => {
  it.each([[true, true], [true, false], [false, true], [false, false]])('sends selected booleans once (%s, %s)', async (openAtLogin, runAsAdministrator) => {
    const f = fixture({ openAtLogin, runAsAdministrator })
    await f.save()
    expect(f.state.startupStatus.value.ok).toBe(true)
    expect(f.received()).toEqual({ openAtLogin, runAsAdministrator })
    expect(f.invoke).toHaveBeenCalledExactlyOnceWith('meguri:startup-settings:save', expect.any(Object))
    expect(f.state.preferences.apply).toHaveBeenCalledOnce()
    expect(f.state.startupDirty.value).toBe(false)
    expect(f.state.startupSaving.value).toBe(false)
    expect((await f.html()).match(/Windows 启动设置已应用。/g)).toHaveLength(1)
  })

  it('shows one bridge error, releases saving, and allows retry', async () => {
    const f = fixture({ reject: true })
    await f.save()
    expect(f.state.startupStatus.value.ok).toBe(false)
    expect(f.state.startupDirty.value).toBe(true)
    expect(f.state.startupSaving.value).toBe(false)
    expect(f.state.preferences.apply).not.toHaveBeenCalled()
    expect((await f.html()).match(/An object could not be cloned\./g)).toHaveLength(1)
    await f.save()
    expect(f.invoke).toHaveBeenCalledTimes(2)
  })

  it('retains a saved choice and reports pending application once', async () => {
    const f = fixture({ pending: true })
    await f.save()
    expect(f.state.startupDirty.value).toBe(false)
    expect(f.state.startupStatus.value.ok).toBe(false)
    expect(f.state.startupDraft.value).toEqual({ openAtLogin: true, runAsAdministrator: false })
    expect((await f.html()).match(/Windows 启动任务尚未更新/g)).toHaveLength(1)
    await f.save()
    expect(f.invoke).toHaveBeenCalledTimes(2)
  })

  it('keeps distinct operation feedback visible and prevents concurrent saves', async () => {
    const f = fixture()
    f.state.startupFeedback.value = { kind: 'neutral', message: '正在保存' }
    const html = await f.html()
    expect(html).toContain('初始状态')
    expect(html).toContain('正在保存')
    f.state.startupSaving.value = true
    await f.save()
    expect(f.invoke).not.toHaveBeenCalled()
  })
})
