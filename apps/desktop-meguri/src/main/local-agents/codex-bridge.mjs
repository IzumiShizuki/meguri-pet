/**
 * Codex work bridge over the official `codex app-server --stdio` JSONL RPC.
 *
 * Meguri is a read-mostly client: it lists the user's existing Codex tasks,
 * reads one turn's receipt, and can start a turn inside an already existing
 * thread after the user confirmed the task card. It never approves tools, never
 * answers an interactive question, never reads Codex credentials and never
 * retries an ambiguous send.
 */

import { spawn } from 'node:child_process'
import { readdirSync, statSync } from 'node:fs'
import { isAbsolute, join } from 'node:path'
import { StringDecoder } from 'node:string_decoder'

import { MAX_REPLY_LENGTH, MAX_TARGET_ID_LENGTH, isAbsoluteLocalPath, textValue } from './paths.mjs'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu
const MAX_FRAME_BYTES = 16 * 1024 * 1024
const DEFAULT_TIMEOUT_MS = 60000

export class CodexBridgeError extends Error {
  constructor(state, message) {
    super(message ?? state)
    this.name = 'CodexBridgeError'
    this.state = state
  }
}

function isThreadId(value) {
  return typeof value === 'string' && UUID.test(value)
}

function threadTitle(thread) {
  const name = textValue(thread?.name, 200)
  if (name)
    return name
  const preview = typeof thread?.preview === 'string' ? thread.preview : ''
  const singleLine = preview.split('\n').map(line => line.trim()).filter(Boolean)[0] ?? ''
  if (singleLine && [...singleLine].length <= 200)
    return singleLine
  return '未命名任务'
}

/** A thread the pet may offer as a target: user-started, in an existing local project. */
export function isForwardableThread(thread) {
  if (!thread || !isThreadId(thread.id))
    return false
  if (thread.ephemeral === true)
    return false
  if (thread.parentThreadId)
    return false
  if (thread.agentNickname || thread.agentRole)
    return false
  if (!['cli', 'vscode'].includes(thread.source))
    return false
  if (!isAbsoluteLocalPath(thread.cwd))
    return false
  return true
}

export function codexWorkState(thread) {
  const status = thread?.status?.type
  if (status === 'active')
    return 'active'
  if (status === 'systemError')
    return 'error'
  return 'idle'
}

export function createCodexBridge(options = {}) {
  const environment = options.environment ?? process.env
  const spawnImpl = options.spawnImpl ?? spawn
  const timeoutMs = Number.isFinite(options.timeoutMs) ? Math.max(1000, options.timeoutMs) : DEFAULT_TIMEOUT_MS
  const codexHome = options.codexHome
  const explicitExecutable = textValue(options.executable, 4096) || textValue(environment.PET_CODEX_EXECUTABLE, 4096)
  const installRoot = options.installRoot
  const pathDirectories = (options.pathVariable ?? environment.PATH ?? environment.Path ?? '').split(';').filter(Boolean)
  const statFile = options.statFile ?? ((path) => statSync(path))
  const readdir = options.readdir ?? ((path) => readdirSync(path, { withFileTypes: true }))

  let child
  let starting
  let closing = false
  let sequence = 0
  const pending = new Map()

  function findExecutable() {
    if (explicitExecutable) {
      if (!isAbsolute(explicitExecutable))
        return ''
      try {
        return statFile(explicitExecutable).isFile() ? explicitExecutable : ''
      }
      catch {
        return ''
      }
    }
    const candidates = []
    try {
      for (const entry of readdir(installRoot)) {
        if (!entry.isDirectory())
          continue
        const candidate = join(installRoot, entry.name, 'codex.exe')
        try {
          const info = statFile(candidate)
          if (info.isFile())
            candidates.push({ path: candidate, changed: info.mtimeMs ?? 0 })
        }
        catch {
          // Not every version directory ships the CLI binary.
        }
      }
    }
    catch {
      // Codex is optional; a missing install directory is a normal state.
    }
    candidates.sort((left, right) => right.changed - left.changed)
    if (candidates[0])
      return candidates[0].path
    for (const directory of pathDirectories) {
      const candidate = join(directory, 'codex.exe')
      try {
        if (statFile(candidate).isFile())
          return candidate
      }
      catch {
        // Keep looking.
      }
    }
    return ''
  }

  function fail() {
    for (const waiter of pending.values()) {
      clearTimeout(waiter.timer)
      waiter.reject(new CodexBridgeError('unavailable', 'Codex 连接已中断。'))
    }
    pending.clear()
    child = undefined
    starting = undefined
  }

  function write(message) {
    if (!child || !child.stdin.writable)
      throw new CodexBridgeError('unavailable', 'Codex 连接不可用。')
    child.stdin.write(`${JSON.stringify(message)}\n`)
  }

  function rpc(method, params) {
    const id = String(++sequence)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id)
        reject(new CodexBridgeError('unavailable', `Codex ${method} 超时。`))
      }, timeoutMs)
      pending.set(id, { resolve, reject, timer })
      try {
        write({ id, method, params })
      }
      catch (error) {
        clearTimeout(timer)
        pending.delete(id)
        reject(error)
      }
    })
  }

  function handleFrame(message) {
    // An external companion must never silently approve tools or answer questions.
    if (message.method && message.id !== undefined) {
      if (['item/commandExecution/requestApproval', 'item/fileChange/requestApproval'].includes(message.method)) {
        write({ id: message.id, result: { decision: 'decline' } })
        return
      }
      write({
        id: message.id,
        error: { code: -32601, message: '请在 Codex 中继续这个请求；桌宠没有审批界面。' },
      })
      return
    }
    const waiter = pending.get(String(message.id))
    if (!waiter)
      return
    pending.delete(String(message.id))
    clearTimeout(waiter.timer)
    if (message.error)
      waiter.reject(new CodexBridgeError('unavailable', 'Codex 拒绝了这次请求。'))
    else
      waiter.resolve(message.result)
  }

  function start() {
    if (closing)
      return Promise.reject(new CodexBridgeError('unavailable', 'Codex 连接已关闭。'))
    if (starting)
      return starting
    starting = (async () => {
      const executable = findExecutable()
      if (!executable)
        throw new CodexBridgeError('unavailable', '没有找到本机 Codex，请先安装并登录 Codex。')
      const launched = spawnImpl(executable, ['app-server', '--stdio'], {
        windowsHide: true,
        stdio: ['pipe', 'pipe', 'pipe'],
        env: { ...environment, CODEX_HOME: codexHome },
      })
      child = launched
      // Auth and plugin diagnostics never reach the pet log or the renderer.
      launched.stderr.resume()
      const failed = () => {
        if (child === launched)
          fail()
      }
      launched.on('error', failed)
      launched.on('exit', failed)
      launched.stdin.on('error', failed)
      const decoder = new StringDecoder('utf8')
      let buffer = ''
      launched.stdout.on('data', (chunk) => {
        if (child !== launched)
          return
        buffer += decoder.write(chunk)
        if (Buffer.byteLength(buffer) > MAX_FRAME_BYTES) {
          launched.kill()
          fail()
          return
        }
        let newline
        while ((newline = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, newline)
          buffer = buffer.slice(newline + 1)
          if (!line.trim())
            continue
          let message
          try {
            message = JSON.parse(line)
          }
          catch {
            launched.kill()
            fail()
            return
          }
          handleFrame(message)
        }
      })
      await rpc('initialize', {
        clientInfo: { name: 'meguri_desktop', title: 'Meguri Desktop', version: '0.1.0' },
      })
      write({ method: 'initialized' })
    })()
    return starting.catch((error) => {
      child?.kill()
      fail()
      throw error
    })
  }

  async function list(query = '', limit = 60) {
    await start()
    const boundedLimit = Number.isSafeInteger(limit) ? Math.min(Math.max(limit, 1), 200) : 60
    const searchTerm = textValue(query, 200)
    const result = await rpc('thread/list', {
      limit: boundedLimit,
      sortKey: 'updated_at',
      sortDirection: 'desc',
      archived: false,
      sourceKinds: ['cli', 'vscode'],
      ...(searchTerm ? { searchTerm } : {}),
    })
    const items = Array.isArray(result?.data) ? result.data : []
    // Fork lineage can list the same thread more than once; a target is offered once.
    const seen = new Set()
    const targets = []
    for (const thread of items) {
      if (!isForwardableThread(thread) || seen.has(thread.id))
        continue
      seen.add(thread.id)
      targets.push({
        targetId: textValue(thread.id, MAX_TARGET_ID_LENGTH),
        title: threadTitle(thread),
        projectPath: textValue(thread.cwd, 4096),
        state: codexWorkState(thread),
      })
    }
    return targets
  }

  async function discover(targetId) {
    if (!isThreadId(targetId))
      return { available: false, reason: 'invalid_target' }
    try {
      await start()
      const result = await rpc('thread/read', { threadId: targetId, includeTurns: false })
      const thread = result?.thread
      if (thread?.id !== targetId)
        return { available: false, reason: 'not_found' }
      if (thread.status?.type === 'active')
        return { available: false, reason: 'busy' }
      return { available: true, projectPath: textValue(thread.cwd, 4096), title: threadTitle(thread) }
    }
    catch (error) {
      return { available: false, reason: error instanceof CodexBridgeError ? error.state : 'unavailable' }
    }
  }

  /**
   * Starts exactly one turn in an existing thread. A lost response stays
   * unknown; the durable receipt already recorded the attempt, so Meguri never
   * repeats this write.
   */
  async function send(targetId, text, requestId) {
    if (!isThreadId(targetId) || !isThreadId(requestId) || !text.trim() || text.includes('\u0000'))
      throw new CodexBridgeError('invalid_target', '任务卡内容或目标无效。')
    await start()
    const resumed = await rpc('thread/resume', { threadId: targetId, excludeTurns: true })
    if (resumed?.thread?.id !== targetId)
      throw new CodexBridgeError('unavailable', 'Codex 无法恢复这个任务。')
    if (resumed.thread.status?.type === 'active')
      throw new CodexBridgeError('busy', '这个 Codex 任务正在运行，请等待它结束。')
    try {
      const result = await rpc('turn/start', {
        threadId: targetId,
        clientUserMessageId: requestId,
        input: [{ type: 'text', text, text_elements: [] }],
      })
      if (!isThreadId(result?.turn?.id ?? ''))
        throw new CodexBridgeError('unknown_delivery', '未确认 Codex 是否已接收。')
      return { turnId: result.turn.id }
    }
    catch (error) {
      if (error instanceof CodexBridgeError && error.state === 'available')
        throw error
      throw new CodexBridgeError('unknown_delivery', '未确认 Codex 是否已接收；不会自动重发。')
    }
  }

  async function receipt(targetId, turnId) {
    const unknown = { status: 'unknown', detail: '暂时无法核实这次任务的状态；不会自动重发。' }
    if (!isThreadId(targetId) || !isThreadId(turnId))
      return unknown
    try {
      await start()
      const result = await rpc('thread/read', { threadId: targetId, includeTurns: true })
      const thread = result?.thread
      if (thread?.id !== targetId)
        return unknown
      const turn = (thread.turns ?? []).find(item => item?.id === turnId)
      if (!turn)
        return unknown
      if (turn.status === 'completed') {
        const reply = (turn.items ?? [])
          .filter(item => item?.type === 'agentMessage' && typeof item.text === 'string')
          .map(item => item.text)
          .join('\n')
          .slice(0, MAX_REPLY_LENGTH)
        return { status: 'completed', result: reply, detail: 'Codex 任务已完成。' }
      }
      if (turn.status === 'failed')
        return { status: 'failed', detail: 'Codex 报告这次任务失败，请回到 Codex 查看。' }
      if (turn.status === 'interrupted')
        return { status: 'unknown', detail: '这次任务在 Codex 中被中断。' }
      return { status: 'working', detail: 'Codex 正在执行这次任务。' }
    }
    catch {
      return unknown
    }
  }

  /**
   * Reports whether this Codex install can accept work.
   *
   * `account/read` is the authoritative answer, but it can fail while Codex
   * refreshes account metadata (for example a workspace routing lookup). That
   * failure is not evidence that the install is unusable, so it falls back to
   * the local thread index, which needs no network.
   */
  async function compatible() {
    try {
      await start()
    }
    catch (error) {
      return { compatible: false, reason: error instanceof CodexBridgeError ? error.state : 'unavailable' }
    }
    try {
      const account = await rpc('account/read', { refreshToken: false })
      return { compatible: true, account: account?.account ? 'signed_in' : 'api_key' }
    }
    catch {
      try {
        await rpc('thread/list', { limit: 1, archived: false })
        return { compatible: true, account: 'unverified' }
      }
      catch {
        return { compatible: false, reason: 'unavailable' }
      }
    }
  }

  /** Cheap offline check used by the settings page before spawning app-server. */
  function installed() {
    return Boolean(findExecutable())
  }

  return {
    installed,
    compatible,
    list,
    discover,
    send,
    receipt,
    close() {
      closing = true
      const launched = child
      if (!launched)
        return Promise.resolve()
      return new Promise((resolve) => {
        const timer = setTimeout(() => {
          launched.kill()
          resolve()
        }, 3000)
        launched.once('exit', () => {
          clearTimeout(timer)
          resolve()
        })
        try {
          launched.stdin.end()
        }
        catch {
          launched.kill()
          clearTimeout(timer)
          resolve()
        }
      }).then(() => { fail() })
    },
  }
}
