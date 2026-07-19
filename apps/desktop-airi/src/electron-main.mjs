import { app, BrowserWindow, globalShortcut, ipcMain, screen } from 'electron'

const homeUrl = process.env.MEGURI_DESKTOP_URL ?? 'http://127.0.0.1:5173/?mode=overlay'
const overlayWidth = Number(process.env.MEGURI_OVERLAY_WIDTH ?? 400)
const overlayHeight = Number(process.env.MEGURI_OVERLAY_HEIGHT ?? 610)

let windowRef

function createOverlay() {
  const display = screen.getPrimaryDisplay()
  const bounds = display.workArea
  windowRef = new BrowserWindow({
    width: overlayWidth,
    height: overlayHeight,
    x: Math.max(bounds.x + bounds.width - overlayWidth - 24, bounds.x),
    y: Math.max(bounds.y + 24, bounds.y),
    frame: false,
    transparent: true,
    resizable: true,
    hasShadow: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    show: false,
    webPreferences: { contextIsolation: true, sandbox: true },
  })
  windowRef.setAlwaysOnTop(true, 'floating')
  windowRef.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  windowRef.loadURL(homeUrl)
  windowRef.once('ready-to-show', () => windowRef?.showInactive())
  windowRef.on('closed', () => { windowRef = undefined })
}

app.whenReady().then(() => {
  createOverlay()
  globalShortcut.register('CommandOrControl+Shift+M', () => {
    if (!windowRef) return
    windowRef.isVisible() ? windowRef.hide() : windowRef.showInactive()
  })
  ipcMain.handle('meguri-overlay-toggle', () => {
    if (!windowRef) return false
    if (windowRef.isVisible()) windowRef.hide()
    else windowRef.showInactive()
    return windowRef.isVisible()
  })
})

app.on('will-quit', () => globalShortcut.unregisterAll())
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
