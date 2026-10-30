import { mkdtempSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { createShizukiReminderRuntime, dueTodoReminders } from './shizuki-reminders.mjs'
import { createShizukiTimePrismClient } from './shizuki-timeprism.mjs'

const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })
const root = () => { const value = mkdtempSync(join(tmpdir(), 'meguri-query-boundary-')); roots.push(value); return value }
const now = Date.parse('2030-01-02T10:00:00Z')
const due = { todoId: 7, title: 'synthetic occurrence', done: false, reminderEnabled: true,
  dueAt: '2030-01-02T18:00:00', deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE', timingMode: 'DEADLINE' }
const flush = () => new Promise<void>(resolve => setImmediate(resolve))
const input = { title: 'synthetic bounded rule', weekdays: [1, 3, 5], time: '18:00', timeZoneId: 'Asia/Shanghai',
  enabled: true, reminderEnabled: true, deadlineRemindValue: 10, deadlineRemindUnit: 'MINUTE',
  requestId: '00000000-0000-4000-8000-000000000091', expectedAccount: 'https://site.shizuki.online:42' }
const json = (data: unknown) => new Response(JSON.stringify({ data }), { headers: { 'content-type': 'application/json' } })
async function fixture() {
  let saved: any
  const writes: any[] = []
  const client = createShizukiTimePrismClient({ filePath: join(root(), 'synthetic-session.json'), clock: () => now,
    safeStorage: { isEncryptionAvailable: () => true, encryptString: (s: string) => Buffer.from(s), decryptString: (s: Buffer) => s.toString() },
    fetchImpl: async (url: string | URL | Request, options: RequestInit = {}) => {
      if (String(url).endsWith('/auth/tokens')) return json({ result_type: 'TOKEN_ISSUED', access_token: 'fixture', refresh_token: 'fixture', expires_in_sec: 7200, user_id: 42 })
      if (options.method === 'POST' || options.method === 'PUT') {
        const body = JSON.parse(String(options.body)); writes.push(body)
        saved = { ...body, rule_id: 17 }; return json(saved)
      }
      return json(saved ? [saved] : [])
    } })
  await client.connect({ origin: 'https://site.shizuki.online', email: 'fixture@example.invalid', password: 'fixture-only' })
  return { client, writes }
}

describe('Todo query and delivery boundary', () => {
  it('accepts a due snapshot without dispatch or a delivery receipt; explicit scan emits once', async () => {
    const filePath = join(root(), 'seen.json'); const delivered: unknown[] = []
    const runtime = createShizukiReminderRuntime({ filePath, clock: () => now,
      client: { status: () => ({ connected: true, userId: 42 }), listTodos: async () => [] },
      onReminder: (event: unknown) => delivered.push(event) })
    try {
      runtime.start(); await flush(); runtime.useSnapshot([due])
      expect(delivered).toHaveLength(0); expect(existsSync(filePath)).toBe(false)
      runtime.scan(); runtime.scan(); expect(delivered).toHaveLength(1)
    } finally { runtime.stop() }
  })
  it('does not overwrite a newer accepted snapshot with a delayed background read', async () => {
    let release!: (items: unknown[]) => void
    const delivered: unknown[] = []
    const runtime = createShizukiReminderRuntime({ filePath: join(root(), 'seen.json'), clock: () => now,
      client: { status: () => ({ connected: true, userId: 42 }), listTodos: () => new Promise(resolve => { release = resolve }) },
      onReminder: (event: unknown) => delivered.push(event) })
    try {
      runtime.start(); runtime.useSnapshot([{ ...due, done: true }]); release([due]); await flush()
      runtime.scan(); expect(delivered).toHaveLength(0)
    } finally { runtime.stop() }
  })
  it('suppresses an existing occurrence attached to a paused rule', () => {
    const occurrence = { ...due, recurringRuleId: 5, recurringInstanceAt: due.dueAt, timeZoneId: 'Asia/Shanghai', recurringRule: { ruleId: 5, enabled: false } }
    expect(dueTodoReminders([occurrence], 42, now)).toEqual([])
    expect(dueTodoReminders([{ ...occurrence, recurringRule: { ruleId: 5, enabled: true } }], 42, now)).toHaveLength(1)
  })
})

describe('Explicit recurring rule date bounds', () => {
  it('submits and verifies supplied minute/second boundaries and preserves them on an unrelated edit', async () => {
    const { client, writes } = await fixture()
    const saved = await client.saveRecurringRule({ ...input, startAt: '2030-01-03T17:15', endAt: '2030-01-04T19:30:45' })
    expect(saved).toMatchObject({ startAt: '2030-01-03T17:15:00', endAt: '2030-01-04T19:30:45' })
    await client.saveRecurringRule({ ...input, ruleId: 17, title: 'edited synthetic title' })
    expect(writes[1]).toMatchObject({ start_at: '2030-01-03T17:15:00', end_at: '2030-01-04T19:30:45' })
  })
  it('allows an explicit empty end to remove the bound without changing the start', async () => {
    const { client } = await fixture()
    await client.saveRecurringRule({ ...input, startAt: '2030-01-03T00:00', endAt: '2030-01-04T00:00' })
    expect(await client.saveRecurringRule({ ...input, ruleId: 17, endAt: '' })).toMatchObject({ startAt: '2030-01-03T00:00:00', endAt: '' })
  })
  it.each([
    { startAt: '2030-02-30T10:00' },
    { endAt: 'not-a-date' },
    { startAt: '2030-01-04T19:00', endAt: '2030-01-04T18:00' },
    { endAt: '2029-12-31T23:59' },
    { timeZoneId: 'America/New_York', startAt: '2030-03-10T02:30' },
  ])('rejects invalid/inverted/nonexistent bounds before writing: %o', async patch => {
    const { client, writes } = await fixture()
    await expect(client.saveRecurringRule({ ...input, ...patch })).rejects.toMatchObject({ code: 'invalid_schedule' })
    expect(writes).toHaveLength(0)
  })
})
