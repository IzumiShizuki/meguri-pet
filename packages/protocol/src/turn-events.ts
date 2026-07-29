import {
  assertCompatibleProtocolVersion,
  assertRequiredExtensions,
} from './negotiation.ts'
import type { ProtocolVersion, TurnCreateResponse } from './dto.ts'

export const turnEventTypes = [
  'turn.started',
  'turn.stage.changed',
  'retrieval.completed',
  'text.delta',
  'text.completed',
  'semantic.completed',
  'expression.cue',
  'sprite.resolved',
  'memory.candidate.created',
  'memory.write.completed',
  'memory.updated',
  'relationship.updated',
  'tool.proposed',
  'approval.required',
  'approval.resolved',
  'tool.started',
  'tool.completed',
  'tool.failed',
  'skill.started',
  'skill.waiting',
  'skill.completed',
  'skill.failed',
  'agent.started',
  'agent.waiting',
  'agent.completed',
  'agent.failed',
  'semantic.cue',
  'voice.requested',
  'audio.ready',
  'training.candidates.ready',
  'tts.requested',
  'tts.audio.delta',
  'tts.completed',
  'session.synced',
  'turn.completed',
  'turn.cancelled',
  'turn.failed',
] as const

export type KnownTurnEventType = typeof turnEventTypes[number]
export type TurnEventType = KnownTurnEventType | (string & {})
export type ExpressionIntensity = 'low' | 'medium' | 'high'
export type ReplayPolicy = 'STATE' | 'ONCE' | 'ALWAYS'

export interface LegacyClientCapabilities {
  text: boolean
  sprite: boolean
  voice: boolean
  screen_context: boolean
  formal_memory?: boolean
  sse?: boolean
}

/**
 * @deprecated Prefer TurnCreateRequest with an explicit IdentityContext.
 * This shape remains accepted by existing adapters during v1 migration.
 */
export interface TurnRequest {
  user_id: string
  client_id: 'airi' | 'astrbot' | 'desktop_pet' | 'website'
  session_id: string
  parent_session_id?: string
  message: string
  attachments?: Array<Record<string, unknown>>
  client_capabilities: LegacyClientCapabilities
  optional_screen_context_id?: string
  relationship_profile?: 'sibling' | 'pursuit' | 'lover'
  formal_memory_allowed?: boolean
  training_mode?: boolean
  reply_format?: 'default' | 'zh_ja_pairs'
  retrieval_mode?: 'NONE' | 'FAST' | 'SLOW'
}

export interface EventMetadata {
  trace_id: string
  source: string
  created_at: string
  build_id: string
}

export interface TurnEventEnvelope<T extends Record<string, unknown> = Record<string, unknown>> {
  protocol_version: ProtocolVersion
  event_id: string
  required: boolean
  required_extension?: string
  replay_policy?: ReplayPolicy
  type: TurnEventType
  turn_id: string
  session_id: string
  sequence: number
  created_at?: string
  data: T
  metadata: EventMetadata
}

const eventTypeSet = new Set<string>(turnEventTypes)
const stateEventTypes = new Set<string>([
  'turn.started',
  'turn.stage.changed',
  'text.delta',
  'text.completed',
  'semantic.completed',
  'expression.cue',
  'sprite.resolved',
  'approval.required',
  'memory.updated',
  'relationship.updated',
  'session.synced',
  'skill.started',
  'skill.waiting',
  'skill.completed',
  'skill.failed',
  'agent.started',
  'agent.waiting',
  'agent.completed',
  'agent.failed',
  'turn.completed',
  'turn.cancelled',
  'turn.failed',
])

/** Mirrors the Core authority in TurnEventTypes.replayPolicy. */
export function replayPolicyForEvent(
  type: string,
  data: Record<string, unknown> = {},
): ReplayPolicy {
  if (type.startsWith('tts.') || type === 'voice.requested' || type === 'audio.ready')
    return 'ONCE'
  if (type === 'semantic.cue' && (data.channel === 'animation' || data.channel === 'notification'))
    return 'ONCE'
  return stateEventTypes.has(type) ? 'STATE' : 'ALWAYS'
}

export function parseTurnEventEnvelope(
  value: unknown,
  options: { supportedExtensions?: readonly string[] } = {},
): TurnEventEnvelope & { replay_policy: ReplayPolicy, created_at: string } {
  if (!isRecord(value))
    throw new TypeError('event envelope must be an object')
  const protocolVersion = stringField(value, 'protocol_version')
  assertCompatibleProtocolVersion(protocolVersion)
  stringField(value, 'event_id')
  const type = stringField(value, 'type')
  if (typeof value.required !== 'boolean')
    throw new TypeError('required must be a boolean')
  if (value.required_extension !== undefined) {
    const extension = stringField(value, 'required_extension')
    assertRequiredExtensions([extension], options.supportedExtensions ?? [])
  }
  if (!eventTypeSet.has(type) && value.required)
    throw new TypeError(`unsupported required event type: ${type}`)
  stringField(value, 'turn_id')
  stringField(value, 'session_id')
  const sequence = value.sequence
  if (!Number.isSafeInteger(sequence) || Number(sequence) < 1)
    throw new TypeError('event sequence must be a positive integer')
  if (!isRecord(value.data))
    throw new TypeError('event data must be an object')
  if (!isRecord(value.metadata))
    throw new TypeError('event metadata must be an object')
  const metadata = value.metadata
  for (const key of ['trace_id', 'source', 'created_at', 'build_id'])
    stringField(metadata, key)
  const replayPolicy = value.replay_policy ?? replayPolicyForEvent(type, value.data)
  if (!['STATE', 'ONCE', 'ALWAYS'].includes(String(replayPolicy)))
    throw new TypeError(`unsupported replay policy: ${String(replayPolicy)}`)
  const canonicalReplayPolicy = replayPolicyForEvent(type, value.data)
  if (eventTypeSet.has(type) && replayPolicy !== canonicalReplayPolicy) {
    throw new TypeError(
      `non-canonical replay policy for ${type}: expected ${canonicalReplayPolicy}, received ${String(replayPolicy)}`,
    )
  }
  const createdAt = value.created_at ?? metadata.created_at
  if (typeof createdAt !== 'string' || createdAt.length === 0)
    throw new TypeError('created_at must be a non-empty string')
  return {
    ...value,
    protocol_version: protocolVersion,
    replay_policy: replayPolicy,
    created_at: createdAt,
  } as TurnEventEnvelope & { replay_policy: ReplayPolicy, created_at: string }
}

export function isTerminalEvent(type: string): boolean {
  return type === 'turn.completed' || type === 'turn.cancelled' || type === 'turn.failed'
}

function stringField(value: Record<string, unknown>, key: string): string {
  const field = value[key]
  if (typeof field !== 'string' || field.length === 0)
    throw new TypeError(`${key} must be a non-empty string`)
  return field
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export type { TurnCreateResponse }
