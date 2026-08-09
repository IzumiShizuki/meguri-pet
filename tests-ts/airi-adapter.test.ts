import assert from 'node:assert/strict'
import test from 'node:test'

import { MeguriApiAdapter, MeguriDesktopRuntime, type FetchLike, type FetchResponse } from '../adapters/airi/src/index.ts'
import { SessionTurnReducer, type TurnEventEnvelope, type TurnRequest } from '../packages/protocol/src/index.ts'
import { PngRenderer } from '../packages/renderer-contracts/src/index.ts'
import type { LocalTtsAdapter } from '../local-services/tts-adapter/src/index.ts'

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
    server_capabilities_revision: 'airi-caps-1',
    server_capabilities: {
      text: true,
      voice: true,
      sprite: true,
      screen_context: true,
      formal_memory: true,
      sse: true,
    },
    effective_capabilities: {
      text: true,
      voice: true,
      sprite: true,
      screen_context: false,
      formal_memory: false,
      sse: true,
    },
    granted_permissions: {
      screen_read: false,
      microphone: false,
      audio_playback: true,
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
    event_id: `event-airi-${sequence}`,
    required: true,
    type,
    turn_id: 'turn-1',
    session_id: 'session-1',
    sequence,
    data,
    metadata: {
      trace_id: 'trace-1',
      source: 'meguri-core',
      created_at: '2026-07-13T00:00:00Z',
      build_id: 'meguri_v2_02c3db0c507d7c2d',
    },
  }
}

function sse(event: TurnEventEnvelope): string {
  return `id: ${event.sequence}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`
}

const request: TurnRequest = {
  user_id: 'user-1',
  client_id: 'desktop_pet',
  session_id: 'session-1',
  message: 'hello',
  client_capabilities: {
    text: true,
    sprite: true,
    voice: true,
    screen_context: false,
  },
}

class MemoryAdapterStorage {
  readonly values = new Map<string, string>()
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  setItem(key: string, value: string): void { this.values.set(key, value) }
}

test('AIRI migrates a legacy checkpoint without sharing it with other sessions', () => {
  const storage = new MemoryAdapterStorage()
  storage.setItem('meguri.airi.adapter.v1', JSON.stringify({
    version: 1,
    checkpoint: {
      session_id: 'session-1',
      last_sequence: 3,
      processed_event_ids: ['event-1', 'event-2', 'event-3'],
      processed_once_event_ids: ['event-2'],
    },
  }))

  const adapter = new MeguriApiAdapter(
    'http://127.0.0.1:8000',
    async () => jsonResponse({}),
    { storage },
  )

  assert.equal(adapter.createReducer('session-1').lastSequence, 3)
  assert.equal(adapter.createReducer('session-2').lastSequence, 0)
  const migrated = JSON.parse([...storage.values.values()][0])
  assert.equal(migrated.version, 2)
  assert.deepEqual(migrated.checkpoints.map(
    (checkpoint: { session_id?: string }) => checkpoint.session_id,
  ), ['session-1'])
})

test('AIRI negotiates canonical identity and persists protocol state', async () => {
  const storage = new MemoryAdapterStorage()
  let helloBody: Record<string, unknown> = {}
  let turnBody: Record<string, unknown> = {}
  const fetchImpl: FetchLike = async (input, init) => {
    const url = String(input)
    if (url.endsWith('/v1/hello')) {
      helloBody = JSON.parse(String(init?.body))
      return jsonResponse(helloResponse())
    }
    turnBody = JSON.parse(String(init?.body))
    return jsonResponse({
      protocol_version: '1.1',
      turn_id: 'turn-1',
      session_id: 'session-1',
      build_id: 'build-1',
      status: 'accepted',
    }, 202)
  }
  const adapter = new MeguriApiAdapter(
    'http://127.0.0.1:8000',
    fetchImpl,
    {
      storage,
      identity: {
        platformActorId: 'owner-1',
        clientInstanceId: 'airi-install-1',
      },
    },
  )
  await adapter.createCanonicalTurn(request, 'airi-message-1')

  const helloIdentity = helloBody.identity as Record<string, Record<string, unknown>>
  const turnIdentity = turnBody.identity as Record<string, Record<string, unknown>>
  assert.deepEqual(helloBody.protocol_versions, ['1.1', '1.0'])
  assert.equal(helloIdentity.platform_actor.actor_id, 'owner-1')
  assert.equal(helloIdentity.client_instance.id, 'airi-install-1')
  assert.equal(turnBody.protocol_version, '1.1')
  assert.equal(turnIdentity.meguri_user.id, 'user-1')
  assert.equal(turnIdentity.session.id, 'session-1')
  assert.equal(adapter.selectedProtocolVersion, '1.1')
  assert.equal(adapter.serverCapabilitiesRevision, 'airi-caps-1')
  assert.match([...storage.values.values()][0], /airi-caps-1/)
})

test('AIRI checkpoints ONCE events before dispatching page side effects', async () => {
  const storage = new MemoryAdapterStorage()
  const fetchImpl: FetchLike = async (input) => {
    const url = String(input)
    if (url.endsWith('/v1/hello'))
      return jsonResponse(helloResponse())
    if (url.endsWith('/v1/turns')) {
      return jsonResponse({
        protocol_version: '1.1',
        turn_id: 'turn-1',
        session_id: 'session-1',
        build_id: 'build-1',
        status: 'accepted',
      }, 202)
    }
    return streamResponse([
      sse(envelope(1, 'turn.started')),
      sse({
        ...envelope(2, 'tts.requested', { text: 'speak once' }),
        event_id: 'tts-once-1',
        replay_policy: 'ONCE',
      }),
      sse(envelope(3, 'turn.completed')),
    ])
  }
  const renderer = new PngRenderer([
    { characterId: 'meguri', outfitCode: '01', expressionTag: 'neutral', intensity: 'low', spriteFile: 'neutral.png' },
  ])
  await renderer.loadCharacter('meguri')
  const runtime = new MeguriDesktopRuntime(
    new MeguriApiAdapter('http://127.0.0.1:8000', fetchImpl, { storage }),
    renderer,
  )
  let persistedBeforeCallback = false
  await runtime.send(request, {
    onEvent(event) {
      if (event.event_id !== 'tts-once-1')
        return
      const record = JSON.parse([...storage.values.values()][0])
      const checkpoint = record.checkpoints.find(
        (candidate: { session_id?: string }) => candidate.session_id === 'session-1',
      )
      persistedBeforeCallback = checkpoint.last_sequence === 2
        && checkpoint.processed_once_event_ids.includes('tts-once-1')
    },
  })
  assert.equal(persistedBeforeCallback, true)
})

test('AIRI isolates persisted checkpoints when one runtime switches sessions', async () => {
  const storage = new MemoryAdapterStorage()
  const fetchImpl: FetchLike = async (input, init) => {
    const url = new URL(String(input))
    if (url.pathname.endsWith('/v1/hello'))
      return jsonResponse(helloResponse())
    if (url.pathname.endsWith('/v1/turns')) {
      const body = JSON.parse(String(init?.body)) as {
        identity: { session: { id: string } }
      }
      const sessionId = body.identity.session.id
      return jsonResponse({
        protocol_version: '1.1',
        turn_id: `turn-${sessionId}`,
        session_id: sessionId,
        build_id: 'build-1',
        status: 'accepted',
      }, 202)
    }
    const sessionId = decodeURIComponent(url.pathname.split('/').at(-2) ?? '')
    const turnId = `turn-${sessionId}`
    const eventFor = (sequence: number, type: TurnEventEnvelope['type']): TurnEventEnvelope => ({
      ...envelope(sequence, type),
      event_id: `${sessionId}-${sequence}`,
      turn_id: turnId,
      session_id: sessionId,
    })
    return streamResponse([
      sse(eventFor(1, 'turn.started')),
      sse(eventFor(2, 'turn.completed')),
    ])
  }
  const renderer = new PngRenderer([
    { characterId: 'meguri', outfitCode: '01', expressionTag: 'neutral', intensity: 'low', spriteFile: 'neutral.png' },
  ])
  await renderer.loadCharacter('meguri')
  const runtime = new MeguriDesktopRuntime(
    new MeguriApiAdapter('http://127.0.0.1:8000', fetchImpl, { storage }),
    renderer,
  )

  await runtime.send({ ...request, session_id: 'session-1' })
  await runtime.send({ ...request, session_id: 'session-2' })

  const state = JSON.parse([...storage.values.values()][0]) as {
    checkpoints: Array<{ session_id: string, last_sequence: number }>
  }
  assert.deepEqual(
    state.checkpoints.map(checkpoint => [checkpoint.session_id, checkpoint.last_sequence]),
    [['session-1', 2], ['session-2', 2]],
  )
})

test('AIRI adapter creates turn with idempotency key and follows text events', async () => {
  const calls: Array<{ url: string, init?: RequestInit }> = []
  const fetchImpl: FetchLike = async (input, init) => {
    const url = String(input)
    calls.push({ url, init })
    if (url.endsWith('/v1/turns')) {
      return jsonResponse({
        turn_id: 'turn-1',
        session_id: 'session-1',
        build_id: 'meguri_v2_02c3db0c507d7c2d',
        status: 'accepted',
      }, 202)
    }
    return streamResponse([
      sse(envelope(1, 'turn.started')),
      sse(envelope(2, 'text.delta', { delta: 'hello ' })),
      sse(envelope(3, 'text.completed', { text: 'hello Meguri' })),
      sse(envelope(4, 'turn.completed')),
    ])
  }
  const adapter = new MeguriApiAdapter('http://127.0.0.1:8000', fetchImpl)
  const result = await adapter.runTurn(
    { ...request, execution_mode: 'FAST' },
    new SessionTurnReducer(), {
    idempotencyKey: 'desktop-turn-1',
    },
  )
  assert.equal(result.reducer.turns.get('turn-1')?.text, 'hello Meguri')
  assert.equal(result.reducer.turns.get('turn-1')?.status, 'completed')
  const headers = calls[0].init?.headers as Record<string, string>
  assert.equal(headers['Idempotency-Key'], 'desktop-turn-1')
  const body = JSON.parse(String(calls[0].init?.body)) as { execution_mode?: string }
  assert.equal(body.execution_mode, 'FAST')
})

test('AIRI adapter reconnects using last accepted sequence', async () => {
  const requestedAfter: string[] = []
  let streamAttempt = 0
  const fetchImpl: FetchLike = async (input) => {
    const url = new URL(String(input))
    requestedAfter.push(url.searchParams.get('after_sequence') ?? '')
    streamAttempt += 1
    if (streamAttempt === 1) {
      return streamResponse([
        sse(envelope(1, 'turn.started')),
        'data: incomplete',
      ])
    }
    return streamResponse([
      sse(envelope(2, 'text.completed', { text: 'reconnected' })),
      sse(envelope(3, 'turn.completed')),
    ])
  }
  const reducer = new SessionTurnReducer()
  await new MeguriApiAdapter('http://127.0.0.1:8000', fetchImpl)
    .followSession('session-1', reducer, { maxReconnects: 1, untilTurnId: 'turn-1' })
  assert.deepEqual(requestedAfter, ['0', '1'])
  assert.equal(reducer.turns.get('turn-1')?.text, 'reconnected')
})

test('AIRI adapter reconnects after clean stream drop before terminal event', async () => {
  let streamAttempt = 0
  const fetchImpl: FetchLike = async () => {
    streamAttempt += 1
    if (streamAttempt === 1)
      return streamResponse([sse(envelope(1, 'turn.started'))])
    return streamResponse([sse(envelope(2, 'turn.completed'))])
  }
  const reducer = new SessionTurnReducer()
  await new MeguriApiAdapter('http://127.0.0.1:8000', fetchImpl)
    .followSession('session-1', reducer, { maxReconnects: 1, untilTurnId: 'turn-1' })
  assert.equal(streamAttempt, 2)
  assert.equal(reducer.turns.get('turn-1')?.status, 'completed')
})

test('AIRI adapter exposes cancellation endpoint', async () => {
  let method = ''
  let url = ''
  const fetchImpl: FetchLike = async (input, init) => {
    method = init?.method ?? 'GET'
    url = String(input)
    return jsonResponse({ turn_id: 'turn-1', status: 'cancel_requested' })
  }
  await new MeguriApiAdapter('http://127.0.0.1:8000', fetchImpl).cancelTurn('turn-1')
  assert.equal(method, 'POST')
  assert.match(url, /\/v1\/turns\/turn-1\/cancel$/)
})

test('desktop runtime applies expression cue through CharacterRenderer', async () => {
  const fetchImpl: FetchLike = async (input) => {
    const url = String(input)
    if (url.endsWith('/v1/hello'))
      return jsonResponse(helloResponse())
    if (url.endsWith('/v1/turns')) {
      return jsonResponse({
        turn_id: 'turn-1',
        session_id: 'session-1',
        build_id: 'meguri_v2_02c3db0c507d7c2d',
        status: 'accepted',
      }, 202)
    }
    return streamResponse([
      sse(envelope(1, 'turn.started')),
      sse(envelope(2, 'expression.cue', {
        expression_tag: 'happy',
        expression_intensity: 'medium',
        outfit_code: '01',
      })),
      sse(envelope(3, 'turn.completed')),
    ])
  }
  const renderer = new PngRenderer([
    { characterId: 'meguri', outfitCode: '01', expressionTag: 'neutral', intensity: 'low', spriteFile: 'neutral.png' },
    { characterId: 'meguri', outfitCode: '01', expressionTag: 'happy', intensity: 'medium', spriteFile: 'happy.png' },
  ])
  await renderer.loadCharacter('meguri')
  const runtime = new MeguriDesktopRuntime(
    new MeguriApiAdapter('http://127.0.0.1:8000', fetchImpl),
    renderer,
  )
  const turnId = await runtime.send(request)
  assert.equal(turnId, 'turn-1')
  assert.equal(renderer.snapshot().spriteFile, 'happy.png')
})

test('desktop runtime keeps completed text when local TTS fails', async () => {
  const fetchImpl: FetchLike = async (input) => {
    const url = String(input)
    if (url.endsWith('/v1/hello'))
      return jsonResponse(helloResponse())
    if (url.endsWith('/v1/turns')) {
      return jsonResponse({
        turn_id: 'turn-1',
        session_id: 'session-1',
        build_id: 'meguri_v2_02c3db0c507d7c2d',
        status: 'accepted',
      }, 202)
    }
    return streamResponse([
      sse(envelope(1, 'turn.started')),
      sse(envelope(2, 'semantic.completed', {
        voice_style: 'soft',
        expression_intensity: 'medium',
      })),
      sse(envelope(3, 'text.completed', { text: 'text survives' })),
      sse(envelope(4, 'turn.completed')),
    ])
  }
  const renderer = new PngRenderer([
    { characterId: 'meguri', outfitCode: '01', expressionTag: 'neutral', intensity: 'low', spriteFile: 'neutral.png' },
  ])
  await renderer.loadCharacter('meguri')
  const failingTts: LocalTtsAdapter = {
    async synthesize() { throw new Error('offline') },
    async cancel() {},
  }
  const runtime = new MeguriDesktopRuntime(
    new MeguriApiAdapter('http://127.0.0.1:8000', fetchImpl),
    renderer,
    undefined,
    failingTts,
  )
  await runtime.send(request)
  assert.equal(runtime.reducer.turns.get('turn-1')?.text, 'text survives')
  assert.equal(runtime.reducer.turns.get('turn-1')?.status, 'completed')
})
