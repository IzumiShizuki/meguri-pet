import { describe, expect, it } from 'vitest'
import { createDesktopDiagnosticsReport } from './desktop-diagnostics.mjs'

describe('diagnostic whitelist', () => {
  it('excludes paths, tokens, raw errors, account details and conversation content', () => {
    const secret = 'secret-chat-token-path'
    const report = createDesktopDiagnosticsReport({
      appVersion: '0.1.0', runtimeVersions: { node: '24.17.0', private: secret }, platform: 'win32',
      readiness: { status: 'failed', items: [{ capability: 'tts', status: 'failed', label: secret, detail: `C:/Users/${secret}`, apiKey: secret }] },
      preferences: { chat: { wallpaperDataUrl: secret }, memory: { enabled: true }, privacy: { remoteChatAllowed: true, saveHistory: false }, apiKey: secret, conversation: secret },
      failures: [{ source: 'voice', code: 'VOICE_FAILED', message: secret, path: secret }, { source: secret, code: secret }],
    })
    expect(JSON.stringify(report)).not.toContain(secret)
    expect(report.failures[0]).toEqual({ source: 'voice', code: 'VOICE_FAILED' })
    expect(report.failures[1]).toEqual({ source: 'runtime', code: 'UNKNOWN' })
    expect(report.preferences.formalMemoryEnabled).toBe(true)
  })
  it('bounds all entries and validates version strings', () => {
    const report = createDesktopDiagnosticsReport({ appVersion: 'secret/path', failures: Array.from({ length: 100 }, () => ({ source: 'runtime', code: 'UNKNOWN' })) })
    expect(report.application.version).toBe('unknown')
    expect(report.failures).toHaveLength(30)
    expect(JSON.stringify(report).length).toBeLessThan(10_000)
  })
})
