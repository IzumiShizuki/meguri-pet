import { createReadStream, existsSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import { extname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const webRoot = resolve(root, 'apps/desktop-airi/web')
const assetRoot = resolve(root, 'data/meguri/assets')
const reportRoot = resolve(root, 'reports')
const outputRoot = resolve(root, 'output')
const port = Number(process.env.MEGURI_DESKTOP_PORT ?? 5173)

const contentTypes = { '.html': 'text/html; charset=utf-8', '.md': 'text/markdown; charset=utf-8', '.pdf': 'application/pdf', '.png': 'image/png', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8' }

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

const server = createServer((request, response) => {
  try {
    const pathname = new URL(request.url ?? '/', 'http://127.0.0.1').pathname
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
    createReadStream(target).pipe(response)
  } catch {
    response.writeHead(400); response.end('Bad request')
  }
})

server.listen(port, '127.0.0.1', () => console.log(`Meguri desktop home: http://127.0.0.1:${port}`))
