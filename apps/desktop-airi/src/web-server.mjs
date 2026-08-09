import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import { extname, resolve, sep } from 'node:path'
import { Readable } from 'node:stream'
import { fileURLToPath } from 'node:url'

import { collectRecentArtifacts } from './artifact-feed.mjs'
import { desktopOrigin } from './desktop-origin.mjs'
import { matchesDesktopRequestIdentity } from './desktop-request-identity.mjs'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const webRoot = resolve(root, 'apps/desktop-airi/web')
const assetRoot = resolve(root, 'data/meguri/assets')
const reportRoot = resolve(root, 'reports')
const outputRoot = resolve(root, 'output')
const port = Number(process.env.MEGURI_DESKTOP_PORT ?? 5173)
const coreUrl = process.env.MEGURI_CORE_URL ?? 'https://bot.shizuki.online/meguri-core'
// Everything and user-approved multimodal attachments are loopback-only: the
// remote Core cannot read this machine's files. A selected session stays local
// after its first multimodal turn so its Core-side conversation remains intact.
const localCoreUrl = process.env.MEGURI_LOCAL_CORE_URL ?? 'http://127.0.0.1:18080'
const coreTokenFile = process.env.MEGURI_CORE_AUTH_TOKEN_FILE ?? 'D:\\environment\\secrets\\meguri\\desktop-core-token.txt'
let coreToken = ''
try { coreToken = readFileSync(coreTokenFile, 'utf8').trim() } catch { /* local-only Core keeps working without a token */ }

const contentTypes = { '.html': 'text/html; charset=utf-8', '.json': 'application/json; charset=utf-8', '.md': 'text/markdown; charset=utf-8', '.pdf': 'application/pdf', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.txt': 'text/plain; charset=utf-8', '.csv': 'text/csv; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8' }
const coreProxyPrefix = '/core'
const maxProxyBodyBytes = 1024 * 1024
const maxMultimodalProxyBodyBytes = 12 * 1024 * 1024
const localMultimodalSessions = new Map()
const localMultimodalSessionTtlMs = 30 * 60 * 1000

function safeAssetPath(pathname) {
  const relative = decodeURIComponent(pathname.slice('/assets/'.length)).replaceAll('/', sep)
  const target = resolve(assetRoot, relative)
  return target === assetRoot || target.startsWith(assetRoot + sep) ? target : undefined
}

function safeChildPath(rootPath, pathname, prefix) {
  const relative = decodeURIComponent(pathname.slice(prefix.length)).replaceAll('/', sep)
  const target = resolve(rootPath, relative)
  return target === rootPath || target.startsWith(rootPath + sep) ? target : undefined
}

function applyDesktopCors(request, response) {
  const origin = desktopOrigin(request)
  if (origin === undefined)
    return false
  if (origin !== null)
    response.setHeader('Access-Control-Allow-Origin', origin)
  response.setHeader('Vary', 'Origin')
  response.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
  response.setHeader(
    'Access-Control-Allow-Headers',
    'Accept, Content-Type, Idempotency-Key, X-Meguri-Desktop-Client, X-Meguri-Desktop-Session, X-Meguri-Multimodal',
  )
  return true
}

async function readProxyBody(request, maximumBytes) {
  const chunks = []
  let bytes = 0
  for await (const chunk of request) {
    bytes += chunk.length
    if (bytes > maximumBytes)
      throw new Error('Proxy request body is too large')
    chunks.push(chunk)
  }
  return chunks.length ? Buffer.concat(chunks) : undefined
}

function sessionUsesLocalMultimodalCore(sessionId) {
  const expiresAt = localMultimodalSessions.get(sessionId)
  if (!expiresAt)
    return false
  if (expiresAt <= Date.now()) {
    localMultimodalSessions.delete(sessionId)
    return false
  }
  return true
}

function hasMultimodalAttachment(payload) {
  return Array.isArray(payload?.attachments)
    && payload.attachments.some(attachment => attachment
      && typeof attachment === 'object'
      && (attachment.content_access === 'multimodal_read'
        || attachment.content_access === 'document_read'))
}

async function proxyCoreRequest(request, response, requestUrl) {
  if (!applyDesktopCors(request, response)) {
    response.writeHead(403); response.end('Desktop origin required'); return
  }
  if (request.method === 'OPTIONS') {
    response.writeHead(204); response.end(); return
  }
  if (!String(request.headers['user-agent'] ?? '').includes('Electron/')
    || request.headers['x-meguri-desktop-client'] !== 'airi') {
    response.writeHead(403); response.end('AIRI desktop client required'); return
  }
  const desktopSession = String(request.headers['x-meguri-desktop-session'] ?? '')
  if (!/^[A-Za-z0-9._:-]{1,200}$/.test(desktopSession)) {
    response.writeHead(403); response.end('Valid desktop session required'); return
  }
  const corePath = requestUrl.pathname.slice(coreProxyPrefix.length)
  const resourceRequest = corePath === '/v1/resources' || corePath.startsWith('/v1/resources/')
  const documentEditRequest = corePath === '/v1/documents' || corePath.startsWith('/v1/documents/')
  const multimodalRequested = request.headers['x-meguri-multimodal'] === 'true'
  const controller = new AbortController()
  const abortUpstream = () => {
    if (!controller.signal.aborted)
      controller.abort()
  }
  request.once('aborted', abortUpstream)
  const body = request.method === 'GET' || request.method === 'HEAD'
    ? undefined
    : await readProxyBody(request, multimodalRequested
      ? maxMultimodalProxyBodyBytes
      : maxProxyBodyBytes)
  let hasMultimodalContent = false
  if (body && ['/v1/turns', '/v1/chat/respond'].includes(corePath)) {
    let payload
    try { payload = JSON.parse(body.toString('utf8')) } catch {
      response.writeHead(400); response.end('Invalid JSON body'); return
    }
    if (!matchesDesktopRequestIdentity(payload, desktopSession)) {
      response.writeHead(403); response.end('Desktop request identity mismatch'); return
    }
    hasMultimodalContent = hasMultimodalAttachment(payload)
    if (multimodalRequested !== hasMultimodalContent) {
      response.writeHead(400); response.end('Multimodal request marker does not match attachments'); return
    }
    if (hasMultimodalContent) {
      localMultimodalSessions.set(desktopSession, Date.now() + localMultimodalSessionTtlMs)
    }
  }
  const useLocalCore = resourceRequest || documentEditRequest || sessionUsesLocalMultimodalCore(desktopSession)
  if (!coreToken && !useLocalCore) {
    response.writeHead(503); response.end('Meguri Core token is not configured'); return
  }

  const suffix = `${corePath}${requestUrl.search}`
  const upstreamUrl = `${(useLocalCore ? localCoreUrl : coreUrl).replace(/\/+$/, '')}${suffix}`
  const upstreamHeaders = {
    'Accept': String(request.headers.accept ?? 'application/json'),
    'Content-Type': String(request.headers['content-type'] ?? 'application/json'),
    'Idempotency-Key': String(request.headers['idempotency-key'] ?? ''),
    'X-Meguri-Tenant-ID': 'meguri-staging',
    'X-Meguri-User-ID': 'local-airi-user',
    'X-Meguri-Client-ID': 'airi',
    'X-Meguri-Session-ID': desktopSession,
  }
  // The remote-core bearer token is never sent to the loopback core.
  if (!useLocalCore) upstreamHeaders.Authorization = `Bearer ${coreToken}`
  const upstream = await fetch(upstreamUrl, {
    method: request.method,
    headers: upstreamHeaders,
    body,
    signal: controller.signal,
  })
  response.statusCode = upstream.status
  for (const header of ['content-type', 'cache-control', 'x-meguri-build-id']) {
    const value = upstream.headers.get(header)
    if (value)
      response.setHeader(header, value)
  }
  if (!upstream.body) {
    response.end()
    return
  }
  // An SSE consumer may navigate away or cancel its reader. `fetch` then
  // rejects the Web stream with AbortError; without an error listener Node
  // treats that as an unhandled stream error and kills the whole desktop
  // gateway. Treat client disconnect as normal cleanup, while preserving a
  // non-abort upstream failure for the response stream.
  const upstreamStream = Readable.fromWeb(upstream.body)
  const cleanup = () => {
    request.off('aborted', abortUpstream)
    response.off('close', closeUpstream)
  }
  const closeUpstream = () => {
    abortUpstream()
    if (!upstreamStream.destroyed)
      upstreamStream.destroy()
  }
  upstreamStream.once('end', cleanup)
  upstreamStream.once('close', cleanup)
  upstreamStream.on('error', error => {
    cleanup()
    if (error?.name !== 'AbortError' && !response.destroyed)
      response.destroy(error)
  })
  response.once('close', closeUpstream)
  upstreamStream.pipe(response)
}

function serveRecentArtifacts(request, response, requestUrl) {
  // Metadata-only feed of freshly generated reports for the desktop pet and the
  // AIRI stage. Cross-origin browser callers pass the same desktop-origin gate
  // as /core; same-origin page requests carry no Origin header and stay local.
  if (request.headers.origin !== undefined && !applyDesktopCors(request, response)) {
    response.writeHead(403); response.end('Desktop origin required'); return
  }
  if (request.method === 'OPTIONS') {
    response.writeHead(204); response.end(); return
  }
  if (request.method !== 'GET') {
    response.writeHead(405); response.end('Method not allowed'); return
  }
  const afterRaw = requestUrl.searchParams.get('after')
  const after = afterRaw === null ? Number.NaN : Number(afterRaw)
  const feed = collectRecentArtifacts(
    [{ root: reportRoot, hrefPrefix: '/reports' }, { root: outputRoot, hrefPrefix: '/output' }],
    after,
  )
  response.setHeader('Cache-Control', 'no-store')
  response.setHeader('Content-Type', 'application/json; charset=utf-8')
  response.end(JSON.stringify(feed))
}

const server = createServer(async (request, response) => {
  try {
    const requestUrl = new URL(request.url ?? '/', 'http://127.0.0.1')
    const pathname = requestUrl.pathname
    if (pathname === coreProxyPrefix || pathname.startsWith(`${coreProxyPrefix}/`)) {
      await proxyCoreRequest(request, response, requestUrl)
      return
    }
    if (pathname === '/artifacts/recent') {
      serveRecentArtifacts(request, response, requestUrl)
      return
    }
    const target = pathname.startsWith('/assets/')
      ? safeAssetPath(pathname)
      : pathname.startsWith('/reports/')
        ? safeChildPath(reportRoot, pathname, '/reports/')
        : pathname.startsWith('/output/')
          ? safeChildPath(outputRoot, pathname, '/output/')
          : resolve(webRoot, pathname === '/' ? 'index.html' : pathname.slice(1))
    if (!target || !existsSync(target) || !statSync(target).isFile()) {
      response.writeHead(404); response.end('Not found'); return
    }
    response.setHeader('Cache-Control', 'no-store')
    response.setHeader('Content-Type', contentTypes[extname(target).toLowerCase()] ?? 'application/octet-stream')
    if (target === resolve(webRoot, 'index.html')) {
      response.end(readFileSync(target, 'utf8'))
      return
    }
    createReadStream(target).pipe(response)
  } catch (error) {
    console.error('Meguri desktop gateway error:', error)
    if (!response.headersSent)
      response.writeHead(502)
    response.end('Bad gateway')
  }
})

server.listen(port, '127.0.0.1', () => console.log(`Meguri AIRI support gateway: http://127.0.0.1:${port}`))
