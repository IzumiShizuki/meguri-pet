import { app, BrowserWindow, clipboard, dialog, globalShortcut, ipcMain, Menu, nativeImage, Notification, powerMonitor, protocol, safeStorage, screen, shell, systemPreferences, Tray } from 'electron'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'

import channels from '../shared/channels.cjs'
import { resolveGeneratedArtifact } from './artifact-policy.mjs'
import { createClipboardImageAttachment, inspectDroppedAttachment } from './attachment-policy.mjs'
import { createCharacterPackStore } from './character-pack-store.mjs'
import { mergeDesktopPreferences, normalizeDesktopPreferences, privacyPolicyVersion } from './desktop-preferences.mjs'
import { createCompanionActivity } from './companion-activity.mjs'
import { createDesktopUpdateChecker } from './desktop-updates.mjs'
import { createDesktopDiagnosticsReport } from './desktop-diagnostics.mjs'
import { loadDesktopWallpaper } from './desktop-wallpaper.mjs'
import { createLive2DStore } from './live2d-store.mjs'
import { createLive2DProtocolHandler } from './live2d-protocol.mjs'
import { createSpeechResources } from './speech-resources.mjs'
import { createDesktopTray, recoverDesktopWindow, revealDesktop } from './desktop-tray.mjs'
import { ensureEverythingIpc } from './everything-runtime.mjs'
import { cycleOutfitCode, meguriGlobalShortcutBindings, registerMeguriGlobalShortcuts } from './global-shortcuts.mjs'
import { createLocalAgentRuntime, LocalAgentError } from './local-agent-runtime.mjs'
import { readLocalAgentConfig, saveLocalAgentConfig } from './local-agent-config.mjs'
import {
  readMultimodalRelayConfig,
  saveMultimodalRelayConfig,
  testMultimodalRelayConfig,
} from './multimodal-relay-config.mjs'
import { createReadinessCoordinator } from './readiness.mjs'
import { createSettingsWindowOptions, resolveSettingsBounds } from './settings-window-options.mjs'
import { createStudioWindowOptions, resolveStudioBounds } from './studio-window-options.mjs'
import { createStageWindowOptions } from './stage-window-options.mjs'
import { createStageLayoutController, resolveInitialStageLayout } from './stage-layout.mjs'
import { createShizukiTimePrismClient, ShizukiTimePrismError } from './shizuki-timeprism.mjs'
import { createShizukiReminderRuntime } from './shizuki-reminders.mjs'
import { createShizukiQqDelivery } from './shizuki-qq-delivery.mjs'
import { createWindowsVoiceRecognizer } from './voice-recognition.mjs'
import { showWindowAndFocus, toggleWindowVisibility } from './window-visibility.mjs'
import { createPixPinCaptureCoordinator } from './pixpin-capture.mjs'
import {
  createWindowsClipboardWatcher,
  discoverPixPinExecutable,
  ensurePixPinRunning,
  invokePixPinCapture,
  validatePixPinExecutable,
} from './pixpin-runtime.mjs'

const currentDirectory = dirname(fileURLToPath(import.meta.url))
const appRoot = resolve(currentDirectory, '../..')
const projectRoot = resolve(process.env.MEGURI_PROJECT_ROOT ?? join(appRoot, '../..'))
const preloadPath = resolve(currentDirectory, '../preload/index.cjs')
const rendererDevelopmentUrl = process.env.MEGURI_RENDERER_URL
const rendererProductionFile = resolve(appRoot, 'dist/renderer/index.html')
const artifactRoots = [join(projectRoot, 'reports'), join(projectRoot, 'output')]
const desktopSession = `meguri-shell-${process.pid}`
const execFileAsync = promisify(execFile)
const startupSettingsScript = join(projectRoot, 'ops/scripts/configure-meguri-desktop-startup.ps1')
const windowsPowerShell = join(process.env.SystemRoot ?? 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe')
const windowsTaskScheduler = join(process.env.SystemRoot ?? 'C:\\Windows', 'System32/schtasks.exe')
let companionActivityTimer
let companionStartupLogged = false
const companionActivity = createCompanionActivity({
  idleTime: () => powerMonitor.getSystemIdleTime(),
  idleState: threshold => powerMonitor.getSystemIdleState(threshold),
  enabled: () => {
    const preferences = readPreferences()
    return preferences.companion.screenObservationEnabled && preferences.companion.initiative !== 'off'
      && preferences.companion.focus === 'available' && preferences.privacy.remoteChatAllowed
      && preferences.privacy.acceptedPolicyVersion === privacyPolicyVersion
      && stageWindow?.isVisible() && !stageWindow.isMinimized()
  },
  observe: async () => {
    const result = await execFileAsync(windowsPowerShell, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', join(currentDirectory, 'foreground-ocr.ps1'), '-ExcludeProcessId', String(process.pid)],
    { windowsHide: true, timeout: 10_000, maxBuffer: 32 * 1024, encoding: 'utf8' })
    return JSON.parse(result.stdout.replace(/^\uFEFF/u, '').trim())
  },
})

function broadcastCompanionActivity() {
  const status = companionActivity.status()
  for (const window of [stageWindow, settingsWindow])
    if (window && !window.isDestroyed()) window.webContents.send(channels.companionActivityChanged, status)
}

function startCompanionActivity() {
  for (const [event, method] of [['lock-screen', 'lock'], ['unlock-screen', 'unlock'], ['suspend', 'suspend'], ['resume', 'resume']])
    powerMonitor.on(event, () => { companionActivity[method](); broadcastCompanionActivity() })
  companionActivityTimer = setInterval(broadcastCompanionActivity, 5000)
  companionActivityTimer.unref?.()
}
const desktopVersion = JSON.parse(readFileSync(join(appRoot, 'package.json'), 'utf8')).version
const updateChecker = createDesktopUpdateChecker({ currentVersion: desktopVersion, openExternal: url => shell.openExternal(url) })
let startupUpdateChecked = false
const diagnosticFailures = []

protocol.registerSchemesAsPrivileged([{ scheme: 'meguri-live2d', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }])

function recordDiagnosticFailure(source, code) {
  diagnosticFailures.push({ source, code, at: new Date().toISOString() })
  if (diagnosticFailures.length > 100) diagnosticFailures.shift()
}

function broadcastUpdateState(state) {
  for (const window of [stageWindow, settingsWindow])
    if (window && !window.isDestroyed()) window.webContents.send(channels.updatesChanged, state)
}

async function maybeCheckStartupUpdate() {
  const preferences = readPreferences()
  if (startupUpdateChecked || process.env.MEGURI_DISABLE_UPDATE_CHECK === '1'
    || preferences.privacy.acceptedPolicyVersion !== privacyPolicyVersion
    || !preferences.privacy.remoteChatAllowed || !preferences.privacy.checkUpdatesOnStartup) return
  startupUpdateChecked = true
  broadcastUpdateState(await updateChecker.check())
}

let startupWindow
let stageWindow
let stageLayoutController
let stagePresentationSurface = 'ambient'
let settingsWindow
let studioWindow
let desktopTray
let boundsSaveTimer
let readinessRetryTimer
let registeredVisibilityShortcut
let registeredMeguriShortcuts = []
let appExiting = false
let startupConfigurationStatus = { ok: true, message: '尚未同步 Windows 启动设置。' }

const readiness = createReadinessCoordinator({ desktopSession })
let characterPackStore
let live2dStore
const speechResources = createSpeechResources({ projectRoot })
let localAgentRuntime
let localAgentConfigPath
let workObservationTimer
let pixpinCaptureCoordinator
let shizukiClient
let shizukiReminderRuntime
let shizukiQqDelivery
let voiceRecognizer
let stageReadyForReminders = false
const pendingShizukiReminders = []
const locallyNotifiedShizukiReminders = new Set()

function shizukiTimePrism() {
  if (!shizukiClient) {
    shizukiClient = createShizukiTimePrismClient({
      filePath: join(app.getPath('userData'), 'shizuki-session.json'),
      safeStorage,
    })
    shizukiClient.initialize()
  }
  return shizukiClient
}

function qqReminders() {
  if (!shizukiQqDelivery)
    shizukiQqDelivery = createShizukiQqDelivery({ filePath: join(app.getPath('userData'), 'shizuki-qq-outbox.json') })
  return shizukiQqDelivery
}

function shizukiStatus() {
  return { ...shizukiTimePrism().status(), ...qqReminders().status(), ...shizukiReminderRuntime?.status() }
}

function deliverShizukiReminder(reminder) {
  const account = shizukiTimePrism().status()
  const ownerUserId = qqReminders().status().mainUserId
  const mainAccount = account.connected && ownerUserId > 0 && account.userId === ownerUserId
  if (!locallyNotifiedShizukiReminders.has(reminder.key)) {
    const event = {
      todoId: reminder.todoId,
      title: reminder.title,
      dueAt: reminder.dueAt,
      triggerAt: reminder.triggerAt,
      late: reminder.late,
      mainAccount,
    }
    if (stageReadyForReminders && stageWindow && !stageWindow.isDestroyed())
      stageWindow.webContents.send(channels.shizukiReminder, event)
    else {
      pendingShizukiReminders.push(event)
      if (pendingShizukiReminders.length > 5)
        pendingShizukiReminders.shift()
    }
    try {
      if (Notification.isSupported()) {
        const notice = new Notification({
          title: reminder.late ? 'Meguri 补发提醒' : 'Meguri 到点提醒',
          body: reminder.title,
        })
        notice.on('click', () => {
          if (stageWindow && !stageWindow.isDestroyed()) stageWindow.show()
        })
        notice.show()
      }
    }
    catch {
      // The in-app card still delivers the reminder when OS notifications fail.
    }
    locallyNotifiedShizukiReminders.add(reminder.key)
    if (locallyNotifiedShizukiReminders.size > 1000)
      locallyNotifiedShizukiReminders.delete(locallyNotifiedShizukiReminders.values().next().value)
  }
  if (mainAccount && !qqReminders().enqueue(reminder, account.userId, account.origin))
    throw new Error('QQ reminder outbox is full')
}

function shizukiReminders() {
  if (!shizukiReminderRuntime) {
    shizukiReminderRuntime = createShizukiReminderRuntime({
      client: shizukiTimePrism(),
      filePath: join(app.getPath('userData'), 'shizuki-reminder-dedupe.json'),
      onReminder: deliverShizukiReminder,
      onError: () => {
        if (!shizukiTimePrism().status().connected)
          shizukiReminderRuntime?.stop()
      },
    })
  }
  return shizukiReminderRuntime
}

function windowsVoice() {
  if (!voiceRecognizer) {
    voiceRecognizer = createWindowsVoiceRecognizer({
      powershellPath: windowsPowerShell,
      scriptPath: join(currentDirectory, 'voice-recognition.ps1'),
    })
  }
  return voiceRecognizer
}

function shizukiFailure(error) {
  if (error instanceof ShizukiTimePrismError)
    return { ok: false, error: error.message, code: error.code }
  return { ok: false, error: 'Shizuki 操作失败，请稍后重试。', code: 'unavailable' }
}

function characterPacks() {
  if (!characterPackStore)
    characterPackStore = createCharacterPackStore({ root: join(app.getPath('userData'), 'character-packs') })
  return characterPackStore
}

function localAgents() {
  if (!localAgentRuntime) {
    localAgentConfigPath = resolveLocalAgentConfigPath()
    localAgentRuntime = createLocalAgentRuntime({
      config: readLocalAgentConfig(localAgentConfigPath),
      logger: message => console.warn(message),
    })
  }
  return localAgentRuntime
}

function resolveLocalAgentConfigPath() {
  return process.env.MEGURI_LOCAL_AGENT_CONFIG_FILE
    || join(app.getPath('userData'), 'local-agents.json')
}

function broadcastWork(snapshot) {
  for (const window of [stageWindow, settingsWindow]) {
    if (window && !window.isDestroyed())
      window.webContents.send(channels.workChanged, snapshot)
  }
}

/** Reads exact receipts only. It never sends, retries or cancels remote work. */
async function observeWork() {
  const runtime = localAgents()
  let snapshot
  try {
    snapshot = await runtime.snapshot()
  }
  catch {
    return
  }
  const pending = snapshot.requests.filter(record =>
    record.confirmedAt && ['accepted', 'forwarding', 'unknown'].includes(record.phase))
  for (const record of pending.slice(0, 5)) {
    try {
      await runtime.refresh(record.id)
    }
    catch {
      // A failed observation leaves the previous receipt untouched.
    }
  }
  broadcastWork(await runtime.snapshot().catch(() => snapshot))
}

function startWorkObservation() {
  if (workObservationTimer)
    return
  workObservationTimer = setInterval(() => { void observeWork() }, 5000)
  workObservationTimer.unref?.()
}

function stopWorkObservation() {
  clearInterval(workObservationTimer)
  workObservationTimer = undefined
}

function localAgentFailure(error) {
  if (error instanceof LocalAgentError)
    return { ok: false, error: error.message, code: error.code }
  return { ok: false, error: '本机 Agent 操作失败，请稍后重试。', code: 'unavailable' }
}

async function openNativeWork(id) {
  const runtime = localAgents()
  const { url } = await runtime.nativeUrl(id)
  if (!url)
    return { ok: false, error: '没有可打开的本地任务地址。' }
  try {
    const parsed = new URL(url)
    // Only the two known native schemes may reach the shell.
    if (!['http:', 'https:', 'codex:'].includes(parsed.protocol))
      return { ok: false, error: '拒绝了不受支持的本地任务地址。' }
    await shell.openExternal(url)
    return { ok: true, url }
  }
  catch {
    return { ok: false, error: '无法打开本地任务地址。' }
  }
}

function stateFile() {
  return join(app.getPath('userData'), 'desktop-state.json')
}

function readDesktopState() {
  try {
    const value = JSON.parse(readFileSync(stateFile(), 'utf8'))
    return value && typeof value === 'object' ? value : {}
  }
  catch {
    return {}
  }
}

function writeDesktopState(patch) {
  const path = stateFile()
  mkdirSync(dirname(path), { recursive: true })
  const next = { ...readDesktopState(), ...patch }
  writeFileSync(path, `${JSON.stringify(next, null, 2)}\n`, 'utf8')
}

async function resolvePixPinExecutable() {
  const found = await discoverPixPinExecutable({
    configuredPath: readDesktopState().pixpinExecutablePath,
  })
  if (found.ok)
    return found.executable

  const options = {
    title: '选择 PixPin.exe',
    buttonLabel: '选择 PixPin',
    properties: ['openFile'],
    filters: [{ name: 'PixPin', extensions: ['exe'] }],
  }
  const parent = stageWindow && !stageWindow.isDestroyed() ? stageWindow : undefined
  const selected = parent
    ? await dialog.showOpenDialog(parent, options)
    : await dialog.showOpenDialog(options)
  if (selected.canceled || !selected.filePaths[0])
    throw new Error('没有找到 PixPin.exe。请从 Meguri 的截图按钮重新选择 PixPin 安装目录里的 PixPin.exe。')

  const checked = validatePixPinExecutable(selected.filePaths[0])
  if (!checked.ok)
    throw new Error(checked.error)
  writeDesktopState({ pixpinExecutablePath: checked.executable })
  return checked.executable
}

function readPixPinClipboardImage(captureId) {
  let image
  let formats = []
  try {
    formats = clipboard.availableFormats()
    image = clipboard.readImage()
  }
  catch {
    return { ok: false, error: '无法读取 PixPin 的剪贴板截图。请重新截图后再试。' }
  }
  if (!image || image.isEmpty()) {
    const fileBacked = formats.some(format => /file(name|drop|contents|groupdescriptor)|text\/uri-list/iu.test(format))
    return {
      ok: false,
      error: fileBacked
        ? 'PixPin 当前把截图复制为文件。请在 PixPin「配置 → 系统」中将「复制图像为文件」设为「禁用」，然后重试。'
        : 'PixPin 已更新剪贴板，但其中没有可读取的图片。请确认截图动作使用「复制图像」。',
    }
  }
  try {
    return createClipboardImageAttachment(image.toPNG(), new Date(), captureId)
  }
  catch {
    return { ok: false, error: '截图转换为 PNG 失败，请重新截取较小区域后再试。' }
  }
}

function pixpinCaptures() {
  if (!pixpinCaptureCoordinator) {
    pixpinCaptureCoordinator = createPixPinCaptureCoordinator({
      preparePixPin: async () => {
        const executable = await resolvePixPinExecutable()
        return ensurePixPinRunning(executable)
      },
      createClipboardWatcher: options => createWindowsClipboardWatcher(options),
      invokeCapture: (executable, options) => invokePixPinCapture(executable, options),
      readClipboardImage: readPixPinClipboardImage,
      hideStage: () => {
        const windows = [startupWindow, settingsWindow, studioWindow, stageWindow]
          .filter(window => window && !window.isDestroyed())
        const snapshot = windows.map(window => ({
          window,
          visible: window.isVisible(),
          focused: window.isFocused(),
        }))
        for (const item of snapshot) {
          if (item.visible)
            item.window.hide()
        }
        return snapshot
      },
      restoreStage: snapshot => {
        if (!Array.isArray(snapshot))
          return
        let focusedWindow
        for (const item of snapshot) {
          if (!item.visible || item.window.isDestroyed())
            continue
          item.window.show()
          if (item.focused)
            focusedWindow = item.window
        }
        focusedWindow?.focus()
      },
      onChanged: event => {
        if (stageWindow && !stageWindow.isDestroyed())
          stageWindow.webContents.send(channels.pixpinCaptureChanged, event)
      },
    })
  }
  return pixpinCaptureCoordinator
}

async function requestClipboardImageImport() {
  const captures = pixpinCaptures()
  if (captures.status().state !== 'idle') {
    await captures.cancelActive('已切换到剪贴板快捷导入。')
    showWindowAndFocus(stageWindow, { alwaysOnTop: readPreferences().window.alwaysOnTop })
    return
  }
  if (!stageWindow || stageWindow.isDestroyed())
    await createStageWindow()
  if (!stageWindow || stageWindow.isDestroyed())
    return
  showWindowAndFocus(stageWindow, { alwaysOnTop: readPreferences().window.alwaysOnTop })
  const request = () => {
    if (stageWindow && !stageWindow.isDestroyed())
      stageWindow.webContents.send(channels.attachmentClipboardImportRequested)
  }
  if (stageWindow.webContents.isLoading())
    stageWindow.webContents.once('did-finish-load', request)
  else
    request()
}

function readPreferences() {
  const preferences = normalizeDesktopPreferences(readDesktopState().desktopPreferences)
  try {
    const accent = systemPreferences.getAccentColor()
    if (/^[a-f0-9]{8}$/i.test(accent)) preferences.systemAccentColor = `#${accent.slice(0, 6)}`
  }
  catch { /* Fixed palette remains available when the OS has no accent API. */ }
  return preferences
}

function withoutStartupPreferences(patch) {
  if (!patch || typeof patch !== 'object' || Array.isArray(patch))
    return patch
  const safePatch = { ...patch }
  delete safePatch.startup
  return safePatch
}

async function invokeStartupSettingsScript(arguments_) {
  const { stdout } = await execFileAsync(windowsPowerShell, [
    '-NoLogo',
    '-NoProfile',
    '-NonInteractive',
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    startupSettingsScript,
    ...arguments_,
  ], {
    cwd: projectRoot,
    windowsHide: true,
    timeout: 300000,
    maxBuffer: 64 * 1024,
  })
  return stdout.trim()
}

function startupFailureMessage(error) {
  const detail = typeof error?.stderr === 'string' ? error.stderr.trim() : ''
  const message = detail || (error instanceof Error ? error.message : String(error))
  if (/1223|操作已被用户取消|the operation was canceled by the user|the operation was cancelled by the user/iu.test(message)) {
    return '管理员授权已取消，Windows 没有应用启动设置。请重试并在 UAC 提示中选择“是”。'
  }
  return message.slice(0, 1200)
}

async function configureWindowsStartup(startup) {
  if (process.platform !== 'win32')
    return { ok: true, message: 'Windows 启动设置仅在 Windows 上应用。' }
  try {
    const output = await invokeStartupSettingsScript([
      '-OpenAtLogin', String(startup.openAtLogin),
      '-RunAsAdministrator', String(startup.runAsAdministrator),
    ])
    return { ok: true, message: output || 'Windows 启动设置已应用。' }
  }
  catch (error) {
    const message = startupFailureMessage(error)
    console.error('Failed to configure Meguri Windows startup settings:', message)
    return { ok: false, message }
  }
}

async function synchronizeStartupSettings() {
  const preferences = readPreferences()
  if (process.platform === 'win32' && preferences.startup.runAsAdministrator) {
    try {
      const elevation = await invokeStartupSettingsScript([
        '-OpenAtLogin', 'false',
        '-RunAsAdministrator', 'false',
        '-CheckElevation',
      ])
      if (elevation.toLowerCase() !== 'true') {
        try {
          await execFileAsync(windowsTaskScheduler, ['/run', '/tn', 'Meguri Desktop'], {
            cwd: projectRoot,
            windowsHide: true,
            timeout: 30000,
            maxBuffer: 64 * 1024,
          })
          app.quit()
          return false
        }
        catch {
          startupConfigurationStatus = {
            ok: false,
            message: 'Meguri 已以当前权限启动。若要管理员启动，请在设置中保存“启动与权限”并完成 Windows 授权。',
          }
          return true
        }
      }
    }
    catch (error) {
      startupConfigurationStatus = { ok: false, message: startupFailureMessage(error) }
      return true
    }
  }
  startupConfigurationStatus = await configureWindowsStartup(preferences.startup)
  return true
}

function broadcastPreferences(preferences) {
  for (const window of [stageWindow, settingsWindow]) {
    if (window && !window.isDestroyed())
      window.webContents.send(channels.preferencesChanged, preferences)
  }
}

function registerVisibilityShortcut(accelerator) {
  if (registeredVisibilityShortcut)
    globalShortcut.unregister(registeredVisibilityShortcut)
  registeredVisibilityShortcut = undefined
  if (globalShortcut.register(accelerator, () => {
    toggleWindowVisibility(stageWindow, { alwaysOnTop: readPreferences().window.alwaysOnTop })
  })) {
    registeredVisibilityShortcut = accelerator
  }
}

function registerInheritedMeguriShortcuts() {
  registeredMeguriShortcuts = registerMeguriGlobalShortcuts({
    register: (accelerator, callback) => globalShortcut.register(accelerator, callback),
    toggleChat: () => {
      const preferences = readPreferences()
      updatePreferences({ chat: { visible: !preferences.chat.visible } })
    },
    centerStage: centerStageWindow,
    cycleOutfit: (direction) => {
      const preferences = readPreferences()
      updatePreferences({ character: { outfitCode: cycleOutfitCode(preferences.character.outfitCode, direction) } })
    },
    importClipboardImage: () => { void requestClipboardImageImport() },
  })

  for (const accelerator of Object.values(meguriGlobalShortcutBindings)) {
    if (!registeredMeguriShortcuts.includes(accelerator))
      console.warn(`Failed to register Meguri global shortcut: ${accelerator}`)
  }
}

function updatePreferences(patch) {
  companionActivity.cancel()
  const preferences = mergeDesktopPreferences(readPreferences(), patch)
  writeDesktopState({ desktopPreferences: preferences })
  stageLayoutController?.fit(preferences, stagePresentationSurface)
  if (stageWindow && !stageWindow.isDestroyed())
    stageWindow.setAlwaysOnTop(preferences.window.alwaysOnTop, preferences.window.alwaysOnTop ? 'floating' : 'normal')
  registerVisibilityShortcut(preferences.shortcuts.toggleVisibility)
  const enriched = readPreferences()
  broadcastPreferences(enriched)
  void maybeCheckStartupUpdate()
  return enriched
}

function migrateLegacyPreferences(patch) {
  const state = readDesktopState()
  if (state.desktopPreferences || state.desktopPreferencesLegacyMigrated)
    return readPreferences()
  const preferences = mergeDesktopPreferences(readPreferences(), patch)
  writeDesktopState({ desktopPreferences: preferences, desktopPreferencesLegacyMigrated: true })
  stageLayoutController?.fit(preferences, stagePresentationSurface)
  broadcastPreferences(preferences)
  return preferences
}

async function loadRenderer(window, view) {
  if (rendererDevelopmentUrl) {
    const url = new URL(rendererDevelopmentUrl)
    url.searchParams.set('view', view)
    await window.loadURL(url.href)
    return
  }
  if (!existsSync(rendererProductionFile))
    throw new Error('Meguri frontend is not built. Run pnpm --filter @meguri/desktop-meguri build.')
  await window.loadFile(rendererProductionFile, { query: { view } })
}

function createStartupWindow() {
  if (startupWindow && !startupWindow.isDestroyed())
    return Promise.resolve(startupWindow)
  startupWindow = new BrowserWindow({
    title: 'Meguri 正在醒来',
    width: 520,
    height: 590,
    frame: false,
    transparent: false,
    resizable: false,
    maximizable: false,
    fullscreenable: false,
    show: false,
    backgroundColor: '#171425',
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      preload: preloadPath,
      additionalArguments: ['--meguri-view=startup'],
    },
  })
  startupWindow.once('ready-to-show', () => startupWindow?.show())
  startupWindow.on('closed', () => { startupWindow = undefined })
  return loadRenderer(startupWindow, 'startup')
}

function createStageWindow() {
  if (stageWindow)
    return Promise.resolve(stageWindow)
  stageReadyForReminders = false
  const displays = screen.getAllDisplays()
  const preferences = readPreferences()
  const matchingWorkArea = bounds => screen.getDisplayMatching(bounds).workArea
  const initial = resolveInitialStageLayout(readDesktopState(), preferences,
    displays.map(display => display.workArea), screen.getPrimaryDisplay().workArea, matchingWorkArea)
  const bounds = initial.bounds
  stageWindow = new BrowserWindow(createStageWindowOptions({ bounds, preloadPath, alwaysOnTop: preferences.window.alwaysOnTop }))
  stagePresentationSurface = 'ambient'
  stageLayoutController = createStageLayoutController({ window: stageWindow, initial, matchingWorkArea, writeState: writeDesktopState })
  stageLayoutController.persist()
  lockWindowNavigation(stageWindow, 'stage')
  stageWindow.setAlwaysOnTop(preferences.window.alwaysOnTop, preferences.window.alwaysOnTop ? 'floating' : 'normal')
  stageWindow.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  const saveBounds = () => {
    clearTimeout(boundsSaveTimer)
    boundsSaveTimer = setTimeout(() => {
      stageLayoutController?.persist()
    }, 160)
  }
  stageWindow.on('move', saveBounds)
  stageWindow.on('resize', saveBounds)
  stageWindow.on('close', () => stageLayoutController?.persist())
  stageWindow.on('closed', () => {
    clearTimeout(boundsSaveTimer)
    stageLayoutController = undefined
    stageReadyForReminders = false
    stageWindow = undefined
    desktopTray?.refresh()
    void pixpinCaptureCoordinator?.cancelActive('桌宠窗口已关闭，PixPin 截图接收已取消。')
  })
  for (const event of ['show', 'hide', 'minimize', 'restore'])
    stageWindow.on(event, () => desktopTray?.refresh())
  stageWindow.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const parsed = new URL(url)
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:')
        void shell.openExternal(parsed.href)
    }
    catch {
      // Invalid renderer links stay closed.
    }
    return { action: 'deny' }
  })
  return loadRenderer(stageWindow, 'stage').then(() => stageWindow)
}

function createSettingsWindow() {
  if (settingsWindow && !settingsWindow.isDestroyed())
    return Promise.resolve(settingsWindow)
  const stageBounds = stageWindow && !stageWindow.isDestroyed() ? stageWindow.getBounds() : undefined
  const display = stageBounds ? screen.getDisplayMatching(stageBounds) : screen.getPrimaryDisplay()
  const bounds = resolveSettingsBounds(display.workArea)
  settingsWindow = new BrowserWindow(createSettingsWindowOptions({ bounds, preloadPath }))
  lockWindowNavigation(settingsWindow, 'settings')
  settingsWindow.on('close', (event) => {
    if (appExiting)
      return
    event.preventDefault()
    settingsWindow?.hide()
  })
  settingsWindow.on('closed', () => { settingsWindow = undefined })
  settingsWindow.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const parsed = new URL(url)
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:')
        void shell.openExternal(parsed.href)
    }
    catch {
      // Invalid renderer links stay closed.
    }
    return { action: 'deny' }
  })
  return loadRenderer(settingsWindow, 'settings').then(() => settingsWindow)
}

function createStudioWindow() {
  if (studioWindow && !studioWindow.isDestroyed())
    return Promise.resolve(studioWindow)
  const stageBounds = stageWindow && !stageWindow.isDestroyed() ? stageWindow.getBounds() : undefined
  const display = stageBounds ? screen.getDisplayMatching(stageBounds) : screen.getPrimaryDisplay()
  const bounds = resolveStudioBounds(display.workArea)
  studioWindow = new BrowserWindow(createStudioWindowOptions({ bounds, preloadPath }))
  studioWindow.setAlwaysOnTop(true, 'floating')
  lockWindowNavigation(studioWindow, 'studio')
  studioWindow.on('close', (event) => {
    if (appExiting)
      return
    event.preventDefault()
    studioWindow?.hide()
  })
  studioWindow.on('closed', () => { studioWindow = undefined })
  studioWindow.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const parsed = new URL(url)
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:')
        void shell.openExternal(parsed.href)
    }
    catch {
      // Invalid renderer links stay closed.
    }
    return { action: 'deny' }
  })
  return loadRenderer(studioWindow, 'studio').then(() => studioWindow)
}

async function openSettingsWindow() {
  const window = await createSettingsWindow()
  if (window.isMinimized())
    window.restore()
  window.show()
  window.focus()
}

async function openStudioWindow() {
  const window = await createStudioWindow()
  if (window.isMinimized())
    window.restore()
  window.show()
  window.focus()
}

function centerStageWindow() {
  if (!stageWindow || stageWindow.isDestroyed())
    return
  const bounds = stageWindow.getBounds()
  const area = screen.getDisplayMatching(bounds).workArea
  stageWindow.setPosition(
    Math.round(area.x + (area.width - bounds.width) / 2),
    Math.round(area.y + (area.height - bounds.height) / 2),
  )
}

function revealDesktopFromTray(options) {
  return revealDesktop({
    getStage: () => stageWindow,
    createStage: createStageWindow,
    getStartup: () => startupWindow,
    createStartup: createStartupWindow,
    isReady: () => readiness.current().status === 'ready',
    isExiting: () => appExiting,
    recoverStage: window => recoverDesktopWindow(window, {
      center: centerStageWindow,
      alwaysOnTop: readPreferences().window.alwaysOnTop,
    }),
    recoverStartup: window => recoverDesktopWindow(window, { center: () => window.center() }),
    openChat: () => updatePreferences({ chat: { visible: true, expanded: true } }),
  }, options)
}

function trustedRenderer(event) {
  const url = event.senderFrame?.url ?? event.sender.getURL()
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'file:' || ['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname)
  }
  catch {
    return false
  }
}

function isExpectedRendererLocation(url, view) {
  try {
    const actual = new URL(url)
    if (rendererDevelopmentUrl) {
      const expected = new URL(rendererDevelopmentUrl)
      return actual.protocol === expected.protocol
        && actual.host === expected.host
        && actual.pathname === expected.pathname
        && actual.searchParams.get('view') === view
    }
    return actual.protocol === 'file:'
      && resolve(decodeURIComponent(actual.pathname.slice(1))) === rendererProductionFile
      && actual.searchParams.get('view') === view
  }
  catch {
    return false
  }
}

function lockWindowNavigation(window, view) {
  const blockUnexpectedNavigation = (event, url) => {
    if (!isExpectedRendererLocation(url, view))
      event.preventDefault()
  }
  window.webContents.on('will-navigate', blockUnexpectedNavigation)
  window.webContents.on('will-redirect', blockUnexpectedNavigation)
}

function trustedSettingsRenderer(event) {
  const url = event.senderFrame?.url ?? event.sender.getURL()
  return trustedRenderer(event) && event.sender === settingsWindow?.webContents && isExpectedRendererLocation(url, 'settings')
}

function trustedStageRenderer(event) {
  const url = event.senderFrame?.url ?? event.sender.getURL()
  return trustedRenderer(event)
    && event.sender === stageWindow?.webContents
    && isExpectedRendererLocation(url, 'stage')
}

function trustedStudioRenderer(event) {
  const url = event.senderFrame?.url ?? event.sender.getURL()
  return trustedRenderer(event)
    && event.sender === studioWindow?.webContents
    && isExpectedRendererLocation(url, 'studio')
}

function characterPackFailure(error) {
  return { ok: false, error: error instanceof Error ? error.message : String(error) }
}

function characterPackSuccess(action) {
  try {
    const { catalog, pack } = action()
    return { ok: true, catalog, pack }
  }
  catch (error) {
    return characterPackFailure(error)
  }
}

function installIpc() {
  const modelStore = () => live2dStore ??= createLive2DStore({ root: join(app.getPath('userData'), 'live2d-models') })
  const modelResult = async action => {
    try { return { ok: true, catalog: await action(modelStore()) } }
    catch (error) {
      recordDiagnosticFailure('resource', 'RESOURCE_FAILED')
      return { ok: false, error: error instanceof Error ? error.message : 'Live2D 资源操作失败。' }
    }
  }
  protocol.handle('meguri-live2d', createLive2DProtocolHandler({
    store: { asset: (id, path) => modelStore().asset(id, path) },
    allowedOrigins: rendererDevelopmentUrl ? [new URL(rendererDevelopmentUrl).origin] : [],
  }))
  ipcMain.handle(channels.live2dCatalog, event => trustedSettingsRenderer(event) ? modelStore().catalog() : { version: 1, models: [] })
  ipcMain.handle(channels.live2dActive, event => trustedStageRenderer(event) || trustedSettingsRenderer(event) ? modelStore().active() : null)
  for (const [channel, archive] of [[channels.live2dImportDirectory, false], [channels.live2dImportArchive, true]]) {
    ipcMain.handle(channel, async event => {
      if (!trustedSettingsRenderer(event)) return { ok: false, error: '请从设置中导入 Live2D 模型。' }
      const selected = await dialog.showOpenDialog(settingsWindow, {
        title: archive ? '选择 Live2D ZIP' : '选择 Live2D 模型目录',
        properties: [archive ? 'openFile' : 'openDirectory'],
        ...(archive ? { filters: [{ name: 'Live2D 模型 ZIP', extensions: ['zip'] }] } : {}),
      })
      if (selected.canceled || !selected.filePaths[0]) return { ok: false, error: '已取消导入。' }
      return modelResult(store => archive ? store.importArchive(selected.filePaths[0]) : store.importDirectory(selected.filePaths[0]))
    })
  }
  ipcMain.handle(channels.live2dActivate, async (event, id) => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '请从设置中启用 Live2D 模型。' }
    const result = await modelResult(store => store.activate(id))
    if (result.ok) updatePreferences({ character: { renderer: 'live2d', live2dModelId: id } })
    return result
  })
  ipcMain.handle(channels.live2dRemove, async (event, id) => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '请从设置中删除 Live2D 模型。' }
    const result = await modelResult(store => store.remove(id))
    if (result.ok && readPreferences().character.live2dModelId === id)
      updatePreferences({ character: { renderer: 'png', live2dModelId: '' } })
    return result
  })
  ipcMain.handle(channels.speechResourcesList, event => trustedSettingsRenderer(event) ? speechResources.list() : { engine: 'GPT-SoVITS', ready: false, profiles: [] })
  ipcMain.handle(channels.speechResourcesImport, async event => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '请从设置中导入语音资源。' }
    const selected = await dialog.showOpenDialog(settingsWindow, { title: '选择 GPT-SoVITS 语音包目录', properties: ['openDirectory'] })
    if (selected.canceled || !selected.filePaths[0]) return { ok: false, error: '已取消导入。' }
    return speechResources.importPackage(selected.filePaths[0])
  })
  const speechMutation = async action => {
    try { return await action() }
    catch { recordDiagnosticFailure('voice', 'VOICE_FAILED'); return { ok: false, error: '语音资源操作失败，请检查本地 TTS 服务。' } }
  }
  ipcMain.handle(channels.speechResourcesDownload, (event, url) => trustedSettingsRenderer(event)
    ? speechMutation(() => speechResources.download(url)) : { ok: false, error: '语音资源请求不受信任。' })
  ipcMain.handle(channels.speechResourcesActivate, (event, id) => trustedSettingsRenderer(event)
    ? speechMutation(() => speechResources.activate(id)) : { ok: false, error: '语音资源请求不受信任。' })
  ipcMain.handle(channels.speechResourcesRemove, (event, id) => trustedSettingsRenderer(event)
    ? speechMutation(() => speechResources.remove(id)) : { ok: false, error: '语音资源请求不受信任。' })
  ipcMain.handle(channels.wallpaperSelect, async event => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '壁纸只能从设置窗口选择。' }
    const selected = await dialog.showOpenDialog(settingsWindow, { title: '选择聊天壁纸', properties: ['openFile'], filters: [{ name: '图片', extensions: ['png', 'jpg', 'jpeg', 'webp'] }] })
    if (selected.canceled || !selected.filePaths[0]) return { ok: false, error: '已取消壁纸选择。' }
    const result = loadDesktopWallpaper(selected.filePaths[0], bytes => {
      const image = nativeImage.createFromBuffer(bytes)
      return image.isEmpty() ? undefined : image.getSize()
    })
    if (!result.ok) recordDiagnosticFailure('resource', 'RESOURCE_FAILED')
    return result
  })
  ipcMain.handle(channels.diagnosticsExport, async event => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '请在设置中导出诊断信息。' }
    try {
      const chosen = await dialog.showSaveDialog(settingsWindow, { title: '导出 Meguri 诊断信息', defaultPath: `meguri-diagnostics-${new Date().toISOString().slice(0, 10)}.json`, filters: [{ name: '诊断信息', extensions: ['json'] }] })
      if (chosen.canceled || !chosen.filePath) return { ok: false, error: '已取消导出。' }
      const report = createDesktopDiagnosticsReport({ appVersion: desktopVersion, runtimeVersions: process.versions, platform: process.platform, readiness: readiness.current(), preferences: readPreferences(), failures: diagnosticFailures })
      writeFileSync(chosen.filePath, `${JSON.stringify(report, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 })
      return { ok: true, path: chosen.filePath }
    }
    catch { recordDiagnosticFailure('runtime', 'UNKNOWN'); return { ok: false, error: '诊断信息导出失败，请更换保存位置。' } }
  })
  ipcMain.handle(channels.updatesGet, event => trustedStageRenderer(event) || trustedSettingsRenderer(event) ? updateChecker.get() : undefined)
  ipcMain.handle(channels.updatesCheck, async event => {
    if (!trustedSettingsRenderer(event)) return undefined
    const state = await updateChecker.check()
    broadcastUpdateState(state)
    if (state.status === 'unavailable' || state.status === 'rate_limited') recordDiagnosticFailure('updates', 'UPDATE_FAILED')
    return state
  })
  ipcMain.handle(channels.updatesOpenRelease, event => trustedSettingsRenderer(event) ? updateChecker.openRelease() : { ok: false, error: '请在更新设置中打开发布页。' })
  ipcMain.handle(channels.readinessGet, event => trustedRenderer(event) ? readiness.current() : undefined)
  ipcMain.handle(channels.companionActivityStatus, event => trustedStageRenderer(event) || trustedSettingsRenderer(event)
    ? companionActivity.status() : undefined)
  ipcMain.handle(channels.companionScreenObserve, event => trustedStageRenderer(event)
    ? companionActivity.observe().finally(broadcastCompanionActivity) : undefined)
  ipcMain.handle(channels.readinessRetry, async event => {
    if (!trustedRenderer(event))
      return readiness.current()
    const snapshot = await readiness.check()
    if (snapshot.status === 'ready')
      await createStageWindow()
    return snapshot
  })
  ipcMain.handle(channels.rendererReady, event => {
    if (!trustedRenderer(event) || event.sender !== stageWindow?.webContents)
      return
    readiness.markRendererReady()
    stageReadyForReminders = true
    if (!companionStartupLogged) {
      companionStartupLogged = true
      const presence = companionActivity.presence()
      console.info('[Meguri companion] ready', JSON.stringify({
        release: process.env.MEGURI_RELEASE_COMMIT ?? 'workspace', presenceGateSeconds: 600,
        screenObservationConfigured: readPreferences().companion.screenObservationEnabled,
        nativeState: presence.reason, active: presence.active,
      }))
    }
    stageWindow.show()
    desktopTray?.refresh()
    for (const reminder of pendingShizukiReminders.splice(0))
      stageWindow.webContents.send(channels.shizukiReminder, reminder)
    setTimeout(() => startupWindow?.close(), 260)
  })
  ipcMain.handle(channels.shizukiStatus, event =>
    trustedStageRenderer(event) || trustedSettingsRenderer(event)
      ? shizukiStatus()
      : { connected: false, origin: '', email: '', userId: 0, error: '请求来源不受信任。' })
  ipcMain.handle(channels.shizukiSetMainAccount, event => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    try {
      qqReminders().selectOwner(shizukiTimePrism().status().userId)
      return { ok: true, status: shizukiStatus() }
    }
    catch (error) { return { ok: false, error: error.message || '主账号绑定失败。' } }
  })
  ipcMain.handle(channels.shizukiClearMainAccount, event => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    try {
      qqReminders().clearOwner()
      return { ok: true, status: shizukiStatus() }
    }
    catch { return { ok: false, error: '主账号解绑失败。' } }
  })
  ipcMain.handle(channels.shizukiConnect, async (event, input) => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    shizukiReminderRuntime?.stop()
    pendingShizukiReminders.length = 0
    try {
      const status = await shizukiTimePrism().connect(input)
      shizukiReminders().start()
      return { ok: true, status: { ...status, ...qqReminders().status() } }
    }
    catch (error) {
      if (shizukiTimePrism().status().connected) shizukiReminders().start()
      return shizukiFailure(error)
    }
  })
  ipcMain.handle(channels.shizukiDisconnect, async event => {
    if (!trustedSettingsRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    shizukiReminders().stop()
    pendingShizukiReminders.length = 0
    try {
      return { ok: true, status: { ...await shizukiTimePrism().disconnect(), ...qqReminders().status() } }
    }
    catch (error) {
      return shizukiFailure(error)
    }
  })
  ipcMain.handle(channels.shizukiListTodos, async event => {
    if (!trustedStageRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    try {
      const todos = await shizukiTimePrism().listTodos()
      shizukiReminders().useSnapshot(todos)
      return { ok: true, todos }
    }
    catch (error) {
      shizukiReminders().invalidate()
      return shizukiFailure(error)
    }
  })
  ipcMain.handle(channels.shizukiCreateTodo, async (event, input) => {
    if (!trustedStageRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    if (!input?.requestId || !input?.expectedAccount) return { ok: false, error: '请先核对提醒和账号后确认。' }
    try {
      const todo = await shizukiTimePrism().createTodo(input)
      shizukiReminders().applyTodo(todo)
      void shizukiReminders().sync()
      return { ok: true, todo }
    }
    catch (error) {
      shizukiReminders().invalidate()
      return shizukiFailure(error)
    }
  })
  ipcMain.handle(channels.shizukiPendingCreates, event => {
    if (!trustedStageRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    try { return { ok: true, requests: shizukiTimePrism().pendingCreates() } }
    catch (error) { return shizukiFailure(error) }
  })
  ipcMain.handle(channels.shizukiUpdateTodo, async (event, id, input) => {
    if (!trustedStageRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    if (!input?.expectedAccount) return { ok: false, error: '请先核对待办和账号。' }
    try {
      const todo = await shizukiTimePrism().updateTodo(id, input)
      shizukiReminders().applyTodo(todo)
      void shizukiReminders().sync()
      return { ok: true, todo }
    }
    catch (error) { shizukiReminders().invalidate(); return shizukiFailure(error) }
  })
  ipcMain.handle(channels.shizukiCompleteTodo, async (event, id, expectedAccount) => {
    if (!trustedStageRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    if (!expectedAccount) return { ok: false, error: '请先核对待办和账号。' }
    try {
      const todo = await shizukiTimePrism().completeTodo(id, expectedAccount)
      shizukiReminders().applyTodo(todo)
      void shizukiReminders().sync()
      return { ok: true, todo }
    }
    catch (error) {
      shizukiReminders().invalidate()
      return shizukiFailure(error)
    }
  })
  ipcMain.handle(channels.voiceStart, async event => {
    if (!trustedStageRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    if (windowsVoice().isRunning())
      return { ok: false, error: '语音识别正在进行中。', code: 'already_running' }
    stageWindow.webContents.send(channels.voiceChanged, { state: 'listening' })
    const result = await windowsVoice().start()
    if (stageWindow && !stageWindow.isDestroyed()) {
      stageWindow.webContents.send(channels.voiceChanged, result.ok
        ? { state: 'completed', text: result.text }
        : result.code === 'cancelled'
          ? { state: 'cancelled' }
          : { state: 'error', error: result.error, stage: result.stage })
    }
    return result
  })
  ipcMain.handle(channels.voiceCancel, event => {
    if (!trustedStageRenderer(event)) return { ok: false, error: '请求来源不受信任。' }
    const result = windowsVoice().cancel()
    if (result.ok)
      stageWindow.webContents.send(channels.voiceChanged, { state: 'cancelled' })
    return result
  })
  ipcMain.handle(channels.preferencesGet, event => trustedRenderer(event) ? readPreferences() : undefined)
  ipcMain.handle(channels.preferencesUpdate, (event, patch) => trustedRenderer(event) ? updatePreferences(withoutStartupPreferences(patch)) : readPreferences())
  ipcMain.handle(channels.preferencesMigrateLegacy, (event, patch) => trustedRenderer(event) ? migrateLegacyPreferences(withoutStartupPreferences(patch)) : readPreferences())
  ipcMain.handle(channels.startupSettingsStatusGet, event => trustedSettingsRenderer(event)
    ? startupConfigurationStatus
    : { ok: false, message: '启动设置请求不受信任。' })
  ipcMain.handle(channels.startupSettingsSave, async (event, patch) => {
    if (!trustedSettingsRenderer(event))
      return { ok: false, error: '启动设置请求不受信任。' }
    if (!patch || typeof patch !== 'object' || Array.isArray(patch)
      || Object.keys(patch).length === 0
      || Object.entries(patch).some(([key, value]) => !['openAtLogin', 'runAsAdministrator'].includes(key) || typeof value !== 'boolean')) {
      return { ok: false, error: '启动设置格式无效，请重新选择后保存。' }
    }

    const previous = readPreferences()
    const next = mergeDesktopPreferences(previous, { startup: { ...previous.startup, ...patch } })
    const status = await configureWindowsStartup(next.startup)
    startupConfigurationStatus = status
    if (!status.ok)
      return { ok: false, preferences: previous, status, error: status.message }

    try {
      writeDesktopState({ desktopPreferences: next })
      broadcastPreferences(next)
      return {
        ok: true,
        preferences: next,
        status: { ok: true, message: '设置已保存；登录启动和管理员权限将在下次启动时应用。' },
      }
    }
    catch (error) {
      const message = startupFailureMessage(error)
      startupConfigurationStatus = { ok: false, message }
      console.error('Failed to save Meguri Windows startup preferences:', message)
      return { ok: false, preferences: previous, status: startupConfigurationStatus, error: message }
    }
  })
  ipcMain.handle(channels.multimodalRelayGet, event => trustedSettingsRenderer(event)
    ? readMultimodalRelayConfig()
    : { version: 1, enabled: false, baseUrl: '', model: '', apiKeyConfigured: false })
  ipcMain.handle(channels.multimodalRelaySave, (event, draft) => trustedSettingsRenderer(event)
    ? saveMultimodalRelayConfig(draft)
    : { ok: false, error: '多模态配置请求不受信任。' })
  ipcMain.handle(channels.multimodalRelayTest, (event, draft) => trustedSettingsRenderer(event)
    ? testMultimodalRelayConfig(draft)
    : { ok: false, error: '多模态测试请求不受信任。' })
  ipcMain.handle(channels.localAgentsGet, event => trustedSettingsRenderer(event)
    ? localAgents().settings()
    : { version: 1, enabled: false, harnessAutoStart: false, dshHome: '', install: { codex: { installed: false, home: '', signedIn: false }, harness: { cli: 'missing', credentials: 'missing', preset: 'unavailable', logReady: false } } })
  ipcMain.handle(channels.localAgentsSave, (event, patch) => {
    if (!trustedSettingsRenderer(event))
      return { ok: false, error: '本地 Agent 配置请求不受信任。' }
    const saved = saveLocalAgentConfig(localAgentConfigPath ?? resolveLocalAgentConfigPath(), patch)
    if (!saved.ok)
      return saved
    const settings = localAgents().updateSettings(saved.config)
    if (settings.enabled && settings.harnessAutoStart)
      localAgents().startHarnessHost()
    if (settings.enabled)
      startWorkObservation()
    else
      stopWorkObservation()
    return { ok: true, settings }
  })
  ipcMain.handle(channels.localAgentsTest, async (event) => {
    if (!trustedSettingsRenderer(event))
      return { codex: { ok: false, message: '连接测试请求不受信任。' }, harness: { ok: false, message: '连接测试请求不受信任。' } }
    try {
      return await localAgents().connectTest()
    }
    catch (error) {
      const failure = localAgentFailure(error)
      return { codex: { ok: false, message: failure.error }, harness: { ok: false, message: failure.error } }
    }
  })
  // Task forwarding: the renderer may only freeze a card and confirm the exact version it was shown.
  ipcMain.handle(channels.workSnapshot, event => trustedRenderer(event)
    ? localAgents().snapshot()
    : { requests: [], draft: null, connection: { codex: 'disabled', harness: 'disabled' }, executionAvailable: false })
  ipcMain.handle(channels.workTargets, async (event, query) => {
    if (!trustedRenderer(event))
      return { limit: 0, items: [] }
    try {
      return await localAgents().targets(typeof query === 'string' ? query : '')
    }
    catch {
      return { limit: 0, items: [] }
    }
  })
  ipcMain.handle(channels.workProjects, async event => {
    if (!trustedRenderer(event))
      return []
    try {
      return await localAgents().projects()
    }
    catch {
      return []
    }
  })
  ipcMain.handle(channels.workPrepare, async (event, input) => {
    if (!trustedRenderer(event))
      return { ok: false, error: '任务卡请求不受信任。' }
    try {
      const request = await localAgents().prepare(input)
      broadcastWork(await localAgents().snapshot())
      return { ok: true, request }
    }
    catch (error) {
      return localAgentFailure(error)
    }
  })
  ipcMain.handle(channels.workConfirm, async (event, id, version) => {
    if (!trustedRenderer(event) || typeof id !== 'string')
      return { ok: false, error: '任务确认请求不受信任。' }
    try {
      const request = await localAgents().confirm(id, version)
      startWorkObservation()
      broadcastWork(await localAgents().snapshot())
      return { ok: true, request }
    }
    catch (error) {
      broadcastWork(await localAgents().snapshot().catch(() => ({ requests: [], draft: null, connection: {}, executionAvailable: false })))
      return localAgentFailure(error)
    }
  })
  ipcMain.handle(channels.workRefresh, async (event, id) => {
    if (!trustedRenderer(event) || typeof id !== 'string')
      return { ok: false, error: '任务回执请求不受信任。' }
    try {
      const request = await localAgents().refresh(id)
      return { ok: true, request }
    }
    catch (error) {
      return localAgentFailure(error)
    }
  })
  ipcMain.handle(channels.workDismiss, async (event, id, version) => {
    if (!trustedRenderer(event) || typeof id !== 'string')
      return { ok: false, error: '任务提醒请求不受信任。' }
    try {
      const request = await localAgents().dismiss(id, version)
      broadcastWork(await localAgents().snapshot())
      return { ok: true, request }
    }
    catch (error) {
      return localAgentFailure(error)
    }
  })
  ipcMain.handle(channels.workOpenNative, async (event, id) => {
    if (!trustedRenderer(event) || typeof id !== 'string')
      return { ok: false, error: '打开本地任务请求不受信任。' }
    try {
      return await openNativeWork(id)
    }
    catch (error) {
      return localAgentFailure(error)
    }
  })
  ipcMain.handle(channels.windowHide, event => {
    if (trustedRenderer(event))
      BrowserWindow.fromWebContents(event.sender)?.hide()
  })
  ipcMain.handle(channels.windowMinimize, event => {
    if (trustedRenderer(event))
      BrowserWindow.fromWebContents(event.sender)?.minimize()
  })
  ipcMain.handle(channels.windowToggle, event => {
    if (!trustedRenderer(event) || !stageWindow)
      return false
    return toggleWindowVisibility(stageWindow, { alwaysOnTop: readPreferences().window.alwaysOnTop })
  })
  ipcMain.handle(channels.windowSetClickThrough, (event, ignore) => {
    if (!trustedRenderer(event) || event.sender !== stageWindow?.webContents)
      return
    stageWindow.setIgnoreMouseEvents(ignore === true, { forward: true })
  })
  ipcMain.handle(channels.windowPointerPosition, event => {
    if (!trustedRenderer(event) || event.sender !== stageWindow?.webContents)
      return undefined
    const cursor = screen.getCursorScreenPoint()
    const bounds = stageWindow.getBounds()
    return { x: cursor.x - bounds.x, y: cursor.y - bounds.y }
  })
  ipcMain.handle(channels.windowSetPresentationSurface, (event, surface) => {
    if (!trustedStageRenderer(event) || !['ambient', 'ambient-panel', 'galgame'].includes(surface))
      return { ok: false, error: '不支持的舞台切换。' }
    stagePresentationSurface = surface
    stageLayoutController?.fit(readPreferences(), surface)
    return { ok: true }
  })
  ipcMain.handle(channels.windowOpenSettings, async event => {
    if (trustedRenderer(event))
      await openSettingsWindow()
  })
  ipcMain.handle(channels.windowOpenStudio, async event => {
    if (trustedRenderer(event))
      await openStudioWindow()
  })
  ipcMain.handle(channels.windowCenterStage, event => {
    if (trustedRenderer(event))
      centerStageWindow()
  })
  ipcMain.handle(channels.windowReloadStage, event => {
    if (trustedRenderer(event) && stageWindow && !stageWindow.isDestroyed())
      stageWindow.webContents.reload()
  })
  ipcMain.handle(channels.windowSetAlwaysOnTop, (event, value) => trustedRenderer(event)
    ? updatePreferences({ window: { alwaysOnTop: value === true } })
    : readPreferences())
  ipcMain.handle(channels.windowQuit, event => {
    if (trustedRenderer(event))
      app.quit()
  })
  ipcMain.handle(channels.artifactOpen, async (event, pathValue) => {
    if (!trustedRenderer(event))
      return { ok: false, error: 'untrusted renderer' }
    const artifact = resolveGeneratedArtifact(pathValue, artifactRoots)
    if (!artifact)
      return { ok: false, error: 'artifact path is not allowed' }
    const error = await shell.openPath(artifact)
    return error ? { ok: false, error } : { ok: true }
  })
  ipcMain.handle(channels.artifactIcon, async (event, pathValue) => {
    if (!trustedRenderer(event))
      return { ok: false, error: 'untrusted renderer' }
    const artifact = resolveGeneratedArtifact(pathValue, artifactRoots)
    if (!artifact)
      return { ok: false, error: 'artifact path is not allowed' }
    try {
      const icon = await app.getFileIcon(artifact, { size: 'large' })
      return icon.isEmpty() ? { ok: false, error: 'no icon available' } : { ok: true, dataUrl: icon.toDataURL() }
    }
    catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : String(error) }
    }
  })
  ipcMain.handle(channels.attachmentInspect, (event, pathValue) => {
    if (!trustedRenderer(event) || event.sender !== stageWindow?.webContents)
      return { ok: false, error: '文件读取请求不受信任。' }
    return inspectDroppedAttachment(pathValue)
  })
  ipcMain.handle(channels.attachmentClipboardImage, event => {
    if (!trustedStageRenderer(event))
      return { ok: false, error: '剪贴板读取请求不受信任。' }
    try {
      const image = clipboard.readImage()
      if (image.isEmpty()) {
        const fileBacked = clipboard.availableFormats().some(format => /file(name|drop|contents|groupdescriptor)|text\/uri-list/iu.test(format))
        return {
          ok: false,
          error: fileBacked
            ? 'PixPin 当前把截图复制为文件。请在 PixPin「配置 → 系统」中将「复制图像为文件」设为「禁用」，再粘贴或按 Ctrl+Alt+Shift+P 导入。'
            : '剪贴板中没有可读取的图片，请重新截图后粘贴。',
        }
      }
      return createClipboardImageAttachment(image.toPNG())
    }
    catch {
      return { ok: false, error: '无法读取剪贴板图片，请重新截图后粘贴。' }
    }
  })
  ipcMain.handle(channels.pixpinCaptureStart, (event, mode) => {
    if (!trustedStageRenderer(event))
      return { ok: false, error: 'PixPin 截图请求不受信任。' }
    if (mode !== 'draft' && mode !== 'direct_send')
      return { ok: false, error: '不支持的 PixPin 截图模式。' }
    return pixpinCaptures().start(mode)
  })
  ipcMain.handle(channels.pixpinCaptureCancel, (event, captureId) => {
    if (!trustedStageRenderer(event) || typeof captureId !== 'string')
      return { ok: false, error: 'PixPin 截图取消请求不受信任。' }
    return pixpinCaptures().cancel(captureId)
  })
  ipcMain.handle(channels.pixpinCaptureStatus, event => trustedStageRenderer(event)
    ? {
        ...pixpinCaptures().status(),
        clipboardImportShortcutAvailable: registeredMeguriShortcuts.includes(meguriGlobalShortcutBindings.importClipboardImage),
      }
    : { state: 'idle', clipboardImportShortcutAvailable: false })
  ipcMain.handle(channels.characterPacksList, event => trustedStudioRenderer(event)
    ? characterPacks().getCatalog()
    : { version: 1, drafts: [], packages: [] })
  ipcMain.handle(channels.characterPacksImportDirectory, async event => {
    if (!trustedStudioRenderer(event))
      return { ok: false, error: '角色包导入请求不受信任。' }
    const selected = await dialog.showOpenDialog(BrowserWindow.fromWebContents(event.sender), {
      title: '选择角色包目录',
      properties: ['openDirectory'],
    })
    if (selected.canceled || !selected.filePaths[0])
      return { ok: false, error: '没有选择角色包目录。' }
    return characterPackSuccess(() => characterPacks().importDirectory(selected.filePaths[0]))
  })
  ipcMain.handle(channels.characterPacksAddResource, async (event, draftId) => {
    if (!trustedStudioRenderer(event))
      return { ok: false, error: '角色资源导入请求不受信任。' }
    const selected = await dialog.showOpenDialog(BrowserWindow.fromWebContents(event.sender), {
      title: '添加角色资源',
      properties: ['openFile'],
      filters: [{ name: '图片资源', extensions: ['png', 'jpg', 'jpeg'] }],
    })
    if (selected.canceled || !selected.filePaths[0])
      return { ok: false, error: '没有选择角色资源。' }
    return characterPackSuccess(() => characterPacks().addResource(draftId, selected.filePaths[0]))
  })
  ipcMain.handle(channels.characterPacksPublish, (event, draftId) => trustedStudioRenderer(event)
    ? characterPackSuccess(() => characterPacks().publish(draftId))
    : { ok: false, error: '角色包发布请求不受信任。' })
  ipcMain.handle(channels.characterPacksActivate, (event, packageId) => trustedStudioRenderer(event)
    ? characterPackSuccess(() => characterPacks().activate(packageId))
    : { ok: false, error: '角色包启用请求不受信任。' })
  ipcMain.handle(channels.characterPacksDisable, (event, packageId) => trustedStudioRenderer(event)
    ? characterPackSuccess(() => characterPacks().disable(packageId))
    : { ok: false, error: '角色包停用请求不受信任。' })
  ipcMain.handle(channels.characterPacksRestore, (event, packageId) => trustedStudioRenderer(event)
    ? characterPackSuccess(() => characterPacks().restore(packageId))
    : { ok: false, error: '角色包恢复请求不受信任。' })
  ipcMain.handle(channels.characterPacksPreview, (event, packageId, resourceId) => {
    if (!trustedStudioRenderer(event))
      return { ok: false, error: '角色资源预览请求不受信任。' }
    try {
      return { ok: true, ...characterPacks().preview(packageId, resourceId) }
    }
    catch (error) {
      return characterPackFailure(error)
    }
  })
  ipcMain.handle(channels.characterPresentationGet, event => trustedStageRenderer(event)
    ? characterPacks().getActivePresentation()
    : { revision: 'builtin:default' })
}

async function start() {
  startCompanionActivity()
  installIpc()
  await createStartupWindow()
  if (!(await synchronizeStartupSettings()))
    return
  // Acquire ownership after the Windows elevation handoff, before any reminder writer starts.
  if (!app.requestSingleInstanceLock()) { app.quit(); return }
  app.on('second-instance', () => {
    void revealDesktopFromTray().catch(error => console.warn('Failed to reveal Meguri desktop:', error))
  })
  desktopTray = createDesktopTray({
    iconPath: join(appRoot, 'assets/meguri-tray.ico'),
    createTray: iconPath => {
      const tray = new Tray(iconPath)
      console.info('[Meguri tray] registered', JSON.stringify(tray.getBounds()))
      return tray
    },
    buildMenu: template => Menu.buildFromTemplate(template),
    getState: () => ({
      readinessStatus: readiness.current().status,
      rendererReady: stageReadyForReminders,
      visible: stageWindow && !stageWindow.isDestroyed() && stageWindow.isVisible(),
      minimized: stageWindow && !stageWindow.isDestroyed() && stageWindow.isMinimized(),
    }),
    reveal: revealDesktopFromTray,
    openSettings: openSettingsWindow,
    quit: () => app.quit(),
    onError: error => console.warn('Meguri tray error:', error),
  })
  qqReminders().start()
  if (shizukiTimePrism().status().connected)
    shizukiReminders().start()
  registerVisibilityShortcut(readPreferences().shortcuts.toggleVisibility)
  registerInheritedMeguriShortcuts()
  systemPreferences.on('accent-color-changed', () => broadcastPreferences(readPreferences()))
  void maybeCheckStartupUpdate()
  const everything = await ensureEverythingIpc()
  if (!everything.ready)
    console.warn(everything.detail)
  const agents = localAgents()
  if (agents.settings().enabled) {
    startWorkObservation()
    if (agents.settings().harnessAutoStart)
      agents.startHarnessHost()
  }
  readiness.subscribe(snapshot => {
    desktopTray?.refresh()
    if (startupWindow && !startupWindow.isDestroyed())
      startupWindow.webContents.send(channels.readinessChanged, snapshot)
  })
  const snapshot = await readiness.check()
  if (snapshot.status === 'ready')
    await createStageWindow()
  else {
    readinessRetryTimer = setInterval(async () => {
      if (appExiting || stageWindow) {
        clearInterval(readinessRetryTimer)
        readinessRetryTimer = undefined
        return
      }
      const next = await readiness.check()
      if (next.status === 'ready') {
        clearInterval(readinessRetryTimer)
        readinessRetryTimer = undefined
        await createStageWindow()
      }
    }, 10_000)
    readinessRetryTimer.unref?.()
  }
  if (process.env.MEGURI_OPEN_SETTINGS_ON_START === '1')
    await openSettingsWindow()
}

app.setAppUserModelId('dev.meguri.desktop')
app.whenReady().then(start).catch(error => {
  console.error('Failed to start Meguri desktop:', error)
  app.quit()
})

app.on('before-quit', () => { appExiting = true })
app.on('will-quit', () => {
  clearInterval(companionActivityTimer)
  companionActivity.cancel()
  desktopTray?.dispose()
  desktopTray = undefined
  clearInterval(readinessRetryTimer)
  globalShortcut.unregisterAll()
  stopWorkObservation()
  shizukiReminderRuntime?.stop()
  shizukiQqDelivery?.stop()
  voiceRecognizer?.cancel()
  void pixpinCaptureCoordinator?.cancelActive('Meguri 已退出，PixPin 截图接收已取消。')
  // Never leave a spawned app-server or Harness host behind after Meguri exits.
  void localAgentRuntime?.shutdown()
})
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin' && !desktopTray)
    app.quit()
})
