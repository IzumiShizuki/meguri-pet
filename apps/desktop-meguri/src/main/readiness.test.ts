import { describe, expect, it } from 'vitest'

import { createReadinessCoordinator } from './readiness.mjs'

describe('readiness coordinator', () => {
  it('allows optional TTS to continue loading', async () => {
    const coordinator = createReadinessCoordinator({
      desktopSession: 'test-session',
      fetchImpl: (async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.includes(':9880'))
          return { ok: false, status: 503 }
        if (url.endsWith('/core/health'))
          return { ok: true, status: 200, json: async () => ({ status: 'ok' }) }
        return { ok: true, status: 200, headers: new Headers({ 'Content-Type': 'image/png' }) }
      }) as unknown as typeof fetch,
    })
    const snapshot = await coordinator.check()
    expect(snapshot.status).toBe('ready')
    expect(snapshot.items.find(item => item.capability === 'tts')?.status).toBe('ready')
  })

  it('fails closed when Core is not reachable', async () => {
    const coordinator = createReadinessCoordinator({
      desktopSession: 'test-session',
      fetchImpl: (async (input: RequestInfo | URL) => String(input).endsWith('/core/health')
        ? { ok: false, status: 503 }
        : {
            ok: true,
            status: 200,
            headers: new Headers({ 'Content-Type': 'image/png' }),
            json: async () => ({ ready: true }),
          }) as unknown as typeof fetch,
    })
    expect((await coordinator.check()).status).toBe('failed')
  })

  it('sends the native Meguri identity to the Core probe', async () => {
    let requestHeaders: HeadersInit | undefined
    const coordinator = createReadinessCoordinator({
      desktopSession: 'test-session',
      definitions: [{
        capability: 'core',
        label: 'Meguri Core',
        url: 'http://127.0.0.1:15173/core/health',
        desktopHeaders: true,
        validate: (data: { status?: string }) => data?.status === 'ok',
      }],
      fetchImpl: (async (_input: RequestInfo | URL, init?: RequestInit) => {
        requestHeaders = init?.headers
        return { ok: true, status: 200, json: async () => ({ status: 'ok' }) }
      }) as typeof fetch,
    })

    await coordinator.check()
    expect(requestHeaders).toMatchObject({
      'X-Meguri-Desktop-Client': 'meguri',
      'X-Meguri-Desktop-Session': 'test-session',
    })
  })

  it('rejects a successful HTML response masquerading as the canonical sprite', async () => {
    const coordinator = createReadinessCoordinator({
      definitions: [{
        capability: 'assets',
        label: '角色资源',
        url: 'http://127.0.0.1:15173/assets/sprites/meguri/ce04001l.png',
        validateResponse: (response: Response) => response.headers.get('content-type')?.startsWith('image/png') === true,
      }],
      fetchImpl: (async () => ({
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'text/html' }),
      })) as unknown as typeof fetch,
    })

    const snapshot = await coordinator.check()
    expect(snapshot.status).toBe('failed')
    expect(snapshot.items[0]?.detail).toContain('角色资源')
  })
})
