import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import test from 'node:test'

import {
  PngRenderer,
  pngCatalogFromExpressionMap,
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

test('canonical expression export resolves only real build-matched PNG assets', () => {
  const root = resolve(import.meta.dirname, '..')
  const buildReport = JSON.parse(readFileSync(resolve(root, 'datasets/meguri/build_report.json'), 'utf8'))
  const expressionMap = JSON.parse(readFileSync(
    resolve(root, 'datasets/meguri/exports/expression_map/expression_map.json'),
    'utf8',
  ))
  const canonical = pngCatalogFromExpressionMap(expressionMap, {
    expectedBuildId: buildReport.build_id,
    resolveSpritePath: projectPath => resolve(root, projectPath),
    assetExists: existsSync,
  })
  assert.ok(canonical.length > 100)
  const neutralSleep = canonical.find(entry => entry.spriteFile.endsWith('ce04003l.png'))
  assert.equal(neutralSleep?.outfitCode, '04')
  assert.equal(neutralSleep?.expressionTag, 'neutral')
  assert.ok(neutralSleep && existsSync(neutralSleep.spriteFile))
})
