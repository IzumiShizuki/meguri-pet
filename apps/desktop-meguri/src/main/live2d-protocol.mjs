/** Resolves only catalogued model assets; browser URLs never become arbitrary file paths. */
/** @param {{store: {asset(id: string, path: string): {bytes: Buffer, mimeType: string}}, allowedOrigins?: string[]}} options */
export function createLive2DProtocolHandler({ store, allowedOrigins = [] }) {
  const origins = new Set(['null', ...allowedOrigins])
  return request => {
    try {
      const origin = request.headers.get('origin')
      if (origin && !origins.has(origin)) return new Response(null, { status: 403 })
      const url = new URL(request.url)
      if (!['GET', 'HEAD'].includes(request.method) || url.protocol !== 'meguri-live2d:'
        || url.hostname !== 'model' || url.username || url.password || url.port || url.search || url.hash)
        return new Response(null, { status: 400 })
      const [id, ...parts] = url.pathname.slice(1).split('/').map(decodeURIComponent)
      if (!id || parts.length === 0 || parts.some(part => !part || part === '.' || part === '..' || /[\\/:\0]/.test(part)))
        return new Response(null, { status: 400 })
      const asset = store.asset(id, parts.join('/'))
      return new Response(request.method === 'HEAD' ? null : asset.bytes, {
        headers: {
          'Content-Type': asset.mimeType,
          'X-Content-Type-Options': 'nosniff',
          'Access-Control-Allow-Origin': origin ?? 'null',
          'Cache-Control': 'no-store',
        },
      })
    }
    catch { return new Response(null, { status: 404 }) }
  }
}
