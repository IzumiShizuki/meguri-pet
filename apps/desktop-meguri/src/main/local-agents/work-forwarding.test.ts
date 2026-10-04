import { mkdtempSync, rmSync } from 'node:fs'
import { join } from 'node:path'

import { afterEach, describe, expect, it, vi } from 'vitest'

import { createReceipts } from './receipts.mjs'
import { createWorkForwarding } from './work-forwarding.mjs'

const roots: string[] = []

const THREAD_ID = '01a0b06e-c160-7a11-bfa5-f72bf76ca7ea'
const OTHER_THREAD_ID = '01a0b4bc-9166-71c3-8d58-8b9521f853ad'
const TURN_ID = '01a0b4bc-9166-71c3-8d58-8b9521f853ae'

function harnessForTest(overrides: Record<string, unknown> = {}) {
  return {
    probe: vi.fn(async () => ({ state: 'ready' })),
    createWorkSession: vi.fn(async (sessionId: string) => ({ sessionId })),
    submitWork: vi.fn(async () => ({ accepted: true })),
    receipt: vi.fn(async () => ({ status: 'working', detail: '执行中' })),
    hostUrl: vi.fn(async () => 'http://127.0.0.1:1234/'),
    ...overrides,
  }
}

function codexForTest(overrides: Record<string, unknown> = {}) {
  return {
    installed: vi.fn(() => true),
    compatible: vi.fn(async () => ({ compatible: true, account: 'signed_in' })),
    list: vi.fn(async () => [
      { targetId: THREAD_ID, title: '相机模块', projectPath: 'D:\\program\\meguri-pet', state: 'idle' },
      { targetId: OTHER_THREAD_ID, title: '音乐界面', projectPath: 'D:\\program\\shizuki-site', state: 'idle' },
    ]),
    discover: vi.fn(async (targetId: string) => (targetId === THREAD_ID
      ? { available: true, title: '相机模块', projectPath: 'D:\\program\\meguri-pet' }
      : { available: false, reason: 'not_found' })),
    send: vi.fn(async () => ({ turnId: TURN_ID })),
    receipt: vi.fn(async () => ({ status: 'completed', result: '完成', detail: '已完成' })),
    close: vi.fn(async () => {}),
    ...overrides,
  }
}

function forwardingForTest(options: { codex?: Record<string, unknown>, harness?: Record<string, unknown>, workspaceRoot?: string } = {}) {
  const root = mkdtempSync(join(process.cwd(), '.meguri-forwarding-test-'))
  roots.push(root)
  const receipts = createReceipts({ filename: join(root, 'data', 'receipts.sqlite') })
  const codex = codexForTest(options.codex)
  const harness = harnessForTest(options.harness)
  const forwarding = createWorkForwarding({
    receipts,
    codex: codex as never,
    harness: harness as never,
    workspaceRoot: options.workspaceRoot ?? 'D:\\program\\meguri-pet',
  })
  return { forwarding, receipts, codex, harness }
}

afterEach(() => {
  while (roots.length)
    rmSync(roots.pop()!, { recursive: true, force: true })
})

describe('work forwarding', () => {
  it('prepares a card without contacting any work agent', async () => {
    const { forwarding, codex } = forwardingForTest()
    const record = await forwarding.prepare({
      text: '检查相机模块',
      executor: 'codex',
      targetId: THREAD_ID,
      plan: { title: '相机检查', reason: '用户提到了帧率抖动。' },
    })

    expect(record.phase).toBe('awaiting_confirmation')
    expect(record.target).toMatchObject({ targetId: THREAD_ID, projectPath: 'D:\\program\\meguri-pet' })
    expect(codex.discover).toHaveBeenCalledTimes(1)
    expect(codex.send).not.toHaveBeenCalled()
    await forwarding.close()
  })

  it('rejects a target the executor cannot reproduce', async () => {
    const { forwarding } = forwardingForTest()
    await expect(forwarding.prepare({
      text: '检查相机模块',
      executor: 'codex',
      targetId: OTHER_THREAD_ID,
    })).rejects.toThrow(/真实的 Codex 任务目录/u)
    await forwarding.close()
  })

  it('rejects a project directory that disagrees with the Codex task', async () => {
    const { forwarding } = forwardingForTest()
    await expect(forwarding.prepare({
      text: '检查相机模块',
      executor: 'codex',
      targetId: THREAD_ID,
      projectPath: 'D:\\program\\shizuki-site',
    })).rejects.toThrow(/项目与目标任务目录不一致/u)
    await forwarding.close()
  })

  it('sends exactly once and refuses a second confirm', async () => {
    const { forwarding, codex } = forwardingForTest()
    const record = await forwarding.prepare({ text: '检查相机模块', executor: 'codex', targetId: THREAD_ID })

    const first = await forwarding.confirm(record.id, record.version)
    expect(first.phase).toBe('accepted')
    expect(first.appTurnId).toBe(TURN_ID)
    expect(codex.send).toHaveBeenCalledTimes(1)

    // A stale version must not resend, and neither must a repeat of the same confirm.
    const second = await forwarding.confirm(record.id, record.version)
    expect(second.phase).toBe('accepted')
    expect(codex.send).toHaveBeenCalledTimes(1)
    await forwarding.close()
  })

  it('refuses a confirm against a stale card version', async () => {
    const { forwarding, codex } = forwardingForTest()
    const record = await forwarding.prepare({ text: '检查相机模块', executor: 'codex', targetId: THREAD_ID })

    await expect(forwarding.confirm(record.id, record.version + 5)).rejects.toThrow(/已经变化/u)
    expect(codex.send).not.toHaveBeenCalled()
    await forwarding.close()
  })

  it('records an ambiguous Codex send as unknown and never retries it', async () => {
    const send = vi.fn(async () => {
      const error = new Error('未确认') as Error & { state?: string }
      error.state = 'unknown_delivery'
      throw error
    })
    const { forwarding, codex } = forwardingForTest({ codex: { send } })
    const record = await forwarding.prepare({ text: '检查相机模块', executor: 'codex', targetId: THREAD_ID })

    const result = await forwarding.confirm(record.id, record.version)
    expect(result.phase).toBe('unknown')
    expect(result.detail).toContain('不会自动重复发送')

    await forwarding.confirm(record.id, result.version)
    expect(codex.send).toHaveBeenCalledTimes(1)
    await forwarding.close()
  })

  it('blocks a confirm when the selected executor is not connected', async () => {
    const { forwarding, codex } = forwardingForTest({
      codex: { compatible: vi.fn(async () => ({ compatible: false, reason: 'unavailable' })) },
    })
    const record = await forwarding.prepare({ text: '检查相机模块', executor: 'codex', targetId: THREAD_ID })

    await expect(forwarding.confirm(record.id, record.version)).rejects.toThrow(/Codex 当前不可用/u)
    expect(codex.send).not.toHaveBeenCalled()
    await forwarding.close()
  })

  it('creates the Harness work session in the selected project directory', async () => {
    const { forwarding, harness } = forwardingForTest()
    const record = await forwarding.prepare({
      text: '整理日报',
      executor: 'harness',
      projectPath: 'D:\\program\\meguri-pet',
    })

    const result = await forwarding.confirm(record.id, record.version)
    expect(result.phase).toBe('accepted')
    expect(harness.createWorkSession).toHaveBeenCalledWith(expect.stringMatching(/^meguri-work-/u), 'D:\\program\\meguri-pet')
    expect(harness.submitWork).toHaveBeenCalledTimes(1)
    await forwarding.close()
  })

  it('reports a failed Harness session creation as not sent', async () => {
    const createWorkSession = vi.fn(async () => { throw new Error('会话创建失败') })
    const submitWork = vi.fn(async () => ({ accepted: true }))
    const { forwarding } = forwardingForTest({ harness: { createWorkSession, submitWork } })
    const record = await forwarding.prepare({
      text: '整理日报',
      executor: 'harness',
      projectPath: 'D:\\program\\meguri-pet',
    })

    const result = await forwarding.confirm(record.id, record.version)
    expect(result.phase).toBe('unavailable')
    expect(result.detail).toContain('没有发出')
    expect(submitWork).not.toHaveBeenCalled()
    await forwarding.close()
  })

  it('keeps an ambiguous Harness prompt submission unknown and never resends it', async () => {
    const submitWork = vi.fn(async () => { throw new Error('响应丢失') })
    const { forwarding, harness } = forwardingForTest({ harness: { submitWork } })
    const record = await forwarding.prepare({
      text: '整理日报',
      executor: 'harness',
      projectPath: 'D:\\program\\meguri-pet',
    })

    const result = await forwarding.confirm(record.id, record.version)
    expect(result.phase).toBe('unknown')
    expect(result.detail).toContain('不会自动重复发送')

    await forwarding.confirm(record.id, result.version)
    expect(harness.submitWork).toHaveBeenCalledTimes(1)
    await forwarding.close()
  })

  it('refuses a Harness card without any workspace without sending anything', async () => {
    const { forwarding, harness } = forwardingForTest({
      harness: { probe: vi.fn(async () => ({ state: 'ready' })) },
      // No project on the card and no workspace root to fall back to.
      workspaceRoot: '',
    })
    const record = await forwarding.prepare({ text: '整理日报', executor: 'harness' })

    const result = await forwarding.confirm(record.id, record.version)
    expect(result.phase).toBe('unavailable')
    expect(result.detail).toContain('本地项目目录')
    expect(harness.createWorkSession).not.toHaveBeenCalled()
    expect(harness.submitWork).not.toHaveBeenCalled()
    await forwarding.close()
  })

  it('keeps a completed receipt even when a later observation is unknown', async () => {
    const receipt = vi.fn()
      .mockResolvedValueOnce({ status: 'completed', result: '完成', detail: '已完成' })
      .mockResolvedValue({ status: 'unknown', detail: '暂时无法核实' })
    const { forwarding } = forwardingForTest({ codex: { receipt } })
    const record = await forwarding.prepare({ text: '检查相机模块', executor: 'codex', targetId: THREAD_ID })
    const accepted = await forwarding.confirm(record.id, record.version)

    const completed = await forwarding.refresh(accepted.id)
    expect(completed.phase).toBe('completed')
    expect(completed.result).toBe('完成')

    const again = await forwarding.refresh(accepted.id)
    expect(again.phase).toBe('completed')
    expect(again.result).toBe('完成')
    await forwarding.close()
  })

  it('retires an unconfirmed card when the user discards it', async () => {
    const { forwarding, codex } = forwardingForTest()
    const record = await forwarding.prepare({ text: '检查相机模块', executor: 'codex', targetId: THREAD_ID })

    const dismissed = await forwarding.dismiss(record.id, record.version)
    expect(dismissed.phase).toBe('unavailable')
    expect(dismissed.reminderCleared).toBe(true)
    expect(codex.send).not.toHaveBeenCalled()
    await forwarding.close()
  })

  it('reports only metadata for targets and projects', async () => {
    const { forwarding } = forwardingForTest()
    const targets = await forwarding.targets('相机')
    expect(targets.items[0]).toEqual({
      targetId: THREAD_ID,
      title: '相机模块',
      projectPath: 'D:\\program\\meguri-pet',
      state: 'idle',
    })

    const projects = await forwarding.projects()
    expect(projects.map(project => project.rootPath).sort()).toEqual([
      'D:\\program\\meguri-pet',
      'D:\\program\\shizuki-site',
    ])
    await forwarding.close()
  })

  it('reuses a recent connection probe instead of re-spawning on every snapshot', async () => {
    const { forwarding, codex, harness } = forwardingForTest()
    await forwarding.snapshot()
    await forwarding.snapshot()
    await forwarding.snapshot()

    expect(codex.compatible).toHaveBeenCalledTimes(1)
    expect(harness.probe).toHaveBeenCalledTimes(1)

    // Confirming re-probes: readiness is never cached for the irreversible action.
    const record = await forwarding.prepare({ text: '检查相机模块', executor: 'codex', targetId: THREAD_ID })
    await forwarding.confirm(record.id, record.version)
    expect(codex.compatible.mock.calls.length).toBeGreaterThan(1)
    await forwarding.close()
  })

  it('reports the disabled executors without contacting anything', async () => {
    const { forwarding, codex, harness } = forwardingForTest({
      codex: { compatible: vi.fn(async () => ({ compatible: false, reason: 'unavailable' })) },
      harness: { probe: vi.fn(async () => ({ state: 'unavailable' })) },
    })
    const snapshot = await forwarding.snapshot()
    expect(snapshot).toMatchObject({
      requests: [],
      draft: null,
      connection: { codex: 'unavailable', harness: 'unavailable' },
      executionAvailable: false,
    })
    expect(codex.send).not.toHaveBeenCalled()
    expect(harness.submitWork).not.toHaveBeenCalled()
    await forwarding.close()
  })
})
