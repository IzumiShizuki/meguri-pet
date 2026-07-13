import { WebsiteMeguriSession, type KeyValueStorage } from '../../../adapters/website/src/index.ts'
import { MeguriApiClient } from '../../../packages/client-sdk/src/index.ts'

class DemoStorage implements KeyValueStorage {
  private readonly values = new Map<string, string>()

  getItem(key: string): string | null { return this.values.get(key) ?? null }
  setItem(key: string, value: string): void { this.values.set(key, value) }
  removeItem(key: string): void { this.values.delete(key) }
}

const baseUrl = process.argv[2] ?? 'http://127.0.0.1:8000'
const message = process.argv[3] ?? '今天一起继续 Meguri 框架开发。'
const api = new MeguriApiClient(baseUrl)
const session = new WebsiteMeguriSession(
  api,
  { meguriUserId: 'local-demo-user', storageKey: 'local-demo-login' },
  new DemoStorage(),
)
const state = await session.send(message, { idempotencyKey: `website-demo-${Date.now()}` })

console.log(JSON.stringify({
  sessionId: session.sessionId,
  turnId: state.turnId,
  status: state.status,
  text: state.text,
  expression: state.expression,
}, null, 2))
