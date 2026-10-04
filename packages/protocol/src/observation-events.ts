import type { ProtocolVersion } from './dto.ts'

/**
 * Observation-domain contract only. These events are intentionally separate from
 * ordinary turn events because screen observation is not a user message.
 *
 * The existing adapter handshake remains fail-closed: `screen_context` and
 * `screen_read` must stay false until a later native-provider and permission change
 * explicitly enables them.
 */
export const observationEventTypes = [
  'window.attachment.updated',
  'screen.observation.status',
  'screen.context.updated',
  'screen.diff.detected',
] as const

export type ObservationEventType = typeof observationEventTypes[number]
export type ObservationReplayPolicy = 'STATE' | 'ONCE' | 'ALWAYS'
export type ObservationStatus = 'ACTIVE' | 'PAUSED' | 'DENIED' | 'UNAVAILABLE' | 'ELIGIBLE'
export type WindowVisibility = 'VISIBLE' | 'MINIMIZED' | 'HIDDEN' | 'CLOSED' | 'UNAVAILABLE'
export type WindowAnchor =
  | 'TOP_LEFT'
  | 'TOP_CENTER'
  | 'TOP_RIGHT'
  | 'CENTER_LEFT'
  | 'CENTER'
  | 'CENTER_RIGHT'
  | 'BOTTOM_LEFT'
  | 'BOTTOM_CENTER'
  | 'BOTTOM_RIGHT'

export interface ObservationEventMetadata {
  trace_id: string
  source: string
  created_at: string
  build_id: string
}

export interface ObservationEventEnvelope<T extends Record<string, unknown> = Record<string, unknown>> {
  protocol_version: ProtocolVersion
  event_id: string
  required: boolean
  replay_policy: ObservationReplayPolicy
  type: ObservationEventType
  scope_id: string
  sequence: number
  created_at: string
  data: T
  metadata: ObservationEventMetadata
}

export interface WindowBoundsPayload {
  x: number
  y: number
  width: number
  height: number
}

export interface WindowOffsetPayload {
  x: number
  y: number
}

export interface WindowAttachmentPayload {
  user_id: string
  client_id: string
  window_id: string
  process_id: number
  app_name: string
  bounds: WindowBoundsPayload
  anchor: WindowAnchor
  offset: WindowOffsetPayload
  visibility: WindowVisibility
  attachment_status: 'DETACHED' | 'ATTACHED' | 'UNAVAILABLE'
  changed_at: string
}

export interface ObservationStatusPayload {
  status: ObservationStatus
  reason: string
  window_id?: string
  application_name?: string
}

export interface ScreenContextPayload {
  scope_id: string
  application: string
  activity: string
  content: string
  signals: string[]
  source: 'MOCK' | 'NATIVE_WINDOW' | 'PIX_PIN' | 'SCREENPIPE'
  confidence: number
  observed_at: string
  expires_at: string
}

export interface SemanticDiffPayload {
  scope_id: string
  previous_observed_at?: string
  current_observed_at: string
  initial: boolean
  activity_changed: boolean
  content_changed: boolean
  added_signals: string[]
  removed_signals: string[]
}

export function observationReplayPolicyForEvent(type: ObservationEventType): ObservationReplayPolicy {
  return type === 'screen.diff.detected' ? 'ALWAYS' : 'STATE'
}
