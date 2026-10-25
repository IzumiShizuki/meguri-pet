import { app, BrowserWindow, session } from 'electron'
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const outputRoot = resolve(appRoot, '../../tmp/p0-training-ui')
const rendererUrl = 'http://127.0.0.1:4189'
app.setPath('userData', resolve(outputRoot, `isolated-profile-${process.pid}`))
mkdirSync(outputRoot, { recursive: true })
let vite, window
const checks = {}
const screenshots = []
const sleep = ms => new Promise(done => setTimeout(done, ms))
const evaluate = source => window.webContents.executeJavaScript(`(async()=>{${source}})()`, true)
async function waitFor(expression) {
  const until = Date.now() + 8000
  while (Date.now() < until) {
    if (await evaluate(`return Boolean(${expression})`)) return
    await sleep(80)
  }
  throw new Error(`UI condition timed out: ${expression}`)
}
async function capture(name) {
  await sleep(180)
  await window.webContents.capturePage()
  await sleep(100)
  const path = resolve(outputRoot, name)
  writeFileSync(path, (await window.webContents.capturePage()).toPNG())
  screenshots.push(path)
}
const timeout = setTimeout(() => { vite?.kill(); app.exit(2) }, 60000)
app.whenReady().then(async () => {
  let result
  try {
    vite = spawn('D:/environment/nodejs/runtime/node-v24.17.0-win-x64/node.exe',
      [resolve(appRoot, 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4189', '--strictPort'],
      { cwd: appRoot, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    await new Promise((done, reject) => {
      vite.once('error', reject)
      vite.once('exit', code => reject(new Error(`Renderer exited: ${code}`)))
      vite.stdout.on('data', data => { if (String(data).includes('Local:')) done() })
    })
    const partition = `p0-training-${process.pid}`
    session.fromPartition(partition).webRequest.onBeforeRequest((details, callback) => {
      callback({ cancel: !details.url.startsWith(rendererUrl) && !details.url.startsWith(rendererUrl.replace('http:', 'ws:'))
        && !/^(data:|blob:|devtools:)/.test(details.url) })
    })
    window = new BrowserWindow({ show: false, width: 470, height: 800,
      webPreferences: { contextIsolation: true, sandbox: true, backgroundThrottling: false, partition } })
    await window.loadURL(`${rendererUrl}/?view=settings&preview=ready`)
    await waitFor('document.querySelector(".settings-home")')
    await evaluate(`
      const {useConversationStore}=await import('/src/renderer/stores/conversation.ts');
      const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts');
      const {PRIVACY_POLICY_VERSION}=await import('/src/shared/experience-contract.ts');
      window.__trainingRequests=[]; window.__saved=false; window.__revoked=false;
      window.fetch=async (input,init={})=>{
        const url=String(input);
        if(!url.includes('/v1/training/desktop/')) return new Response('{}',{status:503});
        const receipt={turn_id:'fixture-turn',feedback_id:'feedback-00000000-0000-4000-8000-000000000000',status:window.__revoked?'revoked':'training_candidate_saved'};
        let value;
        if(url.endsWith('/pending')) value={pending:window.__saved?[]:[{turn_id:'fixture-turn',comparison_id:'fixture-comparison',training_mode:true,candidates:[{candidate_id:'candidate-a',response:{reply:'我们先把最关键的一步做好，再慢慢整理。'}},{candidate_id:'candidate-b',response:{reply:'我陪你一步步来。先确认目标，然后做一个小而确定的动作。'}}]}],receipts:window.__saved?[receipt]:[]};
        else {window.__trainingRequests.push({url,init}); if(url.endsWith('/revoke')) window.__revoked=true; else {if(JSON.parse(init.body).consent_to_training!==true) throw new Error('Consent missing'); window.__saved=true;} value={...receipt,status:window.__revoked?'revoked':'training_candidate_saved'};}
        return new Response(JSON.stringify(value),{status:200,headers:{'Content-Type':'application/json'}});
      };
      await useDesktopPreferencesStore().update({appearance:'light',privacy:{acceptedPolicyVersion:PRIVACY_POLICY_VERSION,remoteChatAllowed:true}});
      document.querySelectorAll('.settings-entry').forEach(node=>{if(node.querySelector('strong')?.textContent==='监督与训练')node.click()});
    `)
    await waitFor('document.querySelector(".feedback-consent")')
    await evaluate(`document.querySelector('.training-feedback').open=true; document.querySelector('.feedback-candidate input').click()`)
    await sleep(100)
    checks.consentNotAssumed = await evaluate(`return !document.querySelector('.feedback-consent input').checked && document.querySelector('.feedback-actions button').disabled && window.__trainingRequests.length===0`)
    await evaluate(`document.querySelector('.training-record').scrollIntoView({block:'start'})`)
    await capture('feedback-light.png')
    window.setSize(350, 760)
    await evaluate(`const {useDesktopPreferencesStore}=await import('/src/renderer/stores/desktop-preferences.ts'); await useDesktopPreferencesStore().update({appearance:'dark'})`)
    await sleep(100)
    checks.noHorizontalOverflow = await evaluate(`return document.documentElement.scrollWidth<=innerWidth && document.querySelector('.training-settings').scrollWidth<=document.querySelector('.training-settings').clientWidth`)
    await capture('feedback-dark-narrow.png')
    await evaluate(`document.querySelector('.feedback-consent input').click()`)
    await waitFor('!document.querySelector(".feedback-actions button").disabled')
    await evaluate(`document.querySelector('.feedback-actions button').click()`)
    await waitFor('document.querySelector(".feedback-receipt")')
    checks.savedWithStableKey = await evaluate(`return window.__trainingRequests.length===1 && new Headers(window.__trainingRequests[0].init.headers).get('Idempotency-Key').startsWith('desktop-feedback-')`)
    await evaluate(`document.querySelector('.training-feedback').open=true; document.querySelector('.training-feedback button').click()`)
    await waitFor('document.querySelector(".training-feedback summary").textContent.includes("已撤回")')
    checks.withdrawn = await evaluate(`return window.__revoked && window.__trainingRequests.length===2 && !document.querySelector('.training-feedback button')`)
    await capture('feedback-withdrawn.png')
    result={ok:Object.values(checks).every(Boolean),checks,screenshots,evidence:'isolated renderer with fake training API; no live Core or user data changes'}
  } catch(error) { result={ok:false,error:String(error.stack??error),checks,screenshots} }
  finally {
    writeFileSync(resolve(outputRoot,'result.json'),JSON.stringify(result,null,2)+'\n')
    console.log(JSON.stringify(result))
    clearTimeout(timeout); window?.destroy(); vite?.kill(); app.exit(result?.ok?0:1)
  }
})
