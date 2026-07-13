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
  lastSequence: number
  sessionId?: string

  constructor(startSequence = 0) {
    this.lastSequence = startSequence
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

  isTerminal(turnId: string): boolean {
    const status = this.turns.get(turnId)?.status
    return status === 'completed' || status === 'cancelled' || status === 'failed'
  }

  terminalEventSeen(event: TurnEventEnvelope): boolean {
    return isTerminalEvent(event.type)
  }
}
