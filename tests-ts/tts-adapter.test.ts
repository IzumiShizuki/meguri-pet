import assert from 'node:assert/strict'
import test from 'node:test'

import { MockLocalTtsAdapter } from '../local-services/tts-adapter/src/index.ts'

test('mock TTS cache key includes text style intensity and model version', async () => {
  const tts = new MockLocalTtsAdapter()
  const first = await tts.synthesize({
    text: 'hello   Meguri',
    voiceStyle: 'soft',
    intensity: 'medium',
    modelVersion: 'mock-v1',
  })
  const normalized = await tts.synthesize({
    text: 'hello Meguri',
    voiceStyle: 'soft',
    intensity: 'medium',
    modelVersion: 'mock-v1',
  })
  const otherStyle = await tts.synthesize({
    text: 'hello Meguri',
    voiceStyle: 'cheerful',
    intensity: 'medium',
    modelVersion: 'mock-v1',
  })
  assert.equal(first.cacheKey, normalized.cacheKey)
  assert.notEqual(first.cacheKey, otherStyle.cacheKey)
  assert.match(first.audioUrl, /^mock:\/\/tts\//)
})
