import { app, BrowserWindow, protocol } from 'electron'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createLive2DStore } from '../src/main/live2d-store.mjs'
import { createLive2DProtocolHandler } from '../src/main/live2d-protocol.mjs'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const outputRoot = resolve(projectRoot, '.codex-runtime/live2d-smoke')
const source = process.env.MEGURI_LIVE2D_SMOKE_MODEL
if (!source) throw new Error('Set MEGURI_LIVE2D_SMOKE_MODEL to an authorized exported model directory.')
const rendererUrl = process.env.MEGURI_RENDERER_URL ?? 'http://127.0.0.1:4174'
const errors = []
process.on('unhandledRejection', error => { console.error(error); app.exit(1) })
protocol.registerSchemesAsPrivileged([{ scheme: 'meguri-live2d', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }])
app.setPath('userData', resolve(outputRoot, `userdata-${process.pid}`))
const timeout = setTimeout(() => { console.error('Live2D verification timed out'); app.exit(2) }, 45000)
app.whenReady().then(async () => {
mkdirSync(outputRoot, { recursive: true })
const store = createLive2DStore({ root: resolve(outputRoot, `managed-${process.pid}`) })
const catalog = await store.importDirectory(source)
store.activate(catalog.models[0].id)
const active = store.active()
protocol.handle('meguri-live2d', createLive2DProtocolHandler({ store, allowedOrigins: [new URL(rendererUrl).origin] }))
const window = new BrowserWindow({ show: false, width: 500, height: 780, webPreferences: { contextIsolation: true, sandbox: true, backgroundThrottling: false } })
window.webContents.on('console-message', event => { if (event.level === 'error' && !event.message.includes('Electron Security Warning')) errors.push(event.message) })
window.webContents.on('render-process-gone', (_event, details) => errors.push(`renderer gone: ${details.reason}`))
try {
  await window.loadURL(`${rendererUrl}/?preview=stage`)
  const startupDeadline = Date.now() + 8000
  while (!(await window.webContents.executeJavaScript('Boolean(document.querySelector(".privacy-dialog")?.open)'))) {
    if (Date.now() > startupDeadline) throw new Error('Preview did not finish its initial preference load')
    await new Promise(resolvePromise => setTimeout(resolvePromise, 100))
  }
  await window.webContents.executeJavaScript(`(async () => {
    window.meguriDesktop.live2d.active = async () => (${JSON.stringify(active)});
    const { useDesktopPreferencesStore } = await import('/src/renderer/stores/desktop-preferences.ts');
    await useDesktopPreferencesStore().update({ character: { renderer: 'live2d', live2dModelId: ${JSON.stringify(active.id)} }, privacy: { acceptedPolicyVersion:'2026-10-05',remoteChatAllowed:false }, window: { fadeOnHover: false }, chat: { visible: false } });
  })()`)
  let rendered = false
  const deadline = Date.now() + 20000
  while (Date.now() < deadline) {
    rendered = await window.webContents.executeJavaScript(`(() => {
      const canvas = document.querySelector('.live2d-character canvas');
      if (!canvas) return false;
      const target = document.createElement('canvas'); target.width = canvas.width; target.height = canvas.height;
      const ctx = target.getContext('2d'); ctx.drawImage(canvas,0,0);
      const data = ctx.getImageData(0,0,target.width,target.height).data;
      let visible = 0; for (let i=3;i<data.length;i+=4) if (data[i]>20) visible++;
      return visible > 10000;
    })()`)
    if (rendered) break
    await new Promise(resolvePromise => setTimeout(resolvePromise, 200))
  }
  const interaction = await window.webContents.executeJavaScript(`(() => {
    const canvas = document.querySelector('.live2d-character canvas');
    const root = document.querySelector('.live2d-character');
    if (!canvas || !root) return { error: document.querySelector('.live2d-fallback-status')?.textContent };
    const bounds=canvas.getBoundingClientRect();
    const component=root.__vueParentComponent;
    const scene=component?.setupState.scene;
    const hits=[];
    for(let y=bounds.top;y<bounds.bottom;y+=30)for(let x=bounds.left;x<bounds.right;x+=30){const areas=scene?.hitTest(x,y)||[];if(areas.length)hits.push({x,y,areas});}
    const body = hits.find(hit=>hit.areas.includes('Body')) ?? hits[0];
    window.__live2dMotionCalls = [];
    const originalMotion = scene.setMotion;
    scene.setMotion = async (...args) => { window.__live2dMotionCalls.push(args[0]); return originalMotion(...args); };
    if(body){root.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,clientX:body.x,clientY:body.y}));}
    window.dispatchEvent(new PointerEvent('pointermove',{clientX:bounds.left+40,clientY:bounds.top+40}));
    return {width:canvas.width,height:canvas.height,hitCount:hits.length,hitAreas:[...new Set(hits.flatMap(hit=>hit.areas))],pointerRecorded:!!component.setupState.pointer,bounds:{width:bounds.width,height:bounds.height}};
  })()`)
  await new Promise(resolvePromise => setTimeout(resolvePromise, 250))
  interaction.touchMotion = await window.webContents.executeJavaScript('window.__live2dMotionCalls')
  if (active.expressions.length) {
    interaction.expressionApplied = await window.webContents.executeJavaScript(`(async () => { const root=document.querySelector('.live2d-character'); await root.__vueParentComponent.setupState.scene.setExpression(${JSON.stringify(active.expressions[0])}); return true })()`)
  }
  const image = await window.webContents.capturePage()
  writeFileSync(resolve(outputRoot, 'runtime.png'), image.toPNG())
  const state = await window.webContents.executeJavaScript(`(async () => {
    const {useDesktopPreferencesStore} = await import('/src/renderer/stores/desktop-preferences.ts');
    const value = useDesktopPreferencesStore().value;
    return {renderer:value.character.renderer,policyAccepted:!!value.privacy.acceptedPolicyVersion,fallback:document.querySelector('.live2d-fallback-status')?.textContent,canvas:!!document.querySelector('.live2d-character canvas')};
  })()`)
  const expectedErrorStart = errors.length
  await window.webContents.executeJavaScript(`(async () => {
    window.meguriDesktop.live2d.active=async()=>({...${JSON.stringify(active)},modelUrl:'meguri-live2d://model/missing/missing.model3.json'});
    const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts');
    await useDesktopPreferencesStore().update({character:{live2dModelId:'missing-model'}});
  })()`)
  const fallbackDeadline=Date.now()+8000
  let fallback=false
  while(Date.now()<fallbackDeadline){
    fallback=await window.webContents.executeJavaScript(`Boolean(document.querySelector('.live2d-fallback-status') && document.querySelector('.live2d-character > img'))`)
    if(fallback)break
    await new Promise(resolvePromise=>setTimeout(resolvePromise,100))
  }
  writeFileSync(resolve(outputRoot, 'fallback.png'), (await window.webContents.capturePage()).toPNG())
  const report = { rendered, interaction, state, fallback, errors: errors.slice(0,expectedErrorStart), expectedLoadErrors:errors.slice(expectedErrorStart), sample: active.name }
  writeFileSync(resolve(outputRoot, 'runtime.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
  if (!rendered || !fallback || !interaction.hitCount || !interaction.touchMotion?.length || !interaction.pointerRecorded || report.errors.length) process.exitCode = 1
}
catch (error) { console.error(error); process.exitCode = 1 }
finally { clearTimeout(timeout); window.destroy(); app.exit(process.exitCode ?? 0) }
}).catch(error => { clearTimeout(timeout); console.error(error); app.exit(1) })
