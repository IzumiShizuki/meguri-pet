import {
  MeguriApiClient,
  MeguriApiError,
  type FetchLike,
  type FetchResponse,
  type FollowOptions,
  type MeguriApiClientOptions,
} from '../../../packages/client-sdk/src/index.ts'
import {
  SessionTurnReducer,
  type ClientCapabilities,
  type ClientHello,
  type ClientPermissions,
  type HelloResponse,
  type IdentityContext,
  type ProtocolVersion,
  type SessionEventCheckpoint,
  type TurnCreateRequest,
  type TurnCreateResponse,
  type TurnRequest,
} from '../../../packages/protocol/src/index.ts'

export interface AiriAdapterStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export interface AiriAdapterIdentity {
  platformActorId?: string
  clientInstanceId?: string
}

export interface AiriAdapterOptions extends MeguriApiClientOptions {
  storage?: AiriAdapterStorage
  storageKey?: string
  identity?: AiriAdapterIdentity
  protocolVersions?: ProtocolVersion[]
  capabilities?: Partial<ClientCapabilities>
  permissions?: Partial<ClientPermissions>
}

interface AiriAdapterState {
  version: 2
  clientInstanceId?: string
  selectedProtocolVersion?: ProtocolVersion
  serverCapabilitiesRevision?: string
  checkpoints: SessionEventCheckpoint[]
}

interface LegacyAiriAdapterState {
  version: 1
  clientInstanceId?: string
  selectedProtocolVersion?: ProtocolVersion
  serverCapabilitiesRevision?: string
  checkpoint?: SessionEventCheckpoint
}

const defaultCapabilities: ClientCapabilities = {
  text: true,
  voice: true,
  sprite: true,
  screen_context: false,
  formal_memory: false,
  sse: true,
}

const defaultPermissions: ClientPermissions = {
  screen_read: false,
  microphone: false,
  audio_playback: true,
  notifications: false,
  formal_memory_write: false,
}

export class MeguriApiAdapter extends MeguriApiClient {
  private readonly adapterOptions: AiriAdapterOptions
  private readonly storage?: AiriAdapterStorage
  private readonly storageKey: string
  private state: AiriAdapterState

  constructor(
    baseUrl: string,
    fetchImpl: FetchLike = fetch as unknown as FetchLike,
    options: AiriAdapterOptions = {},
  ) {
    super(baseUrl, fetchImpl, options)
    this.adapterOptions = options
    this.storage = options.storage ?? browserStorage()
    this.storageKey = options.storageKey ?? 'meguri.airi.adapter.v1'
    this.state = this.loadState()
    this.state.clientInstanceId ??= createClientInstanceId()
    this.persistState()
  }

  get selectedProtocolVersion(): ProtocolVersion | undefined {
    return this.state.selectedProtocolVersion
  }

  get serverCapabilitiesRevision(): string | undefined {
    return this.state.serverCapabilitiesRevision
  }

  createReducer(sessionId: string): SessionTurnReducer {
    const normalizedSessionId = requiredSessionId(sessionId)
    const checkpoint = this.state.checkpoints.find(
      candidate => candidate.session_id === normalizedSessionId,
    )
    return new SessionTurnReducer(checkpoint ?? 0)
  }

  persistCheckpoint(sessionId: string, reducer: SessionTurnReducer): void {
    const normalizedSessionId = requiredSessionId(sessionId)
    const checkpoint = reducer.checkpoint()
    if (checkpoint.session_id !== normalizedSessionId)
      throw new Error('checkpoint belongs to another session')
    this.state.checkpoints = [
      ...this.state.checkpoints.filter(
        candidate => candidate.session_id !== normalizedSessionId,
      ),
      checkpoint,
    ]
    this.persistState()
  }

  async createCanonicalTurn(
    request: TurnRequest,
    idempotencyKey?: string,
  ): Promise<TurnCreateResponse> {
    const identity = this.identityFor(request)
    const negotiated = await this.negotiate(identity, request)
    const canonical: TurnCreateRequest = {
      protocol_version: negotiated.selected_protocol_version,
      identity,
      message: request.message,
      ...(request.attachments ? { attachments: request.attachments } : {}),
      ...(request.relationship_profile
        ? { relationship_profile: request.relationship_profile }
        : {}),
      ...(request.training_mode !== undefined
        ? { training_mode: request.training_mode }
        : {}),
      ...(request.reply_format ? { reply_format: request.reply_format } : {}),
      ...(request.retrieval_mode ? { retrieval_mode: request.retrieval_mode } : {}),
      ...(request.execution_mode ? { execution_mode: request.execution_mode } : {}),
    }
    return await this.createTurn(canonical, idempotencyKey)
  }

  private async negotiate(
    identity: IdentityContext,
    request: TurnRequest,
  ): Promise<HelloResponse> {
    const hello: ClientHello = {
      protocol_versions: this.adapterOptions.protocolVersions ?? ['1.1', '1.0'],
      identity,
      capabilities: {
        ...defaultCapabilities,
        formal_memory: request.formal_memory_allowed ?? false,
        screen_context: Boolean(request.optional_screen_context_id),
        ...this.adapterOptions.capabilities,
      },
      permissions: {
        ...defaultPermissions,
        formal_memory_write: request.formal_memory_allowed ?? false,
        screen_read: Boolean(request.optional_screen_context_id),
        ...this.adapterOptions.permissions,
      },
    }
    const response = await this.hello(hello)
    this.state.selectedProtocolVersion = response.selected_protocol_version
    this.state.serverCapabilitiesRevision = response.server_capabilities_revision
    this.persistState()
    return response
  }

  private identityFor(request: TurnRequest): IdentityContext {
    return {
      meguri_user: { id: request.user_id },
      platform_actor: {
        platform: 'airi',
        actor_id: this.adapterOptions.identity?.platformActorId ?? 'local-owner',
      },
      client_instance: {
        id: this.adapterOptions.identity?.clientInstanceId
          ?? this.state.clientInstanceId
          ?? createClientInstanceId(),
        profile: 'airi',
      },
      session: { id: request.session_id },
    }
  }

  private loadState(): AiriAdapterState {
    const raw = this.storage?.getItem(this.storageKey)
    if (!raw)
      return emptyState()
    try {
      const value = JSON.parse(raw) as AiriAdapterState | LegacyAiriAdapterState
      if (value?.version === 2) {
        return {
          ...value,
          checkpoints: Array.isArray(value.checkpoints) ? value.checkpoints : [],
        }
      }
      if (value?.version === 1) {
        return {
          version: 2,
          clientInstanceId: value.clientInstanceId,
          selectedProtocolVersion: value.selectedProtocolVersion,
          serverCapabilitiesRevision: value.serverCapabilitiesRevision,
          checkpoints: value.checkpoint?.session_id ? [value.checkpoint] : [],
        }
      }
      return emptyState()
    }
    catch {
      return emptyState()
    }
  }

  private persistState(): void {
    this.storage?.setItem(this.storageKey, JSON.stringify(this.state))
  }
}

function browserStorage(): AiriAdapterStorage | undefined {
  try {
    return (globalThis as { localStorage?: AiriAdapterStorage }).localStorage
  }
  catch {
    return undefined
  }
}

function createClientInstanceId(): string {
  return `airi-${crypto.randomUUID()}`
}

function emptyState(): AiriAdapterState {
  return { version: 2, checkpoints: [] }
}

function requiredSessionId(sessionId: string): string {
  if (!sessionId?.trim())
    throw new TypeError('sessionId is required')
  return sessionId.trim()
}

export {
  MeguriApiError,
}

export type {
  FetchLike,
  FetchResponse,
  FollowOptions,
  MeguriApiClientOptions,
}
