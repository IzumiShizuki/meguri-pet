import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { randomUUID } from 'node:crypto'
import { parseShanghaiTime, REMINDER_TIMEZONE } from '../shared/reminder-intent.mjs'
import { createWriteJournal, requestMarker } from './shizuki-write-journal.mjs'
import { recurringStartOfDay, recurringTime, weeklyCron } from '../shared/recurring-todos.mjs'

const DEFAULT_ORIGIN = 'https://site.shizuki.online'
const AUTH_PATH = '/api/v1/auth/tokens'
const LOGOUT_PATH = '/api/v1/auth/logout'
const TODOS_PATH = '/api/v1/light-apps/todos'
const RULES_PATH = '/api/v1/light-apps/todo-recurring-rules'
const MAX_RESPONSE_BYTES = 1024 * 1024
const REQUEST_TIMEOUT_MS = 15_000

export class ShizukiTimePrismError extends Error {
  constructor(code, message, status = 0) {
    super(message)
    this.name = 'ShizukiTimePrismError'
    this.code = code
    this.status = status
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
    timePrecision: String(valueOf(value, 'timePrecision', 'time_precision') || '').toUpperCase(),
    timingMode: String(valueOf(value, 'timingMode', 'timing_mode') || '').toUpperCase(),
    rangeStartAt: String(valueOf(value, 'rangeStartAt', 'range_start_at') || ''),
    reminderEnabled: valueOf(value, 'reminderEnabled', 'reminder_enabled') === true,
    startRemindValue: valueOf(value, 'startRemindValue', 'start_remind_value') ?? null,
    startRemindUnit: String(valueOf(value, 'startRemindUnit', 'start_remind_unit') || ''),
    deadlineRemindValue: valueOf(value, 'deadlineRemindValue', 'deadline_remind_value') ?? null,
    deadlineRemindUnit: String(valueOf(value, 'deadlineRemindUnit', 'deadline_remind_unit') || ''),
    sortNum: Number(valueOf(value, 'sortNum', 'sort_num') || 0),
    updatedAt: String(valueOf(value, 'updatedAt', 'updated_at') || ''),
    recurringRuleId: positiveId(valueOf(value, 'recurringRuleId', 'recurring_rule_id')) || null,
    recurringInstanceAt: String(valueOf(value, 'recurringInstanceAt', 'recurring_instance_at') || ''),
  }
}

export function normalizeRecurringRule(raw) {
  const v = raw || {}
  return {
    ruleId: positiveId(valueOf(v, 'ruleId', 'rule_id')), projectId: positiveId(valueOf(v, 'projectId', 'project_id')) || null,
    title: String(v.title || ''), detail: String(v.detail || ''), priority: String(v.priority || 'MEDIUM'),
    cronExpr: String(valueOf(v, 'cronExpr', 'cron_expr') || ''), timeZoneId: String(valueOf(v, 'timeZoneId', 'time_zone_id') || 'Asia/Shanghai'),
    startAt: String(valueOf(v, 'startAt', 'start_at') || ''), endAt: String(valueOf(v, 'endAt', 'end_at') || ''),
    enabled: v.enabled !== false, sortNum: Number(valueOf(v, 'sortNum', 'sort_num') || 0),
    overduePolicy: String(valueOf(v, 'overduePolicy', 'overdue_policy') || 'KEEP'),
    reminderEnabled: valueOf(v, 'reminderEnabled', 'reminder_enabled') === true,
    deadlineRemindValue: valueOf(v, 'deadlineRemindValue', 'deadline_remind_value') ?? null,
    deadlineRemindUnit: valueOf(v, 'deadlineRemindUnit', 'deadline_remind_unit') == null
      ? null : String(valueOf(v, 'deadlineRemindUnit', 'deadline_remind_unit')),
  }
}

function rulePayload(rule) {
  return Object.fromEntries(Object.entries(rule).filter(([key]) => key !== 'ruleId')
    .map(([key, value]) => [key.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`), value === '' && ['startAt', 'endAt'].includes(key) ? null : value]))
}

function recurringBoundary(value, zone) {
  if (value === '') return ''
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value)
    || !Number.isFinite(recurringTime(value, zone)))
    throw new ShizukiTimePrismError('invalid_schedule', '起止时间需要为规则时区中有效的日期时间。')
  return value.length === 16 ? `${value}:00` : value
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

export function normalizeLocalDueAt(value, now = Date.now()) {
  if (value === undefined || value === null || value === '')
    return ''
  const input = String(value).trim()
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(input))
    throw new ShizukiTimePrismError('invalid_due_at', '提醒时间需要精确到分钟。')
  const epoch = parseShanghaiTime(input)
  if (!Number.isFinite(epoch)) {
    throw new ShizukiTimePrismError('invalid_due_at', '提醒时间不是有效的本地日期和时间。')
  }
  if (epoch <= now)
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
  const journal = createWriteJournal(`${filePath}.requests.json`, (code, message) => new ShizukiTimePrismError(code, message))
  const ruleJournal = createWriteJournal(`${filePath}.recurring-requests.json`, (code, message) => new ShizukiTimePrismError(code, message))
  let operationQueue = Promise.resolve()
  function serialized(operation) {
    return (...args) => {
      const next = operationQueue.then(() => operation(...args))
      operationQueue = next.catch(() => {})
      return next
    }
  }

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
      if (!response.ok) {
        const code = response.status === 401 ? 'unauthorized' : response.status === 403 ? 'forbidden' : 'site_error'
        const message = response.status === 401 ? 'Shizuki 登录已失效，请重新连接。'
          : response.status === 403 ? '当前 Shizuki 账号无权执行此操作。'
            : `Shizuki 请求失败（HTTP ${response.status}）。`
        throw new ShizukiTimePrismError(code, message, response.status)
      }
      const parsed = await readBoundedResponse(response)
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
    let data
    try { data = await authorized(`${TODOS_PATH}/snapshot`) }
    catch (error) {
      if ([404, 405].includes(error.status))
        throw new ShizukiTimePrismError('snapshot_unsupported', '站点尚不支持无副作用待办快照，请更新站点后重试。', error.status)
      throw error
    }
    return hydrateTodos(data)
  }

  async function syncTodos() {
    let data
    try { data = await authorized(`${TODOS_PATH}/sync`, { method: 'POST' }) }
    catch (error) {
      // Explicit synchronization retains compatibility with the legacy mutating GET.
      if (![404, 405].includes(error.status)) throw error
      data = await authorized(TODOS_PATH)
    }
    return hydrateTodos(data)
  }

  async function hydrateTodos(data) {
    if (!Array.isArray(data))
      throw new ShizukiTimePrismError('invalid_response', 'Shizuki 待办列表格式无效。')
    const todos = data.map(normalizeShizukiTodo).filter(todo => todo.todoId > 0)
    if (!todos.some(todo => todo.recurringRuleId)) return todos
    // A recurring wall-clock deadline is meaningless without its authoritative timezone.
    // Fail the snapshot if rule retrieval fails; never silently assume Shanghai.
    const rules = await listRecurringRules()
    return todos.map(todo => {
      const rule = rules.find(item => item.ruleId === todo.recurringRuleId)
      return rule ? { ...todo, recurringRule: rule, timeZoneId: rule.timeZoneId }
        : todo.recurringRuleId ? { ...todo, reminderEnabled: false, recurringRuleUnavailable: true } : todo
    })
  }

  async function listRecurringRules() {
    const data = await authorized(RULES_PATH)
    if (!Array.isArray(data)) throw new ShizukiTimePrismError('invalid_response', '周期规则列表格式无效。')
    return data.map(normalizeRecurringRule).filter(rule => rule.ruleId > 0)
  }

  async function saveRecurringRule(input) {
    if (!input?.expectedAccount || input.expectedAccount !== `${origin}:${userId}`)
      throw new ShizukiTimePrismError('account_changed', '请重新核对当前账号及周期规则。')
    const id = positiveId(input.ruleId)
    if (input.ruleId !== undefined && !id) throw new ShizukiTimePrismError('invalid_id', '周期规则编号无效。')
    const current = id ? (await listRecurringRules()).find(rule => rule.ruleId === id) : null
    if (id && !current) throw new ShizukiTimePrismError('not_found', '周期规则不存在，请刷新。')
    const title = String(input.title || '').trim()
    if (!title || title.length > 200) throw new ShizukiTimePrismError('invalid_title', '待办标题需要 1 到 200 个字符。')
    const zone = String(input.timeZoneId || current?.timeZoneId || 'Asia/Shanghai')
    try { new Intl.DateTimeFormat('en', { timeZone: zone }).format() }
    catch { throw new ShizukiTimePrismError('invalid_timezone', '请选择有效时区。') }
    let cron
    try { cron = input.weekdays ? weeklyCron(input.weekdays, input.time) : String(input.cronExpr || current?.cronExpr || '').trim() }
    catch (error) { throw new ShizukiTimePrismError('invalid_schedule', error.message) }
    if (!cron || cron.length > 64 || cron.split(/\s+/).length !== 6)
      throw new ShizukiTimePrismError('invalid_schedule', '需要六段 Cron 表达式；已有高级规则可以保留原样。')
    const amount = Number(input.deadlineRemindValue ?? current?.deadlineRemindValue ?? 0)
    const unit = String(input.deadlineRemindUnit || current?.deadlineRemindUnit || 'MINUTE').toUpperCase()
    if (input.reminderEnabled === true && (!Number.isSafeInteger(amount) || amount < 0 || !['MINUTE', 'DAY'].includes(unit) || (amount === 0 && unit === 'DAY') || amount > (unit === 'DAY' ? 365 : 43200)))
      throw new ShizukiTimePrismError('invalid_reminder', '提前量需要为非负分钟或正整数天。')
    const rule = normalizeRecurringRule({ ...current, title, cronExpr: cron, timeZoneId: zone,
      startAt: recurringBoundary(input.startAt === undefined || input.startAt === '' ? current?.startAt || '' : input.startAt, zone),
      endAt: recurringBoundary(input.endAt === undefined ? current?.endAt || '' : input.endAt, zone),
      enabled: input.enabled ?? current?.enabled ?? true, reminderEnabled: input.reminderEnabled === true,
      deadlineRemindValue: input.reminderEnabled === true ? amount : null,
      deadlineRemindUnit: input.reminderEnabled === true ? unit : null })
    const signature = JSON.stringify([origin, userId, id, rule])
    let requestId = input.requestId
    // Reopening the UI after a timeout recovers the same pending create, never POSTs a twin.
    if (!id) {
      const pending = ruleJournal.pending(origin, userId).find(entry => ruleJournal.get(entry.requestId)?.signature === signature)
      if (pending) requestId = pending.requestId
    }
    const previous = !id ? ruleJournal.get(requestId) : null
    if (previous && previous.signature !== signature) throw new ShizukiTimePrismError('request_conflict', '请求已改变，请核对原规则。')
    // Match the site's default explicitly, and retain it across midnight retries.
    // The logical signature above excludes this generated default.
    if (!rule.startAt) rule.startAt = previous?.startAt || recurringStartOfDay(clock(), zone)
    if (rule.endAt && rule.endAt < rule.startAt)
      throw new ShizukiTimePrismError('invalid_schedule', '结束时间不能早于开始时间。')
    if (!id) rule.detail = requestMarker(requestId)
    const verify = saved => {
      if (!saved?.ruleId || Object.entries(rule).some(([key, value]) => key !== 'ruleId' && saved[key] !== value))
        throw new ShizukiTimePrismError('unconfirmed_rule', '服务器尚未确认完整周期规则，请刷新核对。')
      return saved
    }
    if (previous && previous.state !== 'rejected') {
      const matches = (await listRecurringRules()).filter(value => value.detail === rule.detail)
      if (matches.length !== 1) throw new ShizukiTimePrismError('write_uncertain', '周期规则结果不确定；仅核对原请求，没有重复创建。')
      const recovered = verify(matches[0])
      ruleJournal.put(requestId, { ...previous, state: 'confirmed', ruleId: recovered.ruleId })
      return recovered
    }
    if (!accessToken || clock() >= accessExpiresAt - 60_000) await renew()
    const entry = { signature, origin, userId, title, dueAt: cron, startAt: rule.startAt, state: 'uncertain' }
    if (!id) ruleJournal.put(requestId, entry)
    try {
      const response = normalizeRecurringRule(await authorized(id ? `${RULES_PATH}/${id}` : RULES_PATH,
        { method: id ? 'PUT' : 'POST', body: rulePayload(rule) }))
      if (id && response.ruleId !== id) throw new ShizukiTimePrismError('unconfirmed_rule', '服务器返回了不同规则，请刷新核对。')
      verify(response)
      const saved = verify((await listRecurringRules()).find(value => value.ruleId === response.ruleId))
      if (!id) ruleJournal.put(requestId, { ...entry, state: 'confirmed', ruleId: saved.ruleId })
      return saved
    } catch (error) {
      if ([400, 401, 403, 404, 405, 422, 429].includes(error.status)) {
        if (!id) ruleJournal.put(requestId, { ...entry, state: 'rejected' })
        throw new ShizukiTimePrismError('request_rejected', '服务器已拒绝本次周期规则写入，可以修改后重新确认。', error.status)
      }
      throw error
    }
  }

  async function createTodo(input) {
    if (input?.expectedAccount && input.expectedAccount !== `${origin}:${userId}`)
      throw new ShizukiTimePrismError('account_changed', 'Shizuki 账号已改变，请重新核对后确认。')
    if (input?.behavior && input.behavior !== 'reminder')
      throw new ShizukiTimePrismError('unsupported_action', '当前只允许创建提醒，未安排定时动作。')
    if (input?.timezone && input.timezone !== REMINDER_TIMEZONE)
      throw new ShizukiTimePrismError('unsupported_timezone', '请使用 Asia/Shanghai 时间。')
    if (!refreshToken)
      throw new ShizukiTimePrismError('not_connected', '请先在设置中连接 Shizuki 账号。')
    const title = String(input?.title || '').trim()
    if (!title || title.length > 200)
      throw new ShizukiTimePrismError('invalid_title', '待办标题需要 1 到 200 个字符。')
    const rawDue = String(input?.dueAt || '')
    const requestId = input?.requestId || randomUUID()
    const signature = JSON.stringify([origin, userId, title, rawDue])
    const existing = journal.get(requestId)
    const unresolvedTwin = journal.pending(origin, userId).find(entry => entry.state === 'uncertain'
      && entry.requestId !== requestId && entry.title === title && entry.dueAt === rawDue)
    if (unresolvedTwin)
      throw new ShizukiTimePrismError('pending_duplicate', '相同内容和时间已有未确认请求。请恢复并核对原请求，不能另建重复待办。')
    if (existing && existing.signature !== signature)
      throw new ShizukiTimePrismError('request_conflict', '请求内容或账号已改变，请先核对原请求，不要重复创建。')
    const verify = todo => {
      if (!todo.todoId || todo.title !== title || todo.done
        || (rawDue && (todo.dueAt !== `${rawDue}:00` || !todo.reminderEnabled || todo.timePrecision !== 'MINUTE'
          || todo.timingMode !== 'DEADLINE' || todo.deadlineRemindValue !== 0 || todo.deadlineRemindUnit !== 'MINUTE'))) {
        throw new ShizukiTimePrismError('unconfirmed_reminder', `服务器未确认完整提醒字段${todo.todoId ? `（待办编号 ${todo.todoId}）` : ''}。请在待办面板核对；没有确认创建成功。`)
      }
      return todo
    }
    if (existing?.state === 'confirmed') {
      const current = (await listTodos()).find(todo => todo.todoId === existing.todo?.todoId)
      if (!current) throw new ShizukiTimePrismError('request_consumed', '这个请求已创建过待办，记录可能已删除；不会重复创建。')
      return verify(current)
    }
    if (existing?.state === 'uncertain') {
      const matches = (await listTodos()).filter(todo => todo.detail === requestMarker(requestId))
      if (matches.length !== 1)
        throw new ShizukiTimePrismError('write_uncertain', '服务器写入结果仍不确定。已保留请求；本次仅核对记录，没有重复创建。请稍后重试核对或在 TimePrism 检查。')
      const todo = verify(matches[0])
      journal.put(requestId, { ...existing, state: 'confirmed', todo })
      return todo
    }
    const dueAt = normalizeLocalDueAt(rawDue, clock())
    const payload = {
      title,
      detail: requestMarker(requestId),
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
    // Refresh before recording dispatch so auth outages cannot leave a phantom POST.
    if (!accessToken || clock() >= accessExpiresAt - 60_000) await renew()
    const entry = { signature, origin, userId, title, dueAt: rawDue, state: 'uncertain' }
    journal.put(requestId, entry)
    try {
      const data = await authorized(TODOS_PATH, { method: 'POST', body: payload })
      const todo = verify(normalizeShizukiTodo(data))
      journal.put(requestId, { ...entry, state: 'confirmed', todo })
      return todo
    }
    catch (error) {
      // Only explicit rejecting 4xx responses permit POST again. Timeouts/5xx may have committed.
      if ([400, 401, 403, 404, 405, 422, 429].includes(error.status)) {
        journal.put(requestId, { ...entry, state: 'rejected' })
        throw new ShizukiTimePrismError('request_rejected', `${error.message}服务器已拒绝本次写入，可修改或重试。`, error.status)
      }
      throw error
    }
  }

  function pendingCreates() { return journal.pending(origin, userId) }

  async function updateTodo(id, input) {
    if (input?.expectedAccount && input.expectedAccount !== `${origin}:${userId}`)
      throw new ShizukiTimePrismError('account_changed', 'Shizuki 账号已改变，请重新选择待办。')
    const todoId = positiveId(id)
    if (!todoId) throw new ShizukiTimePrismError('invalid_id', '待办编号无效。')
    const current = (await listTodos()).find(todo => todo.todoId === todoId)
    if (!current) throw new ShizukiTimePrismError('not_found', '找不到该待办，请刷新。')
    if (current.recurringRuleId) throw new ShizukiTimePrismError('recurring_rule_required', '请在周期规则中修改提醒；此处只允许完成当前日期实例。')
    const cancellation = input?.cancel === true
    const title = cancellation ? current.title : String(input?.title || '').trim()
    if (!title || title.length > 200) throw new ShizukiTimePrismError('invalid_title', '待办标题需要 1 到 200 个字符。')
    const dueAt = cancellation ? current.dueAt : normalizeLocalDueAt(input?.dueAt, clock())
    if (!cancellation && !dueAt) throw new ShizukiTimePrismError('invalid_due_at', '修改提醒需要准确的未来时间。')
    const next = cancellation ? { ...current, reminderEnabled: false }
      : { ...current, title, dueAt, reminderEnabled: true, timePrecision: 'MINUTE', timingMode: 'DEADLINE', deadlineRemindValue: 0, deadlineRemindUnit: 'MINUTE' }
    const saved = normalizeShizukiTodo(await authorized(`${TODOS_PATH}/${todoId}`, { method: 'PUT', body: todoToShizukiPayload(next) }))
    if (saved.todoId !== todoId || saved.title !== title || saved.dueAt !== dueAt || saved.reminderEnabled !== next.reminderEnabled
      || (!cancellation && (saved.timePrecision !== 'MINUTE' || saved.timingMode !== 'DEADLINE' || saved.deadlineRemindValue !== 0 || saved.deadlineRemindUnit !== 'MINUTE')))
      throw new ShizukiTimePrismError('invalid_response', '服务器尚未确认修改。请刷新核对原记录后重试。')
    return { ...saved, recurringRule: current.recurringRule, timeZoneId: current.timeZoneId }
  }

  async function completeTodo(id, expectedAccount) {
    if (expectedAccount && expectedAccount !== `${origin}:${userId}`)
      throw new ShizukiTimePrismError('account_changed', 'Shizuki 账号已改变，请重新选择待办。')
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
    if (saved.todoId !== todoId || !saved.done || (current.recurringRuleId && (saved.recurringRuleId !== current.recurringRuleId
      || saved.recurringInstanceAt !== current.recurringInstanceAt || saved.dueAt !== current.dueAt)))
      throw new ShizukiTimePrismError('invalid_response', 'Shizuki 未确认待办已完成。')
    return { ...saved, recurringRule: current.recurringRule, timeZoneId: current.timeZoneId }
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

  return { initialize, status, pendingCreates, connect: serialized(connect), disconnect: serialized(disconnect),
    listRecurringRules: serialized(listRecurringRules), saveRecurringRule: serialized(saveRecurringRule),
    listTodos: serialized(listTodos), syncTodos: serialized(syncTodos), createTodo: serialized(createTodo), completeTodo: serialized(completeTodo), updateTodo: serialized(updateTodo) }
}
