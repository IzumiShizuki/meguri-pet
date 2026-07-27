import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

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
  assert.match(serverSource, /payload\?\.user_id !== 'local-airi-user'/)
  assert.match(serverSource, /payload\?\.client_id !== 'airi'/)
  assert.match(serverSource, /payload\?\.session_id !== desktopSession/)
  assert.match(serverSource, /'X-Meguri-User-ID': 'local-airi-user'/)
  assert.match(serverSource, /'X-Meguri-Client-ID': 'airi'/)
  assert.match(serverSource, /'X-Meguri-Session-ID': desktopSession/)
})
