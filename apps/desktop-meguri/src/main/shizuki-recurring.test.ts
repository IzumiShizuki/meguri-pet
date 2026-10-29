import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { createShizukiTimePrismClient, normalizeRecurringRule, normalizeShizukiTodo } from './shizuki-timeprism.mjs'
import { recurringTime, weeklyCron, weeklyPreset } from '../shared/recurring-todos.mjs'
import { dueTodoReminders } from './shizuki-reminders.mjs'
import { projectRecurringCalendar, todoCalendarEntries } from '../renderer/utils/todo-calendar'
const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })
const token = { result_type: 'TOKEN_ISSUED', access_token: 'fixture-access', refresh_token: 'fixture-refresh', expires_in_sec: 7200, user_id: 42 }
const encryption = { isEncryptionAvailable: () => true, encryptString: (v: string) => Buffer.from(v), decryptString: (v: Buffer) => v.toString() }
const json = (data: unknown, status = 200) => new Response(JSON.stringify({ data }), { status, headers: { 'content-type': 'application/json' } })
const rule = { rule_id: 7, title: '虚构规则', cron_expr: '0 0 18 * * MON-FRI', time_zone_id: 'Asia/Tokyo', reminder_enabled: true, deadline_remind_value: 10, deadline_remind_unit: 'MINUTE', enabled: true }
const instance = (id: number, date: string, done = false) => ({ todo_id: id, title: '虚构日期实例', done, due_at: `${date}T18:00:00`, recurring_rule_id: 7, recurring_instance_at: `${date}T18:00:00`, reminder_enabled: true, deadline_remind_value: 10, deadline_remind_unit: 'MINUTE', time_precision: 'MINUTE', timing_mode: 'DEADLINE' })
const input = { title: '虚构规则', weekdays: [1, 2, 3, 4, 5], time: '18:00', timeZoneId: 'Asia/Shanghai', enabled: true, reminderEnabled: true, deadlineRemindValue: 10, deadlineRemindUnit: 'MINUTE', expectedAccount: 'https://site.shizuki.online:42', requestId: '00000000-0000-4000-8000-000000000001' }
const fixtureNow = Date.parse('2026-10-29T23:30:00Z')
// Mirrors LightAppServiceImpl server normalization rather than echoing the POST.
function serverRule(body: any, id = 8) {
  return { ...body, rule_id: id, start_at: body.start_at || '2026-10-30T00:00:00',
    deadline_remind_value: body.reminder_enabled ? (body.deadline_remind_value ?? 0) : null,
    deadline_remind_unit: body.reminder_enabled ? (body.deadline_remind_unit ?? 'MINUTE') : null }
}
async function clientWith(handler: (url: string, options: RequestInit) => Promise<Response>, clock = () => fixtureNow) {
  const root = mkdtempSync(join(tmpdir(), 'meguri-recurring-')); roots.push(root)
  const client = createShizukiTimePrismClient({ filePath: join(root, 'session.json'), safeStorage: encryption, clock,
    fetchImpl: async (url: string | URL | Request, options: RequestInit = {}) => String(url).endsWith('/auth/tokens') ? json(token) : handler(String(url), options) })
  await client.connect({ origin: 'https://site.shizuki.online', email: 'fixture@example.invalid', password: 'fixture-only' })
  return client
}
describe('current site recurring Todo contract', () => {
  it('round trips weekday lists and named ranges without turning advanced Cron into a different schedule', () => {
    expect(weeklyPreset('0 0 18 * * MON-FRI')).toEqual({ weekdays: [1, 2, 3, 4, 5], time: '18:00' })
    expect(weeklyPreset('0 15 8 * * 1,3,5')).toEqual({ weekdays: [1, 3, 5], time: '08:15' })
    expect(weeklyCron([5, 1, 3, 1], '08:15')).toBe('0 15 8 * * 1,3,5')
    expect(() => weeklyCron([], '08:15')).toThrow()
    expect(weeklyPreset('0 */15 * * * *')).toBeNull()
  })
  it('uses rule timezone, rejects nonexistent dates/DST gaps, and chooses first repeated DST wall time', () => {
    expect(recurringTime('2026-10-29T18:00:00', 'Asia/Tokyo')).toBe(Date.parse('2026-10-29T09:00:00Z'))
    expect(recurringTime('2026-03-08T02:30:00', 'America/New_York')).toBeNaN()
    expect(recurringTime('2026-11-01T01:30:00', 'America/New_York')).toBe(Date.parse('2026-11-01T05:30:00Z'))
    expect(recurringTime('2026-02-30T18:00:00')).toBeNaN()
    expect(recurringTime('2026-10-29T18:00:00', 'invalid')).toBeNaN()
  })
  it('loads rule metadata and completes only the selected dated instance', async () => {
    const todos = [instance(101, '2026-10-28'), instance(102, '2026-10-29')]
    const writes: string[] = []
    const client = await clientWith(async (url, options) => {
      if (url.endsWith('/todo-recurring-rules')) return json([rule])
      if (url.endsWith('/todos')) return json(todos)
      if (url.endsWith('/todos/101') && options.method === 'PUT') { writes.push(url); todos[0].done = true; return json(todos[0]) }
      throw new Error('unexpected fixture request')
    })
    expect((await client.listTodos())[0]).toMatchObject({ recurringRuleId: 7, recurringInstanceAt: '2026-10-28T18:00:00', timeZoneId: 'Asia/Tokyo' })
    const done = await client.completeTodo(101, input.expectedAccount)
    expect(done.done).toBe(true); expect(writes).toHaveLength(1)
    expect((await client.listTodos())[1].done).toBe(false)
    await expect(client.updateTodo(102, { title: '变更', dueAt: '2030-01-01T12:00', expectedAccount: input.expectedAccount })).rejects.toMatchObject({ code: 'recurring_rule_required' })
  })
  it('fails closed if recurring timezone cannot be fetched', async () => {
    const client = await clientWith(async url => url.endsWith('/todos') ? json([instance(101, '2026-10-29')]) : json({}, 503))
    await expect(client.listTodos()).rejects.toThrow()
    const missingZone = normalizeShizukiTodo(instance(101, '2026-10-29'))
    expect(dueTodoReminders([missingZone], 42, Date.parse('2026-10-29T09:00:00Z'))).toEqual([])
  })
  it('writes one weekday rule with advance fields and verifies authoritative GET readback', async () => {
    const rules: unknown[] = []; const calls: string[] = []
    const client = await clientWith(async (url, options) => {
      calls.push(`${options.method} ${url}`)
      if (options.method === 'POST') { const saved = serverRule(JSON.parse(String(options.body))); rules.push(saved); return json(saved) }
      return json(rules)
    })
    const saved = await client.saveRecurringRule(input)
    expect(saved).toMatchObject({ ruleId: 8, cronExpr: '0 0 18 * * 1,2,3,4,5', deadlineRemindValue: 10, startAt: '2026-10-30T00:00:00' })
    expect(calls).toHaveLength(2); expect(calls[1]).toContain('GET')
    await client.saveRecurringRule(input)
    expect(calls.filter(call => call.startsWith('POST'))).toHaveLength(1)
  })
  it('recovers an uncertain committed create with a different UI request id without another POST', async () => {
    const rules: unknown[] = []; let posts = 0; let now = fixtureNow
    const client = await clientWith(async (_url, options) => {
      if (options.method === 'POST') { posts++; rules.push(serverRule(JSON.parse(String(options.body)))); throw new Error('network disconnected after commit') }
      return json(rules)
    }, () => now)
    await expect(client.saveRecurringRule(input)).rejects.toThrow()
    now += 86400000
    expect(await client.saveRecurringRule({ ...input, requestId: '00000000-0000-4000-8000-000000000002' })).toMatchObject({ ruleId: 8, startAt: '2026-10-30T00:00:00' })
    expect(posts).toBe(1)
  })
  it('does not claim success when readback changes reminder settings and never retries POST', async () => {
    let saved: any; let posts = 0
    const client = await clientWith(async (_url, options) => {
      if (options.method === 'POST') { posts++; saved = serverRule(JSON.parse(String(options.body))); return json(saved) }
      return json([{ ...saved, deadline_remind_value: 0 }])
    })
    await expect(client.saveRecurringRule(input)).rejects.toMatchObject({ code: 'unconfirmed_rule' })
    await expect(client.saveRecurringRule(input)).rejects.toMatchObject({ code: 'unconfirmed_rule' })
    expect(posts).toBe(1)
  })
  it('rejects changed account, empty weekday selections and invalid offsets before writing', async () => {
    let calls = 0
    const client = await clientWith(async () => { calls++; return json([]) })
    for (const patch of [{ expectedAccount: 'other' }, { weekdays: [] }, { deadlineRemindValue: -1 }])
      await expect(client.saveRecurringRule({ ...input, ...patch })).rejects.toThrow()
    expect(calls).toBe(0)
  })
  it('keeps advanced Cron, date bounds, project and detail on an acknowledged edit', async () => {
    let existing: any = { ...rule, cron_expr: '0 */15 * * * *', detail: '虚构备注', project_id: 3, start_at: '2026-01-01T00:00:00', end_at: '2027-01-01T00:00:00' }
    const client = await clientWith(async (_url, options) => {
      if (options.method === 'PUT') { existing = serverRule(JSON.parse(String(options.body)), 7); return json(existing) }
      return json([existing])
    })
    const saved = await client.saveRecurringRule({ ...input, ruleId: 7, weekdays: undefined, cronExpr: existing.cron_expr })
    expect(saved).toMatchObject({ cronExpr: '0 */15 * * * *', detail: '虚构备注', projectId: 3, startAt: '2026-01-01T00:00:00', endAt: '2027-01-01T00:00:00' })
  })
  it('projects only selected weekdays without duplicating completed instances', () => {
    const normalized = normalizeRecurringRule(rule)
    const done = { ...normalizeShizukiTodo(instance(101, '2026-10-28', true)), timeZoneId: 'Asia/Tokyo' }
    const dates = ['2026-10-26', '2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30', '2026-10-31', '2026-11-01']
    const projected = projectRecurringCalendar([done], [normalized], dates)
    expect(projected.map(todo => todo.dueAt.slice(0, 10))).toEqual(['2026-10-26', '2026-10-27', '2026-10-29', '2026-10-30'])
    expect(projected.every(todo => todo.forecast && !todo.reminderEnabled)).toBe(true)
    expect(todoCalendarEntries([done])[0].end).toBe('2026-10-28T17:00')
  })
  it('matches server full LocalDateTime bounds, including same-day times and equality', () => {
    const bounded = normalizeRecurringRule({ ...rule, cron_expr: '0 0 18 * * *', start_at: '2026-10-29T20:00:00', end_at: '2026-11-01T12:00:00' })
    const dates = ['2026-10-29', '2026-10-30', '2026-10-31', '2026-11-01']
    expect(projectRecurringCalendar([], [bounded], dates).map(todo => todo.dueAt)).toEqual(['2026-10-30T18:00:00', '2026-10-31T18:00:00'])
    expect(projectRecurringCalendar([], [{ ...bounded, startAt: '2026-10-29T18:00:00', endAt: '2026-11-01T18:00:00' }], dates)).toHaveLength(4)
  })
  it.each([false, true])('accepts server-cleared reminder fields for %s edit and preserves explicit date bounds', async (edit) => {
    let saved: any = serverRule({ ...rule, start_at: '2026-09-01T12:00:00' }, 7)
    const payloads: any[] = []
    const client = await clientWith(async (_url, options) => {
      if (options.method === 'POST' || options.method === 'PUT') {
        const body = JSON.parse(String(options.body)); payloads.push(body); saved = serverRule(body, edit ? 7 : 8); return json(saved)
      }
      return json(edit || payloads.length ? [saved] : [])
    })
    const result = await client.saveRecurringRule({ ...input, ...(edit ? { ruleId: 7 } : {}), reminderEnabled: false })
    expect(result).toMatchObject({ reminderEnabled: false, deadlineRemindValue: null, deadlineRemindUnit: null,
      startAt: edit ? '2026-09-01T12:00:00' : '2026-10-30T00:00:00' })
    expect(payloads[0]).toMatchObject({ deadline_remind_value: null, deadline_remind_unit: null })
  })
  it.each([{ start_at: '2026-11-15T00:00:00' }, { deadline_remind_value: 10, deadline_remind_unit: 'MINUTE' }])('rejects unexpected normalized readback %o', async (mismatch) => {
    let saved: any
    const client = await clientWith(async (_url, options) => {
      if (options.method === 'POST') { saved = serverRule(JSON.parse(String(options.body))); return json(saved) }
      return json([{ ...saved, ...mismatch }])
    })
    await expect(client.saveRecurringRule({ ...input, reminderEnabled: false })).rejects.toMatchObject({ code: 'unconfirmed_rule' })
  })
  it.each([400, 403, 422])('classifies certain %i rejection so a corrected user request can be submitted', async (status) => {
    let saved: any; let writes = 0
    const client = await clientWith(async (_url, options) => {
      if (options.method === 'POST') {
        if (++writes === 1) return json({}, status)
        saved = serverRule(JSON.parse(String(options.body))); return json(saved)
      }
      return json(saved ? [saved] : [])
    })
    await expect(client.saveRecurringRule(input)).rejects.toMatchObject({ code: 'request_rejected', status })
    const corrected = { ...input, title: '修正后的虚构规则', requestId: '00000000-0000-4000-8000-000000000002' }
    expect((await client.saveRecurringRule(corrected)).title).toBe(corrected.title)
    expect(writes).toBe(2)
  })
  it('deduplicates equivalent occurrence identities and suppresses completed dates', () => {
    const at = Date.parse('2026-10-29T08:50:00Z')
    const pending = { ...normalizeShizukiTodo(instance(102, '2026-10-29')), timeZoneId: 'Asia/Tokyo' }
    const done = { ...pending, todoId: 101, done: true, dueAt: '2026-10-28T18:00:00', recurringInstanceAt: '2026-10-28T18:00:00' }
    const events = dueTodoReminders([pending, done], 42, at)
    expect(events).toHaveLength(1); expect(events[0].triggerAt).toBe(at)
    expect(dueTodoReminders([{ ...pending, todoId: 999 }], 42, at)[0].key).toBe(events[0].key)
  })
})
