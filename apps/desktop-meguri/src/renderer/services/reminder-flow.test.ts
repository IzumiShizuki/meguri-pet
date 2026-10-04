import { describe, expect, it, vi } from 'vitest'
import type { MeguriDesktopBridge, ShizukiTodo } from '../../shared/desktop-contract'
import { ReminderFlow } from './reminder-flow'

const now = Date.parse('2026-10-04T12:00+08:00')
const id = 'c6193024-80a5-4287-80f0-ce5da8500d29'
const todo = { todoId: 17, title: '开会', dueAt: '2026-10-05T09:00:00' } as ShizukiTodo
function fixture() {
  const status = vi.fn(async () => ({ connected: true, origin: 'https://site.shizuki.online', userId: 42 }))
  const createTodo = vi.fn(async (_input: unknown) => ({ ok: true, todo }))
  const pendingCreates = vi.fn(async () => ({ ok: true, requests: [] as import('../../shared/desktop-contract').PendingTodoCreate[] }))
  const bridge = { status, createTodo, pendingCreates } as unknown as NonNullable<MeguriDesktopBridge['shizuki']>
  return { flow: new ReminderFlow(bridge, () => now, () => id), status, createTodo, pendingCreates }
}

describe('reminder review and confirmation flow', () => {
  it('text and editable voice drafts prepare identically without writing', async () => {
    for (const source of ['text', 'voice']) {
      const { flow, createTodo } = fixture()
      expect(await flow.prepare('明天上午9点提醒我开会')).toBe(true)
      expect(flow).toMatchObject({ phase: 'review', title: '开会', dueAt: '2026-10-05T09:00' })
      expect(createTodo, source).not.toHaveBeenCalled()
      expect(await flow.confirm()).toBe(true)
      expect(createTodo).toHaveBeenCalledWith({ title: '开会', dueAt: '2026-10-05T09:00', requestId: id,
        expectedAccount: 'https://site.shizuki.online:42', behavior: 'reminder', timezone: 'Asia/Shanghai' })
      expect(flow.feedback).toContain('未确认 QQ 送达')
    }
  })

  it('asks a question, accepts a correction and still needs confirmation', async () => {
    const { flow, createTodo } = fixture()
    await flow.prepare('明天3点提醒我开会')
    expect(flow.phase).toBe('clarify')
    expect(await flow.confirm()).toBe(false)
    flow.dueAt = '2026-10-05T15:00'
    await flow.clarify()
    expect(flow.phase).toBe('review')
    expect(createTodo).not.toHaveBeenCalled()
    flow.close()
    expect(flow.phase).toBe('idle')
  })

  it('never sends unsupported actions and lets ordinary chat continue', async () => {
    const { flow, createTodo } = fixture()
    expect(await flow.prepare('明天下午3点帮我检查订单')).toBe(true)
    expect(flow.phase).toBe('unsupported')
    expect(await flow.confirm()).toBe(false)
    expect(createTodo).not.toHaveBeenCalled()
    expect(await flow.prepare('你好')).toBe(false)
  })

  it('preserves the request and draft after an ambiguous failure and reuses its ID', async () => {
    const { flow, createTodo } = fixture()
    createTodo.mockRejectedValueOnce(new Error('response lost'))
    await flow.prepare('明天上午9点提醒我开会')
    expect(await flow.confirm()).toBe(false)
    expect(flow.source).toBe('明天上午9点提醒我开会')
    expect(flow.feedback).toBe('')
    flow.close()
    expect(flow.phase).toBe('review')
    await flow.prepare('明天15:00提醒我开会')
    expect(flow.dueAt).toBe('2026-10-05T09:00')
    expect(await flow.confirm()).toBe(true)
    expect(createTodo.mock.calls.map(([input]) => (input as { requestId: string }).requestId)).toEqual([id, id])
  })

  it('prevents duplicate confirm while awaiting server response', async () => {
    const { flow, createTodo } = fixture()
    let resolve!: (value: { ok: boolean, todo: ShizukiTodo }) => void
    createTodo.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await flow.prepare('明天上午9点提醒我开会')
    const first = flow.confirm()
    await Promise.resolve()
    expect(await flow.confirm()).toBe(false)
    resolve({ ok: true, todo })
    expect(await first).toBe(true)
    expect(createTodo).toHaveBeenCalledTimes(1)
  })

  it('does not report success for a missing saved record', async () => {
    const { flow, createTodo } = fixture()
    createTodo.mockResolvedValueOnce({ ok: false, todo: undefined! })
    await flow.prepare('明天上午9点提醒我开会')
    expect(await flow.confirm()).toBe(false)
    expect(flow.phase).toBe('review')
    expect(flow.error).toContain('尚未确认')
  })

  it('refuses confirmation when time passed or account changed', async () => {
    const { flow, status, createTodo } = fixture()
    await flow.prepare('明天上午9点提醒我开会')
    status.mockResolvedValue({ connected: true, origin: 'https://site.shizuki.online', userId: 43 })
    expect(await flow.confirm()).toBe(false)
    expect(createTodo).not.toHaveBeenCalled()
    flow.clock = () => Date.parse('2026-10-05T09:01+08:00')
    expect(await flow.confirm()).toBe(false)
    expect(flow.error).toContain('已过')
  })

  it('restores a dispatched request after restart and can reconcile after its due time', async () => {
    const { flow, createTodo } = fixture()
    await flow.restore({ requestId: id, title: '开会', dueAt: '2026-10-05T09:00', state: 'uncertain' })
    flow.clock = () => Date.parse('2026-10-05T09:01+08:00')
    expect(await flow.confirm()).toBe(true)
    expect(createTodo).toHaveBeenCalledWith(expect.objectContaining({ requestId: id }))
  })

  it('reuses a pending request when the user resends the same draft after restart', async () => {
    const { flow, pendingCreates, createTodo } = fixture()
    const originalId = '13b31a7b-a2e5-4b58-8851-ad4d1c8bc288'
    pendingCreates.mockResolvedValue({ ok: true, requests: [{ requestId: originalId, title: '开会', dueAt: '2026-10-05T09:00', state: 'uncertain' }] })
    await flow.prepare('明天上午9点提醒我开会')
    expect(flow.requestId).toBe(originalId)
    expect(await flow.confirm()).toBe(true)
    expect(createTodo).toHaveBeenCalledWith(expect.objectContaining({ requestId: originalId }))
  })
})
