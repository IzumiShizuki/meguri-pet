import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, existsSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { app, BrowserWindow, ipcMain, session } from 'electron'

import channels from '../src/shared/channels.cjs'
import { defaultDesktopPreferences } from '../src/main/desktop-preferences.mjs'
import { readLocalAgentConfig } from '../src/main/local-agent-config.mjs'
import { registerLocalAgentSettingsIpc } from '../src/main/local-agent-settings-ipc.mjs'

// Runs the real built SettingsView and preload in an isolated profile. No
// production main process, account, executor, startup hook or network is used.
const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const root = mkdtempSync(join(app.getPath('temp'), 'meguri-agent-settings-smoke-'))
app.setPath('userData', join(root, 'profile'))
const configPath = join(root, 'local-agents.json')
const rendererPath = join(appRoot, 'dist/renderer/index.html')
const rendererUrl = `${pathToFileURL(rendererPath).href}?view=settings`
const evidence = { root, checks: [], saves: 0, probes: 0, hostStarts: 0 }
const install = { codex: { installed: true, home: '', signedIn: true }, harness: { cli: 'installed', credentials: 'configured', preset: 'present', logReady: true } }
let config = readLocalAgentConfig(configPath)
let settingsWindow
let keeper
let failTransport = false
const runtime = {
  settings: () => ({ ...config, install }),
  updateSettings: saved => { config = saved; return runtime.settings() },
  startHarnessHost: () => { evidence.hostStarts++ },
  connectTest: async () => {
    assert.equal(config.enabled, true)
    evidence.probes++
    return { codex: { ok: true, message: 'Codex fixture connected' }, harness: { ok: true, message: 'Harness fixture connected' } }
  },
}
const record = name => evidence.checks.push(name)
const timeout = setTimeout(() => finish(new Error('settings smoke timed out')), 30000)
function finish(error) {
  clearTimeout(timeout)
  evidence.ok = !error
  if (error) evidence.error = error.stack ?? error.message
  writeFileSync(join(root, 'result.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify(evidence))
  app.exit(error ? 1 : 0)
}
async function waitFor(window, expression) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await window.webContents.executeJavaScript(expression)) return
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Expected UI state did not appear: ${expression}`)
}
async function openSettings() {
  const window = new BrowserWindow({ show: false, webPreferences: {
    contextIsolation: true, sandbox: true,
    preload: join(appRoot, 'src/preload/index.cjs'),
    additionalArguments: ['--meguri-view=settings'],
  } })
  settingsWindow = window
  await window.loadURL(rendererUrl)
  await waitFor(window, `[...document.querySelectorAll('.settings-entry')].some(e=>e.textContent.includes('工作 Agent'))`)
  await window.webContents.executeJavaScript(`[...document.querySelectorAll('.settings-entry')].find(e=>e.textContent.includes('工作 Agent')).click()`)
  await waitFor(window, `document.querySelector('.agent-settings input[type=checkbox]')?.disabled === false`)
  return window
}
const saveForm = window => window.webContents.executeJavaScript(`document.querySelector('.agent-settings').requestSubmit()`)
const setDirectory = (window, value) => window.webContents.executeJavaScript(`{
  const input=document.querySelector('.agent-settings input[type=text]');
  input.value=${JSON.stringify(value)}; input.dispatchEvent(new Event('input',{bubbles:true}));
}`)

registerLocalAgentSettingsIpc({
  ipcMain: { handle(channel, handler) {
    ipcMain.handle(channel, (event, ...args) => {
      if (channel === channels.localAgentsSave) {
        if (failTransport) throw new Error('fixture IPC transport failure')
        evidence.saves++
      }
      return handler(event, ...args)
    })
  } },
  channels,
  trustedSettingsRenderer: event => event.sender === settingsWindow?.webContents && (event.senderFrame?.url ?? event.sender.getURL()) === rendererUrl,
  getRuntime: () => runtime,
  resolveConfigPath: () => configPath,
  startObservation: () => {}, stopObservation: () => {},
  failure: () => ({ error: 'fixture probe failure' }),
})
for (const [channel, value] of [
  [channels.preferencesGet, defaultDesktopPreferences],
  [channels.readinessGet, { runId: 1, status: 'ready', items: [] }],
  [channels.multimodalRelayGet, { version: 1, enabled: false, baseUrl: '', model: '', apiKeyConfigured: false }],
  [channels.startupSettingsStatusGet, { ok: true, message: 'fixture startup status' }],
  [channels.shizukiStatus, { connected: false, origin: '', email: '', userId: 0, error: '' }],
]) ipcMain.handle(channel, () => value)

app.whenReady().then(async () => {
  assert.ok(existsSync(rendererPath), 'Build the desktop before the settings smoke test')
  session.defaultSession.webRequest.onBeforeRequest((details, callback) => callback({ cancel: !/^(file|data):/.test(details.url) }))
  keeper = new BrowserWindow({ show: false })
  let window = await openSettings()
  // First demonstrate the regression condition using actual Vue reactive state.
  const vueSource = readFileSync(join(appRoot, 'node_modules/vue/dist/vue.global.prod.js'), 'utf8')
  await window.webContents.executeJavaScript(vueSource)
  const rejected = await window.webContents.executeJavaScript(`(async()=>{
    try { await window.meguriDesktop.localAgents.save(Vue.ref({enabled:true,harnessAutoStart:false,dshHome:''}).value); return ''; }
    catch(error) { return error.message; }
  })()`)
  assert.match(rejected, /could not be cloned/)
  assert.equal(evidence.saves, 0)
  assert.equal(existsSync(configPath), false)
  record('Vue Proxy fails before IPC; real form must send plain data')
  await window.webContents.executeJavaScript(`document.querySelector('.agent-settings input[type=checkbox]').click()`)
  await saveForm(window)
  await waitFor(window, `document.querySelector('.agent-settings .relay-feedback.success')?.textContent.includes('配置已保存并应用')`)
  assert.equal(evidence.saves, 1)
  assert.equal(readLocalAgentConfig(configPath).enabled, true)
  assert.equal(config.enabled, true)
  assert.equal(evidence.hostStarts, 0)
  record('Actual SettingsView saves reactive checkbox via real preload and handler')
  const beforeInvalid = readFileSync(configPath, 'utf8')
  await setDirectory(window, 'relative/path')
  await saveForm(window)
  await waitFor(window, `document.querySelector('.agent-settings .relay-feedback.error')?.textContent.includes('本机绝对路径')`)
  assert.equal(readFileSync(configPath, 'utf8'), beforeInvalid)
  record('Invalid path shows validation feedback and preserves committed bytes')
  await setDirectory(window, '')
  failTransport = true
  await saveForm(window)
  await waitFor(window, `document.querySelector('.agent-settings .relay-feedback.error')?.textContent.includes('未能确认桌面服务')`)
  assert.equal(readFileSync(configPath, 'utf8'), beforeInvalid)
  failTransport = false
  record('IPC rejection shows transport feedback without a false directory diagnosis')
  window.destroy()
  window = await openSettings()
  assert.equal(await window.webContents.executeJavaScript(`document.querySelector('.agent-settings input[type=checkbox]').checked`), true)
  record('Closing and reopening SettingsView reads saved enabled state')
  await window.webContents.executeJavaScript(`[...document.querySelectorAll('.agent-settings button')].find(e=>e.textContent.trim()==='测试连接').click()`)
  await waitFor(window, `document.querySelector('.agent-settings .agent-facts')?.textContent.includes('Harness fixture connected')`)
  assert.equal(evidence.probes, 1)
  record('Explicit connection test displays both probe results without executing tasks')
  const other = new BrowserWindow({ show: false, webPreferences: { contextIsolation: true, sandbox: true, preload: join(appRoot, 'src/preload/index.cjs'), additionalArguments: ['--meguri-view=settings'] } })
  await other.loadURL(rendererUrl)
  const deniedWindow = await other.webContents.executeJavaScript(`window.meguriDesktop.localAgents.save({enabled:false})`)
  assert.equal(deniedWindow.code, 'untrusted_source')
  other.destroy()
  await window.loadURL('data:text/html,<html><body>Unexpected local test origin</body></html>')
  const deniedOrigin = await window.webContents.executeJavaScript(`window.meguriDesktop.localAgents.save({enabled:false})`)
  assert.equal(deniedOrigin.code, 'untrusted_source')
  assert.equal(readFileSync(configPath, 'utf8'), beforeInvalid)
  assert.equal(config.enabled, true)
  record('Other window and unexpected origin are rejected without state changes')
  finish()
}).catch(finish)
