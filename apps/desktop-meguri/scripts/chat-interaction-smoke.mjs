import { app, BrowserWindow, ipcMain, session } from 'electron'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { createStageLayoutController } from '../src/main/stage-layout.mjs'
import { compactStageSize, expandedStageSize, galgameStageSize } from '../src/shared/stage-geometry.mjs'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const outputRoot = resolve(appRoot, '../../.codex-runtime/chat-interaction-smoke')
const rendererUrl = process.env.MEGURI_RENDERER_URL ?? 'http://127.0.0.1:4192'
const userData = resolve(app.getPath('temp'), `meguri-chat-interaction-${process.pid}`)
app.setPath('userData', userData)
mkdirSync(outputRoot, { recursive: true })
mkdirSync(userData, { recursive: true })
const preload = resolve(userData, 'interaction.cjs')
writeFileSync(preload, "const {contextBridge,ipcRenderer}=require('electron');contextBridge.exposeInMainWorld('interactionSmoke',{fit:(surface,preferences)=>ipcRenderer.invoke('interaction-fit',surface,preferences)});")
let window, vite, phase = 'startup'
const errors = []
const checks = {}
const sleep = ms => new Promise(done => setTimeout(done, ms))
const evaluate = source => window.webContents.executeJavaScript(`(async()=>{${source}})()`, true)
const timeout = setTimeout(() => { console.error(`Timeout: ${phase}`); vite?.kill(); app.exit(2) }, 60000)
async function waitFor(expression, ms = 3000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (await evaluate(`return Boolean(${expression})`)) return
    await sleep(40)
  }
  throw new Error(`${phase}: ${expression}`)
}
async function update(patch) {
  await evaluate(`const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts');await useDesktopPreferencesStore().update(${JSON.stringify(patch)});`)
}
async function capture(name) {
  await sleep(300)
  await window.webContents.capturePage()
  await sleep(100)
  writeFileSync(resolve(outputRoot, `${name}.png`), (await window.webContents.capturePage()).toPNG())
}

async function controlRegions() {
  return evaluate(`
    const region=element=>getComputedStyle(element).getPropertyValue('-webkit-app-region');
    const rails=[...document.querySelectorAll('.controls-island')].filter(element=>
      element.checkVisibility() && getComputedStyle(element).pointerEvents!=='none' && !element.closest('[inert]'));
    return {activeRails:rails.length,floatingRails:rails.filter(element=>!element.classList.contains('inline')).length,
      inlineRails:rails.filter(element=>element.classList.contains('inline')).length,
      dragButtons:[...document.querySelectorAll('.controls-rail .drag-region')].filter(element=>element.checkVisibility()).map(region),
      headerDrag:document.querySelector('.chat-dock.expanded .chat-header') ? region(document.querySelector('.chat-header')) : null,
      leavingChats:[...document.querySelectorAll('.chat-dock.dock-leave-active')].map(element=>({
        inert:element.inert,hidden:element.getAttribute('aria-hidden'),
        draggableRegions:[...element.querySelectorAll('.drag-region')].map(region)})),
      actionRegions:[...document.querySelectorAll('.chat-header button')].filter(element=>element.checkVisibility()).map(region)};
  `)
}

app.whenReady().then(async () => {
  try {
    if (!process.env.MEGURI_RENDERER_URL) {
      vite = spawn(process.env.MEGURI_NODE ?? 'D:/environment/nodejs/runtime/node-v24.17.0-win-x64/node.exe',
        [resolve(appRoot, 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4192', '--strictPort'],
        { cwd: appRoot, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
      await new Promise((done, fail) => {
        vite.once('error', fail)
        vite.once('exit', code => fail(new Error(`Vite exited: ${code}`)))
        vite.stdout.on('data', data => { if (String(data).includes('Local:')) done() })
      })
    }
    session.defaultSession.webRequest.onBeforeRequest((details, callback) => callback({ cancel: !(details.url.startsWith(rendererUrl)
      || details.url.startsWith(rendererUrl.replace(/^http/u, 'ws')) || /^(?:data:|blob:|devtools:)/u.test(details.url)) }))
    window = new BrowserWindow({ show: false, x: 1480, y: 160, ...compactStageSize, frame: false, transparent: true,
      webPreferences: { preload, sandbox: true, contextIsolation: true, backgroundThrottling: false } })
    const controller = createStageLayoutController({ window,
      initial: { mode: 'compact', expandedSize: { ...expandedStageSize }, galgameSize: { ...galgameStageSize } },
      matchingWorkArea: () => ({ x: 0, y: 0, width: 1920, height: 1040 }), writeState: () => {} })
    ipcMain.handle('interaction-fit', (_, surface, preferences) => { controller.fit(preferences, surface); return { ok: true } })
    window.webContents.on('console-message', event => {
      if (event.level === 'error' && !event.message.includes('Electron Security Warning')) errors.push(event.message)
    })
    await window.loadURL(`${rendererUrl}/?preview=offline&view=stage`)
    await waitFor('document.querySelector(".privacy-dialog")?.open')
    await evaluate(`window.__fitSurfaces=[];window.meguriDesktop.window.setPresentationSurface=async surface=>{window.__fitSurfaces.push(surface);return window.interactionSmoke.fit(surface,await window.meguriDesktop.preferences.get())};`)
    await update({ privacy: { acceptedPolicyVersion: '2026-10-05', remoteChatAllowed: false },
      chat: { visible: true, expanded: false }, character: { reducedMotion: true }, window: { alwaysOnTop: true, fadeOnHover: true } })
    await waitFor('!document.querySelector(".privacy-dialog") && document.querySelector(".compact-reply")')
    const sprite = readFileSync(resolve(appRoot, '../../data/meguri/assets/sprites/meguri/ce04001l.png')).toString('base64')
    await evaluate(`const {usePresentationStore}=await import('/src/renderer/stores/presentation.ts');usePresentationStore().character.spriteUrl='data:image/png;base64,${sprite}';
      window.__cursor={x:195,y:220};window.__ignore=false;window.__pollFailed=false;
      window.meguriDesktop.window.pointerPosition=async()=>{if(window.__pollFailed)throw new Error('injected cursor failure');return window.__cursor};
      window.meguriDesktop.window.setClickThrough=async ignore=>{window.__ignore=ignore};`)
    phase = 'hover hides portrait'
    await waitFor('document.querySelector(".character-stage.hover-faded") && window.__ignore')
    phase = 'native cursor leaves without DOM mouseleave'
    await evaluate('window.__cursor={x:innerWidth+60,y:220};')
    await waitFor('!document.querySelector(".character-stage.hover-faded")')
    checks.exteriorRecovery = true

    for (const missing of ['undefined', '{x:NaN,y:220}']) {
      phase = `missing native cursor ${missing}`
      await evaluate('window.__cursor={x:195,y:220};')
      await waitFor('document.querySelector(".character-stage.hover-faded")')
      await evaluate(`window.__cursor=${missing};`)
      await waitFor('!document.querySelector(".character-stage.hover-faded") && !window.__ignore')
    }
    phase = 'failed native cursor sample'
    await evaluate('window.__cursor={x:195,y:220};')
    await waitFor('document.querySelector(".character-stage.hover-faded")')
    await evaluate('window.__pollFailed=true;')
    await waitFor('!document.querySelector(".character-stage.hover-faded") && !window.__ignore')
    await evaluate('window.__pollFailed=false;window.__cursor={x:195,y:220};')
    await waitFor('document.querySelector(".character-stage.hover-faded")')
    checks.pollFailureRecovery = true
    await evaluate('const r=document.querySelector(".control-button").getBoundingClientRect();window.__cursor={x:r.x+r.width/2,y:r.y+r.height/2};')
    await waitFor('!document.querySelector(".character-stage.hover-faded") && !window.__ignore')
    checks.controlRecovery = true
    await update({ window: { fadeOnHover: false } })
    await capture('compact')
    checks.compactControls = await controlRegions()
    assert.equal(checks.compactControls.activeRails, 1)
    assert.equal(checks.compactControls.floatingRails, 1)
    assert.deepEqual(checks.compactControls.dragButtons, ['drag'])

    phase = 'expanded header'
    await update({ chat: { expanded: true } })
    await waitFor('document.querySelector(".chat-dock.expanded .controls-island.inline")')
    await evaluate(`const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');useConversationStore().messages=[
      {id:'u',role:'user',status:'complete',text:'今天有什么安排？'},
      {id:'m',role:'meguri',status:'complete',text:'先把手头的事情做完，晚一点一起休息吧。\\n你也可以随时叫我帮忙。'}];`)
    checks.header = await evaluate(`const buttons=[...document.querySelectorAll('.chat-header button')];
      const rects=buttons.map(b=>{const r=b.getBoundingClientRect();return {label:b.getAttribute('aria-label'),x:r.x,y:r.y,right:r.right,bottom:r.bottom,width:r.width,height:r.height,hittable:document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)?.closest('button')===b}});
      return {buttons:rects,rails:document.querySelectorAll('.controls-island').length,historyPadding:getComputedStyle(document.querySelector('.chat-history')).paddingRight,overflow:document.documentElement.scrollWidth>innerWidth};`)
    assert.equal(checks.header.overflow, false)
    assert.equal(checks.header.rails, 1)
    assert.ok(parseFloat(checks.header.historyPadding) < 20)
    for (const r of checks.header.buttons) { assert.ok(r.label); assert.ok(r.hittable); assert.ok(r.width >= 28 && r.height >= 28); assert.ok(r.x >= 0 && r.right <= 390) }
    await capture('expanded-header')
    checks.expandedControls = await controlRegions()
    assert.equal(checks.expandedControls.activeRails, 1)
    assert.equal(checks.expandedControls.floatingRails, 0)
    assert.equal(checks.expandedControls.headerDrag, 'drag')
    assert.ok(checks.expandedControls.actionRegions.every(region => region === 'no-drag'))
    phase = 'drawer mouse and keyboard'
    await evaluate('document.querySelector(".controls-rail button[aria-expanded]").click();')
    await waitFor('document.querySelector(".controls-drawer button")')
    await evaluate('document.querySelector(".controls-drawer button").focus();document.querySelector(".controls-island.inline").dispatchEvent(new MouseEvent("mouseleave"));')
    await sleep(1700)
    assert.equal(await evaluate('return Boolean(document.querySelector(".controls-drawer"))'), true)
    checks.drawer = await evaluate('const r=document.querySelector(".controls-drawer").getBoundingClientRect();return {x:r.x,y:r.y,right:r.right,bottom:r.bottom};')
    assert.ok(checks.drawer.x >= 0 && checks.drawer.right <= 390 && checks.drawer.bottom <= 560)
    await capture('expanded-drawer')
    window.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Escape' })
    await waitFor('!document.querySelector(".controls-drawer")')
    phase = 'hide chat while its controls drawer is open'
    await evaluate('document.querySelector(".controls-rail button[aria-expanded]").click();')
    await waitFor('document.querySelector(".controls-drawer")')
    await evaluate(`document.querySelector('.control-grid button[title="隐藏聊天"]').click();`)
    checks.hidingChatControls = await controlRegions()
    assert.equal(checks.hidingChatControls.activeRails, 1)
    assert.equal(checks.hidingChatControls.floatingRails, 1)
    assert.equal(checks.hidingChatControls.leavingChats.length, 1)
    assert.ok(checks.hidingChatControls.leavingChats.every(chat => chat.inert && chat.hidden === 'true'
      && chat.draggableRegions.every(region => region === 'no-drag')))
    await waitFor('!document.querySelector(".chat-dock")')
    await update({ chat: { visible: true } })
    await waitFor('document.querySelector(".chat-dock.expanded .controls-island.inline")')
    phase = 'right-click opens the current controls after remount'
    await evaluate('document.querySelector(".stage-shell").dispatchEvent(new MouseEvent("contextmenu",{bubbles:true,cancelable:true}));')
    await waitFor('document.querySelector(".controls-island.inline .controls-drawer")')
    window.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Escape' })
    await waitFor('!document.querySelector(".controls-drawer")')
    checks.controlsAfterRemount = true
    phase = 'quick hide/show during the exit animation'
    for (let index = 0; index < 4; index++) {
      await update({ chat: { visible: false } })
      const hidden = await controlRegions()
      assert.equal(hidden.activeRails, 1)
      assert.equal(hidden.floatingRails, 1)
      await update({ chat: { visible: true } })
      await waitFor('document.querySelector(".chat-dock:not([inert]) .controls-island.inline")')
      const visible = await controlRegions()
      assert.equal(visible.activeRails, 1)
      assert.equal(visible.inlineRails, 1)
    }
    await waitFor('!document.querySelector(".dock-leave-active")')
    checks.quickVisibilityCycles = 4
    phase = 'chat close preserves bounds'
    const before = window.getBounds()
    await evaluate('document.querySelector(".chat-collapse").click();')
    await waitFor('document.querySelector(".chat-dock.compact")')
    checks.fitSurfaces = await evaluate('return window.__fitSurfaces;')
    assert.deepEqual(window.getBounds(), before)
    checks.chatBounds = before
    assert.ok(checks.fitSurfaces.every(surface => surface === 'ambient'))
    checks.collapsedControls = await controlRegions()
    assert.equal(checks.collapsedControls.activeRails, 1)
    assert.equal(checks.collapsedControls.floatingRails, 1)
    await evaluate('document.querySelector(".stage-shell").dispatchEvent(new MouseEvent("contextmenu",{bubbles:true,cancelable:true}));')
    await waitFor('document.querySelector(".controls-island:not(.inline) .controls-drawer")')
    window.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Escape' })
    await waitFor('!document.querySelector(".controls-drawer")')
    phase = 'temporary workspace at right screen edge'
    for (let index = 0; index < 6; index++) {
      await evaluate('window.dispatchEvent(new KeyboardEvent("keydown",{key:"k",ctrlKey:true,shiftKey:true,bubbles:true}));')
      await waitFor('innerWidth>=800 && document.querySelector(".skills-workspace")')
      window.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Escape' })
      await waitFor('innerWidth<=392 && !document.querySelector(".skills-workspace")')
      assert.equal(window.getBounds().x, before.x)
      assert.equal(window.getBounds().y, before.y)
    }
    checks.nativePanelCycles = 6
    phase = 'narrow header'
    await update({ chat: { expanded: true } })
    await waitFor('document.querySelector(".chat-dock.expanded .controls-island.inline")')
    window.setSize(360, 560)
    await waitFor('Math.abs(innerWidth-360)<=2')
    checks.narrowHeader = await evaluate(`const rects=[...document.querySelectorAll('.chat-header button')].map(b=>b.getBoundingClientRect());
      return {fits:rects.every(r=>r.x>=0&&r.right<=innerWidth),sameRow:rects.every(r=>Math.abs(r.y-rects[0].y)<1),rails:document.querySelectorAll('.controls-island').length};`)
    assert.deepEqual(checks.narrowHeader, { fits: true, sameRow: true, rails: 1 })
    await capture('narrow-header')
    await update({ appearance: 'dark' })
    await capture('dark-header')
    assert.deepEqual(errors, [])
    writeFileSync(resolve(outputRoot, 'result.json'), JSON.stringify({ ok: true, checks }, null, 2))
    console.log(JSON.stringify({ ok: true, checks, outputRoot }))
    clearTimeout(timeout); vite?.kill(); window.destroy(); app.exit(0)
  }
  catch (error) {
    console.error(`${phase}: ${error.stack}`)
    writeFileSync(resolve(outputRoot, 'result.json'), JSON.stringify({ ok: false, phase, error: error.message, checks, errors }, null, 2))
    clearTimeout(timeout); vite?.kill(); window?.destroy(); app.exit(1)
  }
})
