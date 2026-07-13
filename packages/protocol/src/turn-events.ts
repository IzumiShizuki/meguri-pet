export const turnEventTypes = [
  'turn.started',
  'text.delta',
  'text.completed',
  'semantic.completed',
  'expression.cue',
  'sprite.resolved',
  'memory.candidate.created',
  'memory.write.completed',
  'tool.started',
  'tool.completed',
  'tts.requested',
  'tts.audio.delta',
  'tts.completed',
  'session.synced',
  'turn.completed',
  'turn.cancelled',
  'turn.failed',
] as const

export type TurnEventType = typeof turnEventTypes[number]
export type ExpressionIntensity = 'low' | 'medium' | 'high'

export interface ClientCapabilities {
  text: boolean
  sprite: boolean
  voice: boolean
  screen_context: boolean
}

export interface TurnRequest {
  user_id: string
  client_id: 'astrbot' | 'desktop_pet' | 'website'
  session_id: string
  message: string
  attachments?: Array<Record<string, unknown>>
  client_capabilities: ClientCapabilities
  optional_screen_context_id?: string
  relationship_profile?: 'sibling' | 'pursuit' | 'lover'
}

export interface TurnCreateResponse {
  turn_id: string
  session_id: string
  build_id: string
  status: 'accepted' | 'running' | 'completed' | 'failed' | 'cancelled'
}

export interface EventMetadata {
  trace_id: string
  source: string
  created_at: string
  build_id: string
}

export interface TurnEventEnvelope<T extends Record<string, unknown> = Record<string, unknown>> {
  type: TurnEventType
  turn_id: string
  session_id: string
  sequence: number
  data: T
  metadata: EventMetadata
}

const eventTypeSet = new Set<string>(turnEventTypes)

export function parseTurnEventEnvelope(value: unknown): TurnEventEnvelope {
  if (!isRecord(value))
    throw new TypeError('event envelope must be an object')
  if (!eventTypeSet.has(stringField(value, 'type')))
    throw new TypeError(`unsupported event type: ${String(value.type)}`)
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
  return value as unknown as TurnEventEnvelope
}

export function isTerminalEvent(type: TurnEventType): boolean {
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
