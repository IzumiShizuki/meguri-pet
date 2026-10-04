import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  readMultimodalRelayConfig,
  saveMultimodalRelayConfig,
  testMultimodalRelayConfig,
} from './multimodal-relay-config.mjs'

const roots: string[] = []

function paths() {
  const root = mkdtempSync(join(process.cwd(), '.meguri-relay-test-'))
  roots.push(root)
  return {
    configPath: join(root, 'config', 'relay.json'),
    secretPath: join(root, 'secret', 'relay.key'),
  }
}

afterEach(() => {
  while (roots.length)
    rmSync(roots.pop()!, { recursive: true, force: true })
})

describe('multimodal relay configuration', () => {
  it('normalizes missing state and stores secrets separately without returning plaintext', () => {
    const locations = paths()
    expect(readMultimodalRelayConfig(locations)).toMatchObject({
      enabled: false,
      baseUrl: '',
      model: '',
      apiKeyConfigured: false,
    })

    const result = saveMultimodalRelayConfig({
      enabled: true,
      baseUrl: 'https://relay.example/v1/',
      model: 'qwen3.7-flash',
      apiKey: 'secret-value',
    }, locations)

    expect(result).toMatchObject({
      ok: true,
      config: {
        enabled: true,
        baseUrl: 'https://relay.example/v1',
        model: 'qwen3.7-flash',
        apiKeyConfigured: true,
      },
    })
    expect(JSON.stringify(result)).not.toContain('secret-value')
    expect(readFileSync(locations.configPath, 'utf8')).not.toContain('secret-value')
    expect(readFileSync(locations.secretPath, 'utf8').trim()).toBe('secret-value')
  })

  it('retains a saved secret on blank updates and rejects unsafe or incomplete settings', () => {
    const locations = paths()
    mkdirSync(join(locations.secretPath, '..'), { recursive: true })
    writeFileSync(locations.secretPath, 'existing-secret\n')

    expect(saveMultimodalRelayConfig({
      enabled: true,
      baseUrl: 'https://relay.example/v1',
      model: 'vision/model-v1',
      apiKey: '',
    }, locations)).toMatchObject({ ok: true })
    expect(readFileSync(locations.secretPath, 'utf8')).toBe('existing-secret\n')

    expect(saveMultimodalRelayConfig({
      enabled: true,
      baseUrl: 'http://remote.example/v1',
      model: 'vision-model',
    }, locations)).toMatchObject({ ok: false, error: expect.stringContaining('HTTPS') })
    expect(saveMultimodalRelayConfig({
      enabled: true,
      baseUrl: 'http://127.0.0.1:1234/v1',
      model: 'bad model id',
    }, locations)).toMatchObject({ ok: false, error: expect.stringContaining('模型 ID') })

    const emptyLocations = paths()
    expect(saveMultimodalRelayConfig({
      enabled: true,
      baseUrl: 'https://relay.example/v1',
      model: 'vision-model',
    }, emptyLocations)).toMatchObject({ ok: false, error: expect.stringContaining('API Key') })
  })

  it('tests actual image input only when explicitly invoked and sanitizes provider failures', async () => {
    const locations = paths()
    const acceptedFetch = vi.fn(async (_url: string, init: RequestInit) => {
      const body = JSON.parse(String(init.body))
      expect(body.messages[0].content[1].type).toBe('image_url')
      expect((init.headers as Record<string, string>).Authorization).toBe('Bearer transient-secret')
      return new Response(JSON.stringify({ choices: [{ message: { content: 'OK' } }] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    await expect(testMultimodalRelayConfig({
      baseUrl: 'https://relay.example/v1',
      model: 'vision-model',
      apiKey: 'transient-secret',
    }, { ...locations, fetchImpl: acceptedFetch })).resolves.toEqual({
      ok: true,
      message: '视觉输入测试通过（vision-model）。',
    })
    expect(acceptedFetch).toHaveBeenCalledOnce()

    const rejectedFetch = vi.fn(async () => new Response(JSON.stringify({
      error: { message: 'unknown variant image_url\nAuthorization: hidden' },
    }), { status: 400, headers: { 'Content-Type': 'application/json' } }))
    const rejected = await testMultimodalRelayConfig({
      baseUrl: 'https://relay.example/v1',
      model: 'text-only',
      apiKey: 'another-secret',
    }, { ...locations, fetchImpl: rejectedFetch })
    expect(rejected).toMatchObject({ ok: false, error: expect.stringContaining('image_url Authorization') })
    expect(JSON.stringify(rejected)).not.toContain('another-secret')
  })
})
