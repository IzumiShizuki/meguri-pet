import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

import { createShizukiReminderRuntime, dueTodoReminders, parseShizukiLocalTime } from './shizuki-reminders.mjs'

const roots: string[] = []
afterEach(() => {
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true })
})

function localTime(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}T${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:00`
}

describe('Shizuki desktop reminders', () => {
  it('uses local wall time and accepts a zero-minute offset without alerting completed todos', () => {
    const anchor = '2026-09-29T12:00:00'
    const at = parseShizukiLocalTime(anchor)
    expect(at).toBe(new Date(2026, 8, 29, 12, 0).getTime())
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
})
