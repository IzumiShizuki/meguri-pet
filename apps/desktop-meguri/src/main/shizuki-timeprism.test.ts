import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

import { createShizukiTimePrismClient, normalizeShizukiOrigin } from './shizuki-timeprism.mjs'

const roots: string[] = []
afterEach(() => {
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true })
})

function storagePath() {
  const root = mkdtempSync(join(tmpdir(), 'meguri-shizuki-'))
  roots.push(root)
  return join(root, 'session.json')
}

const encryption = {
  isEncryptionAvailable: () => true,
  encryptString: (value: string) => Buffer.from(`protected:${value}`, 'utf8'),
  decryptString: (value: Buffer) => value.toString('utf8').replace(/^protected:/, ''),
}

function jsonResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify({ data }), { status, headers: { 'content-type': 'application/json' } })
}

function token(accessToken: string, refreshToken: string) {
  return {
    result_type: 'TOKEN_ISSUED',
    access_token: accessToken,
    refresh_token: refreshToken,
    expires_in_sec: 7200,
    user_id: 42,
  }
}

describe('Shizuki TimePrism client', () => {
  it('accepts HTTPS or loopback and rejects public HTTP before credentials are sent', () => {
    expect(normalizeShizukiOrigin('https://site.shizuki.online')).toBe('https://site.shizuki.online')
    expect(normalizeShizukiOrigin('http://127.0.0.1:7070')).toBe('http://127.0.0.1:7070')
    expect(() => normalizeShizukiOrigin('http://111.228.35.186:8080')).toThrow()
    expect(() => normalizeShizukiOrigin('https://site.shizuki.online/other')).toThrow()
  })

  it('stores only an encrypted refresh token and persists rotation before the next request', async () => {
    const filePath = storagePath()
    let time = 0
    const calls: Array<{ url: string, method: string, auth: string, body: Record<string, unknown> | null }> = []
    const fetchImpl = async (url: string | URL | Request, options: RequestInit = {}) => {
      const href = String(url)
      calls.push({
        url: href,
        method: options.method || 'GET',
        auth: new Headers(options.headers).get('authorization') || '',
        body: options.body ? JSON.parse(String(options.body)) : null,
      })
      if (href.endsWith('/auth/tokens')) {
        const body = calls.at(-1)?.body
        return jsonResponse(body?.grant_type === 'EMAIL_PASSWORD' ? token('access-1', 'refresh-1') : token('access-2', 'refresh-2'))
      }
      return jsonResponse([])
    }
    const client = createShizukiTimePrismClient({ filePath, safeStorage: encryption, fetchImpl, clock: () => time })
    await client.connect({ origin: 'https://site.shizuki.online', email: 'owner@example.com', password: 'secret' })
    expect(client.status()).toMatchObject({ connected: true, userId: 42 })
    expect(readFileSync(filePath, 'utf8')).not.toContain('refresh-1')
    expect(readFileSync(filePath, 'utf8')).not.toContain('secret')

    time = 7_200_000
    await client.listTodos()
    expect(calls.some(call => call.body?.grant_type === 'REFRESH_TOKEN' && call.body?.refresh_token === 'refresh-1')).toBe(true)
    expect(calls.at(-1)?.auth).toBe('Bearer access-2')
    expect(encryption.decryptString(Buffer.from(JSON.parse(readFileSync(filePath, 'utf8')).refreshTokenEncrypted, 'base64'))).toBe('refresh-2')
  })

  it('writes an exact-time reminder and preserves all Todo fields when completing it', async () => {
    const filePath = storagePath()
    const due = new Date(Date.now() + 86_400_000)
    const local = `${due.getFullYear()}-${String(due.getMonth() + 1).padStart(2, '0')}-${String(due.getDate()).padStart(2, '0')}T12:00`
    const savedTodo = {
      todo_id: 17, title: '睡觉', detail: '早点休息', priority: 'HIGH', done: false,
      due_at: `${local}:00`, show_on_calendar: true, time_precision: 'MINUTE',
      timing_mode: 'DEADLINE', reminder_enabled: true, deadline_remind_value: 0,
      deadline_remind_unit: 'MINUTE', sort_num: 9,
    }
    let createBody: Record<string, unknown> | undefined
    let completeBody: Record<string, unknown> | undefined
    const fetchImpl = async (url: string | URL | Request, options: RequestInit = {}) => {
      const href = String(url)
      const body = options.body ? JSON.parse(String(options.body)) : null
      if (href.endsWith('/auth/tokens')) return jsonResponse(token('access', 'refresh'))
      if (href.endsWith('/todos') && options.method === 'POST') {
        createBody = body
        return jsonResponse(savedTodo)
      }
      if (href.endsWith('/todos') && options.method === 'GET') return jsonResponse([savedTodo])
      if (href.endsWith('/todos/17') && options.method === 'PUT') {
        completeBody = body
        return jsonResponse({ ...savedTodo, done: true })
      }
      throw new Error(`unexpected route ${href}`)
    }
    const client = createShizukiTimePrismClient({ filePath, safeStorage: encryption, fetchImpl })
    await client.connect({ origin: 'https://site.shizuki.online', email: 'owner@example.com', password: 'secret' })
    const created = await client.createTodo({ title: '睡觉', dueAt: local })
    expect(created.todoId).toBe(17)
    expect(createBody).toMatchObject({ due_at: `${local}:00`, reminder_enabled: true, deadline_remind_value: 0, deadline_remind_unit: 'MINUTE' })
    const completed = await client.completeTodo(17)
    expect(completed.done).toBe(true)
    expect(completeBody).toMatchObject({ title: '睡觉', detail: '早点休息', priority: 'HIGH', sort_num: 9, done: true, deadline_remind_value: 0 })
  })
})
