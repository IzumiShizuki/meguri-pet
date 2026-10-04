import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { MeguriApiClient } from '@meguri/client-sdk'
import { SessionTurnReducer } from '@meguri/protocol'

import { createClipboardImageAttachment, inspectDroppedAttachment } from '../src/main/attachment-policy.mjs'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const projectRoot = resolve(appRoot, '../..')
const gatewayUrl = process.env.MEGURI_SMOKE_GATEWAY_URL ?? 'http://127.0.0.1:15173/core'
const mode = process.env.MEGURI_SMOKE_MODE === 'image' ? 'image' : 'document'
const documentPath = resolve(process.env.MEGURI_SMOKE_DOCUMENT
  ?? resolve(appRoot, 'test-fixtures/readable-note.md'))
const imagePath = resolve(process.env.MEGURI_SMOKE_IMAGE
  ?? resolve(projectRoot, 'data/meguri/assets/sprites/meguri/ce01001m.png'))
const sessionId = `meguri-attachment-smoke-${Date.now()}`
const identity = {
  meguri_user: { id: 'local-airi-user' },
  platform_actor: { platform: 'desktop', actor_id: 'local-smoke' },
  client_instance: { id: `smoke-${process.pid}`, profile: 'desktop_pet' },
  session: { id: sessionId },
}

const documentResult = inspectDroppedAttachment(documentPath)
if (!documentResult.ok)
  throw new Error(documentResult.error)
const screenshotResult = createClipboardImageAttachment(readFileSync(imagePath))
if (!screenshotResult.ok)
  throw new Error(screenshotResult.error)

const desktopFetch = async (input, init = {}) => {
  const headers = new Headers(init.headers)
  headers.set('User-Agent', 'Electron/43.1.1 MeguriAttachmentSmoke')
  headers.set('X-Meguri-Desktop-Client', 'meguri')
  headers.set('X-Meguri-Desktop-Session', sessionId)
  if (typeof init.body === 'string' && /"content_access":"(?:multimodal|document)_read"/u.test(init.body))
    headers.set('X-Meguri-Multimodal', 'true')
  return fetch(input, { ...init, headers })
}

const client = new MeguriApiClient(gatewayUrl, desktopFetch)
const hello = await client.hello({
  protocol_versions: ['1.1', '1.0'],
  identity,
  capabilities: {
    text: true,
    voice: false,
    sprite: true,
    screen_context: false,
    formal_memory: false,
    sse: true,
  },
  permissions: {
    screen_read: false,
    microphone: false,
    audio_playback: false,
    notifications: false,
    formal_memory_write: false,
  },
})
const attachments = mode === 'image' ? [screenshotResult.attachment] : [documentResult.attachment]
const created = await client.createTurn({
  protocol_version: hello.selected_protocol_version,
  identity,
  message: mode === 'image'
    ? '这是本地截图附件链路验证。请简短描述你看到的图像。'
    : '这是本地文档附件链路验证。请读取文档中的精确验证短语，只需简短回答。',
  attachments,
}, `attachment-smoke-${Date.now()}`)

const reducer = new SessionTurnReducer()
const eventTypes = []
let responseText = ''
let failureText = ''
await client.subscribe(sessionId, reducer, {
  untilTurnId: created.turn_id,
  signal: AbortSignal.timeout(120_000),
  onEvent: event => {
    eventTypes.push(event.type)
    if (event.type === 'text.completed' && typeof event.data.text === 'string')
      responseText = event.data.text
    if (event.type === 'turn.failed' && typeof event.data.error === 'string')
      failureText = event.data.error
  },
})
const terminalType = eventTypes.findLast(type => type.startsWith('turn.'))
const documentReadSucceeded = mode === 'document' && terminalType === 'turn.completed'
  && eventTypes.includes('text.completed')
  && responseText.includes('MEGURI-DROP-READY-819')
const visionGateSucceeded = mode === 'image' && terminalType === 'turn.failed'
  && failureText.includes('not configured for image or PDF attachments')
const succeeded = documentReadSucceeded || visionGateSucceeded

console.log(JSON.stringify({
  ok: succeeded,
  mode,
  document_read_ok: documentReadSucceeded,
  vision_gate_ok: visionGateSucceeded,
  session_id: sessionId,
  turn_id: created.turn_id,
  terminal_event: terminalType,
  attachment_kinds: attachments.map(attachment => ({
    type: attachment.type,
    content_access: attachment.content_access,
    name: attachment.name,
    size_bytes: attachment.size_bytes,
  })),
  event_types: [...new Set(eventTypes)],
  response_text: responseText,
  failure_text: failureText,
}, null, 2))

if (!succeeded)
  process.exitCode = 1
