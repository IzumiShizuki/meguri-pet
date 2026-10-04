export function desktopTrayStatus({ readinessStatus, rendererReady, visible, minimized }) {
  if (readinessStatus === 'failed') return '服务未就绪'
  if (readinessStatus !== 'ready' || !rendererReady) return '正在启动'
  return visible && !minimized ? '正在运行' : '桌宠已隐藏'
}

export function createDesktopTray({ createTray, buildMenu, iconPath, getState, reveal, openSettings, quit, onError = console.warn }) {
  let tray
  let disposed = false

  function refresh() {
    if (disposed) return
    const status = desktopTrayStatus(getState())
    tray.setToolTip(`Meguri · ${status}`)
    tray.setContextMenu(buildMenu([
      { label: `Meguri · ${status}`, enabled: false },
      { type: 'separator' },
      { label: '显示桌宠（居中）', click: perform(() => reveal()) },
      { label: '打开聊天', click: perform(() => reveal({ chat: true })) },
      { label: '设置', click: perform(openSettings) },
      { type: 'separator' },
      { label: '退出 Meguri', click: perform(quit) },
    ]))
  }

  function perform(action) {
    return async () => {
      if (disposed) return
      try {
        await action()
        refresh()
      }
      catch (error) { onError(error) }
    }
  }

  function dispose() {
    if (disposed) return
    disposed = true
    tray?.removeAllListeners()
    tray?.destroy()
  }

  try {
    tray = createTray(iconPath)
    tray.on('click', perform(() => reveal()))
    tray.on('double-click', perform(() => reveal()))
    refresh()
    return { refresh, dispose }
  }
  catch (error) {
    dispose()
    onError(error)
    return undefined
  }
}

// Restoring always shows the window. Repeated tray clicks never toggle it hidden.
export function recoverDesktopWindow(window, { center, alwaysOnTop = false }) {
  if (!window || window.isDestroyed()) return false
  if (window.isMinimized()) window.restore()
  center()
  window.setAlwaysOnTop(alwaysOnTop, alwaysOnTop ? 'floating' : 'normal')
  window.show()
  window.focus()
  return true
}

export async function revealDesktop({ getStage, createStage, getStartup, createStartup, isReady, isExiting, recoverStage, recoverStartup, openChat }, { chat = false } = {}) {
  if (isExiting()) return
  const usable = window => window && !window.isDestroyed()
  if (isReady()) {
    if (!usable(getStage())) await createStage()
    if (isExiting()) return
    const stage = getStage()
    if (usable(stage)) {
      if (chat) openChat()
      recoverStage(stage)
      return
    }
  }
  if (!usable(getStartup())) await createStartup()
  if (!isExiting()) recoverStartup(getStartup())
}
