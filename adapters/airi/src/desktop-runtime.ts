import type { CharacterRenderer } from '../../../packages/renderer-contracts/src/index.ts'
import type {
  LocalTtsAdapter,
  VoiceIntensity,
  VoiceStyle,
} from '../../../local-services/tts-adapter/src/index.ts'
import {
  SessionTurnReducer,
  type TurnEventEnvelope,
  type TurnRequest,
} from '../../../packages/protocol/src/index.ts'
import { MeguriApiAdapter, type FollowOptions } from './meguri-api-adapter.ts'

export class MeguriDesktopRuntime {
  readonly reducer: SessionTurnReducer
  private readonly api: MeguriApiAdapter
  private readonly renderer: CharacterRenderer
  private readonly tts?: LocalTtsAdapter
  private readonly voiceCues = new Map<string, { voiceStyle: VoiceStyle, intensity: VoiceIntensity }>()

  constructor(
    api: MeguriApiAdapter,
    renderer: CharacterRenderer,
    reducer = new SessionTurnReducer(),
    tts?: LocalTtsAdapter,
  ) {
    this.api = api
    this.renderer = renderer
    this.reducer = reducer
    this.tts = tts
  }

  async send(
    request: TurnRequest,
    options: FollowOptions & { idempotencyKey?: string } = {},
  ): Promise<string> {
    const created = await this.api.createTurn(request, options.idempotencyKey)
    await this.api.followSession(created.session_id, this.reducer, {
      ...options,
      untilTurnId: created.turn_id,
      onEvent: async (event) => {
        await this.applyRendererCue(event)
        await options.onEvent?.(event)
      },
    })
    return created.turn_id
  }

  async cancel(turnId: string): Promise<void> {
    await this.api.cancelTurn(turnId)
  }

  private async applyRendererCue(event: TurnEventEnvelope): Promise<void> {
    if (event.type === 'semantic.completed') {
      const style = event.data.voice_style
      const intensity = event.data.expression_intensity
      if (isVoiceStyle(style) && isVoiceIntensity(intensity))
        this.voiceCues.set(event.turn_id, { voiceStyle: style, intensity })
    }
    else if (event.type === 'expression.cue' || event.type === 'sprite.resolved') {
      const outfit = event.data.outfit_code
      const tag = event.data.expression_tag
      const intensity = event.data.expression_intensity
      if (typeof outfit === 'string')
        await this.renderer.setOutfit(outfit)
      if (
        typeof tag === 'string'
        && (intensity === 'low' || intensity === 'medium' || intensity === 'high')
      ) {
        await this.renderer.setExpression(tag, intensity)
      }
    }
    else if (event.type === 'tts.requested') {
      await this.renderer.speak({
        text: typeof event.data.text === 'string' ? event.data.text : undefined,
        audioUrl: typeof event.data.audio_url === 'string' ? event.data.audio_url : undefined,
      })
    }
    else if (event.type === 'text.completed' && this.tts && typeof event.data.text === 'string') {
      const style = this.voiceCues.get(event.turn_id) ?? {
        voiceStyle: 'neutral' as const,
        intensity: 'low' as const,
      }
      try {
        const cue = await this.tts.synthesize({
          text: event.data.text,
          voiceStyle: style.voiceStyle,
          intensity: style.intensity,
          modelVersion: 'mock-v1',
        })
        await this.renderer.speak({
          text: event.data.text,
          audioUrl: cue.audioUrl,
          durationMs: cue.durationMs,
        })
      }
      catch {
        // Local TTS is optional and must never fail the text turn.
      }
    }
    else if (event.type === 'turn.cancelled' || event.type === 'turn.failed') {
      this.voiceCues.delete(event.turn_id)
      await this.renderer.resetToIdle()
    }
    else if (event.type === 'turn.completed') {
      this.voiceCues.delete(event.turn_id)
    }
  }
}

function isVoiceStyle(value: unknown): value is VoiceStyle {
  return typeof value === 'string' && [
    'neutral', 'soft', 'cheerful', 'restrained', 'sleepy', 'teasing', 'affectionate', 'worried',
  ].includes(value)
}

function isVoiceIntensity(value: unknown): value is VoiceIntensity {
  return value === 'low' || value === 'medium' || value === 'high'
}
