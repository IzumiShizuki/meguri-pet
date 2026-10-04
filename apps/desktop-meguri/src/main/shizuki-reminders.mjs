import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

const SCAN_INTERVAL_MS = 30_000
const SYNC_INTERVAL_MS = 75_000
const GRACE_MS = 12 * 60_000
const RETENTION_MS = 7 * 24 * 60 * 60_000

export function parseShizukiLocalTime(value) {
  const raw = String(value || '').trim()
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(raw)
  if (!match) return NaN
  const [year, month, day, hour, minute] = match.slice(1, 6).map(Number)
  const second = match[6] === undefined ? 0 : Number(match[6])
  const date = new Date(year, month - 1, day, hour, minute, second)
  if (date.getFullYear() !== year || date.getMonth() + 1 !== month || date.getDate() !== day
    || date.getHours() !== hour || date.getMinutes() !== minute || date.getSeconds() !== second)
    return NaN
  return date.getTime()
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
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return value
  }
  catch {
    return {}
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
    rmSync(temp, { force: true })
  }
}

export function createShizukiReminderRuntime({ client, filePath, onReminder, onError = () => {}, clock = () => Date.now() }) {
  let active = false
  let syncTimer
  let scanTimer
  let syncingGeneration = -1
  let snapshot = []
  let dedupe = readDedupe(filePath)
  let generation = 0

  function scan() {
    if (!active || !client.status().connected) return
    const now = clock()
    dedupe = Object.fromEntries(Object.entries(dedupe).filter(([, seenAt]) => Number.isFinite(seenAt)
      && now - seenAt <= RETENTION_MS))
    for (const reminder of dueTodoReminders(snapshot, client.status().userId, now)) {
      if (dedupe[reminder.key]) continue
      try {
        onReminder(reminder)
        dedupe[reminder.key] = now
        writeDedupe(filePath, dedupe)
      }
      catch (error) {
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
      scan()
    }
    catch (error) {
      onError(error)
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
    dedupe = readDedupe(filePath)
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
  }

  function useSnapshot(todos) {
    if (!active || !client.status().connected) return
    snapshot = Array.isArray(todos) ? todos : []
    scan()
  }

  return { start, stop, sync, scan, useSnapshot }
}
