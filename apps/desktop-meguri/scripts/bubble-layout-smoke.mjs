import { app, BrowserWindow, ipcMain, protocol, session } from 'electron'
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'

import { createStageLayoutController } from '../src/main/stage-layout.mjs'
import { compactStageSize, expandedStageSize, galgameStageSize } from '../src/shared/stage-geometry.mjs'
import { createLive2DStore } from '../src/main/live2d-store.mjs'
import { createLive2DProtocolHandler } from '../src/main/live2d-protocol.mjs'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const projectRoot = resolve(appRoot, '../..')
const outputRoot = resolve(projectRoot, '.codex-runtime/bubble-layout-smoke')
const rendererUrl = process.env.MEGURI_RENDERER_URL ?? 'http://127.0.0.1:4188'
const modelSource = process.env.MEGURI_LIVE2D_SMOKE_MODEL ?? resolve(projectRoot, '.codex-runtime/live2d-smoke/Haru')
const screenshots = []
const consoleErrors = []
const live2dRequests = []
const failedRequests = []
const checks = {}
let window, vite, controller, phase = 'initialize'
const sleep = ms => new Promise(resolvePromise => setTimeout(resolvePromise, ms))
const userData = resolve(app.getPath('temp'), `meguri-bubble-smoke-${process.pid}`)
app.setPath('userData', userData)
protocol.registerSchemesAsPrivileged([{ scheme: 'meguri-live2d', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }])
mkdirSync(outputRoot, { recursive: true })
mkdirSync(userData, { recursive: true })
const preload = resolve(userData, 'bubble-smoke-preload.cjs')
writeFileSync(preload, "const {contextBridge,ipcRenderer}=require('electron');contextBridge.exposeInMainWorld('bubbleSmoke',{fit:(surface,preferences)=>ipcRenderer.invoke('bubble-smoke-fit',surface,preferences)});\n")
const timeout = setTimeout(() => { console.error(`Timeout: ${phase}`); vite?.kill(); app.exit(2) }, 60000)

const evaluate = source => window.webContents.executeJavaScript(`(async () => { ${source} })()`, true)
async function waitFor(expression, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await evaluate(`return Boolean(${expression})`)) return
    await sleep(50)
  }
  throw new Error(`Timed out (${phase}): ${expression}; bounds=${JSON.stringify(window.getBounds())}; state=${JSON.stringify(await inspect())}`)
}
async function capture(name) {
  await sleep(120)
  await window.webContents.capturePage()
  await sleep(100)
  const path = resolve(outputRoot, name)
  writeFileSync(path, (await window.webContents.capturePage()).toPNG())
  screenshots.push(path)
}
async function inspect() {
  return evaluate(`
    const rect = selector => { const r=document.querySelector(selector)?.getBoundingClientRect(); return r && {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom}; };
    return {viewport:{width:innerWidth,height:innerHeight},portrait:rect('.character-stage'),panel:rect('.chat-dock'),body:rect('.chat-body'),composer:rect('.composer'),reply:rect('.compact-reply'),draft:document.querySelector('.composer textarea')?.value,historyVisible:document.querySelector('.chat-history')?.checkVisibility(),sideBubble:document.querySelector('.stage-shell')?.classList.contains('side-bubble'),overflow:document.documentElement.scrollWidth>innerWidth};
  `)
}
function stable(state) {
  assert.equal(state.portrait.width, 350)
  assert.equal(state.portrait.height, 540)
  assert.ok(state.portrait.y >= 8 && state.portrait.bottom <= state.viewport.height)
  assert.ok(state.composer.bottom <= state.viewport.height, JSON.stringify(state))
  assert.ok(state.panel.right <= state.viewport.width)
  assert.equal(state.overflow, false)
}
async function update(patch) {
  await evaluate(`const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts');await useDesktopPreferencesStore().update(${JSON.stringify(patch)});`)
}
async function click(selector) { await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`); }
async function startRenderer() {
  if (process.env.MEGURI_RENDERER_URL) return
  const node = process.env.MEGURI_NODE ?? 'D:/environment/nodejs/runtime/node-v24.17.0-win-x64/node.exe'
  vite = spawn(node, [resolve(appRoot, 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4188', '--strictPort'], { cwd: appRoot, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  let errors = ''
  vite.stderr.on('data', data => { errors = `${errors}${data}`.slice(-2000) })
  await new Promise((resolvePromise, reject) => {
    vite.once('error', reject)
    vite.once('exit', code => reject(new Error(`Vite exited (${code}): ${errors}`)))
    vite.stdout.on('data', data => { if (String(data).includes('Local:')) resolvePromise() })
  })
}

app.whenReady().then(async () => {
  let result
  try {
    await startRenderer()
    // This Electron process already has its own temporary userData directory.
    // Use its default session so custom schemes follow the production host path.
    const isolated = session.defaultSession
    isolated.webRequest.onBeforeRequest((details, callback) => callback({ cancel: !(details.url.startsWith(rendererUrl) || details.url.startsWith(rendererUrl.replace(/^http/u, 'ws')) || /^(?:data:|blob:|devtools:|meguri-live2d:)/u.test(details.url)) }))
    isolated.webRequest.onErrorOccurred(details => failedRequests.push({url:details.url.replace(/\?.*$/u, ''), error:details.error}))
    let active
    if (existsSync(resolve(modelSource, 'Haru.model3.json'))) {
      const store = createLive2DStore({ root: resolve(userData, 'live2d-models') })
      const catalog = await store.importDirectory(modelSource)
      store.activate(catalog.models[0].id)
      active = store.active()
      const modelHandler = createLive2DProtocolHandler({ store, allowedOrigins: [new URL(rendererUrl).origin] })
      protocol.handle('meguri-live2d', async request => {
        const response = await modelHandler(request)
        live2dRequests.push({ url: request.url, origin: request.headers.get('origin'), status: response.status })
        return response
      })
    }
    window = new BrowserWindow({ show: false, x: 400, y: 160, ...compactStageSize, frame: false, transparent: true, webPreferences: { preload, contextIsolation: true, sandbox: true, backgroundThrottling: false } })
    controller = createStageLayoutController({ window, initial: { mode: 'compact', expandedSize: { ...expandedStageSize }, galgameSize: { ...galgameStageSize } }, matchingWorkArea: () => ({ x: 0, y: 0, width: 1920, height: 1040 }), writeState: () => {} })
    ipcMain.handle('bubble-smoke-fit', (event, surface, preferences) => {
      assert.equal(event.sender, window.webContents)
      assert.ok(['ambient', 'ambient-panel', 'galgame'].includes(surface))
      controller.fit(preferences, surface)
      return { ok: true }
    })
    window.webContents.on('console-message', event => { if (event.level === 'error' && !event.message.includes('Electron Security Warning')) consoleErrors.push(event.message) })
    await window.loadURL(`${rendererUrl}/?preview=offline&view=stage`)
    await waitFor('document.querySelector(".privacy-dialog")?.open')
    await evaluate(`window.meguriDesktop.window.setPresentationSurface=async surface=>window.bubbleSmoke.fit(surface,await window.meguriDesktop.preferences.get());`)
    await update({ privacy: { acceptedPolicyVersion: '2026-10-05', remoteChatAllowed: false }, chat: { visible: true, expanded: false }, character: { reducedMotion: true }, window: { fadeOnHover: false } })
    await waitFor('!document.querySelector(".privacy-dialog") && document.querySelector(".compact-reply")')
    const spritePath = resolve(projectRoot, 'data/meguri/assets/sprites/meguri/ce04001l.png')
    assert.ok(existsSync(spritePath), 'Use the existing local Meguri portrait for the visual check')
    const spriteDataUrl = `data:image/png;base64,${readFileSync(spritePath).toString('base64')}`
    await evaluate(`const {usePresentationStore}=await import('/src/renderer/stores/presentation.ts');usePresentationStore().character.spriteUrl=${JSON.stringify(spriteDataUrl)};`)
    await waitFor('document.querySelector("img.character-sprite")?.naturalWidth>0')
    const reply = '已经一点多了，该睡了哦。晚安，做个好梦。\n' + '这是一段用来检查长回复换行和完整记录的文字。'.repeat(28)
    await evaluate(`const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');const conversation=useConversationStore();conversation.messages=[{id:'older',role:'meguri',status:'complete',text:'较早的一句回复。'},{id:'user',role:'user',status:'complete',text:'晚安'},{id:'reply',role:'meguri',status:'complete',text:${JSON.stringify(reply)},speech:{text:'晚安，做个好梦。',voiceStyle:'gentle',intensity:'calm'}}];const textarea=document.querySelector('.composer textarea');textarea.value='保留这份未发送的草稿';textarea.dispatchEvent(new Event('input',{bubbles:true}));`)
    phase = 'compact bubble'
    checks.compact = await inspect()
    stable(checks.compact)
    assert.equal(checks.compact.historyVisible, false)
    assert.ok(checks.compact.composer.height > 0)
    assert.ok(checks.compact.reply.height <= 110)
    assert.ok(checks.compact.panel.height < 210)
    await capture('compact.png')

    phase = 'compact speech replay'
    await evaluate(`
      window.__speechSmokeSaved={fetch:window.fetch,Audio:window.Audio,AudioContext:window.AudioContext};
      window.__speechSmokeRequests=[];window.__speechSmokeFail=false;
      window.fetch=async(input,init)=>{
        if(String(typeof input==='string'?input:input.url).includes('/tts/synthesize')){
          window.__speechSmokeRequests.push(JSON.parse(init.body));
          return new Response(new Blob(['mock audio']),{status:window.__speechSmokeFail?503:200});
        }
        return window.__speechSmokeSaved.fetch(input,init);
      };
      window.Audio=class extends EventTarget{volume=1;src='';constructor(src){super();this.src=src;window.__speechSmokeAudio=this}play(){return Promise.resolve()}pause(){}};
      window.AudioContext=class{constructor(){throw new Error('Smoke uses silent fake audio')}};
    `)
    await update({ speech: { ttsEnabled: false, ambientEnabled: false, volume: 0.45, speed: 1.2 } })
    await click('.compact-reply .message-replay')
    await waitFor('document.querySelector(".compact-reply .message-replay")?.textContent.includes("停止")')
    checks.compactReplay = await evaluate(`
      const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');
      const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts');
      return {request:window.__speechSmokeRequests.at(-1),messages:useConversationStore().messages.length,
        automaticSpeech:useDesktopPreferencesStore().value.speech.ttsEnabled,volume:window.__speechSmokeAudio.volume};
    `)
    assert.equal(checks.compactReplay.request.text, '晚安，做个好梦。')
    assert.equal(checks.compactReplay.request.voice_style, 'gentle')
    assert.equal(checks.compactReplay.request.speed_factor, 1.2)
    assert.equal(checks.compactReplay.volume, 0.45)
    assert.equal(checks.compactReplay.automaticSpeech, false)
    assert.equal(checks.compactReplay.messages, 3)
    assert.equal((await inspect()).historyVisible, false)
    assert.ok(Math.abs(window.getBounds().width - 390) <= 2)
    await capture('compact-replay.png')
    await click('[aria-label="展开聊天"]')
    await waitFor('Math.abs(innerWidth-390)<=2 && document.querySelector(".chat-history")?.checkVisibility()')
    await waitFor('!document.querySelector(".stage-shell").classList.contains("side-bubble")')
    assert.ok(await evaluate(`const {speechPlaybackState}=await import('/src/renderer/services/speech-playback.ts');return speechPlaybackState.speaking`), 'A layout preference refresh must not stop explicit playback when automatic speech is disabled')
    await click('[aria-label="收起聊天记录"]')
    await waitFor('Math.abs(innerWidth-390)<=2 && document.querySelector(".compact-reply .message-replay")?.textContent.includes("停止")')
    await click('.compact-reply .message-replay')
    await waitFor('document.querySelector(".compact-reply .message-replay")?.textContent.includes("重放")')
    await evaluate(`window.__speechSmokeFail=true`)
    await click('.compact-reply .message-replay')
    await waitFor('document.querySelector(".compact-reply .message-replay-error")?.textContent.includes("本地语音")')
    assert.equal((await inspect()).historyVisible, false)
    await capture('compact-replay-error.png')
    await evaluate(`window.__speechSmokeFail=false`)
    await click('.compact-reply .message-replay')
    await waitFor('document.querySelector(".compact-reply .message-replay")?.textContent.includes("停止")')
    assert.ok(await evaluate(`return !document.querySelector('.compact-reply .message-replay-error')`))
    await evaluate(`window.__speechSmokeAudio.dispatchEvent(new Event('ended'))`)
    await waitFor('document.querySelector(".compact-reply .message-replay")?.textContent.includes("重放")')
    checks.compactReplayStopAndRetry = true
    const replayCount = await evaluate(`return window.__speechSmokeRequests.length`)
    await evaluate(`document.querySelector('.chat-dock').__vueParentComponent.setupState.voiceCallState='listening'`)
    await waitFor('document.querySelector(".compact-reply .message-replay")?.disabled')
    await click('.compact-reply .message-replay')
    assert.equal(await evaluate(`return window.__speechSmokeRequests.length`), replayCount)
    await evaluate(`document.querySelector('.chat-dock').__vueParentComponent.setupState.voiceCallState='idle'`)
    await waitFor('!document.querySelector(".compact-reply .message-replay")?.disabled')
    checks.voiceCallDisablesReplay = true

    phase = 'repeated expansion and collapse'
    const initialBounds = window.getBounds()
    for (let index = 0; index < 8; index++) {
      await click('[aria-label="展开聊天"]')
      await waitFor('Math.abs(innerWidth-390)<=2 && document.querySelector(".chat-dock.expanded")')
      await sleep(80)
      await waitFor('document.querySelector(".chat-history")?.checkVisibility()')
      await waitFor('!document.querySelector(".stage-shell").classList.contains("side-bubble")')
      await waitFor('document.querySelector(".composer").getBoundingClientRect().bottom<=innerHeight && Math.abs(document.querySelector(".character-stage").getBoundingClientRect().x-(innerWidth-350)/2)<=2')
      const expanded = await inspect()
      stable(expanded)
      assert.equal(expanded.historyVisible, true)
      assert.equal(expanded.draft, '保留这份未发送的草稿')
      assert.ok(expanded.body.height > 350)
      assert.equal(expanded.sideBubble, false)
      if (index === 0) {
        await click('.chat-history .message.meguri .message-replay')
        await waitFor('document.querySelector(".chat-history .message-replay")?.textContent.includes("停止")')
        assert.equal(await evaluate(`return window.__speechSmokeRequests.at(-1).text`), '较早的一句回复。')
        assert.equal(await evaluate(`return document.querySelectorAll('.chat-history .message.user .message-replay').length`), 0)
        await click('.chat-history .message.meguri .message-replay')
        checks.historyReplay = true
      }
      if (index === 0) { checks.expanded = expanded; await capture('expanded.png') }
      await click('[aria-label="收起聊天记录"]')
      await waitFor('Math.abs(innerWidth-390)<=2 && document.querySelector(".compact-reply")')
      await waitFor('document.querySelector(".composer").getBoundingClientRect().bottom<=innerHeight && Math.abs(document.querySelector(".character-stage").getBoundingClientRect().x-(innerWidth-350)/2)<=2')
      const bounds = window.getBounds()
      checks.toggleBounds ??= []
      checks.toggleBounds.push(bounds)
      assert.ok(Math.abs(bounds.x - initialBounds.x) <= 2, 'DIP rounding must not accumulate across toggles')
      assert.ok(Math.abs(bounds.y - initialBounds.y) <= 2)
      stable(await inspect())
    }
    checks.repeatedToggles = 8
    await evaluate(`const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');const conversation=useConversationStore();conversation.sending=true;conversation.messages.push({id:'stream',role:'meguri',status:'streaming',text:'新的流式回复'});`)
    await waitFor('document.querySelector(".compact-reply-text")?.textContent.includes("新的流式回复")')
    assert.ok(Math.abs(window.getBounds().width - 390) <= 2)
    await evaluate(`const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');useConversationStore().sending=false;`)
    checks.streamingCompact = true
    assert.equal(await evaluate(`return document.querySelectorAll('.compact-reply .message-replay').length`), 0)
    await evaluate(`const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');useConversationStore().messages.pop();`)

    phase = 'send error keeps history collapsed'
    await evaluate(`const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');useConversationStore().error='发送未完成，请稍后重试。';`)
    await waitFor('document.querySelector(".conversation-error")')
    await waitFor('Math.abs(document.querySelector(".character-stage").getBoundingClientRect().x-(innerWidth-350)/2)<=2')
    checks.compactSendError = await inspect()
    stable(checks.compactSendError)
    assert.equal(checks.compactSendError.historyVisible, false)
    assert.ok(checks.compactSendError.panel.height < 245)
    assert.ok(Math.abs(window.getBounds().width - 390) <= 2)
    await capture('compact-send-error.png')
    await evaluate(`const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');useConversationStore().error='';`)

    phase = 'Todo temporary panel'
    await evaluate(`window.meguriDesktop.shizuki={status:async()=>({connected:true,origin:'preview',userId:1}),listTodos:async()=>({ok:true,todos:Array.from({length:18},(_,index)=>({todoId:index+1,title:'待办事项 '+(index+1),dueAt:'2026-10-08T09:00:00',completed:false,reminderEnabled:true}))}),pendingCreates:async()=>({ok:true,requests:[]})};`)
    await click('[aria-label="打开 Shizuki 待办"]')
    await waitFor('Math.abs(innerWidth-800)<=2 && document.querySelector(".shizuki-todo-panel")')
    await waitFor('document.querySelector(".stage-shell").classList.contains("side-bubble")')
    checks.todo = await inspect()
    stable(checks.todo)
    assert.equal(checks.todo.historyVisible, false)
    const todo = await evaluate(`const r=document.querySelector('.shizuki-todo-panel').getBoundingClientRect();return {bottom:r.bottom,scrollable:document.querySelector('.shizuki-todo-panel').scrollHeight>r.height,items:document.querySelectorAll('.shizuki-todo-item').length};`)
    assert.ok(todo.bottom <= checks.todo.composer.y)
    assert.equal(todo.items, 18)
    assert.equal(todo.scrollable, true)
    assert.ok(await evaluate(`return document.querySelector('.shizuki-todo-item:last-child').getBoundingClientRect().bottom <= document.querySelector('.shizuki-todo-note').getBoundingClientRect().top`), 'Todo list must not paint over its footer note')
    await capture('todo.png')
    await click('[aria-label="关闭待办"]')
    await waitFor('Math.abs(innerWidth-390)<=2 && document.querySelector(".compact-reply")')
    assert.equal((await inspect()).draft, '保留这份未发送的草稿')
    checks.todoReturnsToCompact = true

    phase = 'reminder review'
    await evaluate(`const dock=document.querySelector('.chat-dock').__vueParentComponent.setupState;await dock.reminderFlow.prepare('明天上午九点提醒我整理桌面');`)
    await waitFor('Math.abs(innerWidth-800)<=2 && document.querySelector(".reminder-review")')
    await waitFor('document.querySelector(".stage-shell").classList.contains("side-bubble")')
    checks.reminder = await inspect()
    stable(checks.reminder)
    assert.equal(checks.reminder.historyVisible, false)
    assert.ok(checks.reminder.body.bottom <= checks.reminder.composer.y)
    await capture('reminder.png')
    await evaluate(`document.querySelector('.chat-dock').__vueParentComponent.setupState.reminderFlow.close()`)
    await waitFor('Math.abs(innerWidth-390)<=2 && document.querySelector(".compact-reply")')

    phase = 'attachment review'
    await evaluate(`const dock=document.querySelector('.chat-dock').__vueParentComponent.setupState;dock.selectedAttachments=[{type:'local_file_reference',source:'everything',reference_id:'fixture-file',name:'bilibili-2026-10-06.md',path:'D:/preview/bilibili-2026-10-06.md',kind:'file',content_access:'not_read',size_bytes:40}];`)
    await waitFor('Math.abs(innerWidth-800)<=2 && document.querySelector(".resource-panel")')
    await waitFor('document.querySelector(".stage-shell").classList.contains("side-bubble")')
    checks.attachments = await inspect()
    stable(checks.attachments)
    assert.equal(checks.attachments.historyVisible, false)
    assert.equal(await evaluate(`return document.querySelector('.chat-body').checkVisibility()`), false)
    const resource = await evaluate(`return document.querySelector('.resource-panel').getBoundingClientRect().bottom`)
    assert.ok(resource <= (await inspect()).composer.y)
    await capture('attachments.png')
    await click('[aria-label="移除 bilibili-2026-10-06.md"]')
    await waitFor('Math.abs(innerWidth-390)<=2 && document.querySelector(".compact-reply")')
    checks.attachmentsReturnToCompact = true

    phase = 'skills and work panels'
    for (const [key, selector, name] of [['k', '.skills-workspace', 'skills'], ['j', '.work-panel', 'work']]) {
      await evaluate(`window.dispatchEvent(new KeyboardEvent('keydown',{key:${JSON.stringify(key)},ctrlKey:true,shiftKey:true,bubbles:true}))`)
      await waitFor(`Math.abs(innerWidth-800)<=2 && document.querySelector(${JSON.stringify(selector)})`)
      await waitFor('document.querySelector(".stage-shell").classList.contains("side-bubble")')
      await sleep(250)
      const position = await evaluate(`const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.x,right:r.right}`)
      assert.ok(position.x >= 390 && position.right <= 802)
      await capture(`${name}.png`)
      await evaluate(`window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`)
      await waitFor('Math.abs(innerWidth-390)<=2')
      checks[name] = true
    }

    phase = 'narrow fallback and manual resize'
    await update({ chat: { expanded: true } })
    await waitFor('Math.abs(innerWidth-390)<=2')
    window.setSize(360, 560)
    await waitFor('Math.abs(innerWidth-360)<=2 && !document.querySelector(".stage-shell").classList.contains("side-bubble")')
    checks.narrow = await inspect()
    stable(checks.narrow)
    assert.ok(checks.narrow.body.height > 350)
    await capture('narrow.png')
    window.setSize(920, 760)
    await waitFor('Math.abs(innerWidth-920)<=2')
    stable(await inspect())
    await update({ chat: { expanded: false } })
    await waitFor('Math.abs(innerWidth-390)<=2 && Math.abs(innerHeight-560)<=2')
    checks.manualResizeReturnsToCompact = true

    phase = 'Galgame history replay'
    await evaluate(`const {usePresentationSurfaceStore}=await import('/src/renderer/stores/presentation-surface.ts');usePresentationSurfaceStore().enterGalgame()`)
    await waitFor('document.querySelector(".galgame-history .message-replay")')
    await click('.galgame-history .message-replay')
    await waitFor('document.querySelector(".galgame-history .message-replay")?.textContent.includes("停止")')
    assert.equal(await evaluate(`return window.__speechSmokeRequests.at(-1).text`), '较早的一句回复。')
    await click('.galgame-history .message-replay')
    checks.galgameReplay = true
    await evaluate(`const {usePresentationSurfaceStore}=await import('/src/renderer/stores/presentation-surface.ts');usePresentationSurfaceStore().exitGalgame()`)
    await waitFor('Math.abs(innerWidth-390)<=2 && document.querySelector(".compact-reply")')
    await evaluate(`Object.assign(window,window.__speechSmokeSaved)`)

    phase = 'Live2D stable canvas'
    if (active) {
      await evaluate(`window.meguriDesktop.live2d.active=async()=>(${JSON.stringify(active)});`)
      checks.modelFetch = await evaluate(`const response=await fetch(${JSON.stringify(active.modelUrl)});return {status:response.status,textLength:(await response.text()).length};`)
      await update({ character: { renderer: 'live2d', live2dModelId: active.id, reducedMotion: false } })
      await waitFor('document.querySelector(".live2d-character canvas")', 15000)
      await waitFor(`(() => {
        const canvas=document.querySelector('.live2d-character canvas');if(!canvas)return false;
        const target=document.createElement('canvas');target.width=canvas.width;target.height=canvas.height;
        const ctx=target.getContext('2d');ctx.drawImage(canvas,0,0);const data=ctx.getImageData(0,0,target.width,target.height).data;
        let visible=0;for(let index=3;index<data.length;index+=4)if(data[index]>20)visible++;return visible>10000;
      })()`, 15000)
      await evaluate(`window.__bubbleSmokeCanvas=document.querySelector('.live2d-character canvas')`)
      await waitFor('Math.abs(document.querySelector(".live2d-character canvas").getBoundingClientRect().width-350)<0.01 && Math.abs(document.querySelector(".live2d-character canvas").getBoundingClientRect().height-540)<0.01')
      const canvasBounds = () => evaluate(`const c=document.querySelector('.live2d-character canvas');const r=c.getBoundingClientRect();return {width:r.width,height:r.height,pixelWidth:c.width,pixelHeight:c.height};`)
      checks.live2dCompact = await canvasBounds()
      stable(await inspect())
      await capture('live2d-compact.png')
      await update({ chat: { expanded: true } })
      await waitFor('Math.abs(innerWidth-390)<=2')
      await waitFor('!document.querySelector(".stage-shell").classList.contains("side-bubble")')
      checks.live2dExpanded = await canvasBounds()
      assert.deepEqual(checks.live2dExpanded, checks.live2dCompact)
      assert.ok(await evaluate(`return document.querySelector('.live2d-character canvas')===window.__bubbleSmokeCanvas`), 'Opening a bubble must not reload Live2D')
      stable(await inspect())
      await capture('live2d-expanded.png')
      await update({ chat: { expanded: false } })
      await waitFor('Math.abs(innerWidth-390)<=2')
      assert.deepEqual(await canvasBounds(), checks.live2dCompact)
    }
    else checks.live2d = 'Fixture unavailable; PNG and geometry verified.'
    assert.deepEqual(consoleErrors, [])
    result = { ok: true, checks, screenshots, consoleErrors, notes: ['Isolated Electron window using the actual native layout controller; preview data and blocked external requests.', 'No production preferences, todos, conversations, or memory were modified.'] }
  }
  catch (error) { result = { ok: false, phase, error: error.stack ?? String(error), checks, screenshots, consoleErrors, live2dRequests, failedRequests } }
  finally {
    writeFileSync(resolve(outputRoot, 'result.json'), `${JSON.stringify(result, null, 2)}\n`)
    console.log(JSON.stringify(result))
    clearTimeout(timeout)
    window?.destroy()
    vite?.kill()
    app.exit(result?.ok ? 0 : 1)
  }
}).catch(error => { clearTimeout(timeout); vite?.kill(); console.error(error); app.exit(1) })
