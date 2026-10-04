/**
 * Task-card forwarding boundary.
 *
 * The companion turns a request into a *card*. Nothing runs until the user
 * confirms that exact card version, and a confirmed card is dispatched at most
 * once. This module owns that boundary: validation, the readiness gate, the
 * atomic claim, the single send, and read-only receipt observation.
 *
 * Read paths (`snapshot`, `targets`, `projects`, `refresh`, `dismiss`) never
 * write to a work agent. Only `confirm` may, and only once per card.
 */

import { randomUUID } from 'node:crypto'

import {
  FORWARD_TARGET_LIMIT,
  MAX_TASK_TEXT_LENGTH,
  canonicalPath,
  isAbsoluteLocalPath,
  samePath,
  textValue,
} from './paths.mjs'
import { publicReceipt, validPlan, validTaskText } from './receipts.mjs'

export class ForwardingError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'ForwardingError'
    this.code = code
  }
}

function fail(code, message) {
  throw new ForwardingError(code, message)
}

function sameVersion(record, expectedVersion) {
  if (!Number.isSafeInteger(expectedVersion) || record.version !== expectedVersion)
    fail('version_conflict', '这张任务卡已经变化，请刷新后再确认。')
}

function lastPathSegment(path) {
  return path.split(/[\\/]/u).filter(Boolean).pop() ?? path
}

/**
 * Deterministic, case-normalised project key.
 *
 * It only keys a metadata list, so a lower-cased path is sufficient and avoids
 * touching the filesystem from the renderer-facing discovery path.
 */
function stableRootKey(rootPath) {
  return canonicalPath(rootPath)
}

export function createWorkForwarding(options) {
  const { receipts, codex, harness } = options
  const discoverProjects = options.discoverProjects ?? (async () => [])
  const workspaceRoot = options.workspaceRoot
  const now = options.now ?? (() => Date.now())
  // Probing an executor can spawn a process or make a loopback round trip, so
  // the polling loop reuses a recent answer instead of re-probing every tick.
  const connectionTtlMs = Number.isFinite(options.connectionTtlMs) ? Math.max(1000, options.connectionTtlMs) : 30000
  let connectionCache

  /** Normalises one card and re-verifies its target against the executor itself. */
  async function validateInput(input) {
    const raw = input && typeof input === 'object' ? input : {}
    const text = validTaskText(raw.text)
    if (!text)
      fail('invalid_request', `任务内容不能为空，且不能超过 ${MAX_TASK_TEXT_LENGTH} 个字符。`)
    const executor = raw.executor === undefined ? 'codex' : raw.executor
    if (!['codex', 'harness'].includes(executor))
      fail('invalid_request', '未知的执行者。')

    let plan
    if (raw.plan !== undefined && raw.plan !== null) {
      plan = validPlan(raw.plan)
      if (!plan)
        fail('invalid_request', '任务卡标题无效。')
    }

    let target
    let project
    if (executor === 'codex') {
      const targetId = textValue(raw.targetId, 200)
      if (!targetId)
        fail('invalid_request', '请选择一个 Codex 任务。')
      const discovery = await codex.discover(targetId)
      if (!discovery?.available) {
        fail('not_found', discovery?.reason === 'busy'
          ? '这个 Codex 任务正在运行，请等待它结束。'
          : '目标不在真实的 Codex 任务目录中，请重新整理任务卡。')
      }
      target = { targetId, title: discovery.title, projectPath: discovery.projectPath }
      if (raw.projectPath && !samePath(raw.projectPath, target.projectPath))
        fail('invalid_request', '所选项目与目标任务目录不一致，请修改任务卡。')
      project = isAbsoluteLocalPath(target.projectPath) ? { rootPath: target.projectPath } : undefined
    }
    else {
      if (raw.targetId)
        fail('invalid_request', 'DeepSeek Harness 不接受 Codex 任务目标。')
      const projectPath = textValue(raw.projectPath, 4096)
      if (projectPath && !isAbsoluteLocalPath(projectPath))
        fail('invalid_request', '项目目录必须是本机绝对路径。')
      project = projectPath ? { rootPath: projectPath } : undefined
    }

    return { text, executor, target, project, plan }
  }

  async function executorReady(record) {
    if (record.executor === 'codex') {
      const compatibility = await codex.compatible()
      if (!compatibility.compatible)
        return { ready: false, detail: '本机 Codex 当前不可用，请先启动并登录 Codex。' }
      const discovery = await codex.discover(record.target.targetId)
      if (!discovery.available)
        return { ready: false, detail: '选定的 Codex 任务当前不可恢复。' }
      return { ready: true }
    }
    const probe = await harness.probe()
    if (probe.state === 'ready')
      return { ready: true }
    if (probe.state === 'authentication_required')
      return { ready: false, detail: 'DeepSeek Harness 需要重新登录。' }
    return { ready: false, detail: '本机 DeepSeek Harness 尚未启动。' }
  }

  function current(id) {
    const record = receipts.get(id)
    if (!record)
      fail('not_found', '找不到这张任务卡。')
    return record
  }

  async function targets(query = '') {
    const searchTerm = textValue(query, 200)
    try {
      const items = await codex.list(searchTerm, 60)
      return { limit: FORWARD_TARGET_LIMIT, items }
    }
    catch {
      return { limit: FORWARD_TARGET_LIMIT, items: [] }
    }
  }

  /** Read-only metadata only: indexed projects plus verified Codex task directories. */
  async function projects() {
    const roots = new Map()
    let indexed = []
    try {
      indexed = await discoverProjects()
    }
    catch {
      indexed = []
    }
    for (const entry of Array.isArray(indexed) ? indexed : []) {
      const rootPath = textValue(entry?.rootPath, 4096)
      if (!isAbsoluteLocalPath(rootPath))
        continue
      roots.set(stableRootKey(rootPath), {
        id: textValue(entry.id, 200) || rootPath,
        name: textValue(entry.name, 200) || lastPathSegment(rootPath),
        rootPath,
        source: 'index',
      })
    }
    const discovered = await targets('')
    for (const target of discovered.items) {
      const rootPath = target.projectPath
      if (!isAbsoluteLocalPath(rootPath) || roots.has(stableRootKey(rootPath)))
        continue
      roots.set(stableRootKey(rootPath), {
        id: `directory-${stableRootKey(rootPath)}`,
        name: lastPathSegment(rootPath),
        rootPath,
        source: 'task_directory',
      })
    }
    return [...roots.values()]
  }

  /** Cached executor readiness for the read-only snapshot; `confirm` re-probes. */
  async function connectionState() {
    if (connectionCache && now() - connectionCache.at < connectionTtlMs)
      return connectionCache.value
    const [codexState, harnessState] = await Promise.all([
      codex.compatible().catch(() => ({ compatible: false, reason: 'unavailable' })),
      harness.probe().catch(() => ({ state: 'unavailable' })),
    ])
    const value = {
      codex: codexState.compatible ? 'compatible' : codexState.reason ?? 'unavailable',
      harness: harnessState.state,
      executionAvailable: Boolean(codexState.compatible) || harnessState.state === 'ready',
    }
    connectionCache = { at: now(), value }
    return value
  }

  return {
    /** Read-only view for the task card and the settings page. */
    async snapshot() {
      const connection = await connectionState()
      return {
        requests: receipts.list().map(record => publicReceipt(record)),
        draft: receipts.draft() ?? null,
        connection: { codex: connection.codex, harness: connection.harness },
        executionAvailable: connection.executionAvailable,
      }
    },

    targets,
    projects,

    /** Freezes the card. No work agent has been contacted. */
    async prepare(input) {
      const frozen = await validateInput(input)
      return publicReceipt(receipts.create(frozen))
    },

    /**
     * The only path that may contact a work agent.
     *
     * The claim is committed before the send, so a crash or a second confirm can
     * never produce a second send.
     */
    async confirm(id, expectedVersion) {
      const record = current(id)
      if (record.phase !== 'awaiting_confirmation')
        return publicReceipt(record)
      sameVersion(record, expectedVersion)
      const frozen = await validateInput({
        text: record.text,
        executor: record.executor,
        targetId: record.target?.targetId,
        projectPath: record.project?.rootPath,
        plan: record.plan,
      })
      const ready = await executorReady(record)
      if (!ready.ready)
        fail('unavailable', ready.detail)

      let claimed = false
      const claimedRecord = receipts.mutate(id, (draft) => {
        if (draft.phase !== 'awaiting_confirmation')
          return false
        if (draft.version !== expectedVersion)
          return fail('version_conflict', '这张任务卡已经变化，请刷新后再确认。')
        draft.phase = 'forwarding'
        draft.confirmedAt = new Date().toISOString()
        if (draft.executor === 'harness')
          draft.harnessSessionId = `meguri-work-${randomUUID().replaceAll('-', '').slice(0, 24)}`
        claimed = true
        return true
      })
      if (!claimed)
        return publicReceipt(claimedRecord)

      if (claimedRecord.executor === 'codex') {
        try {
          const sent = await codex.send(claimedRecord.target.targetId, frozen.text, claimedRecord.requestId)
          return publicReceipt(receipts.mutate(id, (draft) => {
            draft.phase = 'accepted'
            draft.appTurnId = sent.turnId
            draft.nativeStatus = 'working'
            draft.detail = 'Codex 已接收这次任务。'
            return true
          }))
        }
        catch (error) {
          const lost = error?.state === 'unknown_delivery'
          return publicReceipt(receipts.mutate(id, (draft) => {
            draft.phase = lost ? 'unknown' : 'unavailable'
            draft.nativeStatus = 'unknown'
            draft.detail = lost
              ? '尚未确认 Codex 是否接收；不会自动重复发送。'
              : error?.message ?? 'Codex 拒绝了这次转交，任务没有发出。'
            return true
          }))
        }
      }

      const workspace = claimedRecord.project?.rootPath
        ?? (isAbsoluteLocalPath(workspaceRoot) ? workspaceRoot : '')
      if (!workspace) {
        return publicReceipt(receipts.mutate(id, (draft) => {
          draft.phase = 'unavailable'
          draft.nativeStatus = 'unknown'
          draft.detail = 'DeepSeek Harness 需要先选择一个本地项目目录；任务没有发出。'
          return true
        }))
      }

      // A failure here means no prompt was ever queued, so the card can be
      // reported as not-sent instead of unknown.
      try {
        await harness.createWorkSession(claimedRecord.harnessSessionId, workspace)
      }
      catch (error) {
        return publicReceipt(receipts.mutate(id, (draft) => {
          draft.phase = 'unavailable'
          draft.nativeStatus = 'unknown'
          draft.detail = `${error?.message ?? 'DeepSeek Harness 工作会话创建失败'}任务没有发出。`
          return true
        }))
      }

      // Past this point the prompt may have been queued, so an ambiguous
      // response stays unknown and is never resent.
      try {
        await harness.submitWork(claimedRecord.harnessSessionId, claimedRecord.requestId, frozen.text)
        return publicReceipt(receipts.mutate(id, (draft) => {
          draft.phase = 'accepted'
          draft.nativeStatus = 'working'
          draft.detail = 'DeepSeek Harness 已接收这次任务。'
          return true
        }))
      }
      catch {
        return publicReceipt(receipts.mutate(id, (draft) => {
          draft.phase = 'unknown'
          draft.nativeStatus = 'unknown'
          draft.detail = '尚未确认 Harness 是否接收；不会自动重复发送。'
          return true
        }))
      }
    },

    /** Read-only observation. A slower unknown observation never downgrades a completed card. */
    async refresh(id) {
      const record = current(id)
      if (['completed', 'unavailable'].includes(record.phase))
        return publicReceipt(record)
      if (record.phase === 'awaiting_confirmation' || !record.confirmedAt)
        return publicReceipt(record)

      const observation = record.executor === 'codex'
        ? (record.appTurnId
            ? await codex.receipt(record.target.targetId, record.appTurnId)
            : { status: 'unknown', detail: '尚未取得 Codex 的任务编号。' })
        : await harness.receipt(record.harnessSessionId, record.requestId)

      return publicReceipt(receipts.mutate(id, (draft) => {
        if (['completed', 'unavailable'].includes(draft.phase))
          return false
        if (observation.status === 'completed') {
          draft.phase = 'completed'
          draft.nativeStatus = 'completed'
          draft.result = observation.result ?? ''
          draft.nativeSessionUrl = observation.sessionUrl
          draft.detail = observation.detail
          return true
        }
        if (observation.status === 'failed') {
          draft.phase = 'unavailable'
          draft.nativeStatus = 'failed'
          draft.detail = observation.detail
          return true
        }
        if (observation.status === 'approval') {
          draft.nativeStatus = 'approval'
          draft.nativeApprovalTools = (observation.approvalTools ?? []).slice(0, 8)
          if (draft.nativeSessionUrl === observation.sessionUrl && draft.detail === observation.detail)
            return false
          draft.nativeSessionUrl = observation.sessionUrl
          draft.detail = observation.detail
          return true
        }
        if (observation.status === 'working') {
          if (draft.nativeStatus === 'working' && draft.detail === observation.detail)
            return false
          draft.nativeStatus = 'working'
          draft.detail = observation.detail
          return true
        }
        if (draft.phase === 'unknown' && draft.nativeStatus === 'unknown')
          return false
        draft.phase = 'unknown'
        draft.nativeStatus = 'unknown'
        draft.detail = observation.detail
        return true
      }))
    },

    /**
     * Hiding a card only affects the reminder list. A card that was never
     * confirmed is retired (and can never be sent afterwards); a card that was
     * confirmed keeps its exact delivery state and result.
     */
    async dismiss(id, expectedVersion) {
      const record = current(id)
      sameVersion(record, expectedVersion)
      if (['forwarding', 'accepted'].includes(record.phase))
        fail('invalid_request', '正在进行的任务不能隐藏。')
      return publicReceipt(receipts.mutate(id, (draft) => {
        if (draft.phase === 'awaiting_confirmation') {
          draft.phase = 'unavailable'
          draft.nativeStatus = 'unknown'
          draft.detail = '这张任务卡已被丢弃，没有发送给任何执行者。'
        }
        draft.reminderCleared = true
        return true
      }))
    },

    async close() {
      await Promise.allSettled([typeof codex.close === 'function' ? codex.close() : Promise.resolve()])
      receipts.close()
    },
  }
}
