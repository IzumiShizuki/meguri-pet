import assert from 'node:assert/strict'
import test from 'node:test'

import {
  SequenceGapError,
  SessionTurnReducer,
  SseTurnEventParser,
  parseTurnEventEnvelope,
  type TurnEventEnvelope,
  type TurnEventType,
} from '../packages/protocol/src/index.ts'

function event(
  sequence: number,
  type: TurnEventType,
  data: Record<string, unknown> = {},
): TurnEventEnvelope {
  return {
    protocol_version: '1.0',
    event_id: `event-${sequence}`,
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

function sse(value: TurnEventEnvelope): string {
  return `id: ${value.sequence}\nevent: ${value.type}\ndata: ${JSON.stringify(value)}\n\n`
}

test('SSE parser handles split chunks and heartbeat comments', () => {
  const parser = new SseTurnEventParser()
  const payload = `: heartbeat\n\n${sse(event(1, 'turn.started'))}`
  assert.deepEqual(parser.push(payload.slice(0, 13)), [])
  const parsed = parser.push(payload.slice(13))
  assert.equal(parsed.length, 1)
  assert.equal(parsed[0].type, 'turn.started')
  parser.finish()
})

test('event parser advances optional unknown events but rejects required ones', () => {
  const optional = parseTurnEventEnvelope({
    ...event(1, 'turn.started'),
    type: 'future.presentation.hint',
    required: false,
  })
  assert.equal(optional.type, 'future.presentation.hint')
  assert.throws(() => parseTurnEventEnvelope({
    ...event(1, 'turn.started'),
    type: 'future.required.command',
  }), /unsupported required event type/)
  assert.throws(
    () => parseTurnEventEnvelope({ ...event(1, 'turn.started'), sequence: 0 }),
    /positive integer/,
  )
})

test('reducer assembles text and ignores duplicate replay', () => {
  const reducer = new SessionTurnReducer()
  assert.equal(reducer.apply(event(1, 'turn.started')), true)
  assert.equal(reducer.apply(event(2, 'text.delta', { delta: 'hello ' })), true)
  assert.equal(reducer.apply(event(2, 'text.delta', { delta: 'duplicate' })), false)
  reducer.apply(event(3, 'text.delta', { delta: 'Meguri' }))
  reducer.apply(event(4, 'turn.completed'))
  assert.equal(reducer.turns.get('turn-1')?.text, 'hello Meguri')
  assert.equal(reducer.turns.get('turn-1')?.status, 'completed')
})

test('reducer deduplicates stable event IDs without replaying side effects', () => {
  const reducer = new SessionTurnReducer()
  reducer.apply(event(1, 'turn.started'))
  assert.equal(reducer.apply({
    ...event(2, 'tts.requested', { text: 'hello' }),
    event_id: 'tts-once-1',
  }), true)
  assert.equal(reducer.apply({
    ...event(3, 'tts.requested', { text: 'must not play twice' }),
    event_id: 'tts-once-1',
  }), false)
  assert.equal(reducer.lastSequence, 3)
  assert.deepEqual(reducer.checkpoint(), {
    session_id: 'session-1',
    last_sequence: 3,
    processed_event_ids: ['event-1', 'tts-once-1'],
  })
})

test('reducer restores event ID deduplication from a serialized checkpoint', () => {
  const first = new SessionTurnReducer()
  first.apply(event(1, 'turn.started'))
  first.apply({ ...event(2, 'tts.requested'), event_id: 'tts-once-1' })

  const checkpoint = JSON.parse(JSON.stringify(first.checkpoint()))
  const restored = new SessionTurnReducer(checkpoint)
  assert.equal(restored.apply({
    ...event(3, 'tts.requested'),
    event_id: 'tts-once-1',
  }), false)
  assert.equal(restored.apply(event(4, 'turn.completed')), true)
  assert.equal(restored.lastSequence, 4)
  assert.equal(restored.turns.get('turn-1')?.status, 'completed')
})

test('reducer checkpoints an optional unknown event without changing turn state', () => {
  const reducer = new SessionTurnReducer()
  reducer.apply(event(1, 'turn.started'))
  reducer.apply({
    ...event(2, 'future.presentation.hint'),
    required: false,
  })
  reducer.apply(event(3, 'turn.completed'))
  assert.equal(reducer.lastSequence, 3)
  assert.deepEqual(reducer.checkpoint().processed_event_ids, [
    'event-1',
    'event-2',
    'event-3',
  ])
  assert.equal(reducer.turns.get('turn-1')?.status, 'completed')
})

test('reducer surfaces sequence gaps for reconnect', () => {
  const reducer = new SessionTurnReducer()
  reducer.apply(event(1, 'turn.started'))
  assert.throws(() => reducer.apply(event(3, 'text.delta')), SequenceGapError)
  assert.equal(reducer.lastSequence, 1)
})
