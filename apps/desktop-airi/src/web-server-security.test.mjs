import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { createServer } from 'node:net'
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
  assert.match(serverSource, /if \(!useLocalCore\) upstreamHeaders\.Authorization/)
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
