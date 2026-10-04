import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

const DEFAULT_ORIGIN = 'https://site.shizuki.online'
const AUTH_PATH = '/api/v1/auth/tokens'
const LOGOUT_PATH = '/api/v1/auth/logout'
const TODOS_PATH = '/api/v1/light-apps/todos'
const MAX_RESPONSE_BYTES = 1024 * 1024
const REQUEST_TIMEOUT_MS = 15_000

export class ShizukiTimePrismError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'ShizukiTimePrismError'
    this.code = code
  }
}

export function normalizeShizukiOrigin(value = DEFAULT_ORIGIN) {
  let url
  try {
    url = new URL(String(value || '').trim())
  }
  catch {
    throw new ShizukiTimePrismError('invalid_origin', 'Shizuki 站点地址无效。')
  }
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
  if ((url.protocol !== 'https:' && !(loopback && url.protocol === 'http:'))
    || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new ShizukiTimePrismError('invalid_origin', '站点地址必须是 HTTPS 根地址；本机调试可使用 HTTP 回环地址。')
  }
  return url.origin
}

function valueOf(record, camel, snake) {
  return record?.[camel] ?? record?.[snake]
}

function positiveId(value) {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : 0
}

export function normalizeShizukiTodo(raw) {
  const value = raw && typeof raw === 'object' ? raw : {}
  return {
    todoId: positiveId(valueOf(value, 'todoId', 'todo_id')),
    projectId: positiveId(valueOf(value, 'projectId', 'project_id')) || null,
    title: String(value.title || ''),
    detail: String(value.detail || ''),
    priority: String(value.priority || 'MEDIUM').toUpperCase(),
    done: value.done === true,
    dueAt: String(valueOf(value, 'dueAt', 'due_at') || ''),
    showOnCalendar: valueOf(value, 'showOnCalendar', 'show_on_calendar') !== false,
    timePrecision: String(valueOf(value, 'timePrecision', 'time_precision') || 'MINUTE').toUpperCase(),
    timingMode: String(valueOf(value, 'timingMode', 'timing_mode') || 'DEADLINE').toUpperCase(),
    rangeStartAt: String(valueOf(value, 'rangeStartAt', 'range_start_at') || ''),
    reminderEnabled: valueOf(value, 'reminderEnabled', 'reminder_enabled') === true,
    startRemindValue: valueOf(value, 'startRemindValue', 'start_remind_value') ?? null,
    startRemindUnit: String(valueOf(value, 'startRemindUnit', 'start_remind_unit') || ''),
    deadlineRemindValue: valueOf(value, 'deadlineRemindValue', 'deadline_remind_value') ?? null,
    deadlineRemindUnit: String(valueOf(value, 'deadlineRemindUnit', 'deadline_remind_unit') || ''),
    sortNum: Number(valueOf(value, 'sortNum', 'sort_num') || 0),
    updatedAt: String(valueOf(value, 'updatedAt', 'updated_at') || ''),
  }
}

export function todoToShizukiPayload(todo, done = todo.done) {
  const payload = {
    project_id: todo.projectId,
    title: todo.title,
    detail: todo.detail,
    priority: todo.priority,
    done,
    due_at: todo.dueAt || null,
    show_on_calendar: todo.showOnCalendar,
    time_precision: todo.timePrecision,
    timing_mode: todo.timingMode,
    range_start_at: todo.rangeStartAt || null,
    reminder_enabled: todo.reminderEnabled,
    start_remind_value: todo.startRemindValue,
    start_remind_unit: todo.startRemindUnit || null,
    deadline_remind_value: todo.deadlineRemindValue,
    deadline_remind_unit: todo.deadlineRemindUnit || null,
    sort_num: todo.sortNum,
  }
  return payload
}

export function normalizeLocalDueAt(value) {
  if (value === undefined || value === null || value === '')
    return ''
  const input = String(value).trim()
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(input))
    throw new ShizukiTimePrismError('invalid_due_at', '提醒时间需要精确到分钟。')
  const date = new Date(input)
  if (!Number.isFinite(date.getTime()) || date.getFullYear() !== Number(input.slice(0, 4))
    || date.getMonth() + 1 !== Number(input.slice(5, 7)) || date.getDate() !== Number(input.slice(8, 10))
    || date.getHours() !== Number(input.slice(11, 13)) || date.getMinutes() !== Number(input.slice(14, 16))) {
    throw new ShizukiTimePrismError('invalid_due_at', '提醒时间不是有效的本地日期和时间。')
  }
  if (date.getTime() <= Date.now())
    throw new ShizukiTimePrismError('invalid_due_at', '提醒时间需要晚于当前时间。')
  return `${input}:00`
}

async function readBoundedResponse(response) {
  const declaredLength = Number(response.headers.get('content-length'))
  if (declaredLength > MAX_RESPONSE_BYTES)
    throw new ShizukiTimePrismError('invalid_response', 'Shizuki 响应过大。')
  const reader = response.body?.getReader()
  if (!reader)
    return null
  const chunks = []
  let size = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    size += value.byteLength
    if (size > MAX_RESPONSE_BYTES) {
      await reader.cancel()
      throw new ShizukiTimePrismError('invalid_response', 'Shizuki 响应过大。')
    }
    chunks.push(value)
  }
  const body = Buffer.concat(chunks).toString('utf8')
  if (!body) return null
  try {
    return JSON.parse(body)
  }
  catch {
    throw new ShizukiTimePrismError('invalid_response', 'Shizuki 返回了无法解析的数据。')
  }
}

function unwrapApiData(body) {
  if (!body || typeof body !== 'object')
    throw new ShizukiTimePrismError('invalid_response', 'Shizuki 返回了空数据。')
  return Object.hasOwn(body, 'data') ? body.data : body
}

export function createShizukiTimePrismClient({
  filePath,
  safeStorage,
  fetchImpl = globalThis.fetch,
  clock = () => Date.now(),
  origin: initialOrigin = DEFAULT_ORIGIN,
}) {
  if (!filePath || !safeStorage || typeof fetchImpl !== 'function')
    throw new TypeError('Shizuki TimePrism client requires storage, encryption, and fetch')

  let origin = normalizeShizukiOrigin(initialOrigin)
  let email = ''
  let userId = 0
  let refreshToken = ''
  let accessToken = ''
  let accessExpiresAt = 0
  let refreshPromise
  let lastError = ''

  function status() {
    return { connected: Boolean(refreshToken), origin, email, userId, error: lastError }
  }

  function persistSession(next) {
    if (!safeStorage.isEncryptionAvailable())
      throw new ShizukiTimePrismError('encryption_unavailable', 'Windows 凭据加密不可用，无法安全保存站点登录。')
    const encrypted = safeStorage.encryptString(next.refreshToken).toString('base64')
    const contents = JSON.stringify({
      version: 1,
      origin: next.origin,
      email: next.email,
      userId: next.userId,
      refreshTokenEncrypted: encrypted,
    })
    const temp = `${filePath}.${process.pid}.tmp`
    mkdirSync(dirname(filePath), { recursive: true })
    try {
      writeFileSync(temp, contents, { encoding: 'utf8', mode: 0o600 })
      renameSync(temp, filePath)
    }
    catch {
      rmSync(temp, { force: true })
      throw new ShizukiTimePrismError('storage_failed', '无法安全保存 Shizuki 登录，请重新连接。')
    }
  }

  function clearLocalSession() {
    refreshToken = ''
    accessToken = ''
    accessExpiresAt = 0
    email = ''
    userId = 0
    rmSync(filePath, { force: true })
  }

  function initialize() {
    if (!existsSync(filePath)) return status()
    try {
      if (!safeStorage.isEncryptionAvailable())
        throw new Error('encryption unavailable')
      const saved = JSON.parse(readFileSync(filePath, 'utf8'))
      if (saved.version !== 1 || !saved.refreshTokenEncrypted)
        throw new Error('invalid session')
      const savedOrigin = normalizeShizukiOrigin(saved.origin)
      const decrypted = safeStorage.decryptString(Buffer.from(saved.refreshTokenEncrypted, 'base64'))
      if (!decrypted) throw new Error('empty token')
      origin = savedOrigin
      refreshToken = decrypted
      email = String(saved.email || '')
      userId = positiveId(saved.userId)
      lastError = ''
    }
    catch {
      lastError = '已保存的 Shizuki 登录无法解密，请重新连接。'
    }
    return status()
  }

  async function request(path, { method = 'GET', token = '', body } = {}) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
    try {
      const response = await fetchImpl(`${origin}${path}`, {
        method,
        headers: {
          Accept: 'application/json',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        signal: controller.signal,
        redirect: 'error',
      })
      const parsed = await readBoundedResponse(response)
      if (!response.ok) {
        const code = response.status === 401 ? 'unauthorized' : response.status === 403 ? 'forbidden' : 'site_error'
        const message = response.status === 401 ? 'Shizuki 登录已失效，请重新连接。'
          : response.status === 403 ? '当前 Shizuki 账号无权执行此操作。'
            : `Shizuki 请求失败（HTTP ${response.status}）。`
        throw new ShizukiTimePrismError(code, message)
      }
      return unwrapApiData(parsed)
    }
    catch (error) {
      if (error instanceof ShizukiTimePrismError) throw error
      throw new ShizukiTimePrismError(
        controller.signal.aborted ? 'timeout' : 'network_error',
        controller.signal.aborted ? '连接 Shizuki 超时。' : '无法连接 Shizuki，请检查网络和站点地址。',
      )
    }
    finally {
      clearTimeout(timer)
    }
  }

  function applyTokenPayload(raw, nextEmail = email) {
    const access = String(valueOf(raw, 'accessToken', 'access_token') || '')
    const refresh = String(valueOf(raw, 'refreshToken', 'refresh_token') || '')
    const id = positiveId(valueOf(raw, 'userId', 'user_id'))
    const expires = Number(valueOf(raw, 'expiresInSec', 'expires_in_sec'))
    const resultType = String(valueOf(raw, 'resultType', 'result_type') || '')
    if (resultType !== 'TOKEN_ISSUED' || !access || !refresh || !id || !Number.isFinite(expires) || expires <= 0)
      throw new ShizukiTimePrismError('invalid_response', 'Shizuki 登录响应不完整。')
    persistSession({ origin, email: nextEmail, userId: id, refreshToken: refresh })
    refreshToken = refresh
    accessToken = access
    accessExpiresAt = clock() + expires * 1000
    userId = id
    email = nextEmail
    lastError = ''
  }

  async function connect(input) {
    const nextOrigin = normalizeShizukiOrigin(input?.origin || DEFAULT_ORIGIN)
    const nextEmail = String(input?.email || '').trim()
    const password = String(input?.password || '')
    if (!nextEmail || !password || nextEmail.length > 320 || password.length > 1024)
      throw new ShizukiTimePrismError('invalid_credentials', '请输入 Shizuki 邮箱和密码。')
    const previousOrigin = origin
    origin = nextOrigin
    try {
      const payload = await request(AUTH_PATH, {
        method: 'POST',
        body: { grant_type: 'EMAIL_PASSWORD', email: nextEmail, password },
      })
      applyTokenPayload(payload, nextEmail)
      return status()
    }
    catch (error) {
      origin = previousOrigin
      const failure = error.code === 'unauthorized'
        ? new ShizukiTimePrismError('invalid_credentials', 'Shizuki 邮箱或密码错误。')
        : error
      lastError = failure.message
      throw failure
    }
  }

  async function renew() {
    if (!refreshToken)
      throw new ShizukiTimePrismError('not_connected', '请先在设置中连接 Shizuki 账号。')
    if (refreshPromise) return refreshPromise
    refreshPromise = (async () => {
      try {
        const payload = await request(AUTH_PATH, {
          method: 'POST',
          body: { grant_type: 'REFRESH_TOKEN', refresh_token: refreshToken },
        })
        applyTokenPayload(payload)
        return accessToken
      }
      catch (error) {
        lastError = error.message
        if (error.code === 'unauthorized' || error.code === 'storage_failed') {
          clearLocalSession()
          lastError = error.message
        }
        throw error
      }
      finally {
        refreshPromise = undefined
      }
    })()
    return refreshPromise
  }

  async function authorized(path, options = {}) {
    if (!refreshToken)
      throw new ShizukiTimePrismError('not_connected', '请先在设置中连接 Shizuki 账号。')
    if (!accessToken || clock() >= accessExpiresAt - 60_000)
      await renew()
    try {
      const data = await request(path, { ...options, token: accessToken })
      lastError = ''
      return data
    }
    catch (error) {
      if (error.code !== 'unauthorized') {
        lastError = error.message
        throw error
      }
      await renew()
      const data = await request(path, { ...options, token: accessToken })
      lastError = ''
      return data
    }
  }

  async function listTodos() {
    const data = await authorized(TODOS_PATH)
    if (!Array.isArray(data))
      throw new ShizukiTimePrismError('invalid_response', 'Shizuki 待办列表格式无效。')
    return data.map(normalizeShizukiTodo).filter(todo => todo.todoId > 0)
  }

  async function createTodo(input) {
    const title = String(input?.title || '').trim()
    if (!title || title.length > 200)
      throw new ShizukiTimePrismError('invalid_title', '待办标题需要 1 到 200 个字符。')
    const dueAt = normalizeLocalDueAt(input?.dueAt)
    const payload = {
      title,
      detail: '',
      priority: 'MEDIUM',
      done: false,
      ...(dueAt ? {
        due_at: dueAt,
        show_on_calendar: true,
        time_precision: 'MINUTE',
        timing_mode: 'DEADLINE',
        reminder_enabled: true,
        deadline_remind_value: 0,
        deadline_remind_unit: 'MINUTE',
      } : {}),
    }
    const data = await authorized(TODOS_PATH, { method: 'POST', body: payload })
    const todo = normalizeShizukiTodo(data)
    if (!todo.todoId)
      throw new ShizukiTimePrismError('invalid_response', 'Shizuki 未返回已创建的待办。')
    return todo
  }

  async function completeTodo(id) {
    const todoId = positiveId(id)
    if (!todoId)
      throw new ShizukiTimePrismError('invalid_id', '待办编号无效。')
    const todos = await listTodos()
    const current = todos.find(todo => todo.todoId === todoId)
    if (!current)
      throw new ShizukiTimePrismError('not_found', 'Shizuki 中找不到该待办，请刷新列表。')
    if (current.done) return current
    const data = await authorized(`${TODOS_PATH}/${todoId}`, {
      method: 'PUT',
      body: todoToShizukiPayload(current, true),
    })
    const saved = normalizeShizukiTodo(data)
    if (!saved.todoId || !saved.done)
      throw new ShizukiTimePrismError('invalid_response', 'Shizuki 未确认待办已完成。')
    return saved
  }

  async function disconnect() {
    const token = accessToken
    const refresh = refreshToken
    try {
      if (refresh)
        await request(LOGOUT_PATH, { method: 'POST', token, body: { refresh_token: refresh } })
    }
    catch {
      // Local disconnection must still succeed when the site is unavailable.
    }
    clearLocalSession()
    lastError = ''
    return status()
  }

  return { initialize, status, connect, disconnect, listTodos, createTodo, completeTodo }
}
