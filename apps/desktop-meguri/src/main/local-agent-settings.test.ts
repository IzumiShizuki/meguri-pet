import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

import { afterEach, describe, expect, it, vi } from 'vitest'

import { readLocalAgentConfig, saveLocalAgentConfig } from './local-agent-config.mjs'
import { createLocalAgentRuntime } from './local-agent-runtime.mjs'
import { registerLocalAgentSettingsIpc } from './local-agent-settings-ipc.mjs'

vi.mock('./local-agents/dsh-discovery.mjs', () => ({
  findDshCli: () => 'D:/fixture/dsh/bin.js',
  harnessCredentialState: () => 'configured',
  codexHomeState: () => ({ home: 'D:/fixture/codex', signedIn: true }),
}))

const roots: string[] = []
function temporary() {
  const root = mkdtempSync(join(process.cwd(), '.meguri-agent-settings-test-'))
  roots.push(root)
  return { root, config: join(root, 'local-agents.json'), home: join(root, 'harness') }
}
afterEach(() => {
  for (const root of roots.splice(0))
    rmSync(root, { recursive: true, force: true })
})

describe('local-agent settings persistence', () => {
  it('saves and reopens a complete normalized document, preserving legacy optional defaults', () => {
    const p = temporary()
    writeFileSync(p.config, JSON.stringify({ version: 1, enabled: true, harnessAutoStart: false }))
    expect(readLocalAgentConfig(p.config)).toEqual({ version: 1, enabled: true, harnessAutoStart: false, dshHome: '' })
    expect(saveLocalAgentConfig(p.config, { dshHome: p.home })).toMatchObject({ ok: true })
    expect(readLocalAgentConfig(p.config)).toEqual({ version: 1, enabled: true, harnessAutoStart: false, dshHome: p.home })
    expect(saveLocalAgentConfig(p.config, { enabled: false, harnessAutoStart: true })).toMatchObject({ ok: true, config: { harnessAutoStart: false } })
    expect(readdirSync(p.root)).toEqual(['local-agents.json'])
  })

  it.each([
    null, [], { enabled: 'yes' }, { harnessAutoStart: 1 }, { dshHome: 42 },
    { dshHome: 'relative' }, { dshHome: 'https://example.test' }, { dshHome: '\\\\server\\share' },
  ])('rejects invalid input without touching committed data: %j', (patch) => {
    const p = temporary()
    saveLocalAgentConfig(p.config, { enabled: false })
    const before = readFileSync(p.config, 'utf8')
    const result = saveLocalAgentConfig(p.config, patch)
    expect(result).toMatchObject({ ok: false, code: 'invalid_settings', error: expect.any(String) })
    expect(readFileSync(p.config, 'utf8')).toBe(before)
  })

  it('preserves the previous bytes and removes its temporary file when replacement fails', () => {
    const p = temporary()
    saveLocalAgentConfig(p.config, { enabled: false })
    const before = readFileSync(p.config, 'utf8')
    const result = saveLocalAgentConfig(p.config, { enabled: true }, {
      rename: () => { throw Object.assign(new Error('fixture failure'), { code: 'EPERM' }) },
    })
    expect(result).toMatchObject({ ok: false, code: 'write_failed' })
    expect(readFileSync(p.config, 'utf8')).toBe(before)
    expect(readdirSync(p.root)).toEqual(['local-agents.json'])
  })

  it('does not report a committed save as failed if final cleanup throws', () => {
    const p = temporary()
    expect(saveLocalAgentConfig(p.config, { enabled: true }, {
      rm: () => { throw new Error('fixture cleanup failure') },
    })).toMatchObject({ ok: true })
    expect(readLocalAgentConfig(p.config).enabled).toBe(true)
  })

  it('serializes concurrently queued partial saves without losing unrelated fields', async () => {
    const p = temporary()
    const results = await Promise.all([
      Promise.resolve().then(() => saveLocalAgentConfig(p.config, { enabled: true })),
      Promise.resolve().then(() => saveLocalAgentConfig(p.config, { dshHome: p.home })),
      Promise.resolve().then(() => saveLocalAgentConfig(p.config, { harnessAutoStart: false })),
    ])
    expect(results.every(result => result.ok)).toBe(true)
    expect(readLocalAgentConfig(p.config)).toEqual({ version: 1, enabled: true, harnessAutoStart: false, dshHome: p.home })
    expect(readdirSync(p.root)).toEqual(['local-agents.json'])
  })
})

function fixture() {
  const p = temporary()
  const codex = { installed: () => true, compatible: vi.fn(async () => ({ compatible: true, account: 'signed_in' })), close: vi.fn(async () => {}) }
  const harness = { probe: vi.fn(async () => ({ state: 'ready' })) }
  const runtime = createLocalAgentRuntime({
    config: readLocalAgentConfig(p.config),
    paths: { dshHome: p.home },
    codexBridge: codex,
    harnessBridge: harness,
  })
  const startHost = vi.spyOn(runtime, 'startHarnessHost')
  const update = vi.spyOn(runtime, 'updateSettings')
  const startObservation = vi.fn()
  const stopObservation = vi.fn()
  const trusted = { sender: 'settings-window', url: 'file:///fixture/index.html?view=settings' }
  const channels = { localAgentsGet: 'get', localAgentsSave: 'save', localAgentsTest: 'test' }
  const handlers = new Map<string, (...args: any[]) => any>()
  registerLocalAgentSettingsIpc({
    ipcMain: { handle: (channel: string, handler: (...args: any[]) => any) => handlers.set(channel, handler) },
    channels,
    trustedSettingsRenderer: (event: typeof trusted) => event.sender === trusted.sender && event.url === trusted.url,
    getRuntime: () => runtime,
    resolveConfigPath: () => p.config,
    startObservation,
    stopObservation,
    failure: (error: Error) => ({ error: error.message }),
  })
  return { ...p, runtime, codex, harness, startHost, update, startObservation, stopObservation, trusted, call: (name: string, patch?: unknown, event = trusted) => handlers.get(name)!(event, patch) }
}

describe('trusted local-agent settings handlers and active runtime', () => {
  it('applies committed settings, reads them back, and probes only on explicit test', async () => {
    const f = fixture()
    expect(f.call('get').enabled).toBe(false)
    expect(existsSync(f.home)).toBe(false)
    expect(f.call('save', { enabled: true, harnessAutoStart: false, dshHome: f.home })).toMatchObject({ ok: true, settings: { enabled: true } })
    expect(f.runtime.settings().enabled).toBe(true)
    expect(f.call('get').dshHome).toBe(f.home)
    expect(readLocalAgentConfig(f.config).enabled).toBe(true)
    expect(f.startObservation).toHaveBeenCalledOnce()
    expect(f.startHost).not.toHaveBeenCalled()
    expect(f.codex.compatible).not.toHaveBeenCalled()
    expect(f.harness.probe).not.toHaveBeenCalled()
    expect(await f.call('test')).toMatchObject({ codex: { ok: true }, harness: { ok: true } })
    expect(f.codex.compatible).toHaveBeenCalledOnce()
    expect(f.harness.probe).toHaveBeenCalledOnce()
    expect(f.call('save', { enabled: false })).toMatchObject({ ok: true, settings: { enabled: false, harnessAutoStart: false } })
    expect(f.stopObservation).toHaveBeenCalledOnce()
    expect(await f.call('test')).toMatchObject({ codex: { ok: false }, harness: { ok: false } })
    expect(f.harness.probe).toHaveBeenCalledOnce()
  })

  it.each([
    { sender: 'other-window', url: 'file:///fixture/index.html?view=settings' },
    { sender: 'settings-window', url: 'https://untrusted.example/?view=settings' },
  ])('rejects unauthorized source %j before disk access or runtime changes', async (event) => {
    const f = fixture()
    expect(f.call('save', { enabled: true }, event)).toMatchObject({ ok: false, code: 'untrusted_source' })
    expect(f.call('get', undefined, event).enabled).toBe(false)
    expect(await f.call('test', undefined, event)).toMatchObject({ codex: { ok: false }, harness: { ok: false } })
    expect(existsSync(f.config)).toBe(false)
    expect(f.update).not.toHaveBeenCalled()
    expect(f.startObservation).not.toHaveBeenCalled()
    expect(f.harness.probe).not.toHaveBeenCalled()
  })

  it('does not apply invalid or unwritable configuration', () => {
    const f = fixture()
    expect(f.call('save', { enabled: true, dshHome: 'relative' })).toMatchObject({ ok: false, code: 'invalid_settings' })
    mkdirSync(f.config) // a directory cannot be atomically replaced by the JSON file
    expect(f.call('save', { enabled: true })).toMatchObject({ ok: false, code: 'write_failed' })
    expect(f.update).not.toHaveBeenCalled()
    expect(f.runtime.settings().enabled).toBe(false)
    expect(f.startObservation).not.toHaveBeenCalled()
  })

  it('distinguishes a committed file from an unexpected runtime-application failure', () => {
    const f = fixture()
    f.update.mockImplementationOnce(() => { throw new Error('fixture apply failure') })
    expect(f.call('save', { enabled: true })).toMatchObject({ ok: false, code: 'apply_failed', error: expect.stringContaining('配置已保存') })
    expect(readLocalAgentConfig(f.config).enabled).toBe(true)
    expect(f.startHost).not.toHaveBeenCalled()
  })
})
