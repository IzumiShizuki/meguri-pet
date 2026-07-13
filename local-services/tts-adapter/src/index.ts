import { createHash } from 'node:crypto'

export type VoiceStyle =
  | 'neutral'
  | 'soft'
  | 'cheerful'
  | 'restrained'
  | 'sleepy'
  | 'teasing'
  | 'affectionate'
  | 'worried'

export type VoiceIntensity = 'low' | 'medium' | 'high'

export interface TtsSynthesisRequest {
  text: string
  voiceStyle: VoiceStyle
  intensity: VoiceIntensity
  modelVersion: string
}

export interface TtsAudioCue {
  cacheKey: string
  audioUrl: string
  mimeType: 'audio/wav'
  durationMs: number
}

export interface LocalTtsAdapter {
  synthesize(request: TtsSynthesisRequest): Promise<TtsAudioCue>
  cancel(cacheKey: string): Promise<void>
}

export class MockLocalTtsAdapter implements LocalTtsAdapter {
  async synthesize(request: TtsSynthesisRequest): Promise<TtsAudioCue> {
    if (!request.text.trim())
      throw new Error('TTS text must not be empty')
    const normalizedText = request.text.trim().replaceAll(/\s+/g, ' ')
    const cacheKey = createHash('sha256')
      .update(JSON.stringify({
        normalizedText,
        voiceStyle: request.voiceStyle,
        intensity: request.intensity,
        modelVersion: request.modelVersion,
      }))
      .digest('hex')
    return {
      cacheKey,
      audioUrl: `mock://tts/${cacheKey}`,
      mimeType: 'audio/wav',
      durationMs: Math.max(300, normalizedText.length * 80),
    }
  }

  async cancel(_cacheKey: string): Promise<void> {}
}
