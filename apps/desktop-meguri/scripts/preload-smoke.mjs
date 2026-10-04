import { app, BrowserWindow } from 'electron'
import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const preloadPath = resolve(appRoot, 'src/preload/index.cjs')
let preloadError = ''
let rendererProcess

async function rendererIsReady(url) {
  try {
    return (await fetch(url)).ok
  }
  catch {
    return false
  }
}

async function ensureRenderer() {
  const url = process.env.MEGURI_SMOKE_RENDERER_URL ?? 'http://127.0.0.1:4174'
  if (await rendererIsReady(url))
    return url
  if (!process.env.npm_execpath)
    throw new Error('preload smoke needs pnpm to start the renderer.')
  rendererProcess = spawn(process.execPath, [process.env.npm_execpath, 'run', 'dev:renderer'], {
    cwd: appRoot,
    stdio: 'ignore',
    shell: false,
  })
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (await rendererIsReady(url))
      return url
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('preload smoke renderer did not become ready.')
}

app.setPath('userData', resolve(app.getPath('temp'), `meguri-preload-smoke-${process.pid}`))

function report(result) {
  const serialized = JSON.stringify(result)
  if (result.ok)
    console.log(serialized)
  else
    console.error(serialized)
}

app.on('web-contents-created', (_event, contents) => {
  contents.on('preload-error', (_preloadEvent, _path, error) => {
    preloadError = error instanceof Error ? error.message : String(error)
  })
})

const forceExit = setTimeout(() => {
  report({ ok: false, error: 'smoke test timed out', preloadError })
  app.exit(2)
}, 8_000)

app.whenReady().then(async () => {
  const keeper = new BrowserWindow({ show: false })
  try {
    const rendererUrl = await ensureRenderer()
    const inspect = async (view) => {
      const window = new BrowserWindow({
        show: false,
        webPreferences: {
          contextIsolation: true,
          sandbox: true,
          preload: preloadPath,
          additionalArguments: [`--meguri-view=${view}`],
        },
      })
      window.webContents.on('console-message', (event) => {
        if (event.level >= 'warning')
          console.error(`[renderer] ${event.message}`)
      })
      const smokeUrl = new URL('preload-smoke.html', rendererUrl.endsWith('/') ? rendererUrl : `${rendererUrl}/`)
      await window.loadURL(smokeUrl.href)
      try {
        return await window.webContents.executeJavaScript(`({
      bridgeType: typeof window.meguriDesktop,
      view: window.meguriDesktop?.view,
      preferencesGetType: typeof window.meguriDesktop?.preferences?.get,
      preferencesUpdateType: typeof window.meguriDesktop?.preferences?.update,
      preferencesMigrateType: typeof window.meguriDesktop?.preferences?.migrateLegacy,
      multimodalRelayGetType: typeof window.meguriDesktop?.multimodalRelay?.get,
      multimodalRelaySaveType: typeof window.meguriDesktop?.multimodalRelay?.save,
      multimodalRelayTestType: typeof window.meguriDesktop?.multimodalRelay?.test,
      localAgentsGetType: typeof window.meguriDesktop?.localAgents?.get,
      localAgentsSaveType: typeof window.meguriDesktop?.localAgents?.save,
      localAgentsTestType: typeof window.meguriDesktop?.localAgents?.test,
      workSnapshotType: typeof window.meguriDesktop?.work?.snapshot,
      workTargetsType: typeof window.meguriDesktop?.work?.targets,
      workPrepareType: typeof window.meguriDesktop?.work?.prepare,
      workConfirmType: typeof window.meguriDesktop?.work?.confirm,
      workDismissType: typeof window.meguriDesktop?.work?.dismiss,
      workOpenNativeType: typeof window.meguriDesktop?.work?.openNative,
      genericSendType: typeof window.meguriDesktop?.work?.send,
      shizukiStatusType: typeof window.meguriDesktop?.shizuki?.status,
      shizukiConnectType: typeof window.meguriDesktop?.shizuki?.connect,
      shizukiDisconnectType: typeof window.meguriDesktop?.shizuki?.disconnect,
      shizukiListType: typeof window.meguriDesktop?.shizuki?.listTodos,
      shizukiCreateType: typeof window.meguriDesktop?.shizuki?.createTodo,
      shizukiPendingType: typeof window.meguriDesktop?.shizuki?.pendingCreates,
      shizukiUpdateType: typeof window.meguriDesktop?.shizuki?.updateTodo,
      shizukiCompleteType: typeof window.meguriDesktop?.shizuki?.completeTodo,
      shizukiReminderType: typeof window.meguriDesktop?.shizuki?.onReminder,
      voiceStartType: typeof window.meguriDesktop?.voice?.start,
      voiceCancelType: typeof window.meguriDesktop?.voice?.cancel,
      voiceChangedType: typeof window.meguriDesktop?.voice?.onChanged,
      settingsActionType: typeof window.meguriDesktop?.window?.openSettings,
      centerActionType: typeof window.meguriDesktop?.window?.centerStage,
      alwaysOnTopActionType: typeof window.meguriDesktop?.window?.setAlwaysOnTop,
      pointerPositionActionType: typeof window.meguriDesktop?.window?.pointerPosition,
      quitActionType: typeof window.meguriDesktop?.window?.quit,
      droppedPathType: typeof window.meguriDesktop?.attachments?.pathForFile,
      attachmentInspectType: typeof window.meguriDesktop?.attachments?.inspect,
      clipboardImageType: typeof window.meguriDesktop?.attachments?.clipboardImage,
      clipboardImportListenerType: typeof window.meguriDesktop?.attachments?.onClipboardImportRequested,
      screenshotStartType: typeof window.meguriDesktop?.screenshots?.start,
      screenshotCancelType: typeof window.meguriDesktop?.screenshots?.cancel,
      screenshotStatusType: typeof window.meguriDesktop?.screenshots?.status,
      screenshotListenerType: typeof window.meguriDesktop?.screenshots?.onChanged,
      characterPacksType: typeof window.meguriDesktop?.characterPacks,
      characterPacksListType: typeof window.meguriDesktop?.characterPacks?.list,
      characterPacksImportType: typeof window.meguriDesktop?.characterPacks?.importDirectory,
      characterPacksPreviewType: typeof window.meguriDesktop?.characterPacks?.preview,
      characterPresentationType: typeof window.meguriDesktop?.characterPresentation?.getActive,
      studioPreferencesType: typeof window.meguriDesktop?.preferences,
      studioAttachmentsType: typeof window.meguriDesktop?.attachments,
      studioScreenshotsType: typeof window.meguriDesktop?.screenshots,
      studioSettingsActionType: typeof window.meguriDesktop?.window?.openSettings,
      genericFilesystemType: typeof window.meguriDesktop?.filesystem,
    })`)
      }
      finally {
        window.destroy()
      }
    }

    const startup = await inspect('startup')
    const stage = await inspect('stage')
    const settings = await inspect('settings')
    const studio = await inspect('studio')
    const contractAvailable = [
      startup.preferencesGetType,
      startup.preferencesUpdateType,
      startup.preferencesMigrateType,
      startup.multimodalRelayGetType,
      startup.multimodalRelaySaveType,
      startup.multimodalRelayTestType,
      startup.settingsActionType,
      startup.centerActionType,
      startup.alwaysOnTopActionType,
      startup.pointerPositionActionType,
      startup.quitActionType,
      startup.droppedPathType,
      startup.attachmentInspectType,
      startup.clipboardImageType,
      startup.clipboardImportListenerType,
      startup.screenshotStartType,
      startup.screenshotCancelType,
      startup.screenshotStatusType,
      startup.screenshotListenerType,
    ].every(value => value === 'function')
    // The renderer can freeze and confirm a card, but never hand the main
    // process a raw send: there is no generic work.send bridge at all.
    const workContractAvailable = [
      stage.localAgentsGetType,
      stage.localAgentsSaveType,
      stage.localAgentsTestType,
      stage.workSnapshotType,
      stage.workTargetsType,
      stage.workPrepareType,
      stage.workConfirmType,
      stage.workDismissType,
      stage.workOpenNativeType,
    ].every(value => value === 'function') && stage.genericSendType === 'undefined'
    const studioContractAvailable = [
      studio.characterPacksListType,
      studio.characterPacksImportType,
      studio.characterPacksPreviewType,
    ].every(value => value === 'function')
      && studio.studioScreenshotsType === 'undefined'
    const stagePresentationAvailable = stage.characterPresentationType === 'function'
    const shizukiStageAvailable = [
      stage.shizukiStatusType, stage.shizukiListType, stage.shizukiCreateType, stage.shizukiCompleteType,
      stage.shizukiReminderType, stage.shizukiPendingType, stage.shizukiUpdateType, stage.voiceStartType, stage.voiceCancelType,
      stage.voiceChangedType,
    ].every(value => value === 'function')
      && stage.shizukiConnectType === 'undefined'
      && stage.shizukiDisconnectType === 'undefined'
    const shizukiSettingsAvailable = [
      settings.shizukiStatusType, settings.shizukiConnectType,
      settings.shizukiDisconnectType,
    ].every(value => value === 'function')
      && settings.shizukiListType === 'undefined'
      && settings.shizukiCreateType === 'undefined'
      && settings.shizukiUpdateType === 'undefined'
      && settings.shizukiPendingType === 'undefined'
      && settings.voiceStartType === 'undefined'
    const sensitiveBridgeHidden = startup.shizukiStatusType === 'undefined'
      && startup.voiceStartType === 'undefined'
      && studio.shizukiStatusType === 'undefined'
      && studio.voiceStartType === 'undefined'
    if (startup.bridgeType !== 'object'
      || startup.view !== 'startup'
      || startup.characterPacksType !== 'undefined'
      || startup.characterPresentationType !== 'undefined'
      || startup.genericFilesystemType !== 'undefined'
      || stage.view !== 'stage'
      || stage.characterPacksType !== 'undefined'
      || studio.view !== 'studio'
      || studio.characterPacksType !== 'object'
      || studio.characterPresentationType !== 'undefined'
      || studio.studioPreferencesType !== 'undefined'
      || studio.studioAttachmentsType !== 'undefined'
      || studio.studioSettingsActionType !== 'undefined'
      || studio.genericFilesystemType !== 'undefined'
      || studio.localAgentsGetType !== 'undefined'
      || studio.workSnapshotType !== 'undefined'
      || !contractAvailable
      || !workContractAvailable
      || !stagePresentationAvailable
      || !studioContractAvailable
      || !shizukiStageAvailable
      || !shizukiSettingsAvailable
      || !sensitiveBridgeHidden) {
      report({ ok: false, startup, stage, settings, studio, preloadError })
      process.exitCode = 1
    }
    else {
      report({ ok: true, startup, stage, settings, studio })
    }
  }
  finally {
    clearTimeout(forceExit)
    keeper.destroy()
    rendererProcess?.kill()
    app.exit(process.exitCode ?? 0)
  }
}).catch((error) => {
  clearTimeout(forceExit)
  rendererProcess?.kill()
  report({ ok: false, error: error instanceof Error ? error.message : String(error), preloadError })
  app.exit(1)
})
