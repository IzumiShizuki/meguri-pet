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
  const fetchImpl: FetchLike = async (input, init) => {
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
  assert.equal(body.user_id, 'bound-user-1')
  assert.equal(body.client_id, 'website')
  assert.equal(state.text, 'hello website')
  assert.equal(state.status, 'completed')
  assert.equal(session.pendingTurnId, undefined)
})

test('website session restores an interrupted active turn after reload', async () => {
  const storage = new MemoryStorage()
  let streamAttempt = 0
  const fetchImpl: FetchLike = async (input) => {
    if (String(input).endsWith('/v1/turns')) {
      return jsonResponse({
        turn_id: 'turn-web-1',
        session_id: 'session-web-1',
        build_id: 'meguri_v2_02c3db0c507d7c2d',
        status: 'accepted',
      }, 202)
    }
    streamAttempt += 1
    if (streamAttempt === 1)
      return streamResponse([sse(envelope(1, 'turn.started'))])
    return streamResponse([
      sse(envelope(1, 'turn.started')),
      sse(envelope(2, 'text.completed', { text: 'restored' })),
      sse(envelope(3, 'turn.completed')),
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

test('website adapter exposes cancellation without clearing resumable state', async () => {
  let cancelledUrl = ''
  const fetchImpl: FetchLike = async (input, init) => {
    const url = String(input)
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
