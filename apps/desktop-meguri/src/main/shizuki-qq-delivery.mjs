import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

const DEFAULT_CORE_URL = 'https://bot.shizuki.online/meguri-core'
const DEFAULT_TOKEN_FILE = 'D:\\environment\\secrets\\meguri\\desktop-core-token.txt'
const MAX_PENDING = 1000
const MAX_AGE_MS = 7 * 24 * 60 * 60_000

function validCoreUrl(value) {
  try {
    const url = new URL(value)
    const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
    if (url.username || url.password || url.search || url.hash
      || (url.protocol !== 'https:' && !(loopback && url.protocol === 'http:')))
      return ''
    return url.href.replace(/\/+$/, '')
  }
  catch {
    return ''
  }
}

function readState(path) {
  try {
    const saved = JSON.parse(readFileSync(path, 'utf8'))
    if (saved?.version !== 1) return { version: 1, ownerUserId: 0, pending: [] }
    return {
      version: 1,
      ownerUserId: Number.isSafeInteger(saved.ownerUserId) && saved.ownerUserId > 0 ? saved.ownerUserId : 0,
      pending: Array.isArray(saved.pending) ? saved.pending.slice(0, MAX_PENDING) : [],
      lastUploadedAt: Number(saved.lastUploadedAt) || 0,
    }
  }
  catch {
    return { version: 1, ownerUserId: 0, pending: [] }
  }
}

function saveState(path, state) {
  const temp = `${path}.${process.pid}.tmp`
  mkdirSync(dirname(path), { recursive: true })
  try {
    writeFileSync(temp, JSON.stringify(state), { encoding: 'utf8', mode: 0o600 })
    renameSync(temp, path)
  }
  catch (error) {
    rmSync(temp, { force: true })
    throw error
  }
}

export function createShizukiQqDelivery({
  filePath,
  coreUrl = process.env.MEGURI_REMINDER_CORE_URL || DEFAULT_CORE_URL,
  tokenFile = process.env.MEGURI_REMINDER_CORE_TOKEN_FILE || DEFAULT_TOKEN_FILE,
  tenantId = process.env.MEGURI_REMINDER_CORE_TENANT_ID || 'meguri-staging',
  fetchImpl = globalThis.fetch,
  clock = () => Date.now(),
}) {
  if (!filePath) throw new TypeError('Reminder delivery needs a state file')
  let state = readState(filePath)
  let timer
  let sending = false
  let lastError = ''
  let failures = 0
  let retryAfter = 0
  const baseUrl = validCoreUrl(coreUrl)

  function status() {
    return {
      mainUserId: state.ownerUserId,
      qqUploadConfigured: Boolean(baseUrl && existsSync(tokenFile)),
      qqPending: state.pending.length,
      qqLastUploadedAt: state.lastUploadedAt || 0,
      qqError: lastError,
      qqNextAttemptAt: retryAfter,
    }
  }

  function selectOwner(userId) {
    if (!Number.isSafeInteger(userId) || userId <= 0)
      throw new Error('请先连接 Shizuki 账号。')
    if (state.ownerUserId && state.ownerUserId !== userId)
      throw new Error('请先取消原主账号绑定，再选择新账号。')
    const next = { ...state, ownerUserId: userId }
    saveState(filePath, next)
    state = next
    return status()
  }

  function clearOwner() {
    const next = { version: 1, ownerUserId: 0, pending: [], lastUploadedAt: 0 }
    saveState(filePath, next)
    state = next
    lastError = ''
    retryAfter = 0
    failures = 0
    return status()
  }

  function enqueue(reminder, userId, origin) {
    if (!state.ownerUserId || state.ownerUserId !== userId) return false
    const eventId = createHash('sha256')
      .update(`${origin}:${userId}:${reminder.key}`).digest('hex')
    if (state.pending.some(event => event.eventId === eventId)) return true
    if (state.pending.length >= MAX_PENDING) {
      lastError = 'QQ 提醒待发队列已满，请检查远端连接。'
      return false
    }
    const event = {
      eventId,
      shizukiUserId: userId,
      todoId: reminder.todoId,
      title: String(reminder.title || '').replace(/[\r\n\x00-\x1f]/g, ' ').trim().slice(0, 200),
      dueAt: reminder.dueAt,
      triggerAt: reminder.triggerAt,
      kind: reminder.kind,
      queuedAt: clock(),
    }
    const next = { ...state, pending: [...state.pending, event] }
    try { saveState(filePath, next) }
    catch {
      lastError = 'QQ 提醒无法写入本机待发队列，将在下次扫描时重试。'
      throw new Error(lastError)
    }
    state = next
    void flush()
    return true
  }

  async function flush() {
    if (sending || !state.ownerUserId || !state.pending.length || clock() < retryAfter) return
    sending = true
    try {
      if (!baseUrl) throw new Error('QQ 提醒 Core 地址无效。')
      const token = readFileSync(tokenFile, 'utf8').trim()
      if (!token) throw new Error('QQ 提醒 Core 凭据未配置。')
      while (state.pending.length) {
        const event = state.pending[0]
        if (event.shizukiUserId !== state.ownerUserId || clock() - event.queuedAt > MAX_AGE_MS) {
          const next = { ...state, pending: state.pending.slice(1) }
          saveState(filePath, next)
          state = next
          continue
        }
        const response = await fetchImpl(`${baseUrl}/v1/reminders/timeprism`, {
          method: 'POST',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`,
            'X-Meguri-Tenant-ID': tenantId,
            'X-Meguri-User-ID': `shizuki-${state.ownerUserId}`,
            'X-Meguri-Client-ID': 'desktop_pet',
            'X-Meguri-Session-ID': 'desktop-timeprism-reminders',
          },
          body: JSON.stringify(event),
          signal: AbortSignal.timeout(10_000),
          redirect: 'error',
        })
        if (!response.ok) throw new Error(`QQ 提醒 Core 上传失败（HTTP ${response.status}）。`)
        if (state.ownerUserId !== event.shizukiUserId || state.pending[0]?.eventId !== event.eventId)
          continue
        const next = { ...state, pending: state.pending.slice(1), lastUploadedAt: clock() }
        saveState(filePath, next)
        state = next
        lastError = ''
        failures = 0
        retryAfter = 0
      }
    }
    catch (error) {
      failures = Math.min(failures + 1, 5)
      retryAfter = clock() + Math.min(5 * 60_000, 30_000 * 2 ** (failures - 1))
      lastError = error instanceof Error && error.message.startsWith('QQ 提醒')
        ? error.message : 'QQ 提醒 Core 暂时不可用，将自动重试。'
    }
    finally {
      sending = false
    }
  }

  function start() {
    if (timer) return
    void flush()
    timer = setInterval(() => { void flush() }, 30_000)
    timer.unref?.()
  }

  function stop() {
    clearInterval(timer)
    timer = undefined
  }

  return { status, selectOwner, clearOwner, enqueue, flush, start, stop }
}
