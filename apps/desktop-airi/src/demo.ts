import { MeguriDesktopRuntime } from '../../../adapters/airi/src/index.ts'
import { MeguriApiAdapter } from '../../../adapters/airi/src/meguri-api-adapter.ts'
import { PngRenderer } from '../../../packages/renderer-contracts/src/index.ts'
import { MockLocalTtsAdapter } from '../../../local-services/tts-adapter/src/index.ts'

const coreUrl = process.env.MEGURI_CORE_URL ?? 'http://127.0.0.1:8000'
const sessionId = `airi-demo-${Date.now()}`
const renderer = new PngRenderer(
  ['01', '02', '03', '04'].flatMap(outfitCode => [
    {
      characterId: 'meguri',
      outfitCode,
      expressionTag: 'neutral',
      intensity: 'low' as const,
      spriteFile: 'placeholder-neutral.png',
    },
    {
      characterId: 'meguri',
      outfitCode,
      expressionTag: 'happy',
      intensity: 'medium' as const,
      spriteFile: 'placeholder-happy.png',
    },
  ]),
)
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
