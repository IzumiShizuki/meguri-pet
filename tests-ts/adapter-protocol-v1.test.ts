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
  parseTurnCreateRequest,
  parseTurnEventEnvelope,
  replayPolicyForEvent,
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
  const identities = Object.values(profiles) as ClientHello[]
  assert.deepEqual(
    [...new Set(identities.map(profile => profile.identity.meguri_user.id))],
    [(flows.identity as { shared_meguri_user_id: string }).shared_meguri_user_id],
  )
  assert.equal(
    new Set(identities.map(profile => profile.identity.session.id)).size,
    (flows.identity as { isolated_session_ids: string[] }).isolated_session_ids.length,
  )
})

test('required Tool, Approval, Skill, and Agent events share the Core replay policy', () => {
  const catalog = flows.required_event_catalog as Array<{ type: string, replay_policy: string }>
  for (const fixture of catalog) {
    const parsed = parseTurnEventEnvelope(envelope(1, fixture.type, {
      replay_policy: undefined,
    }))
    assert.equal(parsed.replay_policy, fixture.replay_policy)
    assert.equal(replayPolicyForEvent(fixture.type), fixture.replay_policy)
  }
})

test('known events reject replay policies that differ from the canonical result', () => {
  assert.throws(
    () => parseTurnEventEnvelope(envelope(1, 'tool.proposed', {
      replay_policy: 'STATE',
    })),
    /non-canonical replay policy.*expected ALWAYS.*received STATE/,
  )
  assert.throws(
    () => parseTurnEventEnvelope(envelope(1, 'semantic.cue', {
      replay_policy: 'STATE',
      data: { channel: 'animation' },
    })),
    /non-canonical replay policy.*expected ONCE.*received STATE/,
  )
  assert.equal(parseTurnEventEnvelope(envelope(1, 'future.optional', {
    required: false,
    replay_policy: 'ALWAYS',
  })).replay_policy, 'ALWAYS')
})

test('terminal and stable error fixtures cover the adapter completion boundary', () => {
  assert.deepEqual(flows.terminal, ['turn.completed', 'turn.cancelled', 'turn.failed'])
  const stableErrors = flows.stable_errors as Array<{ code: string, retryable: boolean }>
  assert.ok(stableErrors.some(error => error.code === 'CURSOR_EXPIRED' && error.retryable))
  assert.ok(stableErrors.some(error => error.code === 'UNSUPPORTED_PROTOCOL_MAJOR' && !error.retryable))
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

test('one executable three-client fixture shares formal memory but isolates raw sessions', () => {
  const scenario = flows.cross_client_memory as {
    formal_memory: { key: string, value: string, writer: string, readers: string[] }
    session_messages: Record<string, string>
    expected_shared_memory_readers: number
    expected_raw_messages_per_session: number
  }
  const core = new DeterministicFixtureCore()

  for (const [client, value] of Object.entries(profiles)) {
    const hello = parseClientHello(value)
    const request = parseTurnCreateRequest({
      protocol_version: hello.protocol_versions[0],
      identity: hello.identity,
      message: scenario.session_messages[client],
    })
    core.submit(request)
  }
  const writer = parseClientHello(profiles[scenario.formal_memory.writer])
  core.writeFormalMemory(
    writer.identity.meguri_user.id,
    scenario.formal_memory.key,
    scenario.formal_memory.value,
  )

  const identities = Object.values(profiles).map(parseClientHello)
  assert.equal(
    identities.filter(profile => core.readFormalMemory(
      profile.identity.meguri_user.id,
      scenario.formal_memory.key,
    ) === scenario.formal_memory.value).length,
    scenario.expected_shared_memory_readers,
  )
  for (const profile of identities) {
    assert.deepEqual(
      core.rawSession(profile.identity.session.id),
      [scenario.session_messages[profile.identity.client_instance.profile]],
    )
    assert.equal(
      core.rawSession(profile.identity.session.id).length,
      scenario.expected_raw_messages_per_session,
    )
    assert.deepEqual(core.checkpoint(profile.identity.session.id), {
      session_id: profile.identity.session.id,
      last_sequence: 2,
      processed_event_ids: [
        `fixture-${profile.identity.session.id}-1`,
        `fixture-${profile.identity.session.id}-2`,
      ],
    })
  }
})

class DeterministicFixtureCore {
  private readonly formalMemory = new Map<string, Map<string, string>>()
  private readonly transcripts = new Map<string, string[]>()
  private readonly reducers = new Map<string, SessionTurnReducer>()

  submit(value: unknown): void {
    const request = parseTurnCreateRequest(value)
    const session = request.identity.session.id
    this.transcripts.set(session, [...(this.transcripts.get(session) ?? []), request.message])
    const reducer = new SessionTurnReducer()
    const turnId = `turn-${session}`
    reducer.apply(parseTurnEventEnvelope(envelope(1, 'turn.started', {
      event_id: `fixture-${session}-1`,
      turn_id: turnId,
      session_id: session,
    })))
    reducer.apply(parseTurnEventEnvelope(envelope(2, 'turn.completed', {
      event_id: `fixture-${session}-2`,
      turn_id: turnId,
      session_id: session,
    })))
    this.reducers.set(session, reducer)
  }

  writeFormalMemory(userId: string, key: string, value: string): void {
    const memory = this.formalMemory.get(userId) ?? new Map<string, string>()
    memory.set(key, value)
    this.formalMemory.set(userId, memory)
  }

  readFormalMemory(userId: string, key: string): string | undefined {
    return this.formalMemory.get(userId)?.get(key)
  }

  rawSession(sessionId: string): string[] {
    return [...(this.transcripts.get(sessionId) ?? [])]
  }

  checkpoint(sessionId: string) {
    const reducer = this.reducers.get(sessionId)
    assert.ok(reducer, `missing fixture reducer for ${sessionId}`)
    return reducer.checkpoint()
  }
}

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
