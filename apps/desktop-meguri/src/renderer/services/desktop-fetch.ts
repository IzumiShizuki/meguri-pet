export const gatewayOrigin = import.meta.env.VITE_MEGURI_GATEWAY_ORIGIN ?? 'http://127.0.0.1:15173'
export const coreUrl = `${gatewayOrigin}/core`

export function requestContainsReadableAttachment(body: BodyInit | null | undefined): boolean {
  if (typeof body !== 'string' || !body.includes('content_access'))
    return false
  try {
    const payload = JSON.parse(body) as { attachments?: Array<{ content_access?: unknown }> }
    return Array.isArray(payload.attachments) && payload.attachments.some(attachment => attachment
      && (attachment.content_access === 'multimodal_read' || attachment.content_access === 'document_read'))
  }
  catch {
    return false
  }
}

export function desktopFetch(sessionId: string, input: string | URL, init: RequestInit = {}): Promise<Response> {
  if (import.meta.env.DEV && typeof window !== 'undefined'
    && new URLSearchParams(window.location.search).get('preview') === 'offline') {
    return Promise.resolve(new Response(JSON.stringify({ error: 'Preview offline' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    }))
  }
  const headers = new Headers(init.headers)
  headers.set('X-Meguri-Desktop-Client', 'meguri')
  headers.set('X-Meguri-Desktop-Session', sessionId)
  if (requestContainsReadableAttachment(init.body))
    headers.set('X-Meguri-Multimodal', 'true')
  return fetch(input, { ...init, headers })
}
