/**
 * DeepSeek Harness (dsh) work bridge.
 *
 * Meguri talks to a *local* dsh Host over loopback HTTP. The Host owns model
 * credentials, sandboxing, approvals and execution; Meguri only prepares a
 * work session, queues one already-confirmed prompt, and reads receipts from
 * the Host's own event journal.
 *
 * The receipt reader is deliberately pessimistic: any missing, reordered or
 * ambiguous field yields `unknown` rather than a guessed `completed`.
 */

import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'

import { MAX_REPLY_LENGTH } from './paths.mjs'

export const WORK_PRESET_ID = 'meguri-work-v1'
export const NATIVE_ID = /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/u
const MAX_RESPONSE_BYTES = 4 * 1024 * 1024
const MAX_PAGES = 16
const MAX_MESSAGES_PER_PAGE = 6
const DEFAULT_TIMEOUT_MS = 8000
const APPROVAL_TOOL_WHITELIST = new Set(['bash', 'read_file', 'write_file', 'edit_file', 'apply_patch', 'pwsh'])
const TERMINAL_FAILURES = new Set(['error', 'aborted', 'blocked', 'max-tokens', 'interrupted'])

export class HarnessBridgeError extends Error {
  constructor(state, message) {
    super(message ?? state)
    this.name = 'HarnessBridgeError'
    this.state = state
  }
}

/** Extracts the loopback launcher URL a dsh `web` run prints. */
export function parseHarnessLauncherUrl(text) {
  if (typeof text !== 'string' || !text)
    return ''
  const match = /dsh web:\s+(http:\/\/127\.0\.0\.1:\d+\/\?token=[^\s]+)/u.exec(text)
  return match ? match[1] : ''
}

/** Reads the launcher line from the Harness web log. Credential text never leaves this module. */
export function harnessLauncherUrl(logPath, options = {}) {
  const readFile = options.readFile ?? ((path) => readFileSync(path, { encoding: 'utf8' }))
  let value
  try {
    value = String(readFile(logPath)).slice(0, 65536)
  }
  catch {
    return ''
  }
  return parseHarnessLauncherUrl(value)
}

function launchEndpoint(rawUrl) {
  let url
  try {
    url = new URL(rawUrl)
  }
  catch {
    throw new HarnessBridgeError('unavailable', 'DeepSeek Harness 启动地址不可用。')
  }
  if (url.protocol !== 'http:'
    || url.hostname !== '127.0.0.1'
    || url.username
    || url.password
    || url.pathname !== '/'
    || url.hash
    || url.searchParams.getAll('token').length !== 1
    || !url.searchParams.get('token')
    || [...url.searchParams.keys()].some(key => key !== 'token'))
    throw new HarnessBridgeError('authentication_required', 'DeepSeek Harness 启动地址不是受信任的本地地址。')
  return url
}

/**
 * Folds the Host's own event sequence into one receipt for one request.
 *
 * Every event must carry a strictly contiguous sequence number, the request
 * must bind to exactly one turn, and approvals must be balanced. Anything else
 * is `unknown`.
 */
export function foldHarnessReceipt(events, requestId, options = {}) {
  const throughSeq = options.throughSeq
  const running = options.running
  const queued = options.queued === true
  const unknown = { status: 'unknown', detail: '暂时无法核实这次任务的状态；不会自动重发。' }
  if (!Array.isArray(events) || !events.length)
    return queued ? { status: 'working', detail: '请求已在 DeepSeek Harness 中排队。' } : unknown
  if (!Number.isSafeInteger(throughSeq) || throughSeq < 0)
    return unknown

  const records = []
  for (let index = 0; index < events.length; index += 1) {
    const event = events[index]
    if (!event || !Number.isSafeInteger(event.seq) || event.seq < 0 || event.seq > throughSeq)
      return unknown
    if (index > 0 && event.seq !== events[index - 1].seq + 1)
      return unknown
    const data = event.data
    const record = { seq: event.seq, type: event.type }
    if (Number.isSafeInteger(data?.turn) && data.turn >= 0)
      record.turn = data.turn
    if (event.type === 'user/message' && data?.source?.kind === 'user' && typeof data.source.rpcId === 'string')
      record.rpcId = data.source.rpcId
    if (event.type === 'turn/end') {
      if (typeof data?.reason?.kind !== 'string')
        return unknown
      record.reason = data.reason.kind
    }
    if (['approval/asked', 'approval/decided'].includes(event.type) && typeof data?.id === 'string')
      record.approvalId = data.id
    if (event.type === 'approval/asked' && APPROVAL_TOOL_WHITELIST.has(data?.toolName))
      record.approvalTool = data.toolName
    if (event.type === 'assistant/message' && !data?.interrupted && Array.isArray(data?.message?.content)) {
      record.text = data.message.content
        .filter(part => part?.type === 'text' && typeof part.text === 'string')
        .map(part => part.text)
        .join('')
        .slice(0, MAX_REPLY_LENGTH)
    }
    records.push(record)
  }
  if (records.at(-1).seq !== throughSeq)
    return unknown

  let currentTurn
  let boundTurn
  let text
  let reason
  let contaminated = false
  const pending = new Map()
  const turnsWithForeignInput = new Set()
  records.sort((left, right) => left.seq - right.seq)
  for (const event of records) {
    if (event.type === 'turn/start') {
      currentTurn = event.turn
      continue
    }
    if (event.type === 'user/message' && currentTurn !== undefined && event.rpcId) {
      if (event.rpcId === requestId) {
        if (boundTurn !== undefined)
          contaminated = true
        boundTurn = currentTurn
      }
      else {
        turnsWithForeignInput.add(currentTurn)
      }
      continue
    }
    if (boundTurn !== undefined && currentTurn === boundTurn) {
      if (event.type === 'approval/asked') {
        if (!event.approvalId || pending.has(event.approvalId))
          contaminated = true
        else
          pending.set(event.approvalId, event.approvalTool)
      }
      if (event.type === 'approval/decided') {
        if (!event.approvalId || !pending.delete(event.approvalId))
          contaminated = true
      }
      if (event.type === 'assistant/message' && event.turn === boundTurn && event.text !== undefined)
        text = event.text
      if (event.type === 'turn/end' && event.turn === boundTurn)
        reason = event.reason
    }
    if (event.type === 'turn/end' && event.turn === currentTurn)
      currentTurn = undefined
  }

  if (boundTurn === undefined)
    return queued ? { status: 'working', detail: '请求已在 DeepSeek Harness 中排队。' } : unknown
  if (contaminated || turnsWithForeignInput.has(boundTurn) || queued)
    return unknown
  if (reason === 'completed') {
    if (pending.size)
      return unknown
    return { status: 'completed', ...(text ? { result: text } : {}), detail: 'DeepSeek Harness 任务已完成。' }
  }
  if (reason && TERMINAL_FAILURES.has(reason))
    return { status: 'failed', detail: 'DeepSeek Harness 未能完成这次任务，请在 Harness 中查看。' }
  if (reason || running === false || currentTurn !== boundTurn)
    return unknown
  if (pending.size) {
    return {
      status: 'approval',
      detail: '任务正在等待 Harness 原生批准，请在 DeepSeek Harness 中处理。',
      approvalTools: [...new Set([...pending.values()].filter(Boolean))],
    }
  }
  return { status: 'working', detail: 'DeepSeek Harness 正在执行这次任务。' }
}

export function createHarnessBridge(options = {}) {
  const fetchImpl = options.fetchImpl ?? globalThis.fetch
  const launchUrl = options.launchUrl
  const timeoutMs = Number.isFinite(options.timeoutMs) ? Math.max(500, options.timeoutMs) : DEFAULT_TIMEOUT_MS
  const now = options.now ?? (() => Date.now())

  if (typeof fetchImpl !== 'function')
    throw new HarnessBridgeError('unavailable', '当前运行时不支持连接 DeepSeek Harness。')

  async function endpoint() {
    const raw = typeof launchUrl === 'function' ? await launchUrl() : launchUrl
    if (!raw)
      throw new HarnessBridgeError('unavailable', 'DeepSeek Harness 尚未启动。')
    return launchEndpoint(raw)
  }

  async function call(method, args, budgetMs = timeoutMs) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), budgetMs)
    try {
      // The launcher URL is a credential: never follow redirects, never resolve it remotely.
      const url = await endpoint()
      const exchange = await fetchImpl(url, { redirect: 'manual', signal: controller.signal })
      const cookie = exchange.headers.get('set-cookie')?.split(';')[0]
      await exchange.body?.cancel()
      if (exchange.status !== 303
        || exchange.headers.get('location') !== '/'
        || !cookie
        || !/^[A-Za-z0-9_-]+=[A-Za-z0-9_.-]+$/u.test(cookie))
        throw new HarnessBridgeError('authentication_required', 'DeepSeek Harness 浏览器会话未能建立。')

      const rpcId = randomUUID()
      const response = await fetchImpl(`${url.origin}/api/${method}`, {
        method: 'POST',
        redirect: 'manual',
        signal: controller.signal,
        headers: { Cookie: cookie, Origin: url.origin, 'Content-Type': 'application/json' },
        body: JSON.stringify({ type: 'client-request', rpcId, method, payload: { args } }),
      })
      if (response.status === 401 || response.status === 403) {
        await response.body?.cancel()
        throw new HarnessBridgeError('authentication_required', 'DeepSeek Harness 需要重新登录。')
      }
      if (!response.ok) {
        await response.body?.cancel()
        throw new HarnessBridgeError('unavailable', 'DeepSeek Harness 暂时不可用。')
      }
      const reader = response.body?.getReader()
      if (!reader)
        throw new HarnessBridgeError('incompatible', 'DeepSeek Harness 返回了无法读取的响应。')
      const chunks = []
      let bytes = 0
      for (;;) {
        const chunk = await reader.read()
        if (chunk.done)
          break
        bytes += chunk.value.byteLength
        if (bytes > MAX_RESPONSE_BYTES) {
          await reader.cancel()
          throw new HarnessBridgeError('incompatible', 'DeepSeek Harness 响应过大。')
        }
        chunks.push(chunk.value)
      }
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      if (body?.type !== 'server-response' || body.rpcId !== rpcId || body.result?.ok !== true)
        throw new HarnessBridgeError('incompatible', 'DeepSeek Harness 返回了不兼容的响应。')
      return body.result.value
    }
    catch (error) {
      if (error instanceof HarnessBridgeError)
        throw error
      throw new HarnessBridgeError('unavailable', '无法连接本机 DeepSeek Harness。')
    }
    finally {
      clearTimeout(timer)
    }
  }

  async function probe() {
    try {
      await call('session/list', { _request: {} })
      return { state: 'ready' }
    }
    catch (error) {
      return { state: error instanceof HarnessBridgeError ? error.state : 'unavailable' }
    }
  }

  /**
   * Creates the dedicated work session. The deterministic title keeps the Host
   * from spending a model call to generate one.
   */
  async function createWorkSession(sessionId, cwd) {
    if (!NATIVE_ID.test(sessionId) || !cwd || /[\0\r\n]/u.test(cwd))
      throw new HarnessBridgeError('incompatible', '工作会话参数无效。')
    const created = await call('session/create', {
      request: { sessionId, agentPreset: WORK_PRESET_ID, cwd },
    })
    if (created?.sessionId !== sessionId || created.agentPreset !== WORK_PRESET_ID)
      throw new HarnessBridgeError('incompatible', 'DeepSeek Harness 拒绝了工作预设。')
    const title = `Meguri work ${sessionId.slice(-8)}`
    const renamed = await call('session/rename', { request: { sessionId, title } })
    if (renamed?.title !== title)
      throw new HarnessBridgeError('incompatible', 'DeepSeek Harness 未能命名工作会话。')
    return { sessionId, title }
  }

  /** Queues exactly one already-confirmed prompt. No rewriting, no retry. */
  async function submitWork(sessionId, requestId, text) {
    if (!NATIVE_ID.test(sessionId) || !NATIVE_ID.test(requestId) || !text.trim())
      throw new HarnessBridgeError('incompatible', '转交参数无效。')
    const value = await call('session/prompt', {
      request: { sessionId, requestId, mode: 'queue', content: [{ type: 'text', text }] },
    })
    if (value?.accepted !== true)
      throw new HarnessBridgeError('incompatible', 'DeepSeek Harness 未接受这次任务。')
    return { accepted: true }
  }

  async function hostUrl() {
    return `${(await endpoint()).origin}/`
  }

  async function receipt(sessionId, requestId) {
    const unknown = { status: 'unknown', detail: '暂时无法核实这次任务的状态；不会自动重发。' }
    if (!NATIVE_ID.test(sessionId) || !NATIVE_ID.test(requestId))
      return unknown
    const deadline = now() + timeoutMs
    try {
      const list = await call('session/list', { _request: {} })
      const matches = (list?.items ?? []).filter(row => row?.sessionId === sessionId)
      if (matches.length !== 1)
        return unknown
      const row = matches[0]
      const throughSeq = row.projections?.asOfSeq
      if (typeof row.running !== 'boolean'
        || !Number.isSafeInteger(throughSeq)
        || throughSeq < 0
        || row.origin === 'subagent'
        || row.parentSessionId
        || row.projections?.values?.agentPreset !== WORK_PRESET_ID)
        return unknown
      const inbox = row.projections?.values?.inbox
      const queued = ['next-turn', 'next-step'].some(key => Array.isArray(inbox?.[key])
        && inbox[key].some(message => message?.source?.kind === 'user' && message.source.rpcId === requestId))

      const events = []
      let beforeSeq
      for (let pageIndex = 0; pageIndex < MAX_PAGES; pageIndex += 1) {
        const remaining = deadline - now()
        if (remaining <= 0)
          return unknown
        const page = await call('session/page', {
          request: {
            address: { kind: 'session', sessionId },
            throughSeq,
            maxMessages: MAX_MESSAGES_PER_PAGE,
            ...(beforeSeq === undefined ? {} : { beforeSeq }),
          },
        }, Math.max(1, remaining))
        const records = Array.isArray(page?.records) ? page.records : undefined
        if (!records || typeof page.hasMore !== 'boolean')
          return unknown
        const batch = records.filter(entry => entry?.type === 'event').map(entry => entry.event)
        if (!batch.length)
          return unknown
        // `hasMore` keeps the page list contiguous; foldHarnessReceipt re-checks sequence continuity.
        events.unshift(...batch)
        if (!page.hasMore)
          break
        beforeSeq = batch[0]?.seq
      }
      return foldHarnessReceipt(events, requestId, { throughSeq, running: row.running, queued })
    }
    catch {
      return unknown
    }
  }

  return { probe, createWorkSession, submitWork, receipt, hostUrl }
}
