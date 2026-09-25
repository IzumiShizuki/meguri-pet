import { app, BrowserWindow, globalShortcut, ipcMain, screen, shell } from 'electron'
import { existsSync, realpathSync, statSync } from 'node:fs'
import { extname, isAbsolute, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const homeUrl = process.env.MEGURI_DESKTOP_URL ?? 'http://127.0.0.1:5173/?mode=overlay'
const preload = fileURLToPath(new URL('./preload.cjs', import.meta.url))
const overlayWidth = Number(process.env.MEGURI_OVERLAY_WIDTH ?? 400)
const overlayHeight = Number(process.env.MEGURI_OVERLAY_HEIGHT ?? 610)
const projectRoot = resolve(process.env.MEGURI_PROJECT_ROOT ?? 'D:\\program\\meguri-pet')
const artifactRoots = [join(projectRoot, 'reports'), join(projectRoot, 'output')]
// Passive document types only; never add executable or script-like extensions,
// because entries in this set become one-click shell.openPath targets.
const artifactExtensions = new Set(['.md', '.pdf', '.jpg', '.jpeg', '.png', '.gif', '.webp', '.txt', '.csv', '.json', '.html'])
const trustedHosts = new Set(['127.0.0.1', 'localhost', '[::1]'])

let windowRef

function trustedRenderer(url) {
  try { return trustedHosts.has(new URL(url).hostname) } catch { return false }
}

function generatedArtifact(pathValue) {
  if (typeof pathValue !== 'string' || !pathValue.trim()) return undefined
  let candidate
  try { candidate = realpathSync(resolve(pathValue)) } catch { return undefined }
  const insideRoot = artifactRoots.some(root => {
    try {
      const remainder = relative(realpathSync(root), candidate)
      return remainder === '' || (!remainder.startsWith('..') && !isAbsolute(remainder))
    } catch { return false }
  })
  if (!insideRoot || !artifactExtensions.has(extname(candidate).toLowerCase())) return undefined
  try { return existsSync(candidate) && statSync(candidate).isFile() ? candidate : undefined } catch { return undefined }
}

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
    webPreferences: { contextIsolation: true, sandbox: true, preload },
  })
  windowRef.setAlwaysOnTop(true, 'floating')
  windowRef.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  windowRef.loadURL(homeUrl)
  windowRef.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const parsed = new URL(url)
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:') shell.openExternal(parsed.href).catch(() => {})
    } catch { /* Invalid links remain closed. */ }
    return { action: 'deny' }
  })
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
  ipcMain.handle('meguri-open-artifact', async (event, pathValue) => {
    if (!trustedRenderer(event.senderFrame?.url ?? '')) return { ok: false, error: 'untrusted renderer' }
    const artifact = generatedArtifact(pathValue)
    if (!artifact) return { ok: false, error: 'artifact path is not allowed' }
    const error = await shell.openPath(artifact)
    return error ? { ok: false, error } : { ok: true }
  })
  ipcMain.handle('meguri-artifact-icon', async (event, pathValue) => {
    // Resolves the Windows default-application icon for a generated artifact so
    // the floating bubble can show what a click would open. Same allow-list as
    // opening; the renderer receives only a small data URL.
    if (!trustedRenderer(event.senderFrame?.url ?? '')) return { ok: false, error: 'untrusted renderer' }
    const artifact = generatedArtifact(pathValue)
    if (!artifact) return { ok: false, error: 'artifact path is not allowed' }
    try {
      const icon = await app.getFileIcon(artifact, { size: 'large' })
      if (!icon || icon.isEmpty()) return { ok: false, error: 'no icon available' }
      return { ok: true, dataUrl: icon.toDataURL() }
    } catch (error) {
      return { ok: false, error: String(error?.message ?? error) }
    }
  })
})

app.on('will-quit', () => globalShortcut.unregisterAll())
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
