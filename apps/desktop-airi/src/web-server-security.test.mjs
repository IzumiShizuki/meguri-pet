import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import { matchesDesktopRequestIdentity } from './desktop-request-identity.mjs'

const serverSource = readFileSync(fileURLToPath(new URL('./web-server.mjs', import.meta.url)), 'utf8')
const pageSource = readFileSync(fileURLToPath(new URL('../web/index.html', import.meta.url)), 'utf8')

test('desktop renderer never receives the Core bearer credential', () => {
  assert.doesNotMatch(pageSource, /MEGURI_CORE_TOKEN|coreToken|Authorization.*Bearer/)
  assert.doesNotMatch(serverSource, /replaceAll\('__MEGURI_CORE_TOKEN__'/)
  assert.match(pageSource, /const coreUrl = '\/core'/)
  assert.match(serverSource, /upstreamHeaders\.Authorization = `Bearer \$\{coreToken\}`/)
})

test('desktop proxy binds upstream identity to the validated local session', () => {
  assert.match(serverSource, /x-meguri-desktop-session/)
  assert.match(serverSource, /Access-Control-Allow-Headers[\s\S]*X-Meguri-Desktop-Session/)
  assert.match(serverSource, /matchesDesktopRequestIdentity\(payload, desktopSession\)/)
  assert.match(serverSource, /'X-Meguri-User-ID': 'local-airi-user'/)
  assert.match(serverSource, /'X-Meguri-Client-ID': 'airi'/)
  assert.match(serverSource, /'X-Meguri-Session-ID': desktopSession/)
})

test('desktop proxy keeps validated multimodal and document sessions on the local Core', () => {
  assert.match(serverSource, /X-Meguri-Multimodal/)
  assert.match(serverSource, /hasMultimodalAttachment\(payload\)/)
  assert.match(serverSource, /multimodalRequested !== hasMultimodalContent/)
  assert.match(serverSource, /localMultimodalSessions\.set\(desktopSession/)
  assert.match(serverSource, /attachment\.content_access === 'document_read'/)
  assert.match(serverSource, /documentEditRequest \|\| sessionUsesLocalMultimodalCore\(desktopSession\)/)
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
