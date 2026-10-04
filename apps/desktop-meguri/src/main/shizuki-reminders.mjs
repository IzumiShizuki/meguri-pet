import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { parseShanghaiTime } from '../shared/reminder-intent.mjs'

const SCAN_INTERVAL_MS = 30_000
const SYNC_INTERVAL_MS = 75_000
const GRACE_MS = 12 * 60_000
const RETENTION_MS = 7 * 24 * 60 * 60_000

export function parseShizukiLocalTime(value) {
  return parseShanghaiTime(String(value || '').trim())
}

function reminderOffsetMs(value, unit) {
  if (value === null || value === undefined || value === '') return NaN
  const numeric = Number(value)
  if (!Number.isSafeInteger(numeric) || numeric < 0) return NaN
  const normalizedUnit = String(unit || '').toUpperCase()
  if (normalizedUnit === 'MINUTE') return numeric * 60_000
  if (normalizedUnit === 'DAY' && numeric > 0) return numeric * 24 * 60 * 60_000
  return NaN
}

export function dueTodoReminders(todos, userId, now = Date.now()) {
  if (!Number.isSafeInteger(userId) || userId <= 0) return []
  const events = []
  for (const todo of Array.isArray(todos) ? todos : []) {
    if (todo?.done || todo?.reminderEnabled !== true || !Number.isSafeInteger(todo.todoId) || todo.todoId <= 0)
      continue
    for (const candidate of [
      { kind: 'deadline', anchor: todo.dueAt, value: todo.deadlineRemindValue, unit: todo.deadlineRemindUnit },
      ...(todo.timingMode === 'RANGE'
        ? [{ kind: 'start', anchor: todo.rangeStartAt, value: todo.startRemindValue, unit: todo.startRemindUnit }]
        : []),
    ]) {
      const anchor = parseShizukiLocalTime(candidate.anchor)
      const offset = reminderOffsetMs(candidate.value, candidate.unit)
      if (!Number.isFinite(anchor) || !Number.isFinite(offset)) continue
      const triggerAt = anchor - offset
      if (now < triggerAt || now - triggerAt > GRACE_MS) continue
      const key = `${userId}:${todo.todoId}:${candidate.kind}:${triggerAt}`
      events.push({
        key,
        todoId: todo.todoId,
        title: String(todo.title || '').slice(0, 200),
        dueAt: String(candidate.anchor),
        triggerAt,
        kind: candidate.kind,
        late: now > triggerAt + SCAN_INTERVAL_MS,
      })
    }
  }
  return events.sort((left, right) => left.triggerAt - right.triggerAt)
}

function readDedupe(filePath) {
  if (!existsSync(filePath)) return {}
  try {
    const value = JSON.parse(readFileSync(filePath, 'utf8'))
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid reminder dedupe')
    if (Object.values(value).some(seenAt => !Number.isFinite(seenAt))) throw new Error('Invalid reminder dedupe entries')
    return value
  }
  catch {
    throw new Error('提醒防重记录无法读取，已停止触发。请检查本机存储。')
  }
}

function writeDedupe(filePath, value) {
  const temp = `${filePath}.${process.pid}.tmp`
  mkdirSync(dirname(filePath), { recursive: true })
  try {
    writeFileSync(temp, JSON.stringify(value), { encoding: 'utf8', mode: 0o600 })
    renameSync(temp, filePath)
  }
  catch {
    try { rmSync(temp, { force: true }) } catch { /* Preserve an unexpected filesystem entry. */ }
    throw new Error('提醒防重记录无法保存，未触发提醒。')
  }
}

export function createShizukiReminderRuntime({ client, filePath, onReminder, onError = () => {}, clock = () => Date.now() }) {
  let active = false
  let syncTimer
  let scanTimer
  let syncingGeneration = -1
  let snapshot = []
  let dedupe = {}
  let generation = 0
  let snapshotTimes = new Map()
  let lastError = ''
  const pendingDelivery = new Map()

  function scan() {
    if (!active || !client.status().connected) return
    const now = clock()
    dedupe = Object.fromEntries(Object.entries(dedupe).filter(([, seenAt]) => Number.isFinite(seenAt)
      && now - seenAt <= RETENTION_MS))
    const fresh = snapshot.filter(todo => now - (snapshotTimes.get(todo.todoId) ?? -Infinity) <= SYNC_INTERVAL_MS + SCAN_INTERVAL_MS)
    for (const reminder of dueTodoReminders(fresh, client.status().userId, now)) {
      if (Object.hasOwn(dedupe, reminder.key) && !pendingDelivery.has(reminder.key)) continue
      try {
        if (!Object.hasOwn(dedupe, reminder.key)) {
          const next = { ...dedupe, [reminder.key]: now }
          writeDedupe(filePath, next)
          dedupe = next
        }
        onReminder(reminder)
        pendingDelivery.delete(reminder.key)
      }
      catch (error) {
        if (Object.hasOwn(dedupe, reminder.key)) pendingDelivery.set(reminder.key, reminder)
        lastError = error.message
        onError(error)
      }
    }
  }

  async function sync() {
    if (!active || syncingGeneration === generation || !client.status().connected) return
    const startedGeneration = generation
    const startedUserId = client.status().userId
    syncingGeneration = generation
    try {
      const todos = await client.listTodos()
      if (!active || generation !== startedGeneration || client.status().userId !== startedUserId) return
      snapshot = todos
      snapshotTimes = new Map(todos.map(todo => [todo.todoId, clock()]))
      lastError = ''
      scan()
    }
    catch (error) {
      if (generation === startedGeneration) {
        snapshot = []
        snapshotTimes.clear()
        lastError = error.message
        onError(error)
      }
    }
    finally {
      if (syncingGeneration === startedGeneration)
        syncingGeneration = -1
    }
  }

  function start() {
    if (active) return
    generation += 1
    active = true
    try { dedupe = readDedupe(filePath) }
    catch (error) { active = false; lastError = error.message; onError(error); return }
    void sync()
    syncTimer = setInterval(() => { void sync() }, SYNC_INTERVAL_MS)
    scanTimer = setInterval(scan, SCAN_INTERVAL_MS)
    syncTimer.unref?.()
    scanTimer.unref?.()
  }

  function stop() {
    generation += 1
    active = false
    clearInterval(syncTimer)
    clearInterval(scanTimer)
    syncTimer = undefined
    scanTimer = undefined
    snapshot = []
    snapshotTimes.clear()
    pendingDelivery.clear()
  }

  function useSnapshot(todos) {
    if (!active || !client.status().connected) return
    snapshot = Array.isArray(todos) ? todos : []
    snapshotTimes = new Map(snapshot.map(todo => [todo.todoId, clock()]))
    scan()
  }

  function applyTodo(todo) {
    if (!active || !client.status().connected) return
    // Invalidate in-flight reads so an older schedule cannot replace an acknowledged edit.
    generation += 1
    snapshot = [todo, ...snapshot.filter(item => item.todoId !== todo.todoId)]
    snapshotTimes.set(todo.todoId, clock())
    scan()
  }

  function invalidate() { generation += 1; snapshot = []; snapshotTimes.clear() }

  return { start, stop, sync, scan, useSnapshot, applyTodo, invalidate, status: () => ({ reminderError: lastError }) }
}
