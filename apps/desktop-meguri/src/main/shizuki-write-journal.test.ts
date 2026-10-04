import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve, sep } from 'node:path'
import { createServer } from 'node:http'
import { afterEach, describe, expect, it } from 'vitest'
import { createShizukiTimePrismClient } from './shizuki-timeprism.mjs'

const roots: string[] = []
afterEach(() => {
  for (const root of roots.splice(0)) {
    if (!resolve(root).startsWith(`${resolve(tmpdir())}${sep}meguri-journal-`)) throw new Error('Unsafe test cleanup')
    rmSync(root, { recursive: true, force: true })
  }
})
const now = Date.parse('2026-10-04T12:00:00+08:00')
const dueAt = '2026-10-05T09:00'
const requestId = '13b31a7b-a2e5-4b58-8851-ad4d1c8bc288'
const input = { title: '开会', dueAt, requestId, expectedAccount: 'https://site.shizuki.online:42' }
const encryption = {
  isEncryptionAvailable: () => true,
  encryptString: (text: string) => Buffer.from(`encrypted:${text}`),
  decryptString: (bytes: Buffer) => bytes.toString().replace(/^encrypted:/, ''),
}
const token = (userId = 42) => ({ result_type: 'TOKEN_ISSUED', user_id: userId, access_token: 'test-access', refresh_token: 'test-refresh', expires_in_sec: 7200 })
const json = (data: unknown, status = 200) => new Response(JSON.stringify({ data }), { status })
type RecordShape = Record<string, unknown>

async function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'meguri-journal-'))
  roots.push(root)
  const filePath = join(root, 'session.json')
  const state = { records: [] as RecordShape[], posts: 0, puts: [] as RecordShape[], mode: 'ok', userId: 42, nextId: 17 }
  const fetchImpl = async (url: string | URL | Request, options: RequestInit = {}) => {
    const path = new URL(String(url)).pathname
    const body = options.body ? JSON.parse(String(options.body)) : {}
    if (path.endsWith('/auth/tokens')) return json(token(state.userId))
    if (path.endsWith('/todos') && options.method === 'POST') {
      state.posts += 1
      if (state.mode === 'reject') return json({}, 400)
      if (state.mode === '500') return json({}, 500)
      const record = { ...body, todo_id: state.nextId++, project_id: 4, sort_num: 9 }
      state.records.push(record)
      if (state.mode === 'lost') throw new Error('network response lost after commit')
      if (state.mode === 'invalid') return new Response('not json')
      if (state.mode === 'empty') return json({})
      if (state.mode === 'drop_fields') {
        delete record.time_precision
        delete record.deadline_remind_value
      }
      return json(record)
    }
    if (path.endsWith('/todos') && options.method === 'GET') {
      if (state.mode === 'list_offline') throw new Error('offline')
      return json(state.records)
    }
    if (options.method === 'PUT') {
      state.puts.push(body)
      const id = Number(path.split('/').at(-1))
      const record = { ...body, todo_id: id }
      state.records = state.records.map(todo => todo.todo_id === id ? record : todo)
      if (state.mode === 'put_lost') throw new Error('PUT response lost')
      return json(record)
    }
    throw new Error(`unexpected test route ${path}`)
  }
  const make = () => createShizukiTimePrismClient({ filePath, safeStorage: encryption, fetchImpl, clock: () => now })
  const client = make()
  await client.connect({ origin: 'https://site.shizuki.online', email: 'test@example.com', password: 'test-password' })
  return { client, make, state, filePath }
}

describe('TimePrism durable creation and lifecycle', () => {
  it('serializes duplicate confirmations and verifies an exact reminder response', async () => {
    const { client, state, filePath } = await fixture()
    const [first, second] = await Promise.all([client.createTodo(input), client.createTodo(input)])
    expect(first.todoId).toBe(second.todoId)
    expect(state.posts).toBe(1)
    expect(state.records[0]).toMatchObject({ due_at: `${dueAt}:00`, title: '开会', reminder_enabled: true,
      time_precision: 'MINUTE', timing_mode: 'DEADLINE', deadline_remind_value: 0, deadline_remind_unit: 'MINUTE', detail: `meguri-request:${requestId}` })
    expect(client.pendingCreates()).toEqual([])
    const journal = readFileSync(`${filePath}.requests.json`, 'utf8')
    expect(journal).not.toMatch(/test-access|test-refresh|test-password/)
  })

  it('recovers a committed POST with lost response after app restart without a second POST', async () => {
    const { client, make, state } = await fixture()
    state.mode = 'lost'
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'network_error' })
    expect(client.pendingCreates()).toEqual([{ requestId, title: '开会', dueAt, state: 'uncertain' }])
    const restarted = make()
    restarted.initialize()
    state.mode = 'ok'
    const recovered = await restarted.createTodo(input)
    expect(recovered.todoId).toBe(17)
    expect(state.posts).toBe(1)
    expect(restarted.pendingCreates()).toEqual([])
  })

  it('blocks the same uncertain reminder under a new request ID', async () => {
    const { client, state } = await fixture()
    state.mode = 'lost'
    await expect(client.createTodo(input)).rejects.toBeDefined()
    await expect(client.createTodo({ ...input, requestId: 'c6193024-80a5-4287-80f0-ce5da8500d29' }))
      .rejects.toMatchObject({ code: 'pending_duplicate' })
    expect(state.posts).toBe(1)
  })

  it('permits retry after definite rejection with the same request ID', async () => {
    const { client, state } = await fixture()
    state.mode = 'reject'
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'request_rejected', status: 400 })
    expect(client.pendingCreates()[0].state).toBe('rejected')
    state.mode = 'ok'
    expect((await client.createTodo(input)).todoId).toBe(17)
    expect(state.posts).toBe(2)
  })

  it.each(['500', 'invalid', 'empty'])('never repeats an uncertain %s write on retry', async mode => {
    const { client, state } = await fixture()
    state.mode = mode
    await expect(client.createTodo(input)).rejects.toBeDefined()
    state.records = [] // absence is not proof that an in-flight request cannot commit
    state.mode = 'ok'
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'write_uncertain' })
    expect(state.posts).toBe(1)
  })

  it('does not accept a server that strips minute reminder support', async () => {
    const { client, state } = await fixture()
    state.mode = 'drop_fields'
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'unconfirmed_reminder' })
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'unconfirmed_reminder' })
    expect(state.posts).toBe(1)
  })

  it('does not repeat POST if reconciliation is offline or a marker is lost/duplicated', async () => {
    const { client, state } = await fixture()
    state.mode = 'lost'
    await expect(client.createTodo(input)).rejects.toBeDefined()
    state.mode = 'list_offline'
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'network_error' })
    state.mode = 'ok'
    const original = { ...state.records[0] }
    state.records[0].detail = ''
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'write_uncertain' })
    state.records = [original, { ...original, todo_id: 18 }]
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'write_uncertain' })
    expect(state.posts).toBe(1)
  })

  it('pins requests to immutable content and account/origin', async () => {
    const { client, state } = await fixture()
    await client.createTodo(input)
    await expect(client.createTodo({ ...input, title: '吃药' })).rejects.toMatchObject({ code: 'request_conflict' })
    state.userId = 43
    await client.connect({ origin: 'https://site.shizuki.online', email: 'another@example.com', password: 'test' })
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'account_changed' })
    expect(client.pendingCreates()).toEqual([])
    await expect(client.createTodo({ ...input, expectedAccount: 'https://site.shizuki.online:43' })).rejects.toMatchObject({ code: 'request_conflict' })
    await expect(client.updateTodo(17, { title: '错误账号', dueAt, expectedAccount: input.expectedAccount })).rejects.toMatchObject({ code: 'account_changed' })
    await expect(client.completeTodo(17, input.expectedAccount)).rejects.toMatchObject({ code: 'account_changed' })
    expect(state.puts).toHaveLength(0)
    expect(state.posts).toBe(1)
  })

  it('refuses unsupported actions/timezones and invalid time before POST', async () => {
    const { client, state } = await fixture()
    await expect(client.createTodo({ ...input, behavior: 'shell' })).rejects.toMatchObject({ code: 'unsupported_action' })
    await expect(client.createTodo({ ...input, timezone: 'America/New_York' })).rejects.toMatchObject({ code: 'unsupported_timezone' })
    await expect(client.createTodo({ ...input, dueAt: '2026-02-30T12:00' })).rejects.toMatchObject({ code: 'invalid_due_at' })
    await expect(client.createTodo({ ...input, dueAt: '2026-10-04T11:00' })).rejects.toMatchObject({ code: 'invalid_due_at' })
    expect(state.posts).toBe(0)
  })

  it('fails closed on a damaged journal without deleting it', async () => {
    const { make, state, filePath } = await fixture()
    writeFileSync(`${filePath}.requests.json`, '{bad json')
    const restarted = make()
    restarted.initialize()
    await expect(restarted.createTodo(input)).rejects.toMatchObject({ code: 'journal_failed' })
    expect(state.posts).toBe(0)
    expect(readFileSync(`${filePath}.requests.json`, 'utf8')).toBe('{bad json')
  })

  it('keeps completed, cancelled or edited confirmed records from being replayed as active', async () => {
    const { client, state } = await fixture()
    await client.createTodo(input)
    state.records[0].reminder_enabled = false
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'unconfirmed_reminder' })
    state.records = []
    await expect(client.createTodo(input)).rejects.toMatchObject({ code: 'request_consumed' })
    expect(state.posts).toBe(1)
  })

  it('edits/cancels the selected record, preserves unrelated fields, and allows idempotent cancellation retry', async () => {
    const { client, state } = await fixture()
    const todo = await client.createTodo(input)
    const edited = await client.updateTodo(todo.todoId, { title: '下午开会', dueAt: '2026-10-05T15:00', expectedAccount: input.expectedAccount })
    expect(edited).toMatchObject({ title: '下午开会', dueAt: '2026-10-05T15:00:00', reminderEnabled: true })
    expect(state.puts[0]).toMatchObject({ project_id: 4, sort_num: 9, detail: `meguri-request:${requestId}` })
    state.mode = 'put_lost'
    await expect(client.updateTodo(todo.todoId, { cancel: true })).rejects.toMatchObject({ code: 'network_error' })
    state.mode = 'ok'
    const cancelled = await client.updateTodo(todo.todoId, { cancel: true })
    expect(cancelled).toMatchObject({ title: '下午开会', reminderEnabled: false, done: false })
    expect(state.posts).toBe(1)
  })

  it('verifies the real HTTP transport with a temporary loopback test server', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-journal-'))
    roots.push(root)
    const records: RecordShape[] = []
    const server = createServer(async (req, res) => {
      const chunks: Buffer[] = []
      for await (const chunk of req) chunks.push(Buffer.from(chunk))
      const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {}
      res.setHeader('Content-Type', 'application/json')
      if (req.url?.endsWith('/auth/tokens')) { res.end(JSON.stringify({ data: token() })); return }
      if (req.headers.authorization !== 'Bearer test-access') { res.writeHead(401); res.end('{}'); return }
      if (req.method === 'POST') {
        records.push({ ...body, todo_id: 1 })
        res.writeHead(201)
        res.end(JSON.stringify({ data: records[0] }))
      }
      else res.end(JSON.stringify({ data: records }))
    })
    await new Promise<void>(done => server.listen(0, '127.0.0.1', done))
    try {
      const address = server.address() as { port: number }
      const origin = `http://127.0.0.1:${address.port}`
      const client = createShizukiTimePrismClient({ filePath: join(root, 'session.json'), safeStorage: encryption, clock: () => now })
      await client.connect({ origin, email: 'test@example.com', password: 'test' })
      const todo = await client.createTodo({ ...input, expectedAccount: `${origin}:42` })
      expect(todo).toMatchObject({ todoId: 1, dueAt: `${dueAt}:00`, deadlineRemindValue: 0 })
      expect((await client.createTodo({ ...input, expectedAccount: `${origin}:42` })).todoId).toBe(1)
      expect(records).toHaveLength(1)
    }
    finally { await new Promise<void>((done, reject) => server.close(error => error ? reject(error) : done())) }
  })
})
