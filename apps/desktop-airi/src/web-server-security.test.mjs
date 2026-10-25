import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { createServer } from 'node:net'
import { createServer as createHttpServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import { matchesDesktopRequestIdentity } from './desktop-request-identity.mjs'

const serverSource = readFileSync(fileURLToPath(new URL('./web-server.mjs', import.meta.url)), 'utf8')
const pageSource = readFileSync(fileURLToPath(new URL('../web/index.html', import.meta.url)), 'utf8')

async function availablePort() {
  const probe = createServer()
  probe.listen(0, '127.0.0.1')
  await once(probe, 'listening')
  const address = probe.address()
  if (!address || typeof address === 'string')
    throw new Error('failed to reserve a desktop gateway test port')
  await new Promise((resolve, reject) => probe.close(error => error ? reject(error) : resolve()))
  return address.port
}

test('desktop renderer never receives the Core bearer credential', () => {
  assert.doesNotMatch(pageSource, /MEGURI_CORE_TOKEN|coreToken|Authorization.*Bearer/)
  assert.doesNotMatch(serverSource, /replaceAll\('__MEGURI_CORE_TOKEN__'/)
  assert.match(pageSource, /const coreUrl = '\/core'/)
  assert.match(serverSource, /upstreamHeaders\.Authorization = `Bearer \$\{coreToken\}`/)
})

test('desktop proxy binds upstream identity to the validated local session and client', () => {
  assert.match(serverSource, /x-meguri-desktop-session/)
  assert.match(serverSource, /Access-Control-Allow-Headers[\s\S]*X-Meguri-Desktop-Session/)
  assert.match(serverSource, /matchesDesktopRequestIdentity\(payload, desktopSession, desktopClient\)/)
  assert.match(serverSource, /'X-Meguri-User-ID': 'local-airi-user'/)
  assert.match(serverSource, /'X-Meguri-Client-ID': desktopClient === 'meguri' \? 'desktop_pet' : 'airi'/)
  assert.match(serverSource, /'X-Meguri-Session-ID': desktopSession/)
})

test('desktop proxy keeps validated multimodal and document sessions on the local Core', () => {
  assert.match(serverSource, /X-Meguri-Multimodal/)
  assert.match(serverSource, /hasMultimodalAttachment\(payload\)/)
  assert.match(serverSource, /multimodalRequested !== hasMultimodalContent/)
  assert.match(serverSource, /localMultimodalSessions\.set\(desktopSession/)
  assert.match(serverSource, /attachment\.content_access === 'document_read'/)
  assert.match(serverSource, /documentEditRequest \|\| skillManagementRequest[\s\S]*sessionUsesLocalMultimodalCore\(desktopSession\)/)
  assert.match(serverSource, /if \(!useLocalCore \|\| memoryRequest \|\| trainingRequest\) upstreamHeaders\.Authorization/)
})

test('memory management uses named routes and forwards credentials only from the trusted gateway', () => {
  assert.match(serverSource, /const memoryRequest = corePath\.startsWith\('\/v1\/memory\/desktop\/'\)/)
  assert.match(serverSource, /memoryRequest && \(desktopClient !== 'meguri'/)
  assert.match(serverSource, /'X-Request-ID': String\(request.headers\['x-request-id'\]/)
  assert.match(serverSource, /!coreToken && \(!useLocalCore \|\| memoryRequest \|\| trainingRequest\)/)
})

test('desktop proxy consumes abort errors from a cancelled upstream SSE stream', () => {
  assert.match(serverSource, /const upstreamStream = Readable\.fromWeb\(upstream\.body\)/)
  assert.match(serverSource, /upstreamStream\.on\('error', error =>/)
  assert.match(serverSource, /error\?\.name !== 'AbortError'/)
  assert.match(serverSource, /response\.once\('close', closeUpstream\)/)
})

test('desktop proxy accepts the canonical AIRI protocol identity', () => {
  assert.equal(matchesDesktopRequestIdentity({
    identity: {
      meguri_user: { id: 'local-airi-user' },
      client_instance: { id: 'airi-install-01', profile: 'airi' },
      session: { id: 'airi-desktop' },
    },
  }, 'airi-desktop'), true)
})

test('desktop proxy accepts the legacy flat identity', () => {
  assert.equal(matchesDesktopRequestIdentity({
    user_id: 'local-airi-user',
    client_id: 'airi',
    session_id: 'airi-desktop',
  }, 'airi-desktop'), true)
})

test('desktop proxy preflight allows the SDK SSE headers', async () => {
  const port = await availablePort()
  const child = spawn(process.execPath, [fileURLToPath(new URL('./web-server.mjs', import.meta.url))], {
    env: { ...process.env, MEGURI_DESKTOP_PORT: String(port) },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  try {
    await Promise.race([
      once(child.stdout, 'data'),
      new Promise((_, reject) => setTimeout(() => reject(new Error('desktop gateway did not start')), 5_000)),
    ])
    const response = await fetch(`http://127.0.0.1:${port}/core/v1/sessions/test/events`, {
      method: 'OPTIONS',
      headers: {
        Origin: 'http://127.0.0.1:4174',
        'Access-Control-Request-Method': 'GET',
        'Access-Control-Request-Headers': 'last-event-id,meguri-protocol-version,x-meguri-desktop-client,x-meguri-desktop-session',
      },
    })
    assert.equal(response.status, 204)
    assert.match(response.headers.get('access-control-allow-headers') ?? '', /Meguri-Protocol-Version/i)
    assert.match(response.headers.get('access-control-allow-headers') ?? '', /Last-Event-ID/i)
  }
  finally {
    child.kill()
    await once(child, 'exit')
  }
})

test('desktop proxy keeps Skill catalog management on the local Core', () => {
  assert.match(serverSource, /skillManagementRequest = corePath === '\/internal\/v1\/skills'/)
  assert.match(serverSource, /corePath\.startsWith\('\/internal\/v1\/skill-sources\/'\)/)
  assert.match(serverSource, /resourceRequest \|\| documentEditRequest \|\| skillManagementRequest/)
})

test('desktop proxy accepts the native Meguri desktop identity', () => {
  assert.equal(matchesDesktopRequestIdentity({
    identity: {
      meguri_user: { id: 'local-airi-user' },
      client_instance: { id: 'meguri-desktop-install-01', profile: 'desktop_pet' },
      session: { id: 'meguri-desktop' },
    },
  }, 'meguri-desktop', 'meguri'), true)
  assert.equal(matchesDesktopRequestIdentity({
    user_id: 'local-airi-user',
    client_id: 'desktop_pet',
    session_id: 'meguri-desktop',
  }, 'meguri-desktop', 'meguri'), true)
})

test('desktop proxy rejects missing, mismatched, or conflicting identities', () => {
  assert.equal(matchesDesktopRequestIdentity({}, 'airi-desktop'), false)
  assert.equal(matchesDesktopRequestIdentity({
    identity: {
      meguri_user: { id: 'another-user' },
      client_instance: { profile: 'airi' },
      session: { id: 'airi-desktop' },
    },
  }, 'airi-desktop'), false)
  assert.equal(matchesDesktopRequestIdentity({
    user_id: 'local-airi-user',
    client_id: 'airi',
    session_id: 'airi-desktop',
    identity: {
      meguri_user: { id: 'local-airi-user' },
      client_instance: { profile: 'airi' },
      session: { id: 'another-session' },
    },
  }, 'airi-desktop'), false)
})

test('memory proxy forwards trusted host identity and refuses foreign clients and unknown paths', async () => {
  const port = await availablePort()
  const temporary = mkdtempSync(join(tmpdir(), 'meguri-memory-gateway-'))
  const tokenFile = join(temporary, 'desktop-token.txt')
  writeFileSync(tokenFile, 'synthetic-gateway-token')
  const received = []
  const upstream = createHttpServer((request, response) => {
    const value = { path: request.url, headers: request.headers, body: '' }
    received.push(value)
    request.on('data', data => { value.body += data.toString('utf8') })
    request.on('end', () => {
      response.setHeader('Content-Type', 'application/json')
      response.end('{"available":true,"provider":"native_pgvector"}')
    })
  })
  upstream.listen(0, '127.0.0.1')
  await once(upstream, 'listening')
  const address = upstream.address()
  const child = spawn(process.execPath, [fileURLToPath(new URL('./web-server.mjs', import.meta.url))], {
    env: { ...process.env, MEGURI_DESKTOP_PORT: String(port), MEGURI_CORE_URL: `http://127.0.0.1:${address.port}`, MEGURI_CORE_AUTH_TOKEN_FILE: tokenFile, MEGURI_DESKTOP_TENANT_ID: 'tenant-test' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const headers = { Origin: 'http://127.0.0.1:4174', 'User-Agent': 'test Electron/43.1.1', 'X-Meguri-Desktop-Client': 'meguri', 'X-Meguri-Desktop-Session': 'session-test' }
  try {
    await Promise.race([once(child.stdout, 'data'), new Promise((_, reject) => setTimeout(() => reject(new Error('gateway startup timed out')), 5000))])
    const result = await fetch(`http://127.0.0.1:${port}/core/v1/memory/desktop/status`, { headers: { ...headers, 'X-Meguri-User-ID': 'foreign-user', Authorization: 'Bearer browser-supplied' } })
    assert.equal(result.status, 200)
    assert.equal(received[0].headers.authorization, 'Bearer synthetic-gateway-token')
    assert.equal(received[0].headers['x-meguri-user-id'], 'local-airi-user')
    assert.equal(received[0].headers['x-meguri-client-id'], 'desktop_pet')
    assert.equal(received[0].headers['x-meguri-session-id'], 'session-test')
    assert.equal(received[0].headers['x-meguri-tenant-id'], 'tenant-test')
    const memoryPath = '/v1/memory/desktop/memories/11111111-1111-4111-8111-111111111111'
    assert.equal((await fetch(`http://127.0.0.1:${port}/core${memoryPath}`, { headers })).status, 200)
    assert.equal(received[1].path, memoryPath)
    const correction = { base_version_id: '22222222-2222-4222-8222-222222222222', content_text: 'User prefers tea', change_reason: 'Owner correction' }
    assert.equal((await fetch(`http://127.0.0.1:${port}/core${memoryPath}/corrections`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json', 'X-Request-ID': 'correction-retry-1', Authorization: 'Bearer untrusted' }, body: JSON.stringify(correction),
    })).status, 200)
    assert.equal(received[2].path, `${memoryPath}/corrections`)
    assert.deepEqual(JSON.parse(received[2].body), correction)
    assert.equal(received[2].headers['x-request-id'], 'correction-retry-1')
    assert.equal(received[2].headers.authorization, 'Bearer synthetic-gateway-token')
    assert.equal(received[2].headers['x-meguri-user-id'], 'local-airi-user')
    assert.equal(received[2].headers['x-meguri-session-id'], 'session-test')
    assert.equal((await fetch(`http://127.0.0.1:${port}/core${memoryPath}`, { method: 'POST', headers })).status, 403)
    assert.equal((await fetch(`http://127.0.0.1:${port}/core${memoryPath}/corrections`, { headers })).status, 403)
    assert.equal((await fetch(`http://127.0.0.1:${port}/core${memoryPath}`, { headers: { ...headers, 'X-Meguri-Desktop-Client': 'airi' } })).status, 403)
    assert.equal((await fetch(`http://127.0.0.1:${port}/core/v1/memory/desktop/status`, { headers: { ...headers, 'X-Meguri-Desktop-Client': 'airi' } })).status, 403)
    assert.equal((await fetch(`http://127.0.0.1:${port}/core/v1/memory/desktop/arbitrary-admin`, { headers })).status, 403)
    assert.equal((await fetch(`http://127.0.0.1:${port}/core/v1/memory/desktop/export`, { method: 'DELETE', headers })).status, 403)
    assert.equal(received.length, 3)
  }
  finally {
    child.kill()
    await once(child, 'exit')
    await new Promise(resolve => upstream.close(resolve))
    rmSync(temporary, { recursive: true, force: true })
  }
})

test('training control routes bind session identity, use host credentials and preserve retry keys', async () => {
  const port = await availablePort()
  const temporary = mkdtempSync(join(tmpdir(), 'meguri-training-gateway-'))
  const tokenFile = join(temporary, 'desktop-token.txt')
  writeFileSync(tokenFile, 'synthetic-training-token')
  const received = []
  const upstream = createHttpServer((request, response) => {
    received.push({ path: request.url, headers: request.headers })
    request.resume()
    response.setHeader('Content-Type', 'application/json')
    response.end('{"pending":[],"receipts":[]}')
  })
  upstream.listen(0, '127.0.0.1')
  await once(upstream, 'listening')
  const address = upstream.address()
  const child = spawn(process.execPath, [fileURLToPath(new URL('./web-server.mjs', import.meta.url))], {
    env: { ...process.env, MEGURI_DESKTOP_PORT: String(port), MEGURI_CORE_URL: `http://127.0.0.1:${address.port}`, MEGURI_CORE_AUTH_TOKEN_FILE: tokenFile, MEGURI_DESKTOP_TENANT_ID: 'training-tenant-test' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const headers = { Origin: 'http://127.0.0.1:4174', 'User-Agent': 'test Electron/43.1.1', 'X-Meguri-Desktop-Client': 'meguri', 'X-Meguri-Desktop-Session': 'previous-session' }
  try {
    await Promise.race([once(child.stdout, 'data'), new Promise((_, reject) => setTimeout(() => reject(new Error('gateway startup timed out')), 5000))])
    const endpoint = `http://127.0.0.1:${port}/core/v1/training/desktop`
    const result = await fetch(`${endpoint}/pending`, { headers: { ...headers, 'X-Meguri-User-ID': 'foreign-user', Authorization: 'Bearer browser-supplied' } })
    assert.equal(result.status, 200)
    assert.equal(received[0].headers.authorization, 'Bearer synthetic-training-token')
    assert.equal(received[0].headers['x-meguri-user-id'], 'local-airi-user')
    assert.equal(received[0].headers['x-meguri-client-id'], 'desktop_pet')
    assert.equal(received[0].headers['x-meguri-session-id'], 'previous-session')
    assert.equal(received[0].headers['x-meguri-tenant-id'], 'training-tenant-test')
    const revoke = await fetch(`${endpoint}/feedback/feedback-12345678-1234-1234-1234-123456789abc/revoke`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json', 'Idempotency-Key': 'stable-revoke-key' }, body: '{}',
    })
    assert.equal(revoke.status, 200)
    assert.equal(received[1].headers['idempotency-key'], 'stable-revoke-key')
    assert.equal(received[1].headers['x-meguri-session-id'], 'previous-session')
    assert.equal((await fetch(`${endpoint}/pending`, { headers: { ...headers, 'X-Meguri-Desktop-Client': 'airi' } })).status, 403)
    assert.equal((await fetch(`${endpoint}/arbitrary-export`, { headers })).status, 403)
    assert.equal((await fetch(`${endpoint}/feedback`, { method: 'DELETE', headers })).status, 403)
    assert.equal(received.length, 2)
  }
  finally {
    child.kill()
    await once(child, 'exit')
    await new Promise(resolve => upstream.close(resolve))
    rmSync(temporary, { recursive: true, force: true })
  }
})
