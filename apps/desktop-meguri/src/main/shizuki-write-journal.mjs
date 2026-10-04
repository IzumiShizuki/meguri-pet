import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

const UUID = /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/i
export const requestMarker = id => `meguri-request:${id}`

// Kept separate from auth storage; contains no credentials. Never discard request identities.
export function createWriteJournal(filePath, failure) {
  let entries = {}
  let broken = false
  if (existsSync(filePath)) {
    try {
      const saved = JSON.parse(readFileSync(filePath, 'utf8'))
      if (saved.version !== 1 || !saved.entries || Array.isArray(saved.entries)) throw new Error('invalid journal')
      for (const [id, entry] of Object.entries(saved.entries)) {
        if (!UUID.test(id) || !entry || !['uncertain', 'rejected', 'confirmed'].includes(entry.state)
          || typeof entry.signature !== 'string' || typeof entry.origin !== 'string' || !Number.isSafeInteger(entry.userId)
          || typeof entry.title !== 'string' || typeof entry.dueAt !== 'string') throw new Error('invalid entry')
      }
      entries = saved.entries
    }
    catch { broken = true }
  }
  function check() {
    if (broken) throw failure('journal_failed', '创建记录无法读取。为防止重复写入已停止创建，请保留记录并检查本机存储。')
  }
  function get(id) { check(); return entries[id] }
  function put(id, entry) {
    check()
    if (!UUID.test(id)) throw failure('invalid_request_id', '创建请求编号无效，请重新核对。')
    if (!entries[id] && Object.keys(entries).length >= 10000)
      throw failure('journal_full', '创建记录已满。为保护重试记录，未写入新的待办。')
    const next = { ...entries, [id]: entry }
    const temp = `${filePath}.${process.pid}.tmp`
    try {
      mkdirSync(dirname(filePath), { recursive: true })
      writeFileSync(temp, JSON.stringify({ version: 1, entries: next }), { encoding: 'utf8', mode: 0o600 })
      renameSync(temp, filePath)
      entries = next
    }
    catch {
      try { rmSync(temp, { force: true }) } catch { /* Preserve an unexpected filesystem entry. */ }
      throw failure('journal_failed', '无法保存创建记录，未确认创建成功。请检查本机存储后核对服务器记录。')
    }
  }
  function pending(origin, userId) {
    check()
    return Object.entries(entries).filter(([, entry]) => entry.origin === origin && entry.userId === userId && entry.state !== 'confirmed')
      .map(([requestId, entry]) => ({ requestId, title: entry.title, dueAt: entry.dueAt, state: entry.state }))
  }
  return { get, put, pending }
}
