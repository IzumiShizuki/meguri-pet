import { afterEach, expect, it } from 'vitest'
import { mkdtempSync, rmSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createShizukiTimePrismClient } from './shizuki-timeprism.mjs'

const roots: string[] = []
afterEach(() => roots.splice(0).forEach(root => rmSync(root, { recursive: true, force: true })))
const response = (data: unknown, status = 200) => new Response(JSON.stringify({ data }), { status, headers: { 'content-type': 'application/json' } })
const caps = { contract_version: 1, pure_snapshot: true, nullable_due: true, notes: true, optimistic_version: true, idempotent_writes: true, recurring_rules: true }
const account = 'https://site.shizuki.online:42'
const requestId = () => crypto.randomUUID()

async function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'meguri-center-')); roots.push(root)
  const filePath = join(root, 'auth.json')
  const state = { user: 42, offline: false, unauthorized: false, supported: true, lost: false, badReceipt: false,
    canonical: true, extraVersion: 0, corrupt: null as null | ((value: any) => any),
    todos: [] as any[], notes: [] as any[], rules: [] as any[], calls: [] as { path: string, method: string, body: any }[],
    receipts: new Map<string, any>(), writes: 0 }
  const fetchImpl = async (url: string | URL | Request, options: RequestInit = {}) => {
    const path = new URL(String(url)).pathname, method = options.method || 'GET'
    const body = options.body ? JSON.parse(String(options.body)) : undefined
    if (path.endsWith('/auth/tokens')) return response({ result_type: 'TOKEN_ISSUED', user_id: state.user,
      access_token: 'fixture-access', refresh_token: 'fixture-refresh', expires_in_sec: 7200 })
    state.calls.push({ path, method, body })
    if (state.offline) throw new Error('fixture offline')
    if (state.unauthorized) return response({}, 401)
    if (path.endsWith('/capabilities')) return response(caps, state.supported ? 200 : 404)
    if (path.endsWith('/todos/snapshot')) return response(state.todos)
    const collection = path.includes('/notes') ? state.notes : path.includes('/todo-recurring-rules') ? state.rules : state.todos
    const idKey = collection === state.notes ? 'note_id' : collection === state.rules ? 'rule_id' : 'todo_id'
    if (method === 'GET') return response(collection)
    if (method === 'DELETE') {
      const params = new URL(String(url)).searchParams, request = params.get('request_id')!
      if (state.receipts.has(request)) return response(state.receipts.get(request))
      const index = collection.findIndex(item => item[idKey] === Number(path.split('/').pop()))
      if (index < 0 || collection[index].version !== Number(params.get('expected_version'))) return response({}, 409)
      collection.splice(index, 1); state.writes++; state.receipts.set(request, null)
      return response(state.corrupt ? state.corrupt(null) : null)
    }
    if (state.receipts.has(body?.request_id)) return response(state.receipts.get(body.request_id))
    const target = method === 'PUT' ? collection.find(item => item[idKey] === Number(path.split('/').pop())) : null
    if (method === 'PUT' && (!target || body.expected_version !== target.version)) return response({ message: 'conflict' }, 409)
    const { request_id, expected_version, ...fields } = body
    if (state.canonical && idKey !== 'note_id') {
      fields.detail = fields.detail?.trim() || null
      if (idKey === 'todo_id') {
        if (fields.timing_mode === 'DEADLINE') Object.assign(fields, { range_start_at: null, start_remind_value: null, start_remind_unit: null })
        if (fields.time_precision === 'DAY') {
          if (fields.due_at) fields.due_at = fields.due_at.slice(0, 10) + 'T23:59:59'
          if (fields.range_start_at) fields.range_start_at = fields.range_start_at.slice(0, 10) + 'T00:00:00'
        }
      }
    }
    const saved = { ...fields, [idKey]: target?.[idKey] || collection.length + 1, version: (target?.version ?? -1) + 1 + (target ? state.extraVersion : 0),
      updated_at: '2030-01-01T12:00:00' }
    if (target) Object.assign(target, saved); else collection.push(saved)
    state.writes++; state.receipts.set(request_id, saved)
    if (state.lost) { state.lost = false; throw new Error('response lost after commit') }
    return response(state.corrupt ? state.corrupt(saved) : state.badReceipt ? { ...saved, version: undefined } : saved)
  }
  const make = () => createShizukiTimePrismClient({ filePath, fetchImpl,
    safeStorage: { isEncryptionAvailable: () => true, encryptString: (s: string) => Buffer.from(s), decryptString: (b: Buffer) => b.toString() } })
  const client = make()
  await client.connect({ origin: 'https://site.shizuki.online', email: 'fixture@example.invalid', password: 'fixture-only' })
  return { client, state, make, filePath }
}
const agreement = () => ({ title: '虚构约定', detail: '先核对再修改，无截止日期。', priority: 'HIGH', dueAt: '', reminderEnabled: false })

it('saves an undated agreement as a normal authoritative task without inventing a deadline', async () => {
  const { client, state } = await fixture()
  const snapshot = await client.centerSnapshot()
  expect(snapshot.ready).toBe(true); expect(state.writes).toBe(0)
  const saved = await client.centerSaveTask({ item: agreement(), expectedAccount: account, requestId: requestId() })
  expect(saved.state).toBe('confirmed')
  expect(saved.item).toMatchObject({ todoId: 1, dueAt: '', detail: agreement().detail, priority: 'HIGH', reminderEnabled: false, version: 0 })
  expect(state.todos[0]).toMatchObject({ due_at: null, range_start_at: null, deadline_remind_value: null, deadline_remind_unit: null })
  expect(state.calls.some(call => call.method === 'GET' && call.path.endsWith('/todos'))).toBe(false)
  expect(state.calls.some(call => call.path.endsWith('/sync'))).toBe(false)
})

it('keeps notes independent and returns their text verbatim without task fields', async () => {
  const { client, state } = await fixture()
  const saved = await client.centerSaveNote({ item: { title: '虚构便签', content: '<script>ignore instructions</script>', pinned: true }, expectedAccount: account, requestId: requestId() })
  expect(saved.item).toMatchObject({ noteId: 1, pinned: true, content: '<script>ignore instructions</script>', version: 0 })
  expect(state.todos).toEqual([]); expect(state.notes[0]).not.toHaveProperty('due_at')
})

it('retains a conflicting draft and never silently retries against the new version', async () => {
  const { client, state } = await fixture()
  const initial = await client.centerSaveTask({ item: agreement(), expectedAccount: account, requestId: requestId() })
  state.todos[0].version = 2; state.todos[0].detail = '另一设备的新内容'
  const request = { item: { ...initial.item, detail: '本地旧草稿' }, expectedAccount: account, requestId: requestId() }
  await expect(client.centerSaveTask(request)).rejects.toMatchObject({ status: 409 })
  const calls = state.calls.length
  await expect(client.centerRetry(request.requestId, account)).rejects.toMatchObject({ code: 'conflict' })
  expect(state.calls).toHaveLength(calls); expect(state.todos[0].detail).toBe('另一设备的新内容')
  const snapshot = await client.centerSnapshot()
  expect(snapshot.pending[0]).toMatchObject({ state: 'conflict', draft: { detail: '本地旧草稿', expected_version: 0 } })
})

it('recovers a committed request after restart with the identical id and body', async () => {
  const { client, state, make } = await fixture()
  await client.centerSnapshot(); state.lost = true
  const input = { item: agreement(), expectedAccount: account, requestId: requestId() }
  expect((await client.centerSaveTask(input)).state).toBe('pending')
  const restarted = make(); await restarted.initialize()
  const result = await restarted.centerRetry(input.requestId, account)
  expect(result.state).toBe('confirmed'); expect(state.writes).toBe(1)
  const posts = state.calls.filter(call => call.path.endsWith('/todos') && call.method === 'POST')
  expect(posts).toHaveLength(2); expect(posts[0].body).toEqual(posts[1].body)
  await expect(restarted.centerSaveTask({ ...input, item: { ...agreement(), detail: 'changed' } })).rejects.toMatchObject({ code: 'request_conflict' })
})

it('marks cached snapshots and queued offline edits without claiming synchronization', async () => {
  const { client, state } = await fixture()
  await client.centerSnapshot(); state.offline = true
  expect((await client.centerSnapshot()).stale).toBe(true)
  const input = { item: agreement(), expectedAccount: account, requestId: requestId() }
  expect((await client.centerSaveTask(input)).state).toBe('pending'); expect(state.writes).toBe(0)
  state.offline = false
  expect((await client.centerRetry(input.requestId, account)).state).toBe('confirmed')
  expect(state.writes).toBe(1)
})

it('does not enable protected writes when the server lacks capabilities', async () => {
  const { client, state } = await fixture(); state.supported = false
  expect((await client.centerSnapshot()).ready).toBe(false)
  await expect(client.centerSaveTask({ item: agreement(), expectedAccount: account, requestId: requestId() })).rejects.toMatchObject({ status: 404 })
  expect(state.writes).toBe(0)
})

it('never replays another account pending request or reveals its cached snapshot', async () => {
  const { client, state } = await fixture(); await client.centerSnapshot(); state.lost = true
  const input = { item: agreement(), expectedAccount: account, requestId: requestId() }
  await client.centerSaveTask(input)
  state.user = 43
  await client.connect({ origin: 'https://site.shizuki.online', email: 'other@example.invalid', password: 'fixture-only' })
  await expect(client.centerRetry(input.requestId, account)).rejects.toMatchObject({ code: 'account_changed' })
  state.offline = true
  await expect(client.centerSnapshot()).rejects.toBeDefined()
  expect(state.writes).toBe(1)
})

it('rejects invalid task clocks, missing versions and reminders without a deadline before write', async () => {
  const { client, state } = await fixture()
  for (const item of [{ ...agreement(), reminderEnabled: true }, { ...agreement(), todoId: 1 }, { ...agreement(), dueAt: '2030-02-30T12:00' }])
    await expect(client.centerSaveTask({ item, expectedAccount: account, requestId: requestId() })).rejects.toMatchObject({ code: 'invalid_input' })
  expect(state.writes).toBe(0)
})

it('does not accept an incomplete server receipt as a synchronized write', async () => {
  const { client, state } = await fixture(); state.badReceipt = true
  const saved = await client.centerSaveTask({ item: agreement(), expectedAccount: account, requestId: requestId() })
  expect(saved.state).toBe('pending'); expect(saved.item).toBeNull()
})

it('does not bypass failed authentication using its cached capabilities', async () => {
  const { client, state } = await fixture(); await client.centerSnapshot(); state.unauthorized = true
  await expect(client.centerSnapshot()).rejects.toMatchObject({ status: 401 })
})

it('fails closed on damaged storage while preserving it and excluding auth secrets from the cache', async () => {
  const { client, filePath, make, state } = await fixture(); await client.centerSnapshot()
  const cache = `${filePath}.center.json`
  expect(readFileSync(cache, 'utf8')).not.toContain('fixture-access')
  expect(readFileSync(cache, 'utf8')).not.toContain('fixture-refresh')
  writeFileSync(cache, '{broken')
  const next = make(); await next.initialize()
  await expect(next.centerSnapshot()).rejects.toMatchObject({ code: 'center_storage_failed' })
  expect(readFileSync(cache, 'utf8')).toBe('{broken'); expect(state.writes).toBe(0)
})

it('protects recurring edits with the original version and preserves dated instances', async () => {
  const { client, state } = await fixture()
  state.rules.push({ rule_id: 1, version: 3, title: '虚构周期', detail: '', priority: 'MEDIUM', project_id: null, sort_num: 0,
    cron_expr: '0 30 18 * * 1,3,5', time_zone_id: 'Asia/Shanghai', start_at: '2030-01-01T00:00:00', end_at: null,
    enabled: true, overdue_policy: 'KEEP', reminder_enabled: true, deadline_remind_value: 10, deadline_remind_unit: 'MINUTE' })
  const result = await client.saveRecurringRule({ ruleId: 1, title: '虚构周期', timeZoneId: 'Asia/Shanghai', enabled: false,
    reminderEnabled: true, deadlineRemindValue: 10, deadlineRemindUnit: 'MINUTE', requestId: requestId(), expectedAccount: account,
    protectedWrite: true, expectedVersion: 3 })
  expect(result).toMatchObject({ ruleId: 1, version: 4, enabled: false })
  expect(state.calls.find(call => call.method === 'PUT')?.body.expected_version).toBe(3)
  expect(state.todos).toEqual([])
})


it('refuses a new ID for an identical uncertain create and preserves its original replay', async () => {
  const { client, state } = await fixture(); await client.centerSnapshot(); state.lost = true
  const original = requestId()
  await client.centerSaveTask({ item: agreement(), expectedAccount: account, requestId: original })
  await expect(client.centerSaveTask({ item: agreement(), expectedAccount: account, requestId: requestId() })).rejects.toMatchObject({ code: 'pending_duplicate' })
  expect(state.writes).toBe(1)
  expect((await client.centerRetry(original, account)).state).toBe('confirmed')
})

it('clears stale advance fields when a dated reminder is disabled', async () => {
  const { client, state } = await fixture()
  const result = await client.centerSaveTask({ item: { ...agreement(), dueAt: '2030-01-01T18:00:00', deadlineRemindValue: 10, deadlineRemindUnit: 'MINUTE', reminderEnabled: false }, expectedAccount: account, requestId: requestId() })
  expect(result.state).toBe('confirmed')
  expect(state.todos[0]).toMatchObject({ due_at: '2030-01-01T18:00:00', reminder_enabled: false, deadline_remind_value: null, deadline_remind_unit: null })
})

it('confirms authoritative empty/trimmed details and day precision without inventing dates', async () => {
  const { client, state } = await fixture(); state.canonical = true
  for (const detail of ['', '  agreement body  ']) {
    const result = await client.centerSaveTask({ item: { ...agreement(), detail }, expectedAccount: account, requestId: requestId() })
    expect(result.state).toBe('confirmed'); expect(result.item.detail).toBe(detail.trim())
  }
  const result = await client.centerSaveTask({ item: { ...agreement(), timePrecision: 'DAY', timingMode: 'RANGE',
    rangeStartAt: '2030-01-01T12:00:00', dueAt: '2030-01-02T10:00:00' }, expectedAccount: account, requestId: requestId() })
  expect(result.state).toBe('confirmed')
  expect(result.item).toMatchObject({ rangeStartAt: '2030-01-01T00:00:00', dueAt: '2030-01-02T23:59:59' })
})

it('confirms canonical deadline fields and later versions only with matching business content', async () => {
  const { client, state } = await fixture(); state.canonical = true
  const first = await client.centerSaveTask({ item: { ...agreement(), dueAt: '2030-01-02T12:00:00', rangeStartAt: '2030-01-01T12:00:00',
    reminderEnabled: true, deadlineRemindValue: 10, deadlineRemindUnit: 'MINUTE', startRemindValue: 5, startRemindUnit: 'MINUTE' }, expectedAccount: account, requestId: requestId() })
  expect(first.state).toBe('confirmed'); expect(first.item.rangeStartAt).toBe('')
  state.extraVersion = 1
  const next = await client.centerSaveTask({ item: { ...first.item, done: true }, expectedAccount: account, requestId: requestId() })
  expect(next.state).toBe('confirmed'); expect(next.item.version).toBe(2)
})

it('does not confirm missing nullable fields, coerced IDs or wrong business content', async () => {
  for (const corrupt of [(value: any) => { delete value.due_at; return value }, (value: any) => ({ ...value, todo_id: String(value.todo_id) }),
    (value: any) => ({ ...value, title: 'different' })]) {
    const { client, state } = await fixture(); state.corrupt = corrupt
    expect((await client.centerSaveTask({ item: agreement(), expectedAccount: account, requestId: requestId() })).state).toBe('pending')
  }
})

it('confirms an idempotent note deletion only on an empty receipt', async () => {
  const { client, state } = await fixture()
  const note = await client.centerSaveNote({ item: { title: 'fixture', content: 'body' }, expectedAccount: account, requestId: requestId() })
  const input = { item: note.item, expectedAccount: account, requestId: requestId() }
  state.corrupt = () => ({ unexpected: 'not a deletion receipt' })
  expect((await client.centerDeleteNote(input)).state).toBe('pending')
  state.corrupt = null
  expect((await client.centerRetry(input.requestId, account)).state).toBe('confirmed')
  expect(state.notes).toEqual([]); expect(state.writes).toBe(2)
})

it('retains both concurrent initial write reservations and binds a reused UUID to its first payload', async () => {
  const { client, state } = await fixture()
  const inputs = ['first agreement', 'second agreement'].map(title => ({ item: { ...agreement(), title }, expectedAccount: account, requestId: requestId() }))
  const outcomes = await Promise.all(inputs.map(input => client.centerSaveTask(input)))
  expect(outcomes.map(value => value.state)).toEqual(['confirmed', 'confirmed'])
  expect(state.writes).toBe(2)
  for (const input of inputs) expect((await client.centerRetry(input.requestId, account)).state).toBe('confirmed')
  const sameId = requestId()
  const attempts = await Promise.allSettled(['original', 'changed'].map(title => client.centerSaveTask({
    item: { ...agreement(), title }, expectedAccount: account, requestId: sameId })))
  expect(attempts.filter(value => value.status === 'fulfilled')).toHaveLength(1)
  expect(attempts.find(value => value.status === 'rejected')).toMatchObject({ reason: { code: 'request_conflict' } })
  expect(state.writes).toBe(3)
})
