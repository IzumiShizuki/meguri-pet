import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { app, BrowserWindow, ipcMain, session } from 'electron'
import channels from '../src/shared/channels.cjs'
import { defaultDesktopPreferences } from '../src/main/desktop-preferences.mjs'
import { weeklyCron } from '../src/shared/recurring-todos.mjs'

// Real built Vue components and sandboxed preload; a hidden temporary profile
// and mock IPC host. Production main, credentials, site and Core are never used.
const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const reportRoot = resolve(appRoot, '../../reports/companion-acceptance-20261029')
mkdirSync(reportRoot, { recursive: true })
const root = mkdtempSync(join(app.getPath('temp'), 'meguri-recurring-todo-smoke-'))
app.setPath('userData', join(root, 'profile'))
const rendererPath = join(appRoot, 'dist/renderer/index.html')
const rendererUrl = `${pathToFileURL(rendererPath).href}?view=stage`
const evidence = { startedAt: new Date().toISOString(), root, mode: 'isolated-real-renderer-mock-ipc', checks: [], screenshots: [], saves: [], blockedNetwork: 0, realSiteRequests: 0 }
const preferences = structuredClone(defaultDesktopPreferences)
Object.assign(preferences.chat, { visible: true, expanded: true })
Object.assign(preferences.window, { alwaysOnTop: false, fadeOnHover: false })
Object.assign(preferences.speech, { ttsEnabled: false, ambientEnabled: false, galgameEnabled: false })
Object.assign(preferences.privacy, { acceptedPolicyVersion: '2026-10-05', remoteChatAllowed: false, saveHistory: false, checkUpdatesOnStartup: false })
preferences.companion.initiative = 'off'
const account = { connected: true, origin: 'https://fixture.invalid', userId: 42, email: 'fixture@example.invalid', error: '' }
let rules = [{ ruleId: 71, title: '隔离样例：周一三五', cronExpr: '0 0 18 * * 1,3,5', timeZoneId: 'Asia/Shanghai', enabled: true, reminderEnabled: true, deadlineRemindValue: 10, deadlineRemindUnit: 'MINUTE', startAt: '2026-10-29T00:00:00', endAt: '', overdueRemindEnabled: false, overdueRemindValue: null, overdueRemindUnit: null }]
let stage, failureMode = '', finished = false
const record = name => evidence.checks.push(name)
const run = code => stage.webContents.executeJavaScript(code)
const timeout = setTimeout(() => { void finish(new Error('recurring todo smoke timed out')) }, 45000)
async function capture(name) {
  // DOM assertions settle before Chromium's compositor. Wait for a paint so a
  // hidden-window capture does not accidentally show the preceding UI state.
  await run(`new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))`)
  await new Promise(resolve => setTimeout(resolve, 100))
  const file = join(reportRoot, `recurring-todo-${name}.png`)
  const screenshot = await stage.webContents.capturePage()
  assert.equal(screenshot.isEmpty(), false)
  writeFileSync(file, screenshot.toPNG())
  evidence.screenshots.push(file)
}
async function finish(error) {
  if (finished) return
  finished = true
  clearTimeout(timeout)
  evidence.ok = !error
  if (error) {
    evidence.error = error.stack ?? error.message
    if (stage && !stage.isDestroyed()) {
      evidence.ui = await run(`document.querySelector('.shizuki-todo-panel')?.innerText || document.body.innerText`).catch(() => '')
      await capture('failure').catch(() => {})
    }
  }
  evidence.completedAt = new Date().toISOString()
  writeFileSync(join(reportRoot, 'recurring-todo-smoke.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify(evidence))
  app.exit(error ? 1 : 0)
}
async function waitFor(expression) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await run(expression)) return
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Expected UI state did not appear: ${expression}`)
}
const submit = () => run(`document.querySelector('.recurring-editor form').requestSubmit()`)
const clickText = text => run(`[...document.querySelectorAll('.recurring-editor button')].find(e => e.textContent.trim() === ${JSON.stringify(text)}).click()`)
const setInput = (selector, value) => run(`{
  const input = document.querySelector(${JSON.stringify(selector)});
  input.value = ${JSON.stringify(value)}; input.dispatchEvent(new Event('input', {bubbles: true}));
}`)
const selectedDays = () => run(`[...document.querySelectorAll('.recurring-editor .weekdays input:checked')].map(e=>Number(e.value))`)
const fieldsetUnlocked = `document.querySelector('.recurring-editor fieldset')?.disabled === false`
const saved = `document.querySelector('.recurring-editor [role=status]')?.textContent.includes('服务器已保存并回读')`
function handle(channel, handler) {
  ipcMain.handle(channel, (event, ...args) => {
    if (finished || !stage || stage.isDestroyed()) return null
    assert.equal(event.sender, stage.webContents)
    assert.equal(event.senderFrame?.url ?? event.sender.getURL(), rendererUrl)
    return handler(...args)
  })
}
for (const [channel, value] of [
  [channels.preferencesGet, preferences],
  [channels.preferencesMigrateLegacy, preferences],
  [channels.readinessGet, { runId: 1, status: 'ready', items: [] }],
  [channels.rendererReady, { ok: true }],
  [channels.companionActivityStatus, { presence: { active: false, activeUntil: 0 } }],
  [channels.windowSetPresentationSurface, { ok: true }],
  [channels.windowSetClickThrough, { ok: true }],
  [channels.windowPointerPosition, null],
  [channels.updatesGet, null],
  [channels.characterPresentationGet, null],
  [channels.live2dActive, null],
  [channels.pixpinCaptureStatus, { state: 'idle' }],
  [channels.workSnapshot, { requests: [], connection: { enabled: false } }],
  [channels.shizukiStatus, account],
  [channels.shizukiListTodos, { ok: true, todos: [] }],
  [channels.shizukiPendingCreates, { ok: true, requests: [] }],
]) handle(channel, () => value)
handle(channels.shizukiListRecurringRules, () => ({ ok: true, rules: structuredClone(rules) }))
handle(channels.shizukiSaveRecurringRule, input => {
  assert.equal(input.expectedAccount, `${account.origin}:${account.userId}`)
  assert.match(input.requestId, /^[\da-f-]{36}$/)
  evidence.saves.push(structuredClone(input))
  if (failureMode === 'rejected') return { ok: false, code: 'request_rejected', error: '隔离样例：服务器明确拒绝，可编辑后重新保存。' }
  if (failureMode === 'uncertain') return { ok: false, code: 'write_uncertain', error: '隔离样例：连接中断，结果未确认，保留原请求。' }
  const rule = { ruleId: input.ruleId || 72, title: input.title, cronExpr: input.cronExpr || weeklyCron(input.weekdays, input.time), timeZoneId: input.timeZoneId, enabled: input.enabled, reminderEnabled: input.reminderEnabled, deadlineRemindValue: input.reminderEnabled ? input.deadlineRemindValue : null, deadlineRemindUnit: input.reminderEnabled ? input.deadlineRemindUnit : null, startAt: '2026-10-29T00:00:00', endAt: '', overdueRemindEnabled: false, overdueRemindValue: null, overdueRemindUnit: null }
  rules = [...rules.filter(item => item.ruleId !== rule.ruleId), rule]
  return { ok: true, rule: structuredClone(rule) }
})

app.whenReady().then(async () => {
  assert.ok(existsSync(rendererPath), 'Build the desktop renderer before this test')
  const html = readFileSync(rendererPath, 'utf8')
  evidence.rendererAssets = [...html.matchAll(/(?:src|href)="\.\/([^\"]+\.(?:js|css))"/g)].map(match => ({ path: match[1], sha256: createHash('sha256').update(readFileSync(join(dirname(rendererPath), match[1]))).digest('hex') }))
  session.defaultSession.webRequest.onBeforeRequest((details, callback) => {
    const cancel = !/^(file|data):/.test(details.url)
    if (cancel) evidence.blockedNetwork++
    callback({ cancel })
  })
  stage = new BrowserWindow({ show: false, width: 1100, height: 1000, webPreferences: { contextIsolation: true, sandbox: true, preload: join(appRoot, 'src/preload/index.cjs'), additionalArguments: ['--meguri-view=stage'], backgroundThrottling: false } })
  await stage.loadURL(rendererUrl)
  await waitFor(`document.querySelector('[aria-label="打开 Shizuki 待办"]')`)
  await run(`document.querySelector('[aria-label="打开 Shizuki 待办"]').click()`)
  await waitFor(`document.querySelector('.recurring-editor li button')`)
  await run(`document.querySelector('.recurring-editor summary').click(); document.querySelector('.recurring-editor li button').click()`)
  assert.deepEqual(await selectedDays(), [1, 3, 5])
  assert.equal(await run(`document.querySelector('.recurring-editor input[type=number]').value`), '10')
  record('Real TodoPanel and RecurringTodoEditor load Mon/Wed/Fri and 10-minute rule through real preload')
  await clickText('周一至周五')
  assert.deepEqual(await selectedDays(), [1, 2, 3, 4, 5])
  await run(`document.querySelector('.recurring-editor .weekdays input[value="2"]').click()`)
  await run(`document.querySelector('.recurring-editor .weekdays input[value="4"]').click()`)
  assert.deepEqual(await selectedDays(), [1, 3, 5])
  await clickText('周一至周五')
  await setInput('.recurring-editor input[maxlength="200"]', '隔离样例：工作日阅读')
  await setInput('.recurring-editor input[type=time]', '21:15')
  await setInput('.recurring-editor input[type=number]', '25')
  assert.equal(evidence.saves.length, 0)
  record('Weekday multi-select and Mon-Fri shortcut change draft only; no save before explicit submit')
  await capture('draft')
  await submit()
  await waitFor(saved)
  assert.equal(evidence.saves.length, 1)
  assert.deepEqual(evidence.saves[0].weekdays, [1, 2, 3, 4, 5])
  assert.equal(evidence.saves[0].deadlineRemindValue, 25)
  assert.equal(evidence.saves[0].time, '21:15')
  record('Explicit real form submit crosses IPC with plain data and renders mock server readback confirmation')
  await run(`document.querySelector('[aria-label="关闭待办"]').click()`)
  await run(`document.querySelector('[aria-label="打开 Shizuki 待办"]').click()`)
  await waitFor(`document.querySelector('.recurring-editor li button')?.textContent.includes('工作日阅读')`)
  await run(`document.querySelector('.recurring-editor summary').click(); document.querySelector('.recurring-editor li button').click()`)
  assert.deepEqual(await selectedDays(), [1, 2, 3, 4, 5])
  assert.equal(await run(`document.querySelector('.recurring-editor input[type=number]').value`), '25')
  assert.equal(await run(`document.querySelector('.recurring-editor input[type=time]').value`), '21:15')
  record('Close/reopen reloads persisted mock rule weekday/time/advance fields')
  failureMode = 'rejected'
  await setInput('.recurring-editor input[maxlength="200"]', '隔离样例：被拒绝草稿')
  await submit()
  await waitFor(`document.querySelector('.recurring-editor [role=alert]')?.textContent.includes('明确拒绝')`)
  await waitFor(fieldsetUnlocked)
  const rejectedId = evidence.saves.at(-1).requestId
  await capture('rejected-editable')
  await setInput('.recurring-editor input[maxlength="200"]', '隔离样例：修正后保存')
  failureMode = ''
  await submit()
  await waitFor(saved)
  assert.notEqual(evidence.saves.at(-1).requestId, rejectedId)
  assert.equal(evidence.saves.at(-1).title, '隔离样例：修正后保存')
  record('Certain server rejection unlocks fields and corrected submit gets a new request ID')
  await clickText('新建周期规则')
  await setInput('.recurring-editor input[maxlength="200"]', '隔离样例：待核对请求')
  failureMode = 'uncertain'
  await submit()
  await waitFor(`document.querySelector('.recurring-editor [role=alert]')?.textContent.includes('结果未确认')`)
  assert.equal(await run(`document.querySelector('.recurring-editor fieldset').disabled`), true)
  assert.equal(await run(`document.querySelector('.recurring-editor button[type=submit]').disabled`), false)
  const uncertainInput = structuredClone(evidence.saves.at(-1))
  await capture('uncertain-locked')
  failureMode = ''
  await submit()
  await waitFor(saved)
  assert.deepEqual(evidence.saves.at(-1), uncertainInput)
  assert.equal(await run(fieldsetUnlocked), true)
  record('Uncertain result locks draft; retry reuses exact request and unlocks only after confirmed success')
  await capture('success')
  assert.equal(stage.isVisible(), false)
  record('Hidden isolated window remained hidden; no site requests or production profile access')
  await finish()
}).catch(finish)
