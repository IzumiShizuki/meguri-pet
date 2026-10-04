import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { createShizukiReminderRuntime, dueTodoReminders, parseShizukiLocalTime } from './shizuki-reminders.mjs'
import { shanghaiWallTime } from '../shared/reminder-intent.mjs'

const roots: string[] = []
afterEach(() => {
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true })
})

function localTime(date: Date) {
  return `${shanghaiWallTime(date.getTime())}:00`
}

describe('Shizuki desktop reminders', () => {
  it('uses local wall time and accepts a zero-minute offset without alerting completed todos', () => {
    const anchor = '2026-09-29T12:00:00'
    const at = parseShizukiLocalTime(anchor)
    expect(at).toBe(Date.parse('2026-09-29T12:00:00+08:00'))
    const pending = { todoId: 1, title: '睡觉', done: false, reminderEnabled: true, dueAt: anchor, deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE', timingMode: 'DEADLINE' }
    const done = { ...pending, todoId: 2, done: true }
    const events = dueTodoReminders([pending, done], 42, at)
    expect(events).toHaveLength(1)
    expect(events[0]).toMatchObject({ title: '睡觉', triggerAt: at, late: false, key: `42:1:deadline:${at}` })
    expect(dueTodoReminders([{ ...pending, deadlineRemindValue: null }], 42, at)).toEqual([])
  })

  it('handles positive offsets and skips reminders older than twelve minutes', () => {
    const anchor = '2026-09-29T12:00:00'
    const at = parseShizukiLocalTime(anchor)
    const todo = { todoId: 3, title: '开会', done: false, reminderEnabled: true, dueAt: anchor, deadlineRemindValue: 10, deadlineRemindUnit: 'MINUTE', timingMode: 'DEADLINE' }
    expect(dueTodoReminders([todo], 42, at - 10 * 60_000)).toHaveLength(1)
    expect(dueTodoReminders([todo], 42, at + 3 * 60_000)).toHaveLength(0)
    expect(parseShizukiLocalTime('2026-02-30T12:00:00')).toBeNaN()
  })

  it('persists per-occurrence deduplication across runtime restarts', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-reminders-'))
    roots.push(root)
    const filePath = join(root, 'seen.json')
    const nowDate = new Date()
    nowDate.setSeconds(0, 0)
    const now = nowDate.getTime()
    const todo = { todoId: 9, title: '休息', done: false, reminderEnabled: true, dueAt: localTime(nowDate), deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE', timingMode: 'DEADLINE' }
    const client = { status: () => ({ connected: true, userId: 42 }), listTodos: async () => [todo] }
    const delivered: string[] = []
    const first = createShizukiReminderRuntime({ client, filePath, clock: () => now, onReminder: (event: { key: string }) => delivered.push(event.key) })
    first.start()
    await first.sync()
    await new Promise(resolve => setTimeout(resolve, 5))
    first.stop()
    expect(delivered).toHaveLength(1)

    const second = createShizukiReminderRuntime({ client, filePath, clock: () => now, onReminder: (event: { key: string }) => delivered.push(event.key) })
    second.start()
    await second.sync()
    await new Promise(resolve => setTimeout(resolve, 5))
    second.stop()
    expect(delivered).toHaveLength(1)
  })

  it('ignores an old account fetch that finishes after disconnect and reconnect', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-reminders-'))
    roots.push(root)
    const nowDate = new Date()
    nowDate.setSeconds(0, 0)
    const now = nowDate.getTime()
    const todo = { todoId: 9, title: '旧账号提醒', done: false, reminderEnabled: true, dueAt: localTime(nowDate), deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE', timingMode: 'DEADLINE' }
    let releaseOld: ((items: typeof todo[]) => void) | undefined
    let userId = 42
    const client = {
      status: () => ({ connected: true, userId }),
      listTodos: () => userId === 42
        ? new Promise<typeof todo[]>(resolve => { releaseOld = resolve })
        : Promise.resolve([]),
    }
    const delivered: string[] = []
    const runtime = createShizukiReminderRuntime({ client, filePath: join(root, 'seen.json'), clock: () => now, onReminder: (event: { key: string }) => delivered.push(event.key) })
    runtime.start()
    runtime.stop()
    userId = 43
    runtime.start()
    await runtime.sync()
    releaseOld?.([todo])
    await new Promise(resolve => setTimeout(resolve, 0))
    runtime.stop()
    expect(delivered).toEqual([])
  })

  it('stops while offline, catches up once inside grace and ignores older occurrences', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-reminders-')); roots.push(root)
    const at = parseShizukiLocalTime('2026-10-05T09:00:00')
    let now = at - 60_000
    const todo = { todoId: 17, title: '开会', dueAt: '2026-10-05T09:00:00', done: false, reminderEnabled: true, deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE' }
    const client = { status: () => ({ connected: true, userId: 42 }), listTodos: async () => [todo] }
    const delivered: unknown[] = []
    const runtime = createShizukiReminderRuntime({ client, filePath: join(root, 'dedupe.json'), clock: () => now, onReminder: (event: unknown) => delivered.push(event) })
    runtime.start(); await new Promise(done => setTimeout(done, 0)); runtime.stop()
    now = at + 5 * 60_000
    runtime.scan(); expect(delivered).toHaveLength(0)
    runtime.start(); await new Promise(done => setTimeout(done, 0)); runtime.stop()
    expect(delivered).toHaveLength(1)
    now = at + 13 * 60_000
    expect(dueTodoReminders([todo], 42, now)).toEqual([])
  })

  it('clears stale snapshots after network failure and resumes only on a successful read', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-reminders-')); roots.push(root)
    const at = parseShizukiLocalTime('2026-10-05T09:00:00')
    let now = at - 60_000
    let offline = false
    const todo = { todoId: 17, title: '开会', dueAt: '2026-10-05T09:00:00', done: false, reminderEnabled: true, deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE' }
    const client = { status: () => ({ connected: true, userId: 42 }), listTodos: async () => { if (offline) throw new Error('offline'); return [todo] } }
    const delivered: unknown[] = []
    const runtime = createShizukiReminderRuntime({ client, filePath: join(root, 'dedupe.json'), clock: () => now, onReminder: (event: unknown) => delivered.push(event) })
    try {
      runtime.start(); await new Promise(done => setTimeout(done, 0))
      offline = true; await runtime.sync(); now = at; runtime.scan()
      expect(delivered).toHaveLength(0)
      expect(runtime.status().reminderError).toBe('offline')
      offline = false; await runtime.sync()
      expect(delivered).toHaveLength(1)
    }
    finally { runtime.stop() }
  })

  it('uses acknowledged mutations immediately and rejects an older in-flight snapshot', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-reminders-')); roots.push(root)
    const at = parseShizukiLocalTime('2026-10-05T09:00:00')
    let now = at - 60_000
    const todo = { todoId: 17, title: '开会', dueAt: '2026-10-05T09:00:00', done: false, reminderEnabled: true, deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE' }
    let release!: (value: typeof todo[]) => void
    const client = { status: () => ({ connected: true, userId: 42 }), listTodos: () => new Promise<typeof todo[]>(done => { release = done }) }
    const delivered: unknown[] = []
    const runtime = createShizukiReminderRuntime({ client, filePath: join(root, 'dedupe.json'), clock: () => now, onReminder: (event: unknown) => delivered.push(event) })
    try {
      runtime.start()
      runtime.useSnapshot([todo])
      runtime.applyTodo({ ...todo, reminderEnabled: false })
      release([todo]); await new Promise(done => setTimeout(done, 0))
      now = at; runtime.scan()
      expect(delivered).toHaveLength(0)
      runtime.applyTodo({ ...todo, dueAt: '2026-10-05T09:01:00' })
      now = at + 60_000; runtime.scan()
      expect(delivered).toHaveLength(1)
    }
    finally { runtime.stop() }
  })

  it('fails closed on damaged/unwritable dedupe and exposes the error', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-reminders-')); roots.push(root)
    const filePath = join(root, 'dedupe.json')
    writeFileSync(filePath, '{bad')
    const now = parseShizukiLocalTime('2026-10-05T09:00:00')
    const todo = { todoId: 17, title: '开会', dueAt: '2026-10-05T09:00:00', done: false, reminderEnabled: true, deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE' }
    const client = { status: () => ({ connected: true, userId: 42 }), listTodos: async () => [todo] }
    const delivered: unknown[] = []
    const runtime = createShizukiReminderRuntime({ client, filePath, clock: () => now, onReminder: (event: unknown) => delivered.push(event) })
    runtime.start(); runtime.scan()
    expect(runtime.status().reminderError).toContain('无法读取')
    expect(delivered).toEqual([])
    runtime.stop()
    writeFileSync(filePath, '{}')
    runtime.start()
    // Block the atomic write after the initial dedupe read, before the asynchronous fetch resolves.
    mkdirSync(`${filePath}.${process.pid}.tmp`)
    await new Promise(done => setTimeout(done, 0))
    expect(delivered).toEqual([])
    expect(runtime.status().reminderError).toContain('无法保存')
    runtime.stop()
  })

  it('triggers on the actual scan timer and does not repeat on subsequent intervals', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const root = mkdtempSync(join(tmpdir(), 'meguri-reminders-')); roots.push(root)
    const at = parseShizukiLocalTime('2026-10-05T09:00:00')
    let now = at - 60_000
    const todo = { todoId: 17, title: '开会', dueAt: '2026-10-05T09:00:00', done: false, reminderEnabled: true, deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE' }
    const client = { status: () => ({ connected: true, userId: 42 }), listTodos: async () => [todo] }
    const delivered: unknown[] = []
    const runtime = createShizukiReminderRuntime({ client, filePath: join(root, 'dedupe.json'), clock: () => now, onReminder: (event: unknown) => delivered.push(event) })
    try {
      runtime.start(); await Promise.resolve()
      now = at - 30_000; await vi.advanceTimersByTimeAsync(30_000)
      expect(delivered).toHaveLength(0)
      now = at; await vi.advanceTimersByTimeAsync(30_000)
      expect(delivered).toHaveLength(1)
      now = at + 30_000; await vi.advanceTimersByTimeAsync(30_000)
      expect(delivered).toHaveLength(1)
    }
    finally { runtime.stop(); vi.useRealTimers() }
  })

  it('reports delivery callback failures and permits bounded retry while running', async () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-reminders-')); roots.push(root)
    const now = parseShizukiLocalTime('2026-10-05T09:00:00')
    const todo = { todoId: 17, title: '开会', dueAt: '2026-10-05T09:00:00', done: false, reminderEnabled: true, deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE' }
    const client = { status: () => ({ connected: true, userId: 42 }), listTodos: async () => [todo] }
    const delivered: unknown[] = []
    let attempts = 0
    const runtime = createShizukiReminderRuntime({ client, filePath: join(root, 'dedupe.json'), clock: () => now, onReminder: (event: unknown) => {
      attempts += 1
      if (attempts === 1) throw new Error('outbox unavailable')
      delivered.push(event)
    } })
    try {
      runtime.start(); await Promise.resolve()
      expect(runtime.status().reminderError).toBe('outbox unavailable')
      runtime.scan(); runtime.scan()
      expect(delivered).toHaveLength(1)
      expect(attempts).toBe(2)
    }
    finally { runtime.stop() }
  })
})
