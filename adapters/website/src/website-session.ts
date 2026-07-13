import {
  MeguriApiClient,
  type FollowOptions,
} from '../../../packages/client-sdk/src/index.ts'
import {
  SessionTurnReducer,
  type ClientCapabilities,
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

export interface WebsiteSessionRecord {
  version: 1
  sessionId: string
  activeTurnId?: string
}

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
    this.reducer = new SessionTurnReducer()
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
    await this.api.followSession(this.sessionId, this.reducer, {
      ...options,
      untilTurnId: turnId,
      onEvent: async (event: TurnEventEnvelope) => {
        await options.onEvent?.(event)
      },
    })
    const state = this.reducer.turns.get(turnId)
    if (!state)
      throw new Error('terminal website turn has no view state')
    this.activeTurnId = undefined
    this.persist()
    return state
  }

  private persist(): void {
    this.store.save({
      version: 1,
      sessionId: this.sessionId,
      activeTurnId: this.activeTurnId,
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
  return record.version === 1
    && typeof record.sessionId === 'string'
    && record.sessionId.length > 0
    && (record.activeTurnId === undefined || typeof record.activeTurnId === 'string')
}
