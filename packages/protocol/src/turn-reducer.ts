import { isTerminalEvent, type ExpressionIntensity, type TurnEventEnvelope } from './turn-events.ts'

export interface ExpressionCue {
  expression_tag: string
  expression_intensity: ExpressionIntensity
  outfit_code?: string
  sprite_file?: string | null
}

export interface TurnViewState {
  turnId: string
  text: string
  status: 'idle' | 'running' | 'completed' | 'cancelled' | 'failed'
  expression?: ExpressionCue
  error?: string
}

export interface SessionEventCheckpoint {
  session_id?: string
  last_sequence: number
  processed_event_ids: string[]
}

export class SequenceGapError extends Error {
  readonly expected: number
  readonly received: number

  constructor(expected: number, received: number) {
    super(`event sequence gap: expected ${expected}, received ${received}`)
    this.expected = expected
    this.received = received
  }
}

export class SessionTurnReducer {
  readonly turns = new Map<string, TurnViewState>()
  private readonly processedEventIds = new Set<string>()
  lastSequence: number
  sessionId?: string

  constructor(start: number | SessionEventCheckpoint = 0) {
    if (typeof start === 'number') {
      validateSequence(start, 'start sequence')
      this.lastSequence = start
      return
    }
    validateSequence(start.last_sequence, 'checkpoint sequence')
    if (start.session_id !== undefined && !start.session_id.trim())
      throw new TypeError('checkpoint session_id must not be empty')
    if (!Array.isArray(start.processed_event_ids))
      throw new TypeError('checkpoint processed_event_ids must be an array')
    for (const eventId of start.processed_event_ids) {
      if (typeof eventId !== 'string' || eventId.length === 0)
        throw new TypeError('checkpoint event IDs must be non-empty strings')
      this.processedEventIds.add(eventId)
    }
    this.lastSequence = start.last_sequence
    this.sessionId = start.session_id
  }

  apply(event: TurnEventEnvelope): boolean {
    if (this.sessionId && event.session_id !== this.sessionId)
      throw new Error('event belongs to another session')
    this.sessionId ??= event.session_id
    if (event.sequence <= this.lastSequence)
      return false
    const expected = this.lastSequence + 1
    if (event.sequence !== expected)
      throw new SequenceGapError(expected, event.sequence)
    this.lastSequence = event.sequence
    if (this.processedEventIds.has(event.event_id))
      return false
    this.processedEventIds.add(event.event_id)
    const state = this.turns.get(event.turn_id) ?? {
      turnId: event.turn_id,
      text: '',
      status: 'idle' as const,
    }
    if (event.type === 'turn.started')
      state.status = 'running'
    else if (event.type === 'text.delta')
      state.text += typeof event.data.delta === 'string' ? event.data.delta : ''
    else if (event.type === 'text.completed' && typeof event.data.text === 'string')
      state.text = event.data.text
    else if (event.type === 'expression.cue' || event.type === 'sprite.resolved')
      state.expression = event.data as unknown as ExpressionCue
    else if (event.type === 'turn.completed')
      state.status = 'completed'
    else if (event.type === 'turn.cancelled')
      state.status = 'cancelled'
    else if (event.type === 'turn.failed') {
      state.status = 'failed'
      state.error = typeof event.data.error === 'string' ? event.data.error : 'turn failed'
    }
    this.turns.set(event.turn_id, state)
    return true
  }

  checkpoint(): SessionEventCheckpoint {
    return {
      session_id: this.sessionId,
      last_sequence: this.lastSequence,
      processed_event_ids: [...this.processedEventIds],
    }
  }

  isTerminal(turnId: string): boolean {
    const status = this.turns.get(turnId)?.status
    return status === 'completed' || status === 'cancelled' || status === 'failed'
  }

  terminalEventSeen(event: TurnEventEnvelope): boolean {
    return isTerminalEvent(event.type)
  }
}

function validateSequence(value: number, label: string): void {
  if (!Number.isSafeInteger(value) || value < 0)
    throw new TypeError(`${label} must be a non-negative safe integer`)
}
