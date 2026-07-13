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
  const result = await adapter.runTurn(request, new SessionTurnReducer(), {
    idempotencyKey: 'desktop-turn-1',
  })
  assert.equal(result.reducer.turns.get('turn-1')?.text, 'hello Meguri')
  assert.equal(result.reducer.turns.get('turn-1')?.status, 'completed')
  const headers = calls[0].init?.headers as Record<string, string>
  assert.equal(headers['Idempotency-Key'], 'desktop-turn-1')
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
