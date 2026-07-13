import {
  SessionTurnReducer,
  SseTurnEventParser,
  type TurnCreateResponse,
  type TurnEventEnvelope,
  type TurnRequest,
} from '../../protocol/src/index.ts'

export interface FollowOptions {
  signal?: AbortSignal
  maxReconnects?: number
  onEvent?: (event: TurnEventEnvelope) => void | Promise<void>
  untilTurnId?: string
}

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
}

export class MeguriApiError extends Error {
  status?: number

  constructor(message: string, status?: number) {
    super(message)
    this.status = status
  }
}

export class MeguriApiClient {
  private readonly baseUrl: string
  private readonly fetchImpl: FetchLike

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
  }

  async createTurn(request: TurnRequest, idempotencyKey?: string): Promise<TurnCreateResponse> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (idempotencyKey)
      headers['Idempotency-Key'] = idempotencyKey
    const response = await this.fetchImpl(`${this.baseUrl}/v1/turns`, {
      method: 'POST',
      headers,
      body: JSON.stringify(request),
    })
    const value = await this.expectObject(response, 'create turn')
    for (const key of ['turn_id', 'session_id', 'build_id', 'status']) {
      if (typeof value[key] !== 'string')
        throw new MeguriApiError(`create turn response is missing ${key}`)
    }
    return value as unknown as TurnCreateResponse
  }

  async cancelTurn(turnId: string): Promise<void> {
    const response = await this.fetchImpl(
      `${this.baseUrl}/v1/turns/${encodeURIComponent(turnId)}/cancel`,
      { method: 'POST' },
    )
    await this.expectObject(response, 'cancel turn')
  }

  async getTurnStatus(turnId: string): Promise<string> {
    const response = await this.fetchImpl(
      `${this.baseUrl}/v1/turns/${encodeURIComponent(turnId)}`,
    )
    const value = await this.expectObject(response, 'get turn status')
    if (typeof value.status !== 'string')
      throw new MeguriApiError('turn status response is missing status')
    return value.status
  }

  async followSession(
    sessionId: string,
    reducer: SessionTurnReducer,
    options: FollowOptions = {},
  ): Promise<void> {
    const maxReconnects = options.maxReconnects ?? 3
    let reconnects = 0
    while (true) {
      if (options.signal?.aborted)
        throw new DOMException('follow aborted', 'AbortError')
      try {
        await this.readEventStream(sessionId, reducer, options)
        if (options.untilTurnId && !reducer.isTerminal(options.untilTurnId))
          throw new MeguriApiError('event stream ended before the turn became terminal')
        return
      }
      catch (error) {
        if (options.signal?.aborted)
          throw error
        if (reconnects >= maxReconnects)
          throw error
        reconnects += 1
      }
    }
  }

  async runTurn(
    request: TurnRequest,
    reducer = new SessionTurnReducer(),
    options: FollowOptions & { idempotencyKey?: string } = {},
  ): Promise<{ created: TurnCreateResponse, reducer: SessionTurnReducer }> {
    const created = await this.createTurn(request, options.idempotencyKey)
    await this.followSession(created.session_id, reducer, {
      ...options,
      untilTurnId: created.turn_id,
    })
    return { created, reducer }
  }

  private async readEventStream(
    sessionId: string,
    reducer: SessionTurnReducer,
    options: FollowOptions,
  ): Promise<void> {
    const url = new URL(
      `${this.baseUrl}/v1/sessions/${encodeURIComponent(sessionId)}/events`,
    )
    url.searchParams.set('after_sequence', String(reducer.lastSequence))
    const response = await this.fetchImpl(url, {
      headers: {
        Accept: 'text/event-stream',
        'Last-Event-ID': String(reducer.lastSequence),
      },
      signal: options.signal,
    })
    if (!response.ok)
      throw new MeguriApiError(`event stream returned HTTP ${response.status}`, response.status)
    if (!response.body)
      throw new MeguriApiError('event stream response has no body')
    const parser = new SseTurnEventParser()
    const decoder = new TextDecoder()
    const reader = response.body.getReader()
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done)
          break
        for (const event of parser.push(decoder.decode(value, { stream: true }))) {
          if (reducer.apply(event))
            await options.onEvent?.(event)
        }
      }
      const tail = decoder.decode()
      if (tail) {
        for (const event of parser.push(tail)) {
          if (reducer.apply(event))
            await options.onEvent?.(event)
        }
      }
      parser.finish()
    }
    finally {
      reader.releaseLock()
    }
  }

  private async expectObject(
    response: FetchResponse,
    operation: string,
  ): Promise<Record<string, unknown>> {
    if (!response.ok)
      throw new MeguriApiError(`${operation} returned HTTP ${response.status}`, response.status)
    const value = await response.json()
    if (typeof value !== 'object' || value === null || Array.isArray(value))
      throw new MeguriApiError(`${operation} response must be an object`)
    return value as Record<string, unknown>
  }
}

function isLoopbackHost(hostname: string): boolean {
  return hostname === '127.0.0.1' || hostname === 'localhost' || hostname === '[::1]'
}
