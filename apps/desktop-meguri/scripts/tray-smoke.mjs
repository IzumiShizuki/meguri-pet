import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { app, BrowserWindow, Menu, nativeImage, Tray } from 'electron'
import { createDesktopTray, recoverDesktopWindow, revealDesktop } from '../src/main/desktop-tray.mjs'

// Synthetic windows and a fresh profile: no user's data, gateway or remote messages.
const profile = mkdtempSync(join(tmpdir(), 'meguri-tray-smoke-'))
app.setPath('userData', profile)
const iconPath = resolve(dirname(fileURLToPath(import.meta.url)), '../assets/meguri-tray.ico')
let controller
let stage
let tray
const errors = []
app.on('window-all-closed', () => {})
const timeout = setTimeout(() => { console.error('native tray smoke timed out'); app.exit(2) }, 15000)

app.whenReady().then(async () => {
  assert.equal(nativeImage.createFromPath(iconPath).isEmpty(), false, 'ICO must decode')
  let chatOpened = false
  let settingsOpened = false
  let menu
  const recovery = {
    getStage: () => stage,
    createStage: async () => {
      stage = new BrowserWindow({ width: 220, height: 150, show: false, title: 'Meguri tray smoke', webPreferences: { sandbox: true } })
      await stage.loadURL('data:text/html,<title>Meguri tray smoke</title><p>Isolated recovery check</p>')
    },
    getStartup: () => undefined, createStartup: () => { throw new Error('unexpected startup window') },
    isReady: () => true, isExiting: () => false,
    recoverStage: window => recoverDesktopWindow(window, { center: () => window.center(), alwaysOnTop: false }),
    recoverStartup: () => { throw new Error('unexpected startup recovery') },
    openChat: () => { chatOpened = true },
  }
  controller = createDesktopTray({
    iconPath,
    createTray: path => { tray = new Tray(path); return tray },
    buildMenu: template => { menu = Menu.buildFromTemplate(template); return menu },
    getState: () => ({ readinessStatus: 'ready', rendererReady: true, visible: stage && !stage.isDestroyed() && stage.isVisible() }),
    reveal: options => revealDesktop(recovery, options),
    openSettings: () => { settingsOpened = true },
    quit: () => controller.dispose(), onError: error => errors.push(error),
  })
  assert.ok(controller, 'native tray must register')
  await tray.listeners('click')[0]()
  assert.equal(stage.isVisible(), true)
  assert.equal(stage.isAlwaysOnTop(), false)
  stage.hide()
  controller.refresh()
  assert.equal(menu.items[0].label, 'Meguri · 桌宠已隐藏')
  await tray.listeners('double-click')[0]()
  assert.equal(stage.isVisible(), true)
  await tray.listeners('click')[0]()
  assert.equal(stage.isVisible(), true, 'repeated clicks must not hide')
  await menu.items.find(item => item.label === '打开聊天').click()
  await menu.items.find(item => item.label === '设置').click()
  assert.ok(chatOpened && settingsOpened)
  stage.destroy()
  await tray.listeners('click')[0]()
  assert.equal(stage.isDestroyed(), false, 'closed stage must be recreated')
  assert.equal(stage.isVisible(), true)
  const bounds = tray.getBounds()
  if (process.platform === 'win32') assert.ok(bounds.width > 0 && bounds.height > 0, 'Windows must expose registered icon bounds')
  await menu.items.find(item => item.label === '退出 Meguri').click()
  assert.equal(tray.isDestroyed(), true)
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ ok: true, check: 'native tray registration, hidden/closed window recovery, menu and disposal', bounds }))
  app.exit(0)
}).catch(error => { console.error(error); app.exit(1) }).finally(() => clearTimeout(timeout))
