import assert from 'node:assert/strict'
import test from 'node:test'

import { WebsiteMeguriSession, WebsiteSessionStore, type KeyValueStorage } from '../adapters/website/src/index.ts'
import { MeguriApiClient, type FetchLike, type FetchResponse } from '../packages/client-sdk/src/index.ts'
import type { TurnEventEnvelope } from '../packages/protocol/src/index.ts'

class MemoryStorage implements KeyValueStorage {
  readonly values = new Map<string, string>()

  getItem(key: string): string | null { return this.values.get(key) ?? null }
  setItem(key: string, value: string): void { this.values.set(key, value) }
  removeItem(key: string): void { this.values.delete(key) }
}

function jsonResponse(value: unknown, status = 200): FetchResponse {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return value },
    body: null,
  }
}

function helloResponse() {
  return {
    selected_protocol_version: '1.1',
    server_capabilities_revision: 'website-caps-1',
    server_capabilities: {
      text: true,
      voice: false,
      sprite: true,
      screen_context: false,
      formal_memory: false,
      sse: true,
    },
    effective_capabilities: {
      text: true,
      voice: false,
      sprite: true,
      screen_context: false,
      formal_memory: false,
      sse: true,
    },
    granted_permissions: {
      screen_read: false,
      microphone: false,
      audio_playback: false,
      notifications: false,
      formal_memory_write: false,
    },
    supported_extensions: [],
  }
}

function streamResponse(chunks: string[]): FetchResponse {
  const encoder = new TextEncoder()
  return {
    ok: true,
    status: 200,
    async json() { return {} },
    body: new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks)
          controller.enqueue(encoder.encode(chunk))
        controller.close()
      },
    }),
  }
}

function envelope(sequence: number, type: TurnEventEnvelope['type'], data = {}): TurnEventEnvelope {
  return {
    protocol_version: '1.0',
    event_id: `event-web-${sequence}`,
    required: true,
    type,
    turn_id: 'turn-web-1',
    session_id: 'session-web-1',
    sequence,
    data,
    metadata: {
      trace_id: 'trace-web-1',
      source: 'meguri-core',
      created_at: '2026-07-13T00:00:00Z',
      build_id: 'meguri_v2_02c3db0c507d7c2d',
    },
  }
}

function sse(event: TurnEventEnvelope): string {
  return `id: ${event.sequence}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`
}

test('website session injects trusted identity and streams a complete turn', async () => {
  let body: Record<string, unknown> = {}
  let helloBody: Record<string, unknown> = {}
  const fetchImpl: FetchLike = async (input, init) => {
    if (String(input).endsWith('/v1/hello')) {
      helloBody = JSON.parse(String(init?.body))
      return jsonResponse(helloResponse())
    }
    if (String(input).endsWith('/v1/turns')) {
      body = JSON.parse(String(init?.body))
      return jsonResponse({
        turn_id: 'turn-web-1',
        session_id: 'session-web-1',
        build_id: 'meguri_v2_02c3db0c507d7c2d',
        status: 'accepted',
      }, 202)
    }
    return streamResponse([
      sse(envelope(1, 'turn.started')),
      sse(envelope(2, 'text.delta', { delta: 'hello ' })),
      sse(envelope(3, 'text.completed', { text: 'hello website' })),
      sse(envelope(4, 'turn.completed')),
    ])
  }
  const session = new WebsiteMeguriSession(
    new MeguriApiClient('http://127.0.0.1:8000', fetchImpl),
    { meguriUserId: 'bound-user-1', storageKey: 'login-1' },
    new MemoryStorage(),
    { createSessionId: () => 'session-web-1' },
  )
  const state = await session.send('hello', { idempotencyKey: 'web-request-1' })
  assert.deepEqual(helloBody.protocol_versions, ['1.1', '1.0'])
  assert.equal(
    (helloBody.identity as Record<string, Record<string, unknown>>).client_instance.profile,
    'website',
  )
  const identity = body.identity as Record<string, Record<string, unknown>>
  assert.equal(body.protocol_version, '1.1')
  assert.equal(identity.meguri_user.id, 'bound-user-1')
  assert.equal(identity.platform_actor.platform, 'meguri.website')
  assert.equal(identity.client_instance.profile, 'website')
  assert.equal(identity.session.id, 'session-web-1')
  assert.equal(state.text, 'hello website')
  assert.equal(state.status, 'completed')
  assert.equal(session.pendingTurnId, undefined)
  assert.equal(session.negotiatedProtocolVersion, '1.1')
  assert.equal(session.capabilitiesRevision, 'website-caps-1')
})

test('website session restores an interrupted active turn after reload', async () => {
  const storage = new MemoryStorage()
  let streamAttempt = 0
  const requestedAfter: string[] = []
  const fetchImpl: FetchLike = async (input) => {
    if (String(input).endsWith('/v1/hello'))
      return jsonResponse(helloResponse())
    if (String(input).endsWith('/v1/turns')) {
      return jsonResponse({
        turn_id: 'turn-web-1',
        session_id: 'session-web-1',
        build_id: 'meguri_v2_02c3db0c507d7c2d',
        status: 'accepted',
      }, 202)
    }
    streamAttempt += 1
    requestedAfter.push(new URL(String(input)).searchParams.get('after_sequence') ?? '')
    if (streamAttempt === 1)
      return streamResponse([
        sse(envelope(1, 'turn.started')),
        sse(envelope(2, 'text.delta', { delta: 'partial' })),
      ])
    return streamResponse([
      sse(envelope(3, 'text.completed', { text: 'restored' })),
      sse(envelope(4, 'turn.completed')),
    ])
  }
  const api = new MeguriApiClient('http://127.0.0.1:8000', fetchImpl)
  const identity = { meguriUserId: 'bound-user-1', storageKey: 'login-1' }
  const first = new WebsiteMeguriSession(api, identity, storage, {
    createSessionId: () => 'session-web-1',
  })
  await assert.rejects(first.send('hello', { maxReconnects: 0 }))
  assert.equal(first.pendingTurnId, 'turn-web-1')

  const restored = new WebsiteMeguriSession(api, identity, storage)
  const state = await restored.resume({ maxReconnects: 0 })
  assert.equal(restored.sessionId, 'session-web-1')
  assert.equal(state?.text, 'restored')
  assert.equal(restored.pendingTurnId, undefined)
  assert.deepEqual(requestedAfter, ['0', '2'])
})

test('website restores a 410 cursor from snapshot and persists it', async () => {
  const storage = new MemoryStorage()
  const fetchImpl: FetchLike = async (input) => {
    const url = String(input)
    if (url.endsWith('/v1/hello'))
      return jsonResponse(helloResponse())
    if (url.endsWith('/v1/turns')) {
      return jsonResponse({
        protocol_version: '1.1',
        turn_id: 'turn-web-1',
        session_id: 'session-web-1',
        build_id: 'build-1',
        status: 'accepted',
      }, 202)
    }
    if (url.includes('/events')) {
      return jsonResponse({
        protocol_version: '1.1',
        error: {
          code: 'CURSOR_EXPIRED',
          message: 'cursor expired',
          retryable: true,
        },
      }, 410)
    }
    return jsonResponse({
      protocol_version: '1.1',
      session_id: 'session-web-1',
      sequence: 40,
      turns: [{
        turn_id: 'turn-web-1',
        status: 'completed',
        text: 'snapshot complete',
      }],
      processed_event_ids: ['state-39'],
      processed_once_event_ids: ['tts-once-4'],
      created_at: '2026-07-28T00:00:00Z',
      future_optional: { ignored: true },
    })
  }
  const session = new WebsiteMeguriSession(
    new MeguriApiClient('http://127.0.0.1:8000', fetchImpl),
    { meguriUserId: 'bound-user-1', storageKey: 'login-snapshot' },
    storage,
    { createSessionId: () => 'session-web-1' },
  )
  const state = await session.send('hello')
  assert.equal(state.text, 'snapshot complete')
  const persisted = JSON.parse([...storage.values.values()][0])
  assert.equal(persisted.checkpoint.last_sequence, 40)
  assert.ok(persisted.checkpoint.processed_once_event_ids.includes('tts-once-4'))
})

test('website persists an event checkpoint before dispatching page side effects', async () => {
  const storage = new MemoryStorage()
  const requestedAfter: string[] = []
  let streamAttempt = 0
  const fetchImpl: FetchLike = async (input) => {
    if (String(input).endsWith('/v1/hello'))
      return jsonResponse(helloResponse())
    if (String(input).endsWith('/v1/turns')) {
      return jsonResponse({
        turn_id: 'turn-web-1',
        session_id: 'session-web-1',
        build_id: 'meguri_v2_02c3db0c507d7c2d',
        status: 'accepted',
      }, 202)
    }
    streamAttempt += 1
    requestedAfter.push(new URL(String(input)).searchParams.get('after_sequence') ?? '')
    if (streamAttempt === 1) {
      return streamResponse([
        sse(envelope(1, 'turn.started')),
        sse(envelope(2, 'text.delta', { delta: 'once' })),
      ])
    }
    return streamResponse([
      sse(envelope(3, 'text.completed', { text: 'complete' })),
      sse(envelope(4, 'turn.completed')),
    ])
  }
  const api = new MeguriApiClient('http://127.0.0.1:8000', fetchImpl)
  const identity = { meguriUserId: 'bound-user-1', storageKey: 'login-1' }
  const first = new WebsiteMeguriSession(api, identity, storage, {
    createSessionId: () => 'session-web-1',
  })

  await assert.rejects(first.send('hello', {
    maxReconnects: 0,
    onEvent(event) {
      if (event.sequence === 2)
        throw new Error('page effect failed')
    },
  }))

  const restored = new WebsiteMeguriSession(api, identity, storage)
  const state = await restored.resume({ maxReconnects: 0 })
  assert.equal(state?.text, 'complete')
  assert.deepEqual(requestedAfter, ['0', '2'])
})

test('website storage is isolated by host-provided identity key', () => {
  const storage = new MemoryStorage()
  const first = new WebsiteSessionStore(storage, 'login-a')
  const second = new WebsiteSessionStore(storage, 'login-b')
  first.save({ version: 1, sessionId: 'session-a' })
  second.save({ version: 1, sessionId: 'session-b' })
  assert.equal(first.load()?.sessionId, 'session-a')
  assert.equal(second.load()?.sessionId, 'session-b')
  assert.doesNotMatch(JSON.stringify([...storage.values]), /bound-user/)
})

test('website migrates a valid v1 session record to a v2 checkpoint', () => {
  const storage = new MemoryStorage()
  const store = new WebsiteSessionStore(storage, 'login-a')
  store.save({ version: 1, sessionId: 'session-a', activeTurnId: 'turn-a' })

  new WebsiteMeguriSession(
    new MeguriApiClient('http://127.0.0.1:8000', async () => jsonResponse({})),
    { meguriUserId: 'bound-user-1', storageKey: 'login-a' },
    storage,
  )

  const migrated = store.load()
  assert.equal(migrated?.version, 2)
  if (migrated?.version === 2) {
    assert.equal(migrated.checkpoint.last_sequence, 0)
    assert.deepEqual(migrated.checkpoint.processed_event_ids, [])
  }
})

test('website adapter exposes cancellation without clearing resumable state', async () => {
  let cancelledUrl = ''
  const fetchImpl: FetchLike = async (input, init) => {
    const url = String(input)
    if (url.endsWith('/v1/hello'))
      return jsonResponse(helloResponse())
    if (url.endsWith('/v1/turns')) {
      return jsonResponse({
        turn_id: 'turn-web-1',
        session_id: 'session-web-1',
        build_id: 'meguri_v2_02c3db0c507d7c2d',
        status: 'accepted',
      }, 202)
    }
    if (init?.method === 'POST') {
      cancelledUrl = url
      return jsonResponse({ turn_id: 'turn-web-1', status: 'cancel_requested' })
    }
    return streamResponse([])
  }
  const session = new WebsiteMeguriSession(
    new MeguriApiClient('http://127.0.0.1:8000', fetchImpl),
    { meguriUserId: 'bound-user-1', storageKey: 'login-1' },
    new MemoryStorage(),
    { createSessionId: () => 'session-web-1' },
  )
  await assert.rejects(session.send('hello', { maxReconnects: 0 }))
  await session.cancel()
  assert.match(cancelledUrl, /\/v1\/turns\/turn-web-1\/cancel$/)
  assert.equal(session.pendingTurnId, 'turn-web-1')
})

test('shared client rejects public and wildcard core URLs by default', () => {
  assert.throws(() => new MeguriApiClient('http://0.0.0.0:8000'))
  assert.throws(() => new MeguriApiClient('http://111.228.35.186:8000'))
  assert.doesNotThrow(() => new MeguriApiClient(
    'https://meguri.example.test',
    fetch as unknown as FetchLike,
    { allowNonLoopback: true },
  ))
})
