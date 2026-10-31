import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { recurringTime } from '../shared/recurring-todos.mjs'

const BASE = '/api/v1/light-apps'
const UUID = /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/i
const REQUIRED = ['pure_snapshot', 'nullable_due', 'notes', 'optimistic_version', 'idempotent_writes', 'recurring_rules']
const TASK_FIELDS = ['project_id', 'title', 'detail', 'priority', 'done', 'due_at', 'show_on_calendar', 'time_precision', 'timing_mode', 'range_start_at', 'reminder_enabled', 'start_remind_value', 'start_remind_unit', 'deadline_remind_value', 'deadline_remind_unit', 'sort_num']
const RULE_FIELDS = ['project_id', 'title', 'detail', 'priority', 'cron_expr', 'time_zone_id', 'start_at', 'end_at', 'enabled', 'sort_num', 'overdue_policy', 'reminder_enabled', 'deadline_remind_value', 'deadline_remind_unit']
const PATHS = { task: 'todos', note: 'notes', rule: 'todo-recurring-rules' }
const IDS = { task: 'todoId', note: 'noteId', rule: 'ruleId' }
const NOTE_FIELDS = ['title', 'content', 'pinned', 'sort_num']
const id = value => Number.isSafeInteger(value) && value > 0
const version = value => Number.isSafeInteger(value) && value >= 0
const ready = caps => caps?.contract_version === 1 && REQUIRED.every(key => caps[key] === true)
const clone = value => structuredClone(value)

// Match the site's canonicalization; immutable persisted requests are never rewritten.
function canonicalReceipt(resource, body) {
  const expected = { ...body }
  if (resource !== 'note') expected.detail = typeof body.detail === 'string'
    ? body.detail.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '') || null : null
  if (resource === 'task') {
    if (body.timing_mode === 'DEADLINE') Object.assign(expected, { range_start_at: null, start_remind_value: null, start_remind_unit: null })
    if (body.time_precision === 'DAY') {
      if (body.due_at) expected.due_at = `${body.due_at.slice(0, 10)}T23:59:59`
      if (expected.range_start_at) expected.range_start_at = `${expected.range_start_at.slice(0, 10)}T00:00:00`
    }
  }
  return expected
}

// No token or password enters this account-scoped cache or immutable write journal.
export function createShizukiCenter({ filePath, request, account, listTodos, normalizeTodo, todoPayload, normalizeRule, rulePayload, failure, clock = Date.now }) {
  let saved = { version: 1, accounts: {} }
  let broken = false
  if (existsSync(filePath)) {
    try {
      const raw = readFileSync(filePath, 'utf8')
      if (Buffer.byteLength(raw) > 8 * 1024 * 1024) throw new Error('size')
      const parsed = JSON.parse(raw)
      if (parsed.version !== 1 || !parsed.accounts || Array.isArray(parsed.accounts)) throw new Error('shape')
      saved = parsed
    }
    catch { broken = true }
  }
  const fail = (code, message, status = 0) => { throw failure(code, message, status) }
  function key(expected) {
    if (broken) fail('center_storage_failed', '本地同步记录无法读取，请保留文件并检查存储；未派发写入。')
    const current = account()
    if (!current.connected || !id(current.userId)) fail('not_connected', '请先连接 Shizuki。')
    const value = `${current.origin}:${current.userId}`
    if (expected && expected !== value) fail('account_changed', '账号已改变，原草稿保留在原账号下。')
    return value
  }
  function state(k) { return clone(saved.accounts[k] || { pending: {}, snapshot: null }) }
  function persist(k, next) {
    const value = { ...saved, accounts: { ...saved.accounts, [k]: next } }
    const text = JSON.stringify(value)
    if (Buffer.byteLength(text) > 8 * 1024 * 1024) fail('center_storage_full', '本地同步记录已满，请先处理待同步草稿。')
    try {
      mkdirSync(dirname(filePath), { recursive: true })
      const temp = `${filePath}.${process.pid}.tmp`
      writeFileSync(temp, text, { encoding: 'utf8', mode: 0o600 })
      renameSync(temp, filePath)
      saved = value
    }
    catch { fail('center_storage_failed', '无法保存同步记录，未确认写入成功。') }
  }
  async function capabilities() {
    const caps = await request(`${BASE}/todo-center/capabilities`)
    if (!ready(caps)) fail('center_unsupported', '站点尚未提供完整的统一中心保护能力。')
    return caps
  }
  function pending(k) {
    return Object.entries(state(k).pending).filter(([, entry]) => entry.state !== 'confirmed')
      .map(([requestId, entry]) => ({ requestId, resource: entry.resource, method: entry.method,
        state: entry.state, error: entry.error || '', draft: clone(entry.body), targetId: entry.targetId }))
  }
  function normalizeNote(raw) {
    if (!raw || !id(raw.note_id) || !version(raw.version) || typeof raw.title !== 'string' || typeof raw.content !== 'string'
      || raw.title.length > 200 || raw.content.length > 20000 || typeof raw.pinned !== 'boolean')
      fail('invalid_response', '便签回执不完整，未确认成功。')
    return { noteId: raw.note_id, title: raw.title, content: raw.content, pinned: raw.pinned,
      sortNum: Number(raw.sort_num || 0), updatedAt: String(raw.updated_at || ''), version: raw.version }
  }
  async function snapshot() {
    const k = key()
    try {
      let caps
      try { caps = await capabilities() }
      catch (error) {
        if (![404, 405].includes(error.status) && error.code !== 'center_unsupported') throw error
        const todos = await listTodos()
        key(k)
        return { todos, notes: [], capabilities: null, ready: false, stale: false, pending: pending(k),
          error: '站点需要更新统一中心接口；当前仅可查看待办。' }
      }
      const todos = await listTodos()
      const rawNotes = await request(`${BASE}/notes`)
      if (!Array.isArray(rawNotes) || todos.some(todo => !version(todo.version)))
        fail('invalid_response', '站点未返回完整版本，已停止编辑。')
      const notes = rawNotes.map(normalizeNote)
      key(k)
      const next = state(k)
      next.snapshot = { todos, notes, capabilities: caps, savedAt: clock() }
      persist(k, next)
      return { ...next.snapshot, ready: true, stale: false, pending: pending(k), error: '' }
    }
    catch (error) {
      key(k)
      const cached = state(k).snapshot
      // Authentication/account errors never re-enable editing through a cached capability.
      if (!cached || [401, 403].includes(error.status) || ['not_connected', 'account_changed'].includes(error.code)) throw error
      return { ...cached, ready: ready(cached.capabilities), stale: true, pending: pending(k),
        error: '当前显示上次缓存；网络恢复后请刷新。写入只有服务器回执后才算已同步。' }
    }
  }
  function text(value, max, required = false) {
    if (typeof value !== 'string' || value.length > max || (required && !value.trim()))
      fail('invalid_input', `内容需要${required ? '1' : '0'}到${max}个字符。`)
    return required ? value.trim() : value
  }
  function taskBody(task) {
    const title = text(task.title, 200, true), detail = text(task.detail ?? '', 2000)
    if (!['LOW', 'MEDIUM', 'HIGH'].includes(task.priority)) fail('invalid_input', '请选择低、普通或紧急。')
    const dueAt = task.dueAt || '', zone = task.timeZoneId || 'Asia/Shanghai'
    if (dueAt && (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(dueAt) || !Number.isFinite(recurringTime(dueAt, zone))))
      fail('invalid_input', '截止时间无效，不能自动补充或移动时间。')
    if (!dueAt && task.reminderEnabled) fail('invalid_input', '无截止约定不能开启到期提醒。')
    const rangeStartAt = task.rangeStartAt || ''
    if (task.timingMode === 'RANGE' && (!dueAt || !rangeStartAt || !Number.isFinite(recurringTime(rangeStartAt, zone))
      || recurringTime(rangeStartAt, zone) > recurringTime(dueAt, zone))) fail('invalid_input', '范围开始和截止时间不完整。')
    const body = todoPayload({ projectId: null, done: false, showOnCalendar: Boolean(dueAt), timePrecision: 'MINUTE',
      timingMode: 'DEADLINE', rangeStartAt: '', startRemindValue: null, startRemindUnit: '',
      deadlineRemindValue: null, deadlineRemindUnit: '', sortNum: 0, ...task, title, detail,
      dueAt: dueAt && dueAt.length === 16 ? `${dueAt}:00` : dueAt, reminderEnabled: task.reminderEnabled === true })
    if (!task.reminderEnabled) Object.assign(body, { start_remind_value: null, start_remind_unit: null, deadline_remind_value: null, deadline_remind_unit: null })
    if (!dueAt) Object.assign(body, { due_at: null, range_start_at: null, timing_mode: 'DEADLINE', reminder_enabled: false,
      start_remind_value: null, start_remind_unit: null, deadline_remind_value: null, deadline_remind_unit: null })
    return Object.fromEntries(TASK_FIELDS.map(field => [field, body[field] ?? null]))
  }
  function validateEntry(entry, requestId) {
    const fields = entry.resource === 'task' ? TASK_FIELDS : entry.resource === 'note' ? NOTE_FIELDS : entry.resource === 'rule' ? RULE_FIELDS : null
    if (!UUID.test(requestId) || !fields || !['POST', 'PUT', 'DELETE'].includes(entry.method)
      || (entry.method !== 'POST' && (!id(entry.targetId) || !version(entry.body.expected_version)))
      || entry.body.request_id !== requestId
      || Object.keys(entry.body).some(k => ![...fields, 'request_id', 'expected_version'].includes(k)))
      fail('center_storage_failed', '同步记录无效，未派发请求。')
  }
  async function dispatch(k, requestId) {
    key(k)
    let next = state(k), entry = next.pending[requestId]
    if (!entry) fail('not_found', '找不到原同步请求。')
    validateEntry(entry, requestId)
    if (entry.state === 'confirmed') return { state: 'confirmed', item: clone(entry.item), resource: entry.resource }
    if (entry.state === 'conflict' || entry.state === 'rejected')
      fail(entry.state, '原请求已被拒绝；请刷新并核对后重新编辑，不能自动覆盖新版本。', entry.state === 'conflict' ? 409 : 422)
    try {
      await capabilities()
      key(k)
      const path = `${BASE}/${PATHS[entry.resource]}${entry.targetId ? `/${entry.targetId}` : ''}`
      const response = await request(entry.method === 'DELETE'
        ? `${path}?request_id=${requestId}&expected_version=${entry.body.expected_version}` : path,
      { method: entry.method, ...(entry.method === 'DELETE' ? {} : { body: entry.body }) })
      key(k)
      let item = null
      if (entry.method !== 'DELETE') {
        item = entry.resource === 'task' ? normalizeTodo(response) : entry.resource === 'rule' ? normalizeRule(response) : normalizeNote(response)
        const returnedId = item[IDS[entry.resource]]
        const rawId = response[entry.resource === 'task' ? 'todo_id' : entry.resource === 'rule' ? 'rule_id' : 'note_id']
        if (!id(rawId) || !id(returnedId) || !version(item.version) || (entry.targetId && returnedId !== entry.targetId)
          || (entry.method === 'PUT' && item.version < entry.body.expected_version + 1))
          fail('invalid_response', '服务器回执的记录或版本不一致，结果仍待核对。')
        const canonical = canonicalReceipt(entry.resource, entry.body)
        for (const field of entry.resource === 'task' ? TASK_FIELDS : entry.resource === 'rule' ? RULE_FIELDS : NOTE_FIELDS) {
          const actual = Object.hasOwn(response, field) ? response[field] : undefined, expected = canonical[field] ?? null
          if (actual !== expected) fail('invalid_response', '服务器未确认完整内容，保留原请求供核对。')
        }
      }
      if (entry.method === 'DELETE' && response != null) fail('invalid_response', '删除回执不完整，保留原请求供核对。')
      next = state(k)
      next.pending[requestId] = { ...entry, state: 'confirmed', item }
      if (next.snapshot && entry.resource !== 'rule') {
        const collection = entry.resource === 'task' ? 'todos' : 'notes'
        const itemId = entry.resource === 'task' ? 'todoId' : 'noteId'
        const existing = next.snapshot[collection].find(value => value[itemId] === (entry.targetId || item?.[itemId]))
        next.snapshot[collection] = next.snapshot[collection].filter(value => value[itemId] !== (entry.targetId || item?.[itemId]))
        if (item) next.snapshot[collection].push({ ...existing, ...item })
      }
      persist(k, next)
      return { state: 'confirmed', resource: entry.resource, item }
    }
    catch (error) {
      key(k)
      const rejected = [400, 401, 403, 404, 405, 409, 422].includes(error.status) || error.code === 'center_unsupported'
      next = state(k)
      next.pending[requestId] = { ...entry, state: error.status === 409 ? 'conflict' : rejected ? 'rejected' : 'pending',
        error: error.status === 409 ? '其他客户端已修改，原草稿已保留。' : rejected ? '服务器拒绝该请求，请核对后重新编辑。' : '结果待核对，重试将使用原请求编号。' }
      persist(k, next)
      if (rejected) throw error
      return { state: 'pending', resource: entry.resource, item: null }
    }
  }
  async function save(input, resource, remove = false) {
    const k = key(input.expectedAccount)
    if (!input.expectedAccount || !UUID.test(input.requestId || '')) fail('invalid_input', '账号与请求编号缺失。')
    const item = input.item || {}, targetId = item[IDS[resource]]
    if (targetId !== undefined && (!id(targetId) || !version(item.version))) fail('invalid_input', '记录编号或版本无效，请刷新。')
    if (remove && !targetId) fail('invalid_input', '请选择要删除的记录。')
    const body = remove ? {} : resource === 'task' ? taskBody(item) : resource === 'rule' ? Object.fromEntries(RULE_FIELDS.map(field => [field, rulePayload(item)[field] ?? null])) : {
      title: text(item.title, 200, true), content: text(item.content ?? '', 20000), pinned: item.pinned === true, sort_num: item.sortNum ?? 0 }
    Object.assign(body, { request_id: input.requestId, ...(targetId ? { expected_version: item.version } : {}) })
    const entry = { resource, targetId, method: remove ? 'DELETE' : targetId ? 'PUT' : 'POST', body, state: 'pending' }
    validateEntry(entry, input.requestId)
    let next = state(k)
    if (!next.pending[input.requestId] && !ready(next.snapshot?.capabilities)) {
      await capabilities()
      key(k)
      // Another save may have reserved its journal entry while capability I/O was pending.
      next = state(k)
    }
    const old = next.pending[input.requestId]
    const signature = JSON.stringify([resource, targetId || null, entry.method, body])
    if (old && old.signature !== signature) fail('request_conflict', '原请求内容已固定，不能改变内容后重用编号。')
    if (!old) {
      const withoutIdentity = value => Object.fromEntries(Object.entries(value).filter(([name]) => name !== 'request_id'))
      if (entry.method === 'POST' && Object.values(next.pending).some(prior => prior.state === 'pending'
        && prior.resource === resource && prior.method === 'POST'
        && JSON.stringify(withoutIdentity(prior.body)) === JSON.stringify(withoutIdentity(body))))
        fail('pending_duplicate', '相同内容已有待核对请求，请使用原请求重试，不要另建重复记录。')
      if (Object.keys(next.pending).length >= 2000) fail('center_storage_full', '同步记录已满，请保留并处理原请求。')
      next.pending[input.requestId] = { ...entry, signature }
      persist(k, next)
    }
    return dispatch(k, input.requestId)
  }
  return { snapshot, saveTask: input => save(input, 'task'), saveRule: input => save(input, 'rule'), saveNote: input => save(input, 'note'),
    deleteNote: input => save(input, 'note', true), retry: (requestId, expectedAccount) => dispatch(key(expectedAccount), requestId) }
}
