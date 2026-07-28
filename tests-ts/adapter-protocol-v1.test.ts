import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import test from 'node:test'

import {
  ProtocolCompatibilityError,
  SessionTurnReducer,
  SseTurnEventParser,
  assertCompatibleProtocolVersion,
  negotiateHello,
  parseClientHello,
  parseTurnEventEnvelope,
  validateDto,
  type ClientCapabilities,
  type ClientHello,
  type ClientPermissions,
  type TurnEventEnvelope,
} from '../packages/protocol/src/index.ts'

const fixturesRoot = resolve(import.meta.dirname, '../contracts/adapter-protocol/v1/fixtures')
const profiles = JSON.parse(readFileSync(resolve(fixturesRoot, 'profiles.json'), 'utf8')) as Record<string, unknown>
const flows = JSON.parse(readFileSync(resolve(fixturesRoot, 'flows.json'), 'utf8')) as Record<string, unknown>

test('AIRI, AstrBot, and Website hello profiles validate from the canonical schema', () => {
  assert.deepEqual(Object.keys(profiles).sort(), ['airi', 'astrbot', 'website'])
  for (const profile of Object.values(profiles))
    assert.doesNotThrow(() => parseClientHello(profile))
})

test('URL v1 accepts future 1.x optional fields and rejects incompatible majors', () => {
  const website = structuredClone(profiles.website) as ClientHello & { future_optional?: unknown }
  website.protocol_versions = ['1.9']
  website.future_optional = { safely_ignored: true }
  website.identity.meguri_user = { ...website.identity.meguri_user, future_optional: true } as { id: string }
  assert.doesNotThrow(() => parseClientHello(website))
  assert.doesNotThrow(() => assertCompatibleProtocolVersion(String(flows.minor_accepted)))
  assert.throws(
    () => assertCompatibleProtocolVersion(String(flows.major_rejected)),
    (error: unknown) => error instanceof ProtocolCompatibilityError
      && error.code === 'UNSUPPORTED_PROTOCOL_MAJOR',
  )
  assert.throws(
    () => parseClientHello({
      ...website,
      capabilities: { ...website.capabilities, future_capability: 'yes' },
    }),
    /must be a boolean/,
  )
})

test('capability negotiation degrades deterministically and cannot self-grant permission', () => {
  const hello = profiles.airi as ClientHello
  const serverCapabilities: ClientCapabilities = {
    text: true,
    voice: false,
    sprite: true,
    screen_context: false,
    formal_memory: true,
    sse: true,
  }
  const grants: ClientPermissions = {
    screen_read: false,
    microphone: false,
    audio_playback: false,
    notifications: false,
    formal_memory_write: false,
  }
  const negotiated = negotiateHello(hello, {
    versions: ['1.0', '1.1'],
    capabilities: serverCapabilities,
    capabilitiesRevision: 'caps-42',
    grantedPermissions: grants,
    extensions: [],
  })
  assert.equal(negotiated.selected_protocol_version, '1.1')
  assert.equal(negotiated.server_capabilities_revision, 'caps-42')
  assert.deepEqual(negotiated.effective_capabilities, serverCapabilities)
  assert.deepEqual(negotiated.granted_permissions, grants)
})

test('unknown optional event advances state while required extension fails', () => {
  const optional = parseTurnEventEnvelope(envelope(1, 'future.optional', {
    required: false,
    protocol_version: '1.9',
    future_optional: true,
  }))
  assert.equal(optional.replay_policy, 'STATE')
  assert.throws(
    () => parseTurnEventEnvelope(envelope(1, 'future.optional', {
      required: false,
      required_extension: 'future.motion.v2',
    })),
    (error: unknown) => error instanceof ProtocolCompatibilityError
      && error.code === 'UNSUPPORTED_REQUIRED_EXTENSION',
  )
  assert.equal(parseTurnEventEnvelope(envelope(1, 'future.optional', {
    required: false,
    required_extension: 'future.motion.v2',
  }), {
    supportedExtensions: ['future.motion.v2'],
  }).required_extension, 'future.motion.v2')
})

test('heartbeat does not allocate business sequence', () => {
  const parser = new SseTurnEventParser()
  const heartbeat = 'event: heartbeat\ndata: {"protocol_version":"1.7","type":"heartbeat","created_at":"2026-07-28T00:00:00Z"}\n\n'
  assert.deepEqual(parser.push(`${heartbeat}${sse(envelope(1, 'turn.started'))}`), [
    parseTurnEventEnvelope(envelope(1, 'turn.started')),
  ])
  assert.throws(
    () => new SseTurnEventParser().push(
      'event: heartbeat\ndata: {"protocol_version":"1.0","type":"heartbeat","sequence":1,"created_at":"now"}\n\n',
    ),
    /must not allocate/,
  )
})

test('snapshot checkpoint prevents ONCE side effects from replaying after reconnect', () => {
  const reducer = new SessionTurnReducer()
  reducer.restoreSnapshot({
    protocol_version: '1.0',
    session_id: 'session-1',
    sequence: 10,
    turns: [{ turn_id: 'turn-1', status: 'running', text: 'ready' }],
    processed_once_event_ids: ['tts-side-effect-1'],
    processed_event_ids: ['state-event-9'],
    created_at: '2026-07-28T00:00:00Z',
  })
  assert.equal(reducer.apply(parseTurnEventEnvelope(envelope(11, 'tts.requested', {
    event_id: 'tts-side-effect-1',
    replay_policy: 'ONCE',
  }))), false)
  assert.equal(reducer.lastSequence, 11)
})

test('canonical schema validates session snapshot and stable cursor error semantics', () => {
  validateDto('SessionSnapshot', {
    protocol_version: '1.3',
    session_id: 'session-1',
    sequence: 40,
    turns: [{ turn_id: 'turn-1', status: 'running', text: 'partial' }],
    created_at: '2026-07-28T00:00:00Z',
  })
  validateDto('ErrorResponse', {
    protocol_version: '1.0',
    error: {
      code: 'CURSOR_EXPIRED',
      message: 'event cursor is outside retention',
      retryable: true,
      details: { snapshot_sequence: 40 },
    },
  })
})

function envelope(
  sequence: number,
  type: string,
  overrides: Partial<TurnEventEnvelope> & Record<string, unknown> = {},
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
    data: {},
    metadata: {
      trace_id: 'trace-1',
      source: 'meguri-core',
      created_at: '2026-07-28T00:00:00Z',
      build_id: 'build-1',
    },
    ...overrides,
  }
}

function sse(event: TurnEventEnvelope): string {
  return `id: ${event.sequence}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`
}
