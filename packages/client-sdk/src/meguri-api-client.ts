import {
  ProtocolCompatibilityError,
  SequenceGapError,
  SessionTurnReducer,
  SseTurnEventParser,
  assertCompatibleProtocolVersion,
  assertRequiredExtensions,
  parseHelloResponse,
  parseErrorResponse,
  parseSessionSnapshot,
  parseTurn,
  parseTurnCancelResponse,
  parseTurnCreateRequest,
  parseTurnCreateResponse,
  type ClientHello,
  type HelloResponse,
  type ProtocolError,
  type SessionSnapshot,
  type Turn,
  type TurnCancelResponse,
  type TurnCreateRequest,
  type TurnCreateResponse,
  type TurnEventEnvelope,
  type TurnRequest,
} from '../../protocol/src/index.ts'

export interface SubscribeOptions {
  signal?: AbortSignal
  maxReconnects?: number
  onEvent?: (event: TurnEventEnvelope) => void | Promise<void>
  onSnapshot?: (snapshot: SessionSnapshot) => void | Promise<void>
  untilTurnId?: string
  pollIntervalMs?: number
  supportedExtensions?: string[]
}

/** @deprecated Use SubscribeOptions. */
export type FollowOptions = SubscribeOptions

export interface FetchResponse {
  ok: boolean
  status: number
  json(): Promise<unknown>
  body: ReadableStream<Uint8Array> | null
}

export type FetchLike = (
  input: string | URL,
  init?: RequestInit,
) => Promise<FetchResponse>

export interface MeguriApiClientOptions {
  allowNonLoopback?: boolean
  pollIntervalMs?: number
}

export class MeguriApiError extends Error {
  readonly status?: number
  readonly code?: ProtocolError['code']
  readonly retryable: boolean
  readonly details?: Record<string, unknown>

  constructor(
    message: string,
    status?: number,
    protocolError?: ProtocolError,
  ) {
    super(message)
    this.status = status
    this.code = protocolError?.code
    this.retryable = protocolError?.retryable ?? isRetryableHttpStatus(status)
    this.details = protocolError?.details
  }
}

export class MeguriApiClient {
  private readonly baseUrl: string
  private readonly fetchImpl: FetchLike
  private readonly pollIntervalMs: number
  private sseSupported: boolean | undefined
  private selectedProtocolVersion?: string
  private supportedExtensions: string[] = []

  constructor(
    baseUrl: string,
    fetchImpl: FetchLike = fetch as unknown as FetchLike,
    options: MeguriApiClientOptions = {},
  ) {
    const parsed = new URL(baseUrl)
    if (!['http:', 'https:'].includes(parsed.protocol))
      throw new TypeError('Meguri API URL must use HTTP or HTTPS')
    if (!options.allowNonLoopback && !isLoopbackHost(parsed.hostname))
      throw new TypeError('Meguri API must use loopback unless explicitly allowed')
    this.baseUrl = baseUrl.replace(/\/$/, '')
    this.fetchImpl = fetchImpl
    this.pollIntervalMs = options.pollIntervalMs ?? 1_000
  }

  async hello(request: ClientHello): Promise<HelloResponse> {
    const response = await this.fetchImpl(`${this.baseUrl}/v1/hello`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    })
    const value = await this.expectObject(response, 'hello')
    const hello = parseHelloResponse(value)
    assertCompatibleProtocolVersion(hello.selected_protocol_version)
    assertRequiredExtensions(request.required_extensions, hello.supported_extensions ?? [])
    this.selectedProtocolVersion = hello.selected_protocol_version
    this.supportedExtensions = [...(hello.supported_extensions ?? [])]
    this.sseSupported = hello.effective_capabilities.sse
    return hello
  }

  async createTurn(
    request: TurnRequest | TurnCreateRequest,
    idempotencyKey?: string,
  ): Promise<TurnCreateResponse> {
    if ('protocol_version' in request)
      parseTurnCreateRequest(request)
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (idempotencyKey)
      headers['Idempotency-Key'] = idempotencyKey
    const response = await this.fetchImpl(`${this.baseUrl}/v1/turns`, {
      method: 'POST',
      headers,
      body: JSON.stringify(request),
    })
    return parseTurnCreateResponse(await this.expectObject(response, 'create turn'))
  }

  async getTurn(turnId: string): Promise<Turn> {
    const value = await this.getTurnObject(turnId)
    return parseTurn(value)
  }

  async cancel(turnId: string): Promise<TurnCancelResponse> {
    const response = await this.fetchImpl(
      `${this.baseUrl}/v1/turns/${encodeURIComponent(turnId)}/cancel`,
      { method: 'POST' },
    )
    return parseTurnCancelResponse(await this.expectObject(response, 'cancel turn'))
  }

  /** @deprecated Use cancel(). */
  async cancelTurn(turnId: string): Promise<void> {
    await this.cancel(turnId)
  }

  /** @deprecated Use getTurn(). */
  async getTurnStatus(turnId: string): Promise<string> {
    const value = await this.getTurnObject(turnId)
    if (typeof value.status !== 'string')
      throw new MeguriApiError('turn status response is missing status')
    return value.status
  }

  async getSessionSnapshot(sessionId: string, signal?: AbortSignal): Promise<SessionSnapshot> {
    const response = await this.fetchImpl(
      `${this.baseUrl}/v1/sessions/${encodeURIComponent(sessionId)}/snapshot`,
      { signal },
    )
    const snapshot = parseSessionSnapshot(await this.expectObject(response, 'get session snapshot'))
    assertCompatibleProtocolVersion(snapshot.protocol_version)
    return snapshot
  }

  async subscribe(
    sessionId: string,
    reducer: SessionTurnReducer,
    options: SubscribeOptions = {},
  ): Promise<void> {
    if (this.sseSupported === false) {
      await this.pollSnapshots(sessionId, reducer, options)
      return
    }
    const maxReconnects = options.maxReconnects ?? 3
    let reconnects = 0
    while (true) {
      throwIfAborted(options.signal)
      try {
        await this.readEventStream(sessionId, reducer, options)
        if (options.untilTurnId && !reducer.isTerminal(options.untilTurnId))
          throw new MeguriApiError('event stream ended before the turn became terminal')
        return
      }
      catch (error) {
        if (options.signal?.aborted)
          throw error
        if (isSseUnsupported(error)) {
          this.sseSupported = false
          await this.pollSnapshots(sessionId, reducer, options)
          return
        }
        if (isCursorExpired(error) || error instanceof SequenceGapError) {
          await this.restoreSnapshot(sessionId, reducer, options)
          if (!options.untilTurnId || reducer.isTerminal(options.untilTurnId))
            return
        }
        if (reconnects >= maxReconnects) {
          if (maxReconnects > 0 && options.untilTurnId && isRetryableSseFailure(error)) {
            await this.pollSnapshots(sessionId, reducer, options)
            return
          }
          throw error
        }
        reconnects += 1
      }
    }
  }

  /** @deprecated Use subscribe(). */
  async followSession(
    sessionId: string,
    reducer: SessionTurnReducer,
    options: FollowOptions = {},
  ): Promise<void> {
    await this.subscribe(sessionId, reducer, options)
  }

  async waitForTurnTerminal(
    turnId: string,
    options: { signal?: AbortSignal, pollIntervalMs?: number } = {},
  ): Promise<Turn> {
    while (true) {
      throwIfAborted(options.signal)
      const turn = await this.getTurn(turnId)
      if (isTerminalStatus(turn.status))
        return turn
      await delay(options.pollIntervalMs ?? this.pollIntervalMs, options.signal)
    }
  }

  async runTurn(
    request: TurnRequest | TurnCreateRequest,
    reducer = new SessionTurnReducer(),
    options: FollowOptions & { idempotencyKey?: string } = {},
  ): Promise<{ created: TurnCreateResponse, reducer: SessionTurnReducer }> {
    const created = await this.createTurn(request, options.idempotencyKey)
    await this.subscribe(created.session_id, reducer, {
      ...options,
      untilTurnId: created.turn_id,
    })
    return { created, reducer }
  }

  private async readEventStream(
    sessionId: string,
    reducer: SessionTurnReducer,
    options: SubscribeOptions,
  ): Promise<void> {
    const url = new URL(`${this.baseUrl}/v1/sessions/${encodeURIComponent(sessionId)}/events`)
    url.searchParams.set('after_sequence', String(reducer.lastSequence))
    const response = await this.fetchImpl(url, {
      headers: {
        Accept: 'text/event-stream',
        'Last-Event-ID': String(reducer.lastSequence),
        ...(this.selectedProtocolVersion
          ? { 'Meguri-Protocol-Version': this.selectedProtocolVersion }
          : {}),
      },
      signal: options.signal,
    })
    if (!response.ok)
      throw await this.responseError(response, 'event stream')
    if (!response.body)
      throw new MeguriApiError('event stream response has no body')
    const supportedExtensions = options.supportedExtensions ?? this.supportedExtensions
    const parser = new SseTurnEventParser(supportedExtensions)
    const decoder = new TextDecoder()
    const reader = response.body.getReader()
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done)
          break
        await this.dispatchParsed(parser.push(decoder.decode(value, { stream: true })), reducer, options)
      }
      const tail = decoder.decode()
      if (tail)
        await this.dispatchParsed(parser.push(tail), reducer, options)
      parser.finish()
    }
    finally {
      reader.releaseLock()
    }
  }

  private async dispatchParsed(
    events: TurnEventEnvelope[],
    reducer: SessionTurnReducer,
    options: SubscribeOptions,
  ): Promise<void> {
    for (const event of events) {
      if (event.required_extension)
        assertRequiredExtensions([event.required_extension], options.supportedExtensions ?? this.supportedExtensions)
      if (reducer.apply(event))
        await options.onEvent?.(event)
    }
  }

  private async pollSnapshots(
    sessionId: string,
    reducer: SessionTurnReducer,
    options: SubscribeOptions,
  ): Promise<void> {
    while (true) {
      throwIfAborted(options.signal)
      await this.restoreSnapshot(sessionId, reducer, options)
      if (!options.untilTurnId || reducer.isTerminal(options.untilTurnId))
        return
      await delay(options.pollIntervalMs ?? this.pollIntervalMs, options.signal)
    }
  }

  private async restoreSnapshot(
    sessionId: string,
    reducer: SessionTurnReducer,
    options: SubscribeOptions,
  ): Promise<void> {
    const snapshot = await this.getSessionSnapshot(sessionId, options.signal)
    reducer.restoreSnapshot(snapshot)
    await options.onSnapshot?.(snapshot)
  }

  private async getTurnObject(turnId: string): Promise<Record<string, unknown>> {
    const response = await this.fetchImpl(`${this.baseUrl}/v1/turns/${encodeURIComponent(turnId)}`)
    return this.expectObject(response, 'get turn')
  }

  private async expectObject(
    response: FetchResponse,
    operation: string,
  ): Promise<Record<string, unknown>> {
    if (!response.ok)
      throw await this.responseError(response, operation)
    const value = await response.json()
    if (!isRecord(value))
      throw new MeguriApiError(`${operation} response must be an object`)
    return value
  }

  private async responseError(response: FetchResponse, operation: string): Promise<MeguriApiError> {
    let protocolError: ProtocolError | undefined
    try {
      const value = await response.json()
      if (isRecord(value) && isRecord(value.error))
        protocolError = parseErrorResponse(value).error
    }
    catch {
      // An HTTP status remains stable even when an intermediary replaces the body.
    }
    return new MeguriApiError(
      protocolError?.message ?? `${operation} returned HTTP ${response.status}`,
      response.status,
      protocolError,
    )
  }
}

function isLoopbackHost(hostname: string): boolean {
  return hostname === '127.0.0.1' || hostname === 'localhost' || hostname === '[::1]'
}

function isSseUnsupported(error: unknown): boolean {
  return error instanceof MeguriApiError && [404, 405, 406, 415, 501].includes(error.status ?? 0)
}

function isCursorExpired(error: unknown): boolean {
  return error instanceof MeguriApiError && (error.status === 410 || error.code === 'CURSOR_EXPIRED')
}

function isRetryableSseFailure(error: unknown): boolean {
  return !(error instanceof MeguriApiError) || error.retryable
}

function isRetryableHttpStatus(status: number | undefined): boolean {
  return status === undefined || status === 408 || status === 429 || (status >= 500 && status <= 599)
}

function isTerminalStatus(status: string): boolean {
  return status === 'completed' || status === 'failed' || status === 'cancelled'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted)
    throw signal.reason ?? new DOMException('operation aborted', 'AbortError')
}

async function delay(milliseconds: number, signal?: AbortSignal): Promise<void> {
  if (milliseconds <= 0)
    return
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(signal.reason ?? new DOMException('operation aborted', 'AbortError'))
    }, { once: true })
  })
}

export { ProtocolCompatibilityError }
