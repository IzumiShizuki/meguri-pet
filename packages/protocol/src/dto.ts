export type ProtocolVersion = `1.${number}`
export type ClientProfile = 'airi' | 'astrbot' | 'website' | 'desktop_pet' | 'custom'
export type TurnStatus =
  | 'accepted'
  | 'running'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'cancel_requested'

export interface IdentityContext {
  meguri_user: { id: string }
  platform_actor: { platform: string, actor_id: string }
  client_instance: { id: string, profile: ClientProfile }
  session: { id: string }
}

export interface ClientCapabilities {
  text: boolean
  voice: boolean
  sprite: boolean
  screen_context: boolean
  formal_memory: boolean
  sse: boolean
  [capability: string]: boolean
}

export interface ClientPermissions {
  screen_read: boolean
  microphone: boolean
  audio_playback: boolean
  notifications: boolean
  formal_memory_write: boolean
}

export interface ClientHello {
  protocol_versions: ProtocolVersion[]
  identity: IdentityContext
  capabilities: ClientCapabilities
  permissions: ClientPermissions
  required_extensions?: string[]
}

export interface HelloResponse {
  selected_protocol_version: ProtocolVersion
  server_capabilities_revision: string
  server_capabilities: ClientCapabilities
  effective_capabilities: ClientCapabilities
  granted_permissions: ClientPermissions
  supported_extensions?: string[]
}

export interface TurnCreateRequest {
  protocol_version: ProtocolVersion
  identity: IdentityContext
  message: string
  attachments?: Array<Record<string, unknown>>
  relationship_profile?: 'sibling' | 'pursuit' | 'lover'
  training_mode?: boolean
  reply_format?: 'default' | 'zh_ja_pairs'
  retrieval_mode?: 'NONE' | 'FAST' | 'SLOW'
  required_extensions?: string[]
}

export interface ProtocolError {
  code:
    | 'INVALID_REQUEST'
    | 'UNAUTHORIZED'
    | 'FORBIDDEN'
    | 'NOT_FOUND'
    | 'CONFLICT'
    | 'TURN_TERMINAL'
    | 'CURSOR_EXPIRED'
    | 'UNSUPPORTED_PROTOCOL_MAJOR'
    | 'UNSUPPORTED_REQUIRED_EXTENSION'
    | 'CAPABILITY_UNAVAILABLE'
    | 'RATE_LIMITED'
    | 'INTERNAL'
  message: string
  retryable: boolean
  details?: Record<string, unknown>
}

export interface Turn {
  protocol_version: ProtocolVersion
  turn_id: string
  session_id: string
  build_id: string
  status: TurnStatus
  created_at: string
  updated_at: string
  output_text?: string
  error?: ProtocolError
}

export interface TurnCreateResponse {
  protocol_version?: ProtocolVersion
  turn_id: string
  session_id: string
  build_id: string
  status: TurnStatus
}

export interface TurnCancelResponse {
  protocol_version?: ProtocolVersion
  turn_id: string
  status: 'cancel_requested' | 'cancelled'
}

export interface TurnSnapshot {
  turn_id: string
  status: 'idle' | 'running' | 'completed' | 'cancelled' | 'failed'
  text: string
  expression?: Record<string, unknown>
  error?: string
}

export interface SessionSnapshot {
  protocol_version: ProtocolVersion
  session_id: string
  sequence: number
  turns: TurnSnapshot[]
  processed_event_ids?: string[]
  processed_once_event_ids?: string[]
  created_at: string
}

export interface ErrorResponse {
  protocol_version: ProtocolVersion
  error: ProtocolError
}
