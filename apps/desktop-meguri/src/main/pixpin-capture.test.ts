import { afterEach, describe, expect, it, vi } from 'vitest'

import { createPixPinCaptureCoordinator, pixpinWatcherStartupTimeoutMs } from './pixpin-capture.mjs'

function attachment(referenceId = 'inline_test') {
  return {
    type: 'inline_attachment' as const,
    source: 'airi_inline' as const,
    reference_id: referenceId,
    name: '截图.png',
    kind: 'image' as const,
    size_bytes: 4,
    modified_at: '2026-09-29T00:00:00.000Z',
    content_access: 'multimodal_read' as const,
    mime_type: 'image/png' as const,
    data_url: 'data:image/png;base64,iVBORw==',
  }
}

function fixture() {
  const changes: Array<(event: { sequence: number, ownerPid: number, ownerPath?: string }) => void> = []
  const events: Array<Record<string, unknown>> = []
  const watcher = { ready: Promise.resolve({ sequence: 40 }), stop: vi.fn() }
  const capture = { started: Promise.resolve(), stop: vi.fn() }
  const readClipboardImage = vi.fn(async (captureId: string) => ({ ok: true, attachment: attachment(`inline_${captureId}`) }))
  const restoreStage = vi.fn()
  let nextId = 0
  const coordinator = createPixPinCaptureCoordinator({
    preparePixPin: async () => ({ executable: 'C:\\Program Files\\PixPin\\PixPin.exe', processIds: [101] }),
    createClipboardWatcher: ({ onChange }: { onChange: (event: { sequence: number, ownerPid: number, ownerPath?: string }) => void }) => {
      changes.push(onChange)
      return watcher
    },
    invokeCapture: () => capture,
    readClipboardImage,
    hideStage: () => ({ visible: true }),
    restoreStage,
    onChanged: event => { events.push(event) },
    timeoutMs: 2_000,
    settleMs: 20,
    createId: () => `capture-${++nextId}`,
  })
  return { coordinator, changes, events, watcher, capture, readClipboardImage, restoreStage }
}

afterEach(() => vi.useRealTimers())

describe('PixPin one-shot capture coordinator', () => {
  it('ignores the baseline and other clipboard owners, then receives one fresh PixPin image', async () => {
    vi.useFakeTimers()
    const state = fixture()
    const started = await state.coordinator.start('draft')
    expect(started).toMatchObject({ ok: true, state: 'selecting' })
    expect(state.coordinator.status()).toMatchObject({ state: 'selecting', captureId: 'capture-1' })

    state.changes[0]({ sequence: 40, ownerPid: 101, ownerPath: 'C:\\Program Files\\PixPin\\PixPin.exe' })
    state.changes[0]({ sequence: 41, ownerPid: 202, ownerPath: 'C:\\Other\\app.exe' })
    await vi.advanceTimersByTimeAsync(40)
    expect(state.readClipboardImage).not.toHaveBeenCalled()

    state.changes[0]({ sequence: 42, ownerPid: 101, ownerPath: 'C:\\Program Files\\PixPin\\PixPin.exe' })
    await vi.advanceTimersByTimeAsync(20)
    expect(state.readClipboardImage).toHaveBeenCalledOnce()
    expect(state.readClipboardImage).toHaveBeenCalledWith('capture-1')
    expect(state.events.at(-1)).toMatchObject({ state: 'received', captureId: 'capture-1', attachment: { reference_id: 'inline_capture-1' } })
    expect(state.coordinator.status()).toEqual({ state: 'idle' })
    expect(state.watcher.stop).toHaveBeenCalledOnce()
    expect(state.capture.stop).toHaveBeenCalledOnce()
    expect(state.restoreStage).toHaveBeenCalledWith({ visible: true })
  })

  it('rejects duplicate triggers and invalidates clipboard events after cancellation', async () => {
    const state = fixture()
    const started = await state.coordinator.start('direct_send')
    const duplicate = await state.coordinator.start('draft')
    expect(duplicate).toMatchObject({ ok: false, captureId: started.captureId })

    await state.coordinator.cancel(started.captureId!)
    state.changes[0]({ sequence: 41, ownerPid: 101, ownerPath: 'C:\\Program Files\\PixPin\\PixPin.exe' })
    expect(state.readClipboardImage).not.toHaveBeenCalled()
    expect(state.events.at(-1)).toMatchObject({ state: 'cancelled', captureId: 'capture-1' })
    expect(state.watcher.stop).toHaveBeenCalledOnce()
    expect(state.restoreStage).toHaveBeenCalledOnce()
  })

  it('does not receive a PixPin update if another process replaces it before the clipboard settles', async () => {
    vi.useFakeTimers()
    const state = fixture()
    await state.coordinator.start('direct_send')

    state.changes[0]({ sequence: 41, ownerPid: 101, ownerPath: 'C:\\Program Files\\PixPin\\PixPin.exe' })
    await vi.advanceTimersByTimeAsync(10)
    state.changes[0]({ sequence: 42, ownerPid: 202 })
    await vi.advanceTimersByTimeAsync(30)

    expect(state.readClipboardImage).not.toHaveBeenCalled()
    expect(state.coordinator.status()).toMatchObject({ state: 'selecting' })
    await state.coordinator.cancel('capture-1')
  })

  it('times out and closes the listener without importing a stale image', async () => {
    vi.useFakeTimers()
    const state = fixture()
    await state.coordinator.start('draft')
    await vi.advanceTimersByTimeAsync(2_000)

    expect(state.readClipboardImage).not.toHaveBeenCalled()
    expect(state.coordinator.status()).toEqual({ state: 'idle' })
    expect(state.events.at(-1)).toMatchObject({ state: 'timed_out', captureId: 'capture-1' })
    expect(state.watcher.stop).toHaveBeenCalledOnce()
    expect(state.restoreStage).toHaveBeenCalledOnce()
  })

  it('fails cleanly if the Windows clipboard watcher never becomes ready', async () => {
    vi.useFakeTimers()
    let rejectReady!: (error: Error) => void
    const watcher = {
      ready: new Promise<{ sequence: number }>((_resolve, reject) => { rejectReady = reject }),
      stop: vi.fn(() => rejectReady(new Error('listener stopped'))),
    }
    const events: Array<Record<string, unknown>> = []
    const restoreStage = vi.fn()
    let watcherCreated = false
    const coordinator = createPixPinCaptureCoordinator({
      preparePixPin: async () => ({ executable: 'C:\\Program Files\\PixPin\\PixPin.exe', processIds: [101] }),
      createClipboardWatcher: () => {
        watcherCreated = true
        return watcher
      },
      invokeCapture: () => ({ started: Promise.resolve() }),
      readClipboardImage: async () => ({ ok: false, error: 'unused' }),
      hideStage: () => ({ visible: true }),
      restoreStage,
      onChanged: event => { events.push(event) },
      createId: () => 'watcher-timeout',
    })
    const starting = coordinator.start('draft')
    for (let turn = 0; turn < 5 && !watcherCreated; turn++)
      await Promise.resolve()
    expect(watcherCreated).toBe(true)
    await vi.advanceTimersByTimeAsync(pixpinWatcherStartupTimeoutMs)

    await expect(starting).resolves.toMatchObject({ ok: false, error: expect.stringContaining('监听器启动超时') })
    expect(events.at(-1)).toMatchObject({ state: 'failed', captureId: 'watcher-timeout' })
    expect(watcher.stop).toHaveBeenCalledOnce()
    expect(restoreStage).toHaveBeenCalledOnce()
    expect(coordinator.status()).toEqual({ state: 'idle' })
  })

  it('accepts the same pixels in a later capture as a different one-shot result', async () => {
    vi.useFakeTimers()
    const state = fixture()
    await state.coordinator.start('draft')
    state.changes[0]({ sequence: 41, ownerPid: 101, ownerPath: 'C:\\Program Files\\PixPin\\PixPin.exe' })
    await vi.advanceTimersByTimeAsync(20)
    await state.coordinator.start('draft')
    state.changes[1]({ sequence: 41, ownerPid: 101, ownerPath: 'C:\\Program Files\\PixPin\\PixPin.exe' })
    state.changes[1]({ sequence: 42, ownerPid: 101, ownerPath: 'C:\\Program Files\\PixPin\\PixPin.exe' })
    await vi.advanceTimersByTimeAsync(20)

    expect(state.readClipboardImage).toHaveBeenNthCalledWith(1, 'capture-1')
    expect(state.readClipboardImage).toHaveBeenNthCalledWith(2, 'capture-2')
    expect(state.events.filter(event => event.state === 'received')).toHaveLength(2)
  })

  it('cleans up on a PixPin launch failure', async () => {
    const state = fixture()
    const coordinator = createPixPinCaptureCoordinator({
      preparePixPin: async () => ({ executable: 'PixPin.exe', processIds: [101] }),
      createClipboardWatcher: () => state.watcher,
      invokeCapture: () => ({ started: Promise.reject(new Error('launch failed')), stop: state.capture.stop }),
      readClipboardImage: state.readClipboardImage,
      hideStage: () => ({ visible: true }),
      restoreStage: state.restoreStage,
      onChanged: event => { state.events.push(event) },
      createId: () => 'failed-capture',
    })
    const result = await coordinator.start('draft')

    expect(result).toMatchObject({ ok: false, error: 'launch failed' })
    expect(state.watcher.stop).toHaveBeenCalledOnce()
    expect(state.restoreStage).toHaveBeenCalledWith({ visible: true })
    expect(coordinator.status()).toEqual({ state: 'idle' })
  })
})
