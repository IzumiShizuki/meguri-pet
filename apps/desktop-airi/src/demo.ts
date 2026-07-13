import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { MeguriDesktopRuntime } from '../../../adapters/airi/src/index.ts'
import { MeguriApiAdapter } from '../../../adapters/airi/src/meguri-api-adapter.ts'
import { PngRenderer, pngCatalogFromExpressionMap } from '../../../packages/renderer-contracts/src/index.ts'
import { MockLocalTtsAdapter } from '../../../local-services/tts-adapter/src/index.ts'

const coreUrl = process.env.MEGURI_CORE_URL ?? 'http://127.0.0.1:8000'
const sessionId = `airi-demo-${Date.now()}`
const root = fileURLToPath(new URL('../../../', import.meta.url))
const buildReport = JSON.parse(readFileSync(resolve(root, 'datasets/meguri/build_report.json'), 'utf8'))
const expressionMap = JSON.parse(readFileSync(
  resolve(root, 'datasets/meguri/exports/expression_map/expression_map.json'),
  'utf8',
))
const renderer = new PngRenderer(pngCatalogFromExpressionMap(expressionMap, {
  expectedBuildId: buildReport.build_id,
  resolveSpritePath: projectPath => resolve(root, projectPath),
  assetExists: existsSync,
}))
await renderer.loadCharacter('meguri')
const runtime = new MeguriDesktopRuntime(
  new MeguriApiAdapter(coreUrl),
  renderer,
  undefined,
  new MockLocalTtsAdapter(),
)
const turnId = await runtime.send({
  user_id: 'local-airi-demo',
  client_id: 'desktop_pet',
  session_id: sessionId,
  message: 'Meguri AIRI adapter local demo',
  client_capabilities: {
    text: true,
    sprite: true,
    voice: false,
    screen_context: false,
  },
}, { idempotencyKey: sessionId })

console.log(JSON.stringify({
  turnId,
  turn: runtime.reducer.turns.get(turnId),
  renderer: renderer.snapshot(),
}, null, 2))
