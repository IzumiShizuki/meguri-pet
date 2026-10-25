import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { createSpeechResources } from './speech-resources.mjs'

const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })
describe('host-authorized speech resources', () => {
  it('adds a local host token without returning it to renderer results', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-speech-'))
    roots.push(root)
    const tokenFile = join(root, 'token')
    const token = 'test-host-only-token-012345678901234567890123456789'
    writeFileSync(tokenFile, token)
    const calls: Array<{ url: string, input: RequestInit }> = []
    const resources = createSpeechResources({ projectRoot: root, tokenFile, fetchImpl: async (url: RequestInfo | URL, input?: RequestInit) => {
      calls.push({ url: String(url), input: input ?? {} })
      return new Response(JSON.stringify({ engine: 'GPT-SoVITS', ready: true, activeId: 'default', profiles: [] }))
    } })
    const result = await resources.list()
    expect(calls[0].url).toBe('http://127.0.0.1:9880/tts/models')
    expect(calls[0].input.headers).toEqual({ Authorization: `Bearer ${token}` })
    expect(JSON.stringify(result)).not.toContain(token)
    expect(() => resources.activate('../secret')).toThrow('标识无效')
  })
  it('reports missing authorization/service as unavailable', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-speech-'))
    roots.push(root)
    const resources = createSpeechResources({ projectRoot: root, tokenFile: join(root, 'missing-token'), fetchImpl: async () => { throw new Error('must not fetch') } })
    expect((await resources.list()).ready).toBe(false)
    expect((await resources.list()).error).toContain('管理授权')
  })
})
