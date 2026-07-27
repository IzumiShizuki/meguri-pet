import {
  MeguriApiClient,
  type FollowOptions,
} from '../../../packages/client-sdk/src/index.ts'
import {
  SessionTurnReducer,
  type ClientCapabilities,
  type SessionEventCheckpoint,
  type TurnEventEnvelope,
  type TurnRequest,
  type TurnViewState,
} from '../../../packages/protocol/src/index.ts'

export interface WebsiteIdentity {
  meguriUserId: string
  storageKey: string
}

export interface KeyValueStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface WebsiteSessionRecordV1 {
  version: 1
  sessionId: string
  activeTurnId?: string
}

export interface WebsiteSessionRecordV2 {
  version: 2
  sessionId: string
  activeTurnId?: string
  checkpoint: SessionEventCheckpoint
  activeTurnState?: TurnViewState
}

export type WebsiteSessionRecord = WebsiteSessionRecordV1 | WebsiteSessionRecordV2

export interface WebsiteSessionOptions {
  capabilities?: Partial<ClientCapabilities>
  createSessionId?: () => string
}

export interface WebsiteSendOptions extends Omit<FollowOptions, 'untilTurnId'> {
  idempotencyKey?: string
  relationshipProfile?: TurnRequest['relationship_profile']
}

const defaultCapabilities: ClientCapabilities = {
  text: true,
  sprite: true,
  voice: false,
  screen_context: false,
}

export class WebsiteSessionStore {
  private readonly storage: KeyValueStorage
  private readonly key: string

  constructor(storage: KeyValueStorage, storageKey: string) {
    if (!storageKey.trim())
      throw new TypeError('website storageKey must not be empty')
    this.storage = storage
    this.key = `meguri.website.session.${storageKey}`
  }

  load(): WebsiteSessionRecord | undefined {
    const raw = this.storage.getItem(this.key)
    if (!raw)
      return undefined
    try {
      const value = JSON.parse(raw) as unknown
      if (!isSessionRecord(value)) {
        this.storage.removeItem(this.key)
        return undefined
      }
      return value
    }
    catch {
      this.storage.removeItem(this.key)
      return undefined
    }
  }

  save(record: WebsiteSessionRecord): void {
    this.storage.setItem(this.key, JSON.stringify(record))
  }

  clear(): void {
    this.storage.removeItem(this.key)
  }
}

export class WebsiteMeguriSession {
  reducer: SessionTurnReducer
  readonly sessionId: string
  private readonly api: MeguriApiClient
  private readonly identity: WebsiteIdentity
  private readonly store: WebsiteSessionStore
  private readonly capabilities: ClientCapabilities
  private activeTurnId?: string

  constructor(
    api: MeguriApiClient,
    identity: WebsiteIdentity,
    storage: KeyValueStorage,
    options: WebsiteSessionOptions = {},
  ) {
    if (!identity.meguriUserId.trim())
      throw new TypeError('trusted meguriUserId must not be empty')
    this.api = api
    this.identity = identity
    this.store = new WebsiteSessionStore(storage, identity.storageKey)
    this.capabilities = { ...defaultCapabilities, ...options.capabilities }
    const saved = this.store.load()
    this.sessionId = saved?.sessionId ?? (options.createSessionId ?? createSessionId)()
    this.activeTurnId = saved?.activeTurnId
    this.reducer = new SessionTurnReducer(saved?.version === 2 ? saved.checkpoint : 0)
    if (saved?.version === 2 && saved.activeTurnState)
      this.reducer.turns.set(saved.activeTurnState.turnId, { ...saved.activeTurnState })
    this.persist()
  }

  get pendingTurnId(): string | undefined {
    return this.activeTurnId
  }

  async send(message: string, options: WebsiteSendOptions = {}): Promise<TurnViewState> {
    const normalized = message.trim()
    if (!normalized)
      throw new TypeError('message must not be empty')
    if (this.activeTurnId)
      throw new Error('an active website turn must be resumed or cancelled first')
    const request: TurnRequest = {
      user_id: this.identity.meguriUserId,
      client_id: 'website',
      session_id: this.sessionId,
      message: normalized,
      client_capabilities: this.capabilities,
      relationship_profile: options.relationshipProfile,
    }
    const created = await this.api.createTurn(request, options.idempotencyKey)
    this.activeTurnId = created.turn_id
    this.persist()
    return await this.followActive(options)
  }

  async resume(options: Omit<WebsiteSendOptions, 'idempotencyKey' | 'relationshipProfile'> = {}): Promise<TurnViewState | undefined> {
    if (!this.activeTurnId)
      return undefined
    return await this.followActive(options)
  }

  async cancel(): Promise<void> {
    if (!this.activeTurnId)
      return
    await this.api.cancelTurn(this.activeTurnId)
  }

  private async followActive(options: FollowOptions): Promise<TurnViewState> {
    const turnId = this.activeTurnId
    if (!turnId)
      throw new Error('no active website turn')
    if (!this.reducer.isTerminal(turnId)) {
      await this.api.followSession(this.sessionId, this.reducer, {
        ...options,
        untilTurnId: turnId,
        onEvent: async (event: TurnEventEnvelope) => {
          // The reducer has already applied the event. Persist its checkpoint
          // before dispatching page-side effects so reloads cannot replay them.
          this.persist()
          await options.onEvent?.(event)
        },
      })
    }
    const state = this.reducer.turns.get(turnId)
    if (!state)
      throw new Error('terminal website turn has no view state')
    this.activeTurnId = undefined
    this.persist()
    return state
  }

  private persist(): void {
    const activeTurnState = this.activeTurnId
      ? this.reducer.turns.get(this.activeTurnId)
      : undefined
    this.store.save({
      version: 2,
      sessionId: this.sessionId,
      activeTurnId: this.activeTurnId,
      checkpoint: this.reducer.checkpoint(),
      activeTurnState: activeTurnState ? { ...activeTurnState } : undefined,
    })
  }
}

function createSessionId(): string {
  return `web_${crypto.randomUUID().replaceAll('-', '')}`
}

function isSessionRecord(value: unknown): value is WebsiteSessionRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    return false
  const record = value as Record<string, unknown>
  if (typeof record.sessionId !== 'string' || record.sessionId.length === 0)
    return false
  if (record.activeTurnId !== undefined && typeof record.activeTurnId !== 'string')
    return false
  if (record.version === 1)
    return true
  if (record.version !== 2 || !isCheckpoint(record.checkpoint))
    return false
  if (record.checkpoint.session_id !== undefined
    && record.checkpoint.session_id !== record.sessionId)
    return false
  return record.activeTurnState === undefined
    || isTurnViewState(record.activeTurnState, record.activeTurnId)
}

function isCheckpoint(value: unknown): value is SessionEventCheckpoint {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    return false
  const checkpoint = value as Record<string, unknown>
  return Number.isSafeInteger(checkpoint.last_sequence)
    && Number(checkpoint.last_sequence) >= 0
    && (checkpoint.session_id === undefined
      || (typeof checkpoint.session_id === 'string' && checkpoint.session_id.length > 0))
    && Array.isArray(checkpoint.processed_event_ids)
    && checkpoint.processed_event_ids.every(eventId => typeof eventId === 'string' && eventId.length > 0)
}

function isTurnViewState(value: unknown, activeTurnId: unknown): value is TurnViewState {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    return false
  const state = value as Record<string, unknown>
  return typeof state.turnId === 'string'
    && state.turnId.length > 0
    && (activeTurnId === undefined || state.turnId === activeTurnId)
    && typeof state.text === 'string'
    && ['idle', 'running', 'completed', 'cancelled', 'failed'].includes(String(state.status))
}
