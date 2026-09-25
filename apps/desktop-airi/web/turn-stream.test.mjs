import assert from 'node:assert/strict'
import test from 'node:test'

import { followDesktopTurn } from './turn-stream.mjs'

test('desktop turn stream reconnects from the last accepted sequence', async () => {
  const requestedAfter = []
  const received = []
  let attempts = 0
  const fetchImpl = async input => {
    const url = new URL(input, 'http://127.0.0.1')
    requestedAfter.push(url.searchParams.get('after_sequence'))
    attempts += 1
    return attempts === 1
      ? streamResponse([sse(envelope(1, 'turn.started'))])
      : streamResponse([
        sse(envelope(2, 'text.delta', { delta: 'reconnected ' })),
        sse(envelope(3, 'turn.completed')),
      ])
  }

  const result = await followDesktopTurn({
    coreUrl: 'http://127.0.0.1:5173/core',
    sessionId: 'session-1',
    turnId: 'turn-1',
    fetchImpl,
    onEvent: event => received.push(event.eventName),
  })

  assert.deepEqual(requestedAfter, ['0', '1'])
  assert.deepEqual(received, ['turn.started', 'text.delta', 'turn.completed'])
  assert.equal(result.terminal, 'turn.completed')
  assert.equal(result.lastSequence, 3)
})

test('desktop turn stream restores a terminal turn from snapshot after reconnect exhaustion', async () => {
  const requestedAfter = []
  let snapshotTurn
  const fetchImpl = async input => {
    const url = new URL(input, 'http://127.0.0.1')
    if (url.pathname.endsWith('/snapshot')) {
      return jsonResponse({
        sequence: 4,
        turns: [{ turn_id: 'turn-1', status: 'completed', text: 'recovered text' }],
      })
    }
    requestedAfter.push(url.searchParams.get('after_sequence'))
    return streamResponse([sse(envelope(requestedAfter.length, 'turn.started'))])
  }

  const result = await followDesktopTurn({
    coreUrl: 'http://127.0.0.1:5173/core',
    sessionId: 'session-1',
    turnId: 'turn-1',
    fetchImpl,
    maxReconnects: 1,
    onSnapshot: turn => { snapshotTurn = turn },
  })

  assert.deepEqual(requestedAfter, ['0', '1'])
  assert.deepEqual(snapshotTurn, {
    turn_id: 'turn-1',
    status: 'completed',
    text: 'recovered text',
  })
  assert.equal(result.terminal, 'turn.completed')
  assert.equal(result.lastSequence, 4)
})

test('desktop turn stream accepts a terminal SSE frame without a trailing delimiter', async () => {
  const terminal = sse(envelope(1, 'turn.completed')).trimEnd()
  const result = await followDesktopTurn({
    coreUrl: 'http://127.0.0.1:5173/core',
    sessionId: 'session-1',
    turnId: 'turn-1',
    fetchImpl: async () => streamResponse([terminal]),
  })

  assert.equal(result.terminal, 'turn.completed')
})

function envelope(sequence, type, data = {}) {
  return {
    protocol_version: '1.0',
    event_id: 'event-' + sequence,
    required: true,
    replay_policy: 'STATE',
    type,
    turn_id: 'turn-1',
    session_id: 'session-1',
    sequence,
    created_at: '2026-08-01T00:00:00Z',
    data,
    metadata: {
      trace_id: 'trace-1',
      source: 'test',
      created_at: '2026-08-01T00:00:00Z',
      build_id: 'build-1',
    },
  }
}

function sse(event) {
  return 'id: ' + event.sequence + '\nevent: ' + event.type
    + '\ndata: ' + JSON.stringify(event) + '\n\n'
}

function streamResponse(chunks) {
  const encoder = new TextEncoder()
  return {
    ok: true,
    status: 200,
    body: new ReadableStream({
      start(controller) {
        for (const chunk of chunks)
          controller.enqueue(encoder.encode(chunk))
        controller.close()
      },
    }),
  }
}

function jsonResponse(value) {
  return {
    ok: true,
    status: 200,
    body: null,
    async json() { return value },
  }
}
