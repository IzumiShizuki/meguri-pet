import { afterEach, describe, expect, it, vi } from 'vitest'

import { desktopFetch, requestContainsReadableAttachment } from './desktop-fetch'

afterEach(() => vi.unstubAllGlobals())

describe('desktop fetch attachment routing marker', () => {
  it('marks only turns that carry readable attachments', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const body = JSON.stringify({
      attachments: [{ content_access: 'document_read' }],
    })

    await desktopFetch('session-1', 'http://127.0.0.1:15173/core/v1/turns', {
      method: 'POST',
      body,
    })

    const headers = new Headers(fetchMock.mock.calls[0][1].headers)
    expect(headers.get('X-Meguri-Multimodal')).toBe('true')
    expect(headers.get('X-Meguri-Desktop-Client')).toBe('meguri')
    expect(headers.get('X-Meguri-Desktop-Session')).toBe('session-1')
  })

  it('does not mark metadata-only or malformed bodies', () => {
    expect(requestContainsReadableAttachment(JSON.stringify({ attachments: [{ content_access: 'not_read' }] }))).toBe(false)
    expect(requestContainsReadableAttachment('{')).toBe(false)
    expect(requestContainsReadableAttachment(undefined)).toBe(false)
  })
})
