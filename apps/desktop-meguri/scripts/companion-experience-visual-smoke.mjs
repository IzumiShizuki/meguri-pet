import { app, BrowserWindow, session } from 'electron'
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const outputRoot = resolve(appRoot, '../../tmp/companion-experience-visual')
const rendererUrl = process.env.MEGURI_RENDERER_URL ?? 'http://127.0.0.1:4186'
const windows = []
const consoleErrors = []
const blockedRequests = []
const screenshots = []
const checks = {}
let vite
let phase = 'initializing'
app.setPath('userData', resolve(app.getPath('temp'), `meguri-companion-visual-${process.pid}`))
mkdirSync(outputRoot, { recursive: true })
const sleep = milliseconds => new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds))
const timeout = setTimeout(() => { console.error(JSON.stringify({ ok: false, phase, error: 'Visual smoke timed out' })); vite?.kill(); app.exit(2) }, 60_000)

async function waitFor(window, expression, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await window.webContents.executeJavaScript(`Boolean(${expression})`)) return
    await sleep(60)
  }
  throw new Error(`Timed out: ${expression}`)
}
async function evaluate(window, source) { return window.webContents.executeJavaScript(`(async () => { ${source} })()`, true) }
async function capture(window, name) {
  await sleep(120)
  await window.webContents.capturePage()
  await sleep(100)
  const image = await window.webContents.capturePage()
  const path = resolve(outputRoot, name)
  writeFileSync(path, image.toPNG())
  screenshots.push(path)
}
async function makeWindow(width, height, view = 'stage') {
  const partition = `companion-visual-${process.pid}-${windows.length}`
  const isolated = session.fromPartition(partition)
  isolated.webRequest.onBeforeRequest((details, callback) => {
    const allowed = details.url.startsWith(rendererUrl) || details.url.startsWith(rendererUrl.replace(/^http/u, 'ws')) || /^(?:data:|blob:|devtools:)/u.test(details.url)
    if (!allowed) blockedRequests.push(details.url.replace(/\?.*$/u, ''))
    callback({ cancel: !allowed })
  })
  const window = new BrowserWindow({ show: false, width, height, webPreferences: { contextIsolation: true, sandbox: true, backgroundThrottling: false, partition } })
  windows.push(window)
  window.webContents.on('console-message', event => {
    if (event.level === 'error' && !event.message.includes('Electron Security Warning')) consoleErrors.push(event.message)
  })
  await window.loadURL(`${rendererUrl}/?preview=offline&view=${view}`)
  await waitFor(window, `document.querySelector(${JSON.stringify(view === 'settings' ? '.settings-shell' : '.privacy-dialog')})`)
  return window
}
async function openSection(window, label) {
  await evaluate(window, `document.querySelector('.settings-back')?.click()`)
  await waitFor(window, 'document.querySelector(".settings-home")')
  await evaluate(window, `Array.from(document.querySelectorAll('.settings-entry')).find(node => node.querySelector('strong')?.textContent === ${JSON.stringify(label)})?.click()`)
  await sleep(120)
}
async function startRenderer() {
  if (process.env.MEGURI_RENDERER_URL) return
  const node = process.env.MEGURI_NODE ?? 'D:/environment/nodejs/runtime/node-v24.17.0-win-x64/node.exe'
  vite = spawn(node, [resolve(appRoot, 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4186', '--strictPort'], { cwd: appRoot, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  let errorOutput = ''
  vite.stderr.on('data', data => { errorOutput = `${errorOutput}${data}`.slice(-3000) })
  await new Promise((resolvePromise, reject) => {
    vite.once('error', reject)
    vite.once('exit', code => reject(new Error(`Vite exited (${code}): ${errorOutput}`)))
    vite.stdout.on('data', data => { if (String(data).includes('Local:')) resolvePromise() })
  })
}

app.whenReady().then(async () => {
  let result
  try {
    await startRenderer()
    phase = 'first-run privacy'
    const stage = await makeWindow(440, 700)
    await waitFor(stage, 'document.querySelector(".privacy-dialog")?.open')
    checks.firstRunPolicy = await evaluate(stage, `const dialog = document.querySelector('.privacy-dialog'); return { modal: dialog.open, choices: dialog.querySelectorAll('input[type=checkbox]').length, remoteInitiallyOff: !dialog.querySelector('input').checked, insideViewport: dialog.getBoundingClientRect().right <= innerWidth + 1 };`)
    await capture(stage, 'privacy-first-run.png')
    await evaluate(stage, `const input = document.querySelector('.privacy-dialog input'); input.click(); document.querySelector('.privacy-dialog button[type=submit]').click();`)
    await waitFor(stage, '!document.querySelector(".privacy-dialog")')
    checks.policyAccepted = await evaluate(stage, `const { useDesktopPreferencesStore } = await import('/src/renderer/stores/desktop-preferences.ts'); const preferences = useDesktopPreferencesStore(); return preferences.value.privacy.acceptedPolicyVersion === '2026-10-05' && preferences.value.privacy.remoteChatAllowed;`)

    phase = 'rich chat and source copying'
    const fixture = '# 格式化回复\n\n- 安全的列表\n- 行内公式 $x^2 + y^2$\n\n$$\\frac{1}{2}$$\n\n| 参数 | 值 |\n| - | - |\n| 长文本 | 测试 |\n\n```js\nconst value = 1\n```\n\n![禁止外链图片](https://example.invalid/tracker.png)\n<script>alert(1)</script>'
    await evaluate(stage, `const { useConversationStore } = await import('/src/renderer/stores/conversation.ts'); const { useDesktopPreferencesStore } = await import('/src/renderer/stores/desktop-preferences.ts'); const conversation = useConversationStore(); conversation.messages = [{ id:'fixture',role:'meguri',status:'complete',text:${JSON.stringify(fixture)} }]; conversation.memoryNotice='正式记忆当前不可用，本次对话不会读取或保存正式记忆。'; await useDesktopPreferencesStore().update({appearance:'light',chat:{expanded:true,colorPalette:'mint'}}); Object.defineProperty(navigator, 'clipboard', {configurable:true,value:{writeText:async text=>{window.__copiedText=text}}});`)
    await waitFor(stage, 'document.querySelector(".chat-history .katex")')
    await evaluate(stage, `document.querySelector('.chat-history .message-copy').click()`)
    await waitFor(stage, 'document.querySelector(".message-copy-row")?.textContent.includes("已复制原文")')
    checks.richChat = await evaluate(stage, `return { headings: !!document.querySelector('.chat-history h1'), formula: !!document.querySelector('.chat-history .katex'), table: !!document.querySelector('.chat-history table'), unsafeElements: document.querySelectorAll('.chat-history script,.chat-history img').length, sourceCopied: window.__copiedText === ${JSON.stringify(fixture)}, horizontalOverflow: document.documentElement.scrollWidth > innerWidth, surroundingTransparent: getComputedStyle(document.querySelector('.stage-shell')).backgroundColor === 'rgba(0, 0, 0, 0)' };`)
    await capture(stage, 'chat-rich-light.png')

    phase = 'Galgame and narrow dark surface'
    await evaluate(stage, `document.querySelector('.controls-island [aria-expanded]')?.click()`)
    await waitFor(stage, `document.querySelector('[aria-label="进入 Galgame 模式"]')`)
    await evaluate(stage, `document.querySelector('[aria-label="进入 Galgame 模式"]').click(); const { useDesktopPreferencesStore } = await import('/src/renderer/stores/desktop-preferences.ts'); await useDesktopPreferencesStore().update({appearance:'dark',chat:{colorPalette:'violet',bubbleOpacity:.2,wallpaperBlur:75}});`)
    await waitFor(stage, 'document.querySelector(".galgame-stage .katex")')
    stage.setSize(360, 680)
    await sleep(100)
    checks.galgame = await evaluate(stage, `const history=document.querySelector('.galgame-history').getBoundingClientRect(); const composer=document.querySelector('.galgame-composer').getBoundingClientRect(); return { formula:!!document.querySelector('.galgame-history .katex'), sourceCopyButton:!!document.querySelector('.galgame-history .message-copy'), composerBelowHistory:history.bottom<=composer.top+1, horizontalOverflow:document.documentElement.scrollWidth>innerWidth };`)
    await capture(stage, 'galgame-rich-dark-narrow.png')

    phase = 'settings and resource states'
    const settings = await makeWindow(470, 760, 'settings')
    await openSection(settings, '对话')
    await waitFor(settings, 'document.querySelector(".palette-options")')
    await evaluate(settings, `document.querySelector('.palette-options button:last-child').click(); Array.from(document.querySelectorAll('.settings-actions button')).find(node=>node.textContent.includes('选择本地壁纸')).click();`)
    await waitFor(settings, 'document.querySelector(".wallpaper-preview")?.style.backgroundImage')
    settings.setSize(350, 700)
    await sleep(80)
    checks.personalization = await evaluate(settings, `const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts'); const value=useDesktopPreferencesStore().value; return {palettes:document.querySelectorAll('.palette-options button').length,wallpaperApplied:value.chat.wallpaperDataUrl.startsWith('data:image/png'),paletteApplied:value.chat.colorPalette==='violet',horizontalOverflow:document.documentElement.scrollWidth>innerWidth};`)
    await capture(settings, 'settings-personalization-narrow.png')
    await openSection(settings, '正式记忆')
    await waitFor(settings, 'document.body.textContent.includes("正式记忆")')
    checks.memoryUnavailable = await evaluate(settings, `return !document.body.textContent.includes('正式记忆服务已就绪') && document.body.textContent.includes('暂不可用')`)
    await capture(settings, 'settings-memory-unavailable.png')
    await openSection(settings, '角色')
    checks.live2dEmpty = await evaluate(settings, `return !!document.querySelector('[aria-label="Live2D 模型管理"]') && document.body.textContent.includes('还没有导入模型')`)
    await capture(settings, 'settings-live2d-empty.png')
    await openSection(settings, '语音')
    checks.voiceUnavailable = await evaluate(settings, `return document.body.textContent.includes('GPT-SoVITS') && document.body.textContent.includes('预览')`)
    await capture(settings, 'settings-voice-unavailable.png')
    await openSection(settings, '应用更新')
    await evaluate(settings, `Array.from(document.querySelectorAll('button')).find(node=>node.textContent==='检查更新')?.click()`)
    await waitFor(settings, 'document.body.textContent.includes("浏览器预览不会查询 GitHub")')
    checks.updateNoNetwork = true
    await capture(settings, 'settings-updates.png')

    phase = 'privacy withdrawal'
    await openSection(settings, '隐私与数据')
    await evaluate(settings, `const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts'); await useDesktopPreferencesStore().update({privacy:{acceptedPolicyVersion:'2026-10-05',remoteChatAllowed:true},memory:{enabled:true}});`)
    await sleep(80)
    await evaluate(settings, `document.querySelector('.privacy-settings input[type=checkbox]').click()`)
    await sleep(80)
    checks.privacyWithdrawn = await evaluate(settings, `const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts'); const {useConversationStore}=await import('/src/renderer/stores/conversation.ts'); const value=useDesktopPreferencesStore().value; const sent=await useConversationStore().send('This must remain blocked'); return !value.privacy.remoteChatAllowed && !value.memory.enabled && !sent && useConversationStore().error.includes('隐私');`)
    await capture(settings, 'settings-privacy-withdrawn.png')
    const ok = checks.firstRunPolicy.modal && checks.firstRunPolicy.choices === 4 && checks.firstRunPolicy.remoteInitiallyOff && checks.firstRunPolicy.insideViewport && checks.policyAccepted
      && checks.richChat.headings && checks.richChat.formula && checks.richChat.table && checks.richChat.unsafeElements === 0 && checks.richChat.sourceCopied && !checks.richChat.horizontalOverflow && checks.richChat.surroundingTransparent
      && checks.galgame.formula && checks.galgame.sourceCopyButton && checks.galgame.composerBelowHistory && !checks.galgame.horizontalOverflow
      && checks.personalization.palettes === 6 && checks.personalization.wallpaperApplied && checks.personalization.paletteApplied && !checks.personalization.horizontalOverflow
      && checks.memoryUnavailable && checks.live2dEmpty && checks.voiceUnavailable && checks.updateNoNetwork && checks.privacyWithdrawn && consoleErrors.length === 0
    result = { ok, checks, screenshots, consoleErrors, blockedRequests, notes: ['Isolated preview partitions; API network requests blocked; no production conversation or memory writes.', 'Clipboard writeText mocked; verifies source and UI feedback without replacing the user clipboard.'] }
  }
  catch (error) { result = { ok: false, phase, error: error instanceof Error ? error.stack : String(error), checks, screenshots, consoleErrors, blockedRequests } }
  finally {
    writeFileSync(resolve(outputRoot, 'result.json'), `${JSON.stringify(result, null, 2)}\n`, 'utf8')
    console.log(JSON.stringify(result))
    clearTimeout(timeout)
    for (const window of windows) window.destroy()
    vite?.kill()
    app.exit(result?.ok ? 0 : 1)
  }
}).catch(error => { clearTimeout(timeout); vite?.kill(); console.error(error); app.exit(1) })
