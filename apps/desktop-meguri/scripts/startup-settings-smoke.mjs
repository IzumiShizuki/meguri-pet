import assert from 'node:assert/strict'
import { readFileSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { createRequire } from 'node:module'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { app, BrowserWindow, ipcMain } from 'electron'
import { startupFeedbackTemplate, startupHandlerCode } from './startup-settings-fixture.mjs'

// Isolated app: no production main import, user state, Core, or Task Scheduler.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(import.meta.url)
const vue = readFileSync(join(dirname(require.resolve('vue/package.json')), 'dist/vue.global.prod.js'), 'utf8')
app.setPath('userData', mkdtempSync(join(tmpdir(), 'meguri-startup-ipc-')))
let mode = 'success'
let requests = []
ipcMain.handle('meguri:startup-settings:save', (_event, patch) => {
  requests.push(patch)
  if (mode === 'reject') throw new Error('An object could not be cloned.')
  const ok = mode === 'success'
  return { ok, saved: true, preferences: { startup: patch }, status: { ok, pending: !ok, message: ok ? '已应用' : '待应用' } }
})
const timer = setTimeout(() => { console.error('Startup IPC smoke timed out'); app.exit(2) }, 15000)

app.whenReady().then(async () => {
const window = new BrowserWindow({
  show: false,
  webPreferences: { contextIsolation: true, sandbox: true, preload: join(root, 'src/preload/index.cjs'), additionalArguments: ['--meguri-view=settings'] },
})
try {
  await window.loadURL('data:text/html,<html><body></body></html>')
  await window.webContents.executeJavaScript(vue)
  const run = async (openAtLogin, runAsAdministrator) => window.webContents.executeJavaScript(`(async () => {
    const { ref, createApp, compile } = Vue;
    const state = {
      startupDraft: ref({ openAtLogin: ${openAtLogin}, runAsAdministrator: ${runAsAdministrator} }),
      startupLoading: ref(false), startupSaving: ref(false), startupDirty: ref(true),
      startupStatus: ref({ ok: true, message: '初始状态' }), startupFeedback: ref(),
      preferences: { apply() {} }, window, Error, Boolean,
    };
    const save = new Function(...Object.keys(state), ${JSON.stringify(`${startupHandlerCode}\nreturn saveStartupSettings`)})(...Object.values(state));
    await save();
    const target = document.createElement('div');
    document.body.append(target);
    const instance = createApp({ setup: () => state, render: compile(${JSON.stringify(startupFeedbackTemplate)}) });
    instance.mount(target);
    const result = { ok: state.startupStatus.value.ok, dirty: state.startupDirty.value, saving: state.startupSaving.value, messages: [...target.querySelectorAll('[role="status"]')].map(p => p.textContent.trim()) };
    instance.unmount(); target.remove();
    return result;
  })()`)
  for (const openAtLogin of [true, false]) {
    for (const runAsAdministrator of [true, false]) {
      const result = await run(openAtLogin, runAsAdministrator)
      assert.equal(result.ok, true, JSON.stringify(result))
      assert.equal(result.dirty, false)
      assert.equal(result.saving, false)
      assert.deepEqual(result.messages, ['已应用'])
      assert.deepEqual(requests.at(-1), { openAtLogin, runAsAdministrator })
    }
  }
  assert.equal(requests.length, 4)
  mode = 'pending'
  assert.deepEqual(await run(true, false), { ok: false, dirty: false, saving: false, messages: ['待应用'] })
  mode = 'reject'
  const failure = await run(true, false)
  assert.equal(failure.ok, false)
  assert.equal(failure.dirty, true)
  assert.equal(failure.saving, false)
  assert.equal(failure.messages.length, 1)
  assert.match(failure.messages[0], /An object could not be cloned/)
  mode = 'success'
  assert.equal((await run(true, false)).ok, true)
  assert.equal(requests.length, 7)
  console.log(JSON.stringify({ ok: true, requests: requests.length, boundary: 'Vue reactive draft → production save handler → real contextBridge → production preload → real IPC stub', windowsSettingsTouched: false }))
}
catch (error) {
  console.error(error)
  process.exitCode = 1
}
finally {
  clearTimeout(timer)
  window.destroy()
  app.exit(process.exitCode ?? 0)
}
})
