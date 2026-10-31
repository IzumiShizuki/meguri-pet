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
const reportRoot = resolve(appRoot, '../../reports/unified-center-20261030')
mkdirSync(reportRoot, { recursive: true })
const root = mkdtempSync(join(app.getPath('temp'), 'meguri-center-smoke-'))
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
  const file = join(reportRoot, `center-${name}.png`)
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
  writeFileSync(join(reportRoot, 'center-smoke.json'), JSON.stringify(evidence, null, 2))
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
const clickText = async text => {
  const selector = `[...document.querySelectorAll('.todo-center button')].find(e => e.textContent.trim() === ${JSON.stringify(text)})`
  await waitFor(`${selector}?.disabled === false`)
  return run(`${selector}.click()`)
}
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

let todos = [], notes = [], pendingRecords = [], nextId = 1
const centerSnapshot = () => ({ ok: true, snapshot: { todos, notes, pending: pendingRecords, ready: true, stale: false, error: '' } })
for (const [channel, value] of [
  [channels.preferencesGet, preferences], [channels.preferencesMigrateLegacy, preferences],
  [channels.readinessGet, { runId: 1, status: 'ready', items: [] }], [channels.rendererReady, { ok: true }],
  [channels.companionActivityStatus, { presence: { active: false, activeUntil: 0 } }],
  [channels.windowSetPresentationSurface, { ok: true }], [channels.windowSetClickThrough, { ok: true }],
  [channels.windowPointerPosition, null], [channels.updatesGet, null], [channels.characterPresentationGet, null],
  [channels.live2dActive, null], [channels.pixpinCaptureStatus, { state: 'idle' }],
  [channels.workSnapshot, { requests: [], connection: { enabled: false } }],
  [channels.shizukiStatus, account], [channels.shizukiListTodos, { ok: true, todos: [] }], [channels.shizukiListRecurringRules, { ok: true, rules: [] }],
]) handle(channel, () => value)
handle(channels.shizukiCenterSnapshot, centerSnapshot)
function save(input, kind) {
  assert.equal(input.expectedAccount, `${account.origin}:${account.userId}`)
  assert.match(input.requestId, /^[\da-f-]{36}$/)
  evidence.saves.push(structuredClone(input))
  if (failureMode === 'lost-ipc') throw new Error('synthetic IPC loss')
  if (failureMode === 'conflict') {
    pendingRecords = [{ requestId: input.requestId, resource: kind, method: 'PUT', state: 'conflict', error: '其他设备已修改', draft: { title: input.item.title, detail: input.item.detail }, targetId: input.item.todoId }]
    return { ok: false, code: 'request_failed', status: 409, error: '版本冲突，原草稿已保留' }
  }
  const idKey = kind === 'task' ? 'todoId' : 'noteId'
  const item = { ...input.item, [idKey]: input.item[idKey] || nextId++, version: (input.item.version ?? -1) + 1, updatedAt: '2030-01-01T12:00:00' }
  if (kind === 'task') todos = [...todos.filter(t => t.todoId !== item.todoId), item]
  else notes = [...notes.filter(n => n.noteId !== item.noteId), item]
  return { ok: true, state: 'confirmed', resource: kind, item }
}
handle(channels.shizukiCenterSaveTask, input => save(input, 'task'))
handle(channels.shizukiCenterSaveNote, input => save(input, 'note'))
const form = '.todo-center > form'
const submitCenter = () => run(`document.querySelector(${JSON.stringify(form)}).requestSubmit()`)
app.whenReady().then(async () => {
  assert.ok(existsSync(rendererPath))
  const html = readFileSync(rendererPath, 'utf8')
  evidence.rendererAssets = [...html.matchAll(/(?:src|href)="\.\/([^\"]+\.(?:js|css))"/g)].map(match => ({ path: match[1], sha256: createHash('sha256').update(readFileSync(join(dirname(rendererPath), match[1]))).digest('hex') }))
  session.defaultSession.webRequest.onBeforeRequest((details, callback) => {
    const cancel = !/^(file|data):/.test(details.url)
    if (cancel) evidence.blockedNetwork++
    callback({ cancel })
  })
  stage = new BrowserWindow({ show: false, width: 1100, height: 1000, webPreferences: { contextIsolation: true, sandbox: true, preload: join(appRoot, 'src/preload/index.cjs'), additionalArguments: ['--meguri-view=stage'], backgroundThrottling: false } })
  await stage.loadURL(rendererUrl)
  await waitFor(`document.querySelector('button[aria-label*="Shizuki"]')`)
  await run(`document.querySelector('button[aria-label*="Shizuki"]').click()`)
  await waitFor(`document.querySelector('.todo-center')?.textContent.includes('新增待办')`)
  await clickText('新增待办 / 无截止约定'); await waitFor(`document.querySelector(${JSON.stringify(form + ' input')})`)
  await setInput(`${form} input[maxlength="200"]`, '隔离测试约定')
  await setInput(`${form} textarea`, '不设截止，先核对再执行。')
  assert.equal(evidence.saves.length, 0)
  record('Existing Todo button opens real new center; editing has no write before confirmation')
  await submitCenter()
  await waitFor(`document.querySelector('.todo-center [role=status]')?.textContent.includes('服务器已确认')`)
  assert.equal(todos[0].dueAt, ''); assert.equal(todos[0].reminderEnabled, false)
  assert.equal(todos[0].detail, '不设截止，先核对再执行。')
  await waitFor(`document.querySelector('.todo-center .records')?.textContent.includes('无截止约定')`)
  record('Real agreement form retains blank deadline and body; confirmed receipt renders as undated, not overdue')
  await clickText('便签'); await waitFor(`document.querySelector('.todo-center')?.textContent.includes('新建便签')`); await clickText('新建便签'); await waitFor(`document.querySelector(${JSON.stringify(form + ' textarea')})`)
  await setInput(`${form} input[maxlength="200"]`, '隔离测试便签')
  await setInput(`${form} textarea`, '<script>window.badInjection = true</script>')
  await submitCenter(); await waitFor(`document.querySelector('.todo-center .records')?.textContent.includes('window.badInjection')`)
  assert.equal(await run('window.badInjection'), undefined)
  assert.equal(notes.length, 1); assert.equal(todos.length, 1)
  record('Note text remains text in independent view; no script execution or task creation')
  await clickText('待办与约定'); await waitFor(`document.querySelector('.todo-center .records')?.textContent.includes('隔离测试约定')`); await clickText('编辑'); await waitFor(`document.querySelector(${JSON.stringify(form + ' textarea')})`)
  await setInput(`${form} textarea`, '冲突时保留的本地正文')
  failureMode = 'conflict'; await submitCenter()
  await waitFor(`document.querySelector('.todo-center [role=alert]')?.textContent.includes('版本冲突')`)
  assert.equal(await run(`document.querySelector(${JSON.stringify(form + ' textarea')}).value`), '冲突时保留的本地正文')
  assert.equal(todos[0].detail, '不设截止，先核对再执行。')
  record('Conflict is visible, preserves draft and leaves authoritative task unchanged')
  await capture('conflict')
  await clickText('取消编辑'); await clickText('新增待办 / 无截止约定'); await waitFor(`document.querySelector(${JSON.stringify(form + ' input')})`)
  await setInput(`${form} input[maxlength="200"]`, '隔离传输恢复')
  failureMode = 'lost-ipc'; await submitCenter()
  await waitFor(`document.querySelector('.todo-center [role=alert]')?.textContent.includes('原草稿已保留')`)
  const original = structuredClone(evidence.saves.at(-1))
  assert.equal(await run(`document.querySelector(${JSON.stringify(form + ' fieldset')}).disabled`), true)
  failureMode = ''; await submitCenter()
  await waitFor(`document.querySelector('.todo-center [role=status]')?.textContent.includes('服务器已确认')`)
  assert.deepEqual(evidence.saves.at(-1), original)
  record('IPC-loss retry retains the exact submitted request and locked content')
  await capture('success')
  assert.equal(stage.isVisible(), false)
  record('Hidden isolated renderer stayed hidden; all external network blocked; no real site/Core/profile used')
  await finish()
}).catch(finish)
