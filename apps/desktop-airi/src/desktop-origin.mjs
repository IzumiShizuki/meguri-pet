export function desktopOrigin(request) {
  const origin = request.headers.origin
  if (origin === 'null' || origin === 'file://' || /^http:\/\/(?:127\.0\.0\.1|localhost):\d+$/.test(origin ?? ''))
    return origin
  if (origin == null
    && String(request.headers['user-agent'] ?? '').includes('Electron/')
    && ['airi', 'meguri'].includes(request.headers['x-meguri-desktop-client'])) {
    // Originless desktop requests do not need a CORS response header.
    return null
  }
  return undefined
}
