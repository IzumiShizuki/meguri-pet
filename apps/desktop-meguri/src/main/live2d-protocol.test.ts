import { describe, expect, it, vi } from 'vitest'
import { createLive2DProtocolHandler } from './live2d-protocol.mjs'

describe('managed Live2D protocol', () => {
  const asset = vi.fn(() => ({ bytes: Buffer.from('{}'), mimeType: 'application/json' }))
  const handle = createLive2DProtocolHandler({ store: { asset }, allowedOrigins: ['http://127.0.0.1:4174'] })

  it('reads an encoded relative model asset without exposing host paths', async () => {
    const response = handle(new Request('meguri-live2d://model/id-1/model%20name.model3.json'))
    expect(response.status).toBe(200)
    expect(asset).toHaveBeenLastCalledWith('id-1', 'model name.model3.json')
    expect(await response.text()).toBe('{}')
    expect(response.headers.get('X-Content-Type-Options')).toBe('nosniff')
  })

  it.each([
    'meguri-live2d://model/id-1/..%2Fsecret',
    'meguri-live2d://model/id-1/a%5Cb',
    'meguri-live2d://model/id-1/a%00b',
    'meguri-live2d://other/id-1/model.json',
    'meguri-live2d://model/id-1/model.json?path=C:/secret',
  ])('rejects malformed URL %s', url => {
    asset.mockClear()
    expect(handle(new Request(url)).status).toBe(400)
    expect(asset).not.toHaveBeenCalled()
  })

  it('rejects a remote page origin and mutation methods', () => {
    expect(handle(new Request('meguri-live2d://model/id/model.json', { headers: { origin: 'https://attacker.example' } })).status).toBe(403)
    expect(handle(new Request('meguri-live2d://model/id/model.json', { method: 'POST' })).status).toBe(400)
  })

  it('returns a controlled error when a resource is absent', () => {
    const missing = createLive2DProtocolHandler({ store: { asset() { throw new Error('C:/private/model') } } })
    expect(missing(new Request('meguri-live2d://model/id/model.json')).status).toBe(404)
  })
})
