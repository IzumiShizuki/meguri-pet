/**
 * Durable forwarding receipts.
 *
 * This store is the "never send twice" boundary. It keeps the task card text,
 * the exact executor target and the dispatch state on disk *before* any
 * irreversible remote write, so a crash, a restart or a second confirm cannot
 * replay a send. It is deliberately not a work queue: nothing here executes,
 * retries or schedules anything.
 */

import { DatabaseSync } from 'node:sqlite'
import { randomUUID } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

import { MAX_TASK_TEXT_LENGTH, MAX_TITLE_LENGTH, hasControlCharacters } from './paths.mjs'

export const FORWARD_PHASES = Object.freeze([
  'awaiting_confirmation',
  'forwarding',
  'accepted',
  'completed',
  'unknown',
  'unavailable',
])

export const EXECUTORS = Object.freeze(['codex', 'harness'])

const SCHEMA = `
CREATE TABLE IF NOT EXISTS confirmations (
  id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS work_drafts (
  id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  payload TEXT NOT NULL
);
`

export class ReceiptError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'ReceiptError'
    this.code = code
  }
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function boundedText(value, max, { allowEmpty = false } = {}) {
  if (typeof value !== 'string')
    return undefined
  const trimmed = value.trim()
  if (!trimmed && !allowEmpty)
    return undefined
  if ([...trimmed].length > max || hasControlCharacters(trimmed))
    return undefined
  return trimmed
}

function readPayload(row) {
  if (!row)
    return undefined
  try {
    const value = JSON.parse(row.payload)
    return isPlainObject(value) ? value : undefined
  }
  catch {
    return undefined
  }
}

export function createReceipts(options = {}) {
  const filename = options.filename
  if (typeof filename !== 'string' || !filename.trim())
    throw new ReceiptError('invalid_request', '回执数据库路径无效。')
  if (options.database === undefined)
    mkdirSync(dirname(filename), { recursive: true })

  const database = options.database ?? new DatabaseSync(filename)
  database.exec('PRAGMA journal_mode = WAL;')
  database.exec(SCHEMA)

  const statements = {
    confirmation: database.prepare('SELECT created_at, payload FROM confirmations WHERE id = ?'),
    draft: database.prepare('SELECT created_at, payload FROM work_drafts WHERE id = ?'),
    latestDraft: database.prepare('SELECT payload FROM work_drafts ORDER BY created_at DESC, id DESC LIMIT 1'),
    row: database.prepare('SELECT payload FROM confirmations ORDER BY created_at DESC, id DESC LIMIT 200'),
    pending: database.prepare(
      "SELECT payload FROM confirmations WHERE json_extract(payload, '$.appTurnId') IS NOT NULL "
      + "OR (json_extract(payload, '$.executor') = 'harness' AND json_extract(payload, '$.harnessSessionId') IS NOT NULL) "
      + "AND json_extract(payload, '$.phase') IN ('accepted','forwarding','unknown') "
      + 'ORDER BY created_at ASC, id ASC LIMIT 20',
    ),
    upsertConfirmation: database.prepare(
      'INSERT INTO confirmations (id, created_at, payload) VALUES (?, ?, ?) '
      + 'ON CONFLICT(id) DO UPDATE SET payload = excluded.payload',
    ),
    upsertDraft: database.prepare(
      'INSERT INTO work_drafts (id, created_at, payload) VALUES (?, ?, ?) '
      + 'ON CONFLICT(id) DO UPDATE SET payload = excluded.payload',
    ),
  }

  /** BEGIN IMMEDIATE serialises every read-modify-write, so a double confirm cannot double-claim. */
  function transaction(work) {
    database.exec('BEGIN IMMEDIATE')
    try {
      const result = work()
      database.exec('COMMIT')
      return result
    }
    catch (error) {
      try {
        database.exec('ROLLBACK')
      }
      catch {
        // The original failure stays authoritative.
      }
      throw error
    }
  }

  function mutate(id, patch) {
    return transaction(() => {
      const row = statements.confirmation.get(id)
      const current = readPayload(row)
      if (!current)
        throw new ReceiptError('not_found', '找不到这张任务卡，请重新整理。')
      const draft = { ...current }
      const changed = patch(draft) !== false
      if (!changed)
        return current
      draft.version = (Number.isSafeInteger(current.version) ? current.version : 1) + 1
      draft.updatedAt = new Date().toISOString()
      statements.upsertConfirmation.run(
        id,
        Number(row.created_at) || Date.now(),
        JSON.stringify(draft),
      )
      return draft
    })
  }

  function mutateDraft(id, patch) {
    return transaction(() => {
      const row = statements.draft.get(id)
      const current = readPayload(row)
      if (!current)
        throw new ReceiptError('not_found', '找不到这张草稿卡。')
      const draft = { ...current }
      const changed = patch(draft) !== false
      if (!changed)
        return current
      if (Object.hasOwn(draft, 'originalText') && draft.originalText !== current.originalText)
        throw new ReceiptError('invalid_request', '原始输入不能被规划或编辑覆盖。')
      draft.version = (Number.isSafeInteger(current.version) ? current.version : 1) + 1
      draft.updatedAt = new Date().toISOString()
      statements.upsertDraft.run(id, Number(row.created_at) || Date.now(), JSON.stringify(draft))
      return draft
    })
  }

  return {
    /** Freezes a card the user has not confirmed yet. Nothing has been sent. */
    create(request) {
      const id = randomUUID()
      const now = Date.now()
      const record = {
        id,
        version: 1,
        phase: 'awaiting_confirmation',
        text: request.text,
        executor: request.executor,
        target: request.target,
        project: request.project,
        plan: request.plan,
        createdAt: new Date(now).toISOString(),
        updatedAt: new Date(now).toISOString(),
        // Durable deduplication boundary for the single irreversible send.
        dispatchAttempted: false,
        requestId: randomUUID(),
      }
      statements.upsertConfirmation.run(id, now, JSON.stringify(record))
      return record
    },
    get(id) {
      return readPayload(statements.confirmation.get(id))
    },
    mutate,
    list() {
      return statements.row.all().map(readPayload).filter(Boolean)
    },
    /** Bounded read of cards that may still change; never a global task history. */
    pending() {
      return statements.pending.all().map(readPayload).filter(Boolean)
    },
    draft() {
      return readPayload(statements.latestDraft.get())
    },
    createDraft(record = {}) {
      const id = typeof record.id === 'string' && record.id ? record.id : randomUUID()
      const now = Date.now()
      const draft = {
        id,
        version: 1,
        status: 'open',
        originalText: record.originalText ?? '',
        text: record.text ?? '',
        executor: record.executor ?? 'codex',
        targetId: record.targetId ?? '',
        projectId: record.projectId ?? '',
        projectVersion: record.projectVersion ?? 0,
        plan: record.plan,
        createdAt: new Date(now).toISOString(),
        updatedAt: new Date(now).toISOString(),
      }
      statements.upsertDraft.run(id, now, JSON.stringify(draft))
      return draft
    },
    mutateDraft,
    close() {
      try {
        database.close()
      }
      catch {
        // Closing twice is not an error worth surfacing to the renderer.
      }
    },
  }
}

/** Strips internal bookkeeping before a receipt crosses the IPC boundary. */
export function publicReceipt(record) {
  if (!record)
    return undefined
  const { dispatchAttempted: _dispatchAttempted, ...rest } = record
  return { ...rest, requestId: '' }
}

export function validTaskText(value) {
  return boundedText(value, MAX_TASK_TEXT_LENGTH) ?? ''
}

/** Normalises an optional card plan; an unusable title invalidates the plan. */
export function validPlan(plan) {
  if (!isPlainObject(plan))
    return undefined
  const title = boundedText(plan.title, MAX_TITLE_LENGTH)
  if (!title)
    return undefined
  return { title, reason: boundedText(plan.reason, MAX_TITLE_LENGTH * 4) ?? '' }
}
