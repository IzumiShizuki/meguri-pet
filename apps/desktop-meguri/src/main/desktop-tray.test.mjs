import { EventEmitter } from 'node:events'
import { describe, expect, it, vi } from 'vitest'
import { createDesktopTray, desktopTrayStatus, recoverDesktopWindow, revealDesktop } from './desktop-tray.mjs'

function fixture() {
  const tray = Object.assign(new EventEmitter(), {
    setToolTip: vi.fn(), setContextMenu: vi.fn(), destroy: vi.fn(),
  })
  const options = {
    createTray: vi.fn(() => tray), buildMenu: template => template, iconPath: 'meguri-tray.ico',
    getState: vi.fn(() => ({ readinessStatus: 'ready', rendererReady: true, visible: false })),
    reveal: vi.fn(), openSettings: vi.fn(), quit: vi.fn(), onError: vi.fn(),
  }
  return { tray, options, menu: () => tray.setContextMenu.mock.lastCall[0] }
}

describe('desktop tray presence and actions', () => {
  it.each([
    [{ readinessStatus: 'checking' }, '正在启动'],
    [{ readinessStatus: 'failed', rendererReady: true, visible: true }, '服务未就绪'],
    [{ readinessStatus: 'ready', rendererReady: false, visible: true }, '正在启动'],
    [{ readinessStatus: 'ready', rendererReady: true, visible: true }, '正在运行'],
    [{ readinessStatus: 'ready', rendererReady: true, visible: false }, '桌宠已隐藏'],
    [{ readinessStatus: 'ready', rendererReady: true, visible: true, minimized: true }, '桌宠已隐藏'],
  ])('reports local startup/visibility state %j', (state, label) => {
    expect(desktopTrayStatus(state)).toBe(label)
  })

  it('keeps a hidden pet entry and refreshes tooltip and disabled status together', () => {
    const { tray, options, menu } = fixture()
    const controller = createDesktopTray(options)
    expect(tray.setToolTip).toHaveBeenLastCalledWith('Meguri · 桌宠已隐藏')
    expect(menu()[0]).toEqual({ label: 'Meguri · 桌宠已隐藏', enabled: false })
    options.getState.mockReturnValue({ readinessStatus: 'ready', rendererReady: true, visible: true })
    controller.refresh()
    expect(tray.setToolTip).toHaveBeenLastCalledWith('Meguri · 正在运行')
    expect(menu()[0].label).toBe('Meguri · 正在运行')
    expect(tray.destroy).not.toHaveBeenCalled()
  })

  it('always reveals on single/double clicks and supplies chat/settings/quit actions', async () => {
    const { tray, options, menu } = fixture()
    createDesktopTray(options)
    await tray.listeners('click')[0]()
    await tray.listeners('click')[0]()
    await tray.listeners('double-click')[0]()
    await menu().find(item => item.label === '打开聊天').click()
    expect(options.reveal.mock.calls).toEqual([[], [], [], [{ chat: true }]])
    await menu().find(item => item.label === '设置').click()
    await menu().find(item => item.label === '退出 Meguri').click()
    expect(options.openSettings).toHaveBeenCalledOnce()
    expect(options.quit).toHaveBeenCalledOnce()
  })

  it('catches an asynchronous action failure and allows later recovery', async () => {
    const { tray, options } = fixture()
    const error = new Error('window unavailable')
    options.reveal.mockRejectedValueOnce(error)
    createDesktopTray(options)
    await tray.listeners('click')[0]()
    expect(options.onError).toHaveBeenCalledWith(error)
    await tray.listeners('click')[0]()
    expect(options.reveal).toHaveBeenCalledTimes(2)
  })

  it('cleans up once and prevents saved callbacks/late completions using a destroyed tray', async () => {
    const { tray, options, menu } = fixture()
    let finish
    options.reveal.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const controller = createDesktopTray(options)
    const savedAction = menu().find(item => item.label === '设置').click
    const inFlight = tray.listeners('click')[0]()
    controller.dispose()
    controller.dispose()
    finish()
    await inFlight
    await savedAction()
    expect(tray.destroy).toHaveBeenCalledOnce()
    expect(tray.listenerCount('click')).toBe(0)
    expect(options.openSettings).not.toHaveBeenCalled()
    expect(tray.setContextMenu).toHaveBeenCalledOnce()
  })

  it('records registration and menu failures while allowing ordinary launch', () => {
    const { tray, options } = fixture()
    options.createTray.mockImplementationOnce(() => { throw new Error('Explorer unavailable') })
    expect(createDesktopTray(options)).toBeUndefined()
    expect(options.onError).toHaveBeenCalledOnce()
    options.buildMenu = () => { throw new Error('menu unavailable') }
    expect(createDesktopTray(options)).toBeUndefined()
    expect(tray.destroy).toHaveBeenCalledOnce()
    expect(tray.listenerCount('click')).toBe(0)
  })
})

describe('desktop recovery', () => {
  it.each([true, false])('restores, centers, shows and focuses while preserving alwaysOnTop=%s', alwaysOnTop => {
    const order = []
    const window = {
      isDestroyed: () => false, isMinimized: () => true,
      restore: () => order.push('restore'), setAlwaysOnTop: vi.fn(),
      show: () => order.push('show'), focus: () => order.push('focus'),
    }
    expect(recoverDesktopWindow(window, { center: () => order.push('center'), alwaysOnTop })).toBe(true)
    expect(order).toEqual(['restore', 'center', 'show', 'focus'])
    expect(window.setAlwaysOnTop).toHaveBeenCalledWith(alwaysOnTop, alwaysOnTop ? 'floating' : 'normal')
    expect(recoverDesktopWindow(undefined, { center: vi.fn() })).toBe(false)
  })

  function recoveryFixture() {
    let stage
    let startup
    const options = {
      getStage: () => stage, createStage: vi.fn(async () => { stage = { isDestroyed: () => false } }),
      getStartup: () => startup, createStartup: vi.fn(async () => { startup = { isDestroyed: () => false } }),
      isReady: () => true, isExiting: () => false,
      recoverStage: vi.fn(), recoverStartup: vi.fn(), openChat: vi.fn(),
    }
    return options
  }

  it('recreates a missing stage once and opens chat only when explicitly selected', async () => {
    const options = recoveryFixture()
    await revealDesktop(options)
    await revealDesktop(options, { chat: true })
    expect(options.createStage).toHaveBeenCalledOnce()
    expect(options.recoverStage).toHaveBeenCalledTimes(2)
    expect(options.openChat).toHaveBeenCalledOnce()
    expect(options.createStartup).not.toHaveBeenCalled()
  })

  it('reveals the startup status instead of bypassing failed checks', async () => {
    const options = recoveryFixture()
    options.isReady = () => false
    await revealDesktop(options, { chat: true })
    await revealDesktop(options)
    expect(options.createStage).not.toHaveBeenCalled()
    expect(options.createStartup).toHaveBeenCalledOnce()
    expect(options.recoverStartup).toHaveBeenCalledTimes(2)
    expect(options.openChat).not.toHaveBeenCalled()
  })

  it('does not recover windows during shutdown, including after awaited creation', async () => {
    const options = recoveryFixture()
    let exiting = true
    options.isExiting = () => exiting
    await revealDesktop(options)
    expect(options.createStage).not.toHaveBeenCalled()
    exiting = false
    options.createStage.mockImplementation(async () => { exiting = true })
    await revealDesktop(options)
    expect(options.recoverStage).not.toHaveBeenCalled()
    expect(options.createStartup).not.toHaveBeenCalled()
  })
})
