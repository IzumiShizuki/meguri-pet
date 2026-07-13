import assert from 'node:assert/strict'
import test from 'node:test'

import {
  PngRenderer,
  type PngAssetEntry,
} from '../packages/renderer-contracts/src/index.ts'

const catalog: PngAssetEntry[] = [
  {
    characterId: 'meguri',
    outfitCode: '01',
    expressionTag: 'neutral',
    intensity: 'low',
    spriteFile: 'placeholder-neutral.png',
  },
  {
    characterId: 'meguri',
    outfitCode: '01',
    expressionTag: 'happy',
    intensity: 'medium',
    spriteFile: 'placeholder-happy.png',
  },
  {
    characterId: 'meguri',
    outfitCode: '03',
    expressionTag: 'neutral',
    intensity: 'low',
    spriteFile: 'placeholder-home-neutral.png',
  },
]

test('PNG renderer switches semantic expressions to catalog assets', async () => {
  const renderer = new PngRenderer(catalog)
  await renderer.loadCharacter('meguri')
  await renderer.setExpression('happy', 'medium')
  assert.equal(renderer.snapshot().spriteFile, 'placeholder-happy.png')
  assert.equal(renderer.snapshot().expressionTag, 'happy')
})

test('PNG renderer falls back to real neutral asset', async () => {
  const renderer = new PngRenderer(catalog)
  await renderer.loadCharacter('meguri')
  await renderer.setExpression('not-in-catalog', 'high')
  assert.equal(renderer.snapshot().spriteFile, 'placeholder-neutral.png')
  assert.equal(renderer.snapshot().expressionTag, 'neutral')
})

test('PNG renderer rejects disabled outfit and resets idle state', async () => {
  const renderer = new PngRenderer(catalog)
  await renderer.loadCharacter('meguri')
  await assert.rejects(renderer.setOutfit('07'), /disabled or unknown/)
  await renderer.speak({ text: 'hello' })
  await renderer.playMotion('small_nod')
  await renderer.resetToIdle()
  assert.equal(renderer.snapshot().speaking, false)
  assert.equal(renderer.snapshot().motionTag, undefined)
})
