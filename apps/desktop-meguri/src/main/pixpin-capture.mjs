import { randomUUID } from 'node:crypto'

export const pixpinCaptureTimeoutMs = 120_000
export const pixpinWatcherStartupTimeoutMs = 15_000
export const pixpinClipboardSettleMs = 180

const captureModes = new Set(['draft', 'direct_send'])

function sameWindowsPath(left, right) {
  return typeof left === 'string'
    && typeof right === 'string'
    && left.trim().replace(/^"|"$/gu, '').replaceAll('/', '\\').toLowerCase()
      === right.trim().replace(/^"|"$/gu, '').replaceAll('/', '\\').toLowerCase()
}

/**
 * Own one user-initiated PixPin capture at a time. Clipboard callbacks are
 * accepted only when both the sequence and owner PID belong to this session.
 *
 * @param {{
 *   preparePixPin: () => Promise<{ executable: string, processIds: number[] }>,
 *   createClipboardWatcher: (handlers: { onChange: (change: { sequence: number, ownerPid: number, ownerPath?: string }) => void, onError: (error: Error) => void }) => { ready: Promise<{ sequence: number }>, stop?: () => void },
 *   invokeCapture: (executable: string, handlers: { onError: (error: Error) => void }) => { started: Promise<void>, stop?: () => void },
 *   readClipboardImage: (captureId: string) => Promise<{ ok: boolean, error?: string, attachment?: object }>,
 *   hideStage?: () => unknown | Promise<unknown>,
 *   restoreStage?: (snapshot: unknown) => unknown | Promise<unknown>,
 *   onChanged?: (event: Record<string, unknown>) => void,
 *   timeoutMs?: number, settleMs?: number, now?: () => number,
 *   setTimer?: (callback: () => void, delay: number) => unknown,
 *   clearTimer?: (timer: unknown) => void,
 *   createId?: () => string,
 * }} [options]
 */
export function createPixPinCaptureCoordinator(options = {}) {
  const {
    preparePixPin,
    createClipboardWatcher,
    invokeCapture,
    readClipboardImage,
    hideStage = () => undefined,
    restoreStage = () => undefined,
    onChanged = () => undefined,
    timeoutMs = pixpinCaptureTimeoutMs,
    settleMs = pixpinClipboardSettleMs,
    now = Date.now,
    setTimer = setTimeout,
    clearTimer = clearTimeout,
    createId = randomUUID,
  } = options
  if (![preparePixPin, createClipboardWatcher, invokeCapture, readClipboardImage].every(value => typeof value === 'function'))
    throw new TypeError('PixPin capture coordinator dependencies are required.')

  let active

  function snapshot(session = active) {
    if (!session)
      return { state: 'idle' }
    return {
      captureId: session.captureId,
      mode: session.mode,
      state: session.state,
      startedAt: session.startedAt,
      deadline: session.deadline,
    }
  }

  function publish(session, state, extra = {}) {
    session.state = state
    onChanged({ ...snapshot(session), ...extra })
  }

  async function finish(session, state, extra = {}) {
    if (active !== session || session.finishing)
      return false
    session.finishing = true
    session.finalState = state
    session.finalError = extra.error
    session.state = state
    clearTimer(session.timeoutTimer)
    clearTimer(session.settleTimer)
    session.watcher?.stop?.()
    session.capture?.stop?.()
    try {
      await restoreStage(session.stageSnapshot)
    }
    catch {
      // Capture state must still close even if the stage window was destroyed.
    }
    if (active === session)
      active = undefined
    publish(session, state, extra)
    return true
  }

  async function readStableClipboardImage(session, sequence) {
    if (active !== session
      || session.latestSequence !== sequence
      || !session.latestOwnerTrusted)
      return

    let result
    try {
      result = await readClipboardImage(session.captureId)
    }
    catch (error) {
      result = { ok: false, error: error instanceof Error ? error.message : String(error) }
    }
    if (active !== session || session.latestSequence !== sequence || !session.latestOwnerTrusted)
      return
    if (!result?.ok || !result.attachment) {
      await finish(session, 'failed', { error: result?.error || 'PixPin 已完成截图，但 Meguri 没有收到可读取的图片。' })
      return
    }
    await finish(session, 'received', { attachment: result.attachment })
  }

  function handleClipboardChange(session, change) {
    if (active !== session || session.state !== 'selecting')
      return
    const sequence = Number(change?.sequence)
    if (!Number.isSafeInteger(sequence) || sequence === session.initialSequence)
      return

    session.latestSequence = sequence
    session.latestOwnerTrusted = session.pixpinProcessIds.includes(Number(change?.ownerPid))
      && sameWindowsPath(change?.ownerPath, session.pixpinExecutable)
    clearTimer(session.settleTimer)

    if (!session.latestOwnerTrusted) {
      onChanged({
        ...snapshot(session),
        hint: '已忽略非 PixPin 写入的剪贴板内容。',
      })
      return
    }

    session.settleTimer = setTimer(() => {
      void readStableClipboardImage(session, sequence)
    }, settleMs)
  }

  async function start(mode) {
    if (!captureModes.has(mode))
      return { ok: false, error: '不支持的 PixPin 截图模式。' }
    if (active)
      return { ok: false, error: '已有 PixPin 截图正在进行，请先完成或取消它。', captureId: active.captureId }

    const session = {
      captureId: createId(),
      mode,
      state: 'starting',
      startedAt: now(),
      deadline: now() + timeoutMs,
      pixpinProcessIds: [],
      initialSequence: undefined,
      latestSequence: undefined,
      latestOwnerTrusted: false,
      timeoutTimer: undefined,
      settleTimer: undefined,
      watcher: undefined,
      capture: undefined,
      stageSnapshot: undefined,
    }
    active = session
    publish(session, 'starting')

    try {
      const prepared = await preparePixPin()
      if (active !== session)
        return { ok: false, error: 'PixPin 截图已取消。', captureId: session.captureId }
      if (!prepared?.executable || !Array.isArray(prepared.processIds) || prepared.processIds.length === 0)
        throw new Error('没有检测到正在运行的 PixPin 实例。请启动 PixPin 后重试。')
      session.pixpinProcessIds = prepared.processIds.map(Number).filter(Number.isSafeInteger)
      session.pixpinExecutable = prepared.executable
      if (session.pixpinProcessIds.length === 0)
        throw new Error('无法确认 PixPin 进程，已取消截图以避免接收其他应用的剪贴板内容。')

      session.stageSnapshot = await hideStage()
      if (active !== session)
        return { ok: false, error: 'PixPin 截图已取消。', captureId: session.captureId }

      session.watcher = createClipboardWatcher({
        onChange: change => handleClipboardChange(session, change),
        onError: error => {
          if (active === session)
            void finish(session, 'failed', { error: error instanceof Error ? error.message : 'Windows 剪贴板监听器已停止。' })
        },
      })
      const watcherReadyTimer = setTimer(() => {
        void finish(session, 'failed', { error: 'Windows 剪贴板监听器启动超时。请确认系统 PowerShell 可用后重试。' })
      }, pixpinWatcherStartupTimeoutMs)
      let baseline
      try {
        baseline = await session.watcher.ready
      }
      finally {
        clearTimer(watcherReadyTimer)
      }
      if (active !== session) {
        return {
          ok: session.finalState === 'received',
          captureId: session.captureId,
          state: session.finalState,
          error: session.finalError,
        }
      }
      const initialSequence = Number(baseline?.sequence)
      if (!Number.isSafeInteger(initialSequence))
        throw new Error('无法读取 Windows 剪贴板代数，已取消截图。')
      session.initialSequence = initialSequence

      session.deadline = now() + timeoutMs
      session.timeoutTimer = setTimer(() => {
        void finish(session, 'timed_out', { error: '等待 PixPin 截图超时。可在 PixPin 按 Esc 取消，或重新发起截图。' })
      }, timeoutMs)
      publish(session, 'selecting')

      session.capture = invokeCapture(prepared.executable, {
        onError: error => {
          if (active === session)
            void finish(session, 'failed', { error: error instanceof Error ? error.message : '无法启动 PixPin 截图。' })
        },
      })
      await session.capture.started
      if (active !== session)
        return { ok: session.finalState === 'received', captureId: session.captureId, state: session.finalState }
      return { ok: true, captureId: session.captureId, state: session.state }
    }
    catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (session.finalState) {
        return {
          ok: session.finalState === 'received',
          captureId: session.captureId,
          state: session.finalState,
          error: session.finalError || message,
        }
      }
      await finish(session, 'failed', { error: message })
      return { ok: false, error: message, captureId: session.captureId }
    }
  }

  async function cancel(captureId) {
    if (!active || active.captureId !== captureId)
      return { ok: false, error: '这次 PixPin 截图已结束或已失效。' }
    await finish(active, 'cancelled', { error: '已取消 Meguri 的截图接收。若 PixPin 仍显示框选界面，请在 PixPin 按 Esc。' })
    return { ok: true, captureId }
  }

  async function cancelActive(reason = 'PixPin 截图已结束。') {
    if (!active)
      return false
    return finish(active, 'cancelled', { error: reason })
  }

  return Object.freeze({
    start,
    cancel,
    cancelActive,
    status: () => snapshot(),
    get activeCaptureId() { return active?.captureId },
  })
}
