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

test('event parser rejects unknown types and invalid sequence', () => {
  assert.throws(
    () => parseTurnEventEnvelope({ ...event(1, 'turn.started'), type: 'unknown' }),
    /unsupported event type/,
  )
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

test('reducer surfaces sequence gaps for reconnect', () => {
  const reducer = new SessionTurnReducer()
  reducer.apply(event(1, 'turn.started'))
  assert.throws(() => reducer.apply(event(3, 'text.delta')), SequenceGapError)
  assert.equal(reducer.lastSequence, 1)
})
