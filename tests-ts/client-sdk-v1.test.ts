import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MeguriApiClient,
  MeguriApiError,
  type FetchLike,
  type FetchResponse,
} from '../packages/client-sdk/src/index.ts'
import {
  SessionTurnReducer,
  type ClientHello,
  type SessionSnapshot,
  type TurnEventEnvelope,
} from '../packages/protocol/src/index.ts'

const helloRequest: ClientHello = {
  protocol_versions: ['1.1', '1.0'],
  identity: {
    meguri_user: { id: 'user-1' },
    platform_actor: { platform: 'website', actor_id: 'account-1' },
    client_instance: { id: 'tab-1', profile: 'website' },
    session: { id: 'session-1' },
  },
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
}

test('hello records selected protocol and server capability revision', async () => {
  const api = new MeguriApiClient('http://127.0.0.1:8000', async () => jsonResponse({
    selected_protocol_version: '1.1',
    server_capabilities_revision: 'caps-7',
    server_capabilities: helloRequest.capabilities,
    effective_capabilities: helloRequest.capabilities,
    granted_permissions: helloRequest.permissions,
    supported_extensions: [],
  }))
  const response = await api.hello(helloRequest)
  assert.equal(response.selected_protocol_version, '1.1')
  assert.equal(response.server_capabilities_revision, 'caps-7')
})

test('cursor expired restores snapshot then reconnects from snapshot sequence', async () => {
  const requestedAfter: string[] = []
  let eventsAttempt = 0
  const fetchImpl: FetchLike = async (input) => {
    const url = new URL(String(input))
    if (url.pathname.endsWith('/snapshot'))
      return jsonResponse(snapshot(5, 'running', 'from snapshot'))
    requestedAfter.push(url.searchParams.get('after_sequence') ?? '')
    eventsAttempt += 1
    if (eventsAttempt === 1) {
      return jsonResponse({
        protocol_version: '1.0',
        error: { code: 'CURSOR_EXPIRED', message: 'cursor expired', retryable: true },
      }, 410)
    }
    return streamResponse([
      sse(envelope(6, 'text.completed', { text: 'complete after snapshot' })),
      sse(envelope(7, 'turn.completed')),
    ])
  }
  const reducer = new SessionTurnReducer()
  await new MeguriApiClient('http://127.0.0.1:8000', fetchImpl)
    .subscribe('session-1', reducer, { untilTurnId: 'turn-1', maxReconnects: 1 })
  assert.deepEqual(requestedAfter, ['0', '5'])
  assert.equal(reducer.turns.get('turn-1')?.text, 'complete after snapshot')
  assert.equal(reducer.turns.get('turn-1')?.status, 'completed')
})

test('SSE unsupported falls back to polling the authoritative snapshot', async () => {
  let snapshotAttempt = 0
  const fetchImpl: FetchLike = async (input) => {
    const url = String(input)
    if (url.endsWith('/events?after_sequence=0'))
      return jsonResponse({ error: 'not acceptable' }, 406)
    snapshotAttempt += 1
    return jsonResponse(snapshot(
      snapshotAttempt,
      snapshotAttempt === 1 ? 'running' : 'completed',
      snapshotAttempt === 1 ? 'partial' : 'complete',
    ))
  }
  const reducer = new SessionTurnReducer()
  await new MeguriApiClient('http://127.0.0.1:8000', fetchImpl, { pollIntervalMs: 0 })
    .subscribe('session-1', reducer, { untilTurnId: 'turn-1' })
  assert.equal(snapshotAttempt, 2)
  assert.equal(reducer.turns.get('turn-1')?.text, 'complete')
})

test('getTurn, cancel, and synchronous helper use one asynchronous turn resource', async () => {
  const calls: string[] = []
  let getAttempt = 0
  const fetchImpl: FetchLike = async (input, init) => {
    const url = String(input)
    calls.push(`${init?.method ?? 'GET'} ${new URL(url).pathname}`)
    if (url.endsWith('/cancel')) {
      return jsonResponse({
        protocol_version: '1.0',
        turn_id: 'turn-1',
        status: 'cancel_requested',
      })
    }
    getAttempt += 1
    return jsonResponse({
      protocol_version: '1.0',
      turn_id: 'turn-1',
      session_id: 'session-1',
      build_id: 'build-1',
      status: getAttempt === 1 ? 'running' : 'completed',
      created_at: '2026-07-28T00:00:00Z',
      updated_at: '2026-07-28T00:00:01Z',
      output_text: 'same turn',
    })
  }
  const api = new MeguriApiClient('http://127.0.0.1:8000', fetchImpl, { pollIntervalMs: 0 })
  assert.equal((await api.getTurn('turn-1')).status, 'running')
  assert.equal((await api.waitForTurnTerminal('turn-1')).turn_id, 'turn-1')
  assert.equal((await api.cancel('turn-1')).status, 'cancel_requested')
  assert.deepEqual(calls, [
    'GET /v1/turns/turn-1',
    'GET /v1/turns/turn-1',
    'POST /v1/turns/turn-1/cancel',
  ])
})

test('stable protocol errors are surfaced by code and retryability', async () => {
  const api = new MeguriApiClient('http://127.0.0.1:8000', async () => jsonResponse({
    protocol_version: '1.0',
    error: {
      code: 'CAPABILITY_UNAVAILABLE',
      message: 'voice is unavailable',
      retryable: false,
    },
  }, 409))
  await assert.rejects(
    api.getTurn('turn-1'),
    (error: unknown) => error instanceof MeguriApiError
      && error.code === 'CAPABILITY_UNAVAILABLE'
      && error.retryable === false,
  )
})

function snapshot(
  sequence: number,
  status: SessionSnapshot['turns'][number]['status'],
  text: string,
): SessionSnapshot {
  return {
    protocol_version: '1.0',
    session_id: 'session-1',
    sequence,
    turns: [{ turn_id: 'turn-1', status, text }],
    processed_event_ids: ['state-event-before-snapshot'],
    processed_once_event_ids: ['tts-once-1'],
    created_at: '2026-07-28T00:00:00Z',
  }
}

function envelope(
  sequence: number,
  type: string,
  data: Record<string, unknown> = {},
): TurnEventEnvelope {
  return {
    protocol_version: '1.0',
    event_id: `event-${sequence}`,
    required: true,
    replay_policy: 'STATE',
    type,
    turn_id: 'turn-1',
    session_id: 'session-1',
    sequence,
    created_at: '2026-07-28T00:00:00Z',
    data,
    metadata: {
      trace_id: 'trace-1',
      source: 'meguri-core',
      created_at: '2026-07-28T00:00:00Z',
      build_id: 'build-1',
    },
  }
}

function sse(event: TurnEventEnvelope): string {
  return `id: ${event.sequence}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`
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
