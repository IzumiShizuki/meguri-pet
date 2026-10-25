import { app, BrowserWindow, session } from 'electron'
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const outputRoot = resolve(appRoot, '../../tmp/p2-companion-smoke')
const userData = resolve(outputRoot, `user-data-${process.pid}`)
const rendererUrl = process.env.MEGURI_RENDERER_URL ?? 'http://127.0.0.1:4186'
const partition = `p2-companion-${process.pid}`
const windows = []
const consoleErrors = []
const blockedRequests = []
const blockedPreviewAssets = []
const screenshots = []
const checks = {}
let vite
let phase = 'initializing'
let requestFilterInstalled = false
mkdirSync(outputRoot, { recursive: true })
app.setPath('userData', userData)

const sleep = milliseconds => new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds))
async function waitFor(window, expression, timeoutMs = 8_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await window.webContents.executeJavaScript(`Boolean(${expression})`)) return
    await sleep(75)
  }
  throw new Error(`Timed out in ${phase}: ${expression}`)
}
async function evaluate(window, source) {
  return window.webContents.executeJavaScript(`(async () => { ${source} })()`, true)
}
async function instrumentSessionTransport(window) {
  await evaluate(window, `const NativeBroadcastChannel=window.BroadcastChannel; window.__p2ChannelPosts=[]; window.BroadcastChannel=class extends NativeBroadcastChannel { postMessage(data) { window.__p2ChannelPosts.push({name:this.name,data}); return super.postMessage(data); } };`)
}
async function capture(window, name) {
  const image = await window.webContents.capturePage()
  const path = resolve(outputRoot, name)
  writeFileSync(path, image.toPNG())
  return path
}
function cleanup() {
  for (const window of windows) {
    if (!window.isDestroyed()) window.destroy()
  }
  if (vite && !vite.killed) vite.kill()
}
const timeout = setTimeout(() => {
  console.error(JSON.stringify({ ok: false, phase, error: 'P2 companion smoke exceeded 60 seconds' }))
  cleanup()
  app.exit(2)
}, 60_000)

async function startRenderer() {
  if (process.env.MEGURI_RENDERER_URL) return
  const node = process.env.MEGURI_NODE ?? 'D:/environment/nodejs/runtime/node-v24.17.0-win-x64/node.exe'
  vite = spawn(node, [resolve(appRoot, 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4186', '--strictPort'], {
    cwd: appRoot,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let errorOutput = ''
  vite.stderr.on('data', data => { errorOutput = `${errorOutput}${data}`.slice(-3000) })
  await new Promise((resolvePromise, reject) => {
    vite.once('error', reject)
    vite.once('exit', code => reject(new Error(`Vite exited (${code}): ${errorOutput}`)))
    vite.stdout.on('data', data => { if (String(data).includes('Local:')) resolvePromise() })
  })
}

async function makeWindow(width, height, view) {
  if (!requestFilterInstalled) {
    session.fromPartition(partition).webRequest.onBeforeRequest((details, callback) => {
      const localWs = rendererUrl.replace(/^http/u, 'ws')
      const allowed = details.url.startsWith(rendererUrl)
        || details.url.startsWith(localWs)
        || /^(?:data:|blob:|devtools:)/u.test(details.url)
      if (!allowed) {
        const url = details.url.replace(/\?.*$/u, '')
        if (new URL(url).pathname.startsWith('/assets/sprites/meguri/')) blockedPreviewAssets.push(url)
        else blockedRequests.push(url)
      }
      callback({ cancel: !allowed })
    })
    requestFilterInstalled = true
  }
  const window = new BrowserWindow({
    show: false,
    width,
    height,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false,
      partition,
    },
  })
  windows.push(window)
  window.webContents.on('console-message', event => {
    if (event.level === 'error' && !event.message.includes('Electron Security Warning'))
      consoleErrors.push(event.message)
  })
  await window.loadURL(`${rendererUrl}/?preview=offline&view=${view}`)
  await waitFor(window, view === 'settings'
    ? 'document.querySelector(".settings-shell")'
    : 'document.querySelector(".stage-shell")')
  return window
}

async function openCompanionSettings(window) {
  await evaluate(window, `document.querySelector('.settings-back')?.click()`)
  await waitFor(window, 'document.querySelector(".settings-home")')
  await evaluate(window, `Array.from(document.querySelectorAll('.settings-entry')).find(node => node.querySelector('strong')?.textContent === '陪伴与主动性')?.click()`)
  await waitFor(window, 'document.querySelector("#companion-initiative")')
}

async function setInput(window, selector, value) {
  await evaluate(window, `const input = document.querySelector(${JSON.stringify(selector)}); const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; setter.call(input, ${JSON.stringify(value)}); input.dispatchEvent(new Event('input', { bubbles: true }));`)
}

async function addCommitmentFromSettings(window, title, delayMs = 60 * 60 * 1000) {
  const future = new Date(Date.now() + delayMs)
  const dueAt = new Date(future.getTime() - future.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
  await setInput(window, '.companion-commitments input[type="text"]', title)
  await setInput(window, '.companion-commitments input[type="datetime-local"]', dueAt)
  await waitFor(window, '!document.querySelector(".companion-commitments button.primary-action").disabled')
  await evaluate(window, `document.querySelector('.companion-commitments button.primary-action').click()`)
  try {
    await waitFor(window, `Array.from(document.querySelectorAll('.companion-commitment-list li')).some(node => node.textContent.includes(${JSON.stringify(title)}))`)
  }
  catch (error) {
    const diagnostic = await evaluate(window, `const preferences=window.__p2Preferences.useDesktopPreferencesStore(); const director=window.__p2Director?.useCompanionDirectorStore(); const commitments=preferences.value.companion.commitments; return { fields: Array.from(document.querySelectorAll('.companion-commitments input')).map(input=>input.value), feedback: document.querySelector('.companion-commitments [role="status"]')?.textContent, saveHistory: preferences.value.privacy.saveHistory, commitments: JSON.parse(JSON.stringify(commitments)), directorCommitments: director ? JSON.parse(JSON.stringify(director.commitments)) : [] }`)
    throw new Error(`${error.message}; state=${JSON.stringify(diagnostic)}`)
  }
}

app.whenReady().then(async () => {
  let result
  try {
    await startRenderer()

    phase = 'companion settings controls and reversible preferences'
    const settings = await makeWindow(470, 790, 'settings')
    await openCompanionSettings(settings)
    await evaluate(settings, `window.__p2Preferences = await import('/src/renderer/stores/desktop-preferences'); window.__p2Director = await import('/src/renderer/stores/companion-director');`)
    await waitFor(settings, 'window.__p2Preferences.useDesktopPreferencesStore().loaded')
    await instrumentSessionTransport(settings)
    await evaluate(settings, `await window.__p2Preferences.useDesktopPreferencesStore().update({privacy:{saveHistory:false}}); window.__p2Director.useCompanionDirectorStore().initialize();`)
    checks.settingsSection = await evaluate(settings, `const select = document.querySelector('#companion-initiative'); return { label: document.querySelector('.settings-page-heading h1')?.textContent === '陪伴与主动性', options: Array.from(select.options).map(option => option.value), toggles: document.querySelectorAll('.setting-row input[type="checkbox"]').length, undo: Array.from(document.querySelectorAll('.settings-actions button')).some(button => button.textContent.includes('撤销上次表现设置')) };`)
    await evaluate(settings, `const rows = Array.from(document.querySelectorAll('.setting-row')); for (const label of ['专注模式', '允许表情变化', '允许动作']) { const row = rows.find(item => item.textContent.includes(label)); const input = row?.querySelector('input[type="checkbox"]'); if (input && !input.checked) input.click(); }`)
    await waitFor(settings, `(() => { const {useDesktopPreferencesStore}=window.__p2Preferences; const p=useDesktopPreferencesStore().value.companion; return p.focus === 'focused' && p.expressionsEnabled && p.motionEnabled; })()`)

    phase = 'stage timer, focus deferral, and muted one-time delivery'
    const stage = await makeWindow(440, 700, 'stage')
    await evaluate(stage, `window.__p2Preferences = await import('/src/renderer/stores/desktop-preferences'); window.__p2Director = await import('/src/renderer/stores/companion-director'); window.__p2Conversation = await import('/src/renderer/stores/conversation');`)
    await waitFor(stage, 'window.__p2Preferences.useDesktopPreferencesStore().loaded')
    await instrumentSessionTransport(stage)
    await waitFor(stage, 'document.querySelector(".privacy-dialog") || document.querySelector(".stage-shell")')
    const privacyOpen = await evaluate(stage, `document.querySelector('.privacy-dialog')?.open === true`)
    if (privacyOpen) {
      await evaluate(stage, `const dialog = document.querySelector('.privacy-dialog'); const input = dialog.querySelector('input[type="checkbox"]'); if (input && !input.checked) input.click(); dialog.querySelector('button[type="submit"]')?.click()`)
      await waitFor(stage, '!document.querySelector(".privacy-dialog")?.open')
    }
    await evaluate(stage, `const preferences=window.__p2Preferences.useDesktopPreferencesStore(); await preferences.update({privacy:{acceptedPolicyVersion:'2026-10-05',saveHistory:false},speech:{ttsEnabled:false,ambientEnabled:true,volume:0.7},companion:{initiative:'normal',focus:'focused',expressionsEnabled:true,motionEnabled:true,commitments:[]}}); const conversation=window.__p2Conversation.useConversationStore(); window.__p2SpeechCalls=0; conversation.playNoticeSpeech=async()=>{window.__p2SpeechCalls++;return false}; const director=window.__p2Director.useCompanionDirectorStore(); const {createBrowserCompanionSessionTransport}=await import('/src/renderer/services/companion-session-sync'); director.initialize(createBrowserCompanionSessionTransport());`)
    await waitFor(stage, `window.__p2Preferences.useDesktopPreferencesStore().value.speech.ttsEnabled === false && window.__p2Preferences.useDesktopPreferencesStore().value.privacy.saveHistory === false`)
    await evaluate(stage, `const director=window.__p2Director.useCompanionDirectorStore(); await director.setRuntimeContext({focus:'focused',activeTurn:false,speaking:false,surface:'ambient'});`)
    await evaluate(stage, `window.__p2ProbeChannel=new BroadcastChannel('p2-companion-probe'); window.__p2ProbeChannel.onmessage=event=>{window.__p2ProbeValue=event.data}`)
    await evaluate(stage, `window.__p2SyncProbe=new BroadcastChannel('meguri-companion-session-v1'); window.__p2SyncMessages=[]; window.__p2SyncProbe.onmessage=event=>window.__p2SyncMessages.push(event.data)`)
    await evaluate(settings, `new BroadcastChannel('p2-companion-probe').postMessage('ready')`)
    await waitFor(stage, `window.__p2ProbeValue==='ready'`, 2_000)
    await evaluate(settings, `const {createBrowserCompanionSessionTransport}=await import('/src/renderer/services/companion-session-sync'); window.__p2Director.useCompanionDirectorStore().initialize(createBrowserCompanionSessionTransport());`)
    await waitFor(stage, `window.__p2SyncMessages.some(message=>message.type==='snapshot_request')`, 2_000)

    await addCommitmentFromSettings(settings, 'P2 smoke undo preserves follow-up')
    try {
      await waitFor(stage, `window.__p2Director.useCompanionDirectorStore().commitments.some(item => item.text === 'P2 smoke undo preserves follow-up')`, 8_000)
    }
    catch (error) {
      const syncState = await evaluate(settings, `const p=window.__p2Preferences.useDesktopPreferencesStore(); const d=window.__p2Director.useCompanionDirectorStore(); const piniaModule=await import('pinia'); const pinia=piniaModule.getActivePinia(); return {origin:location.origin,loaded:p.loaded,saveHistory:p.value.privacy.saveHistory,persisted:p.value.companion.commitments.map(item=>item.text),ephemeral:d.ephemeralCommitments.map(item=>item.text),hasBroadcast:typeof BroadcastChannel==='function',activePiniaMatches:d._p===pinia,storeMatchesPinia:pinia?pinia._s.get('companion-director')===d:false,channelPosts:window.__p2ChannelPosts.filter(post=>post.name==='meguri-companion-session-v1')}`)
      const stageState = await evaluate(stage, `const p=window.__p2Preferences.useDesktopPreferencesStore(); const d=window.__p2Director.useCompanionDirectorStore(); const piniaModule=await import('pinia'); const pinia=piniaModule.getActivePinia(); return {origin:location.origin,loaded:p.loaded,saveHistory:p.value.privacy.saveHistory,persisted:p.value.companion.commitments.map(item=>item.text),ephemeral:d.ephemeralCommitments.map(item=>item.text),commitments:d.commitments.map(item=>item.text),channel:typeof BroadcastChannel==='function',probe:window.__p2ProbeValue,syncMessages:window.__p2SyncMessages,activePiniaMatches:d._p===pinia,storeMatchesPinia:pinia?pinia._s.get('companion-director')===d:false,channelPosts:window.__p2ChannelPosts.filter(post=>post.name==='meguri-companion-session-v1')}`)
      throw new Error(`${error.message}; settings=${JSON.stringify(syncState)}; stage=${JSON.stringify(stageState)}`)
    }
    checks.ephemeralSnapshotSync = await evaluate(stage, `const preferences=window.__p2Preferences.useDesktopPreferencesStore(); const director=window.__p2Director.useCompanionDirectorStore(); return !preferences.value.privacy.saveHistory && preferences.value.companion.commitments.length===0 && director.commitments.some(item=>item.text==='P2 smoke undo preserves follow-up');`)
    await evaluate(settings, `const select = document.querySelector('#companion-initiative'); select.value = 'high'; select.dispatchEvent(new Event('change', { bubbles: true }));`)
    await waitFor(settings, `window.__p2Preferences.useDesktopPreferencesStore().value.companion.initiative === 'high'`)
    await evaluate(settings, `Array.from(document.querySelectorAll('.settings-actions button')).find(button => button.textContent.includes('撤销上次表现设置'))?.click()`)
    await waitFor(settings, `window.__p2Preferences.useDesktopPreferencesStore().value.companion.initiative === 'normal'`)
    checks.undoPreservesCommitment = await evaluate(settings, `const director=window.__p2Director.useCompanionDirectorStore(); return director.commitments.some(item => item.text === 'P2 smoke undo preserves follow-up' && item.status === 'pending');`)

    await addCommitmentFromSettings(settings, 'P2 smoke complete follow-up')
    await evaluate(settings, `const item = Array.from(document.querySelectorAll('.companion-commitment-list li')).find(node => node.textContent.includes('P2 smoke complete follow-up')); item?.querySelector('button')?.click()`)
    await waitFor(settings, `Array.from(document.querySelectorAll('.companion-commitment-list li')).find(node => node.textContent.includes('P2 smoke complete follow-up'))?.textContent.includes('已完成')`)
    await addCommitmentFromSettings(settings, 'P2 smoke cancel follow-up')
    await evaluate(settings, `const item = Array.from(document.querySelectorAll('.companion-commitment-list li')).find(node => node.textContent.includes('P2 smoke cancel follow-up')); Array.from(item?.querySelectorAll('button') ?? []).find(button => button.textContent === '取消')?.click()`)
    await waitFor(settings, `Array.from(document.querySelectorAll('.companion-commitment-list li')).find(node => node.textContent.includes('P2 smoke cancel follow-up'))?.textContent.includes('已取消')`)
    checks.commitmentUi = await evaluate(settings, `const rows = Array.from(document.querySelectorAll('.companion-commitment-list li')); return { added: rows.some(node => node.textContent.includes('P2 smoke undo preserves follow-up')), completed: rows.some(node => node.textContent.includes('P2 smoke complete follow-up') && node.textContent.includes('已完成')), cancelled: rows.some(node => node.textContent.includes('P2 smoke cancel follow-up') && node.textContent.includes('已取消')) };`)
    screenshots.push(await capture(settings, 'companion-settings.png'))

    await addCommitmentFromSettings(settings, 'P2 smoke cross-window cancel')
    await waitFor(stage, `window.__p2Director.useCompanionDirectorStore().commitments.some(item => item.text === 'P2 smoke cross-window cancel')`)
    const cancelId = await evaluate(stage, `return window.__p2Director.useCompanionDirectorStore().commitments.find(item=>item.text==='P2 smoke cross-window cancel').id`)
    await evaluate(stage, `await window.__p2Director.useCompanionDirectorStore().cancelCommitment(${JSON.stringify(cancelId)})`)
    await waitFor(settings, `window.__p2Director.useCompanionDirectorStore().commitments.find(item => item.id === ${JSON.stringify(cancelId)})?.status === 'cancelled'`)
    checks.ephemeralCancelSync = await evaluate(settings, `const preferences=window.__p2Preferences.useDesktopPreferencesStore(); const item=window.__p2Director.useCompanionDirectorStore().commitments.find(value=>value.id===${JSON.stringify(cancelId)}); return !preferences.value.privacy.saveHistory && preferences.value.companion.commitments.length===0 && item?.status==='cancelled';`)

    await addCommitmentFromSettings(settings, 'P2 timer smoke notice')
    const commitmentId = await evaluate(settings, `return window.__p2Director.useCompanionDirectorStore().commitments.find(item=>item.text==='P2 timer smoke notice').id`)
    await waitFor(stage, `window.__p2Director.useCompanionDirectorStore().commitments.some(item => item.id === ${JSON.stringify(commitmentId)})`)
    await evaluate(stage, `const director=window.__p2Director.useCompanionDirectorStore(); await director.updateCommitment(${JSON.stringify(commitmentId)}, item=>({...item,dueAt:Date.now()+1_500}));`)
    await waitFor(settings, `window.__p2Director.useCompanionDirectorStore().commitments.find(item => item.id === ${JSON.stringify(commitmentId)})?.dueAt > Date.now()`)
    await waitFor(stage, `window.__p2Director.useCompanionDirectorStore().deferred.some(item => item.id === ${JSON.stringify(`commitment:${commitmentId}`)})`, 22_000)
    checks.stageFocusQueuesTimerNotice = await evaluate(stage, `const id='commitment:'+${JSON.stringify(commitmentId)}; const director=window.__p2Director.useCompanionDirectorStore(); const conversation=window.__p2Conversation.useConversationStore(); return director.deferred.some(item=>item.id===id) && !conversation.messages.some(item=>item.id===id);`)
    await evaluate(stage, `await window.__p2Director.useCompanionDirectorStore().setRuntimeContext({focus:'available',activeTurn:false,speaking:false,surface:'ambient'});`)
    await waitFor(stage, `window.__p2Conversation.useConversationStore().messages.some(item => item.id === ${JSON.stringify(`commitment:${commitmentId}`)})`)
    await waitFor(settings, `window.__p2Director.useCompanionDirectorStore().commitments.find(item => item.id === ${JSON.stringify(commitmentId)})?.followUpDeliveredAt > 0`)
    checks.stageDelivery = await evaluate(stage, `const id=${JSON.stringify(`commitment:${commitmentId}`)}; const conversation=window.__p2Conversation.useConversationStore(); const director=window.__p2Director.useCompanionDirectorStore(); const preferences=window.__p2Preferences.useDesktopPreferencesStore(); const {playbackReceiptsForSession}=await import('/src/renderer/services/playback-ledger.ts'); const receipts=playbackReceiptsForSession(conversation.sessionId); const item=director.commitments.find(value=>value.id===${JSON.stringify(commitmentId)}); return { messageCount:conversation.messages.filter(message=>message.id===id).length, text:conversation.messages.find(message=>message.id===id)?.text, followUpDelivered:Boolean(item?.followUpDeliveredAt), speechCalls:window.__p2SpeechCalls, receipt:receipts.find(receipt=>receipt.effectId===id && receipt.kind==='notice')?.state, noPersistedCommitments:preferences.value.companion.commitments.length===0, historyRecord:localStorage.getItem('meguri.desktop.conversation.v1') };`)
    checks.ephemeralDeliveredSync = await evaluate(settings, `const preferences=window.__p2Preferences.useDesktopPreferencesStore(); const item=window.__p2Director.useCompanionDirectorStore().commitments.find(value=>value.id===${JSON.stringify(commitmentId)}); return preferences.value.companion.commitments.length===0 && Boolean(item?.followUpDeliveredAt);`)

    const section = checks.settingsSection
    const ok = section?.label
      && JSON.stringify(section.options) === JSON.stringify(['off', 'low', 'normal', 'high'])
      && section.toggles === 3 && section.undo
      && checks.undoPreservesCommitment
      && checks.commitmentUi?.added && checks.commitmentUi?.completed && checks.commitmentUi?.cancelled
      && checks.ephemeralSnapshotSync && checks.ephemeralCancelSync && checks.ephemeralDeliveredSync
      && checks.stageFocusQueuesTimerNotice
      && checks.stageDelivery?.messageCount === 1
      && checks.stageDelivery.text?.includes('P2 timer smoke notice')
      && checks.stageDelivery.followUpDelivered
      && checks.stageDelivery.speechCalls === 0
      && checks.stageDelivery.receipt === 'completed'
      && checks.stageDelivery.noPersistedCommitments
      && !String(checks.stageDelivery.historyRecord ?? '').includes('P2 timer smoke notice')
      && consoleErrors.length === 0 && blockedRequests.length === 0
    result = {
      ok: Boolean(ok),
      checks,
      screenshots,
      consoleErrors,
      blockedRequests,
      blockedPreviewAssets,
      notes: [
        'Hidden Electron windows use a temporary userData profile and preview bridge.',
        'Only the local Vite preview origin is allowed; reminders, Core, server, and TTS endpoints are not contacted.',
        'The decorative sprite request is blocked and recorded separately; no external endpoint is allowed.',
        'The stage check waits for the real 15-second director scheduler and uses one synthetic due commitment.',
        'Speech is disabled and the speech method is guarded; no audio is played.',
      ],
    }
  }
  catch (error) {
    result = {
      ok: false,
      phase,
      error: error instanceof Error ? error.stack : String(error),
      checks,
      screenshots,
      consoleErrors,
      blockedRequests,
      blockedPreviewAssets,
    }
  }
  finally {
    writeFileSync(resolve(outputRoot, 'result.json'), `${JSON.stringify(result, null, 2)}\n`, 'utf8')
    console.log(JSON.stringify(result))
    clearTimeout(timeout)
    cleanup()
    app.exit(result?.ok ? 0 : 1)
  }
}).catch(error => {
  clearTimeout(timeout)
  cleanup()
  console.error(error)
  app.exit(1)
})
