import { app, BrowserWindow } from 'electron'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const projectRoot = resolve(appRoot, '../..')
const outputRoot = resolve(projectRoot, 'tmp/presentation-visual-smoke')
const rendererUrl = process.env.MEGURI_RENDERER_URL ?? 'http://127.0.0.1:4174'
const consoleErrors = []
let phase = 'initializing'

app.setPath('userData', resolve(app.getPath('temp'), `meguri-presentation-visual-${process.pid}`))
mkdirSync(outputRoot, { recursive: true })

function sleep(milliseconds) {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds))
}

async function waitFor(window, selector, timeout = 5_000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const present = await window.webContents.executeJavaScript(
      `Boolean(document.querySelector(${JSON.stringify(selector)}))`,
    )
    if (present)
      return
    await sleep(50)
  }
  throw new Error(`Timed out waiting for ${selector}`)
}

async function screenshot(window, filename) {
  try {
    await window.webContents.capturePage()
    await sleep(100)
    const image = await window.webContents.capturePage()
    const path = resolve(outputRoot, filename)
    writeFileSync(path, image.toPNG())
    return path
  }
  catch (error) {
    if (error instanceof Error && error.message.includes('UnknownVizError'))
      return undefined
    throw error
  }
}

async function createWindow(width, height) {
  const window = new BrowserWindow({
    show: false,
    width,
    height,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false,
    },
  })
  window.webContents.on('console-message', event => {
    if (event.level >= 'error' && !event.message.includes('Electron Security Warning'))
      consoleErrors.push(event.message)
  })
  return window
}

async function inspectStudio(window, expectedLayout) {
  return window.webContents.executeJavaScript(`(() => {
    const shell = document.querySelector('.studio-shell')
    const workspace = document.querySelector('.studio-workspace')
    const library = document.querySelector('.studio-library')
    const inspector = document.querySelector('.studio-inspector')
    const preview = document.querySelector('.studio-preview-image, .studio-preview-fallback')
    const rect = node => node?.getBoundingClientRect()
    const inside = value => Boolean(value && value.left >= -1 && value.top >= -1 && value.right <= innerWidth + 1 && value.bottom <= innerHeight + 1)
    return {
      expectedLayout: ${JSON.stringify(expectedLayout)},
      workspaceDisplay: workspace && getComputedStyle(workspace).display,
      shellInsideViewport: inside(rect(shell)),
      workspaceInsideViewport: inside(rect(workspace)),
      libraryVisible: Boolean(rect(library)?.width && rect(library)?.height),
      inspectorVisible: Boolean(rect(inspector)?.width && rect(inspector)?.height),
      previewVisible: Boolean(rect(preview)?.width && rect(preview)?.height),
      horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      selectedResource: document.querySelector('.studio-resource-list button.selected')?.textContent?.trim(),
    }
  })()`)
}

async function inspectGalgame(window, expectedLayout) {
  return window.webContents.executeJavaScript(`(() => {
    const stage = document.querySelector('.galgame-stage')
    const toolbar = document.querySelector('.galgame-toolbar')
    const dialogue = document.querySelector('.galgame-dialogue')
    const history = document.querySelector('.galgame-history')
    const composer = document.querySelector('.galgame-composer')
    const textarea = document.querySelector('.galgame-composer textarea')
    const rect = node => node?.getBoundingClientRect()
    const stageRect = rect(stage)
    const toolbarRect = rect(toolbar)
    const dialogueRect = rect(dialogue)
    const historyRect = rect(history)
    const composerRect = rect(composer)
    return {
      expectedLayout: ${JSON.stringify(expectedLayout)},
      stageInsideViewport: Boolean(stageRect && stageRect.left >= -1 && stageRect.top >= -1 && stageRect.right <= innerWidth + 1 && stageRect.bottom <= innerHeight + 1),
      dialogueInsideViewport: Boolean(dialogueRect && dialogueRect.left >= -1 && dialogueRect.top >= -1 && dialogueRect.right <= innerWidth + 1 && dialogueRect.bottom <= innerHeight + 1),
      historyBeforeComposer: Boolean(historyRect && composerRect && historyRect.bottom <= composerRect.top),
      composerInsideDialogue: Boolean(composerRect && dialogueRect && composerRect.left >= dialogueRect.left && composerRect.right <= dialogueRect.right && composerRect.bottom <= dialogueRect.bottom),
      toolbarDoesNotCoverDialogue: Boolean(toolbarRect && dialogueRect && toolbarRect.bottom <= dialogueRect.top),
      composerFocused: document.activeElement === textarea,
      horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    }
  })()`)
}

const timeout = setTimeout(() => {
  console.error(JSON.stringify({ ok: false, error: 'presentation visual smoke timed out' }))
  app.exit(2)
}, 25_000)

app.whenReady().then(async () => {
  const windows = []
  try {
    phase = 'loading wide Studio'
    const studioWide = await createWindow(1180, 800)
    windows.push(studioWide)
    await studioWide.loadURL(`${rendererUrl}/?preview=ready&view=studio`)
    await waitFor(studioWide, '.studio-resource-list button.selected')
    phase = 'capturing wide Studio'
    const studioWideLayout = await inspectStudio(studioWide, 'three-column')
    const studioWideScreenshot = await screenshot(studioWide, 'studio-wide.png')

    phase = 'loading narrow Studio'
    const studioNarrow = await createWindow(600, 700)
    windows.push(studioNarrow)
    await studioNarrow.loadURL(`${rendererUrl}/?preview=ready&view=studio`)
    await waitFor(studioNarrow, '.studio-resource-list button.selected')
    phase = 'capturing narrow Studio'
    const studioNarrowLayout = await inspectStudio(studioNarrow, 'stacked')
    const studioNarrowScreenshot = await screenshot(studioNarrow, 'studio-narrow.png')

    phase = 'loading wide Galgame'
    const galWide = await createWindow(860, 720)
    windows.push(galWide)
    await galWide.loadURL(`${rendererUrl}/?preview=ready`)
    await waitFor(galWide, '.controls-island [aria-expanded]')
    await galWide.webContents.executeJavaScript(`document.querySelector('.controls-island [aria-expanded]').click()`)
    await waitFor(galWide, '[aria-label="进入 Galgame 模式"]')
    await galWide.webContents.executeJavaScript(`document.querySelector('[aria-label="进入 Galgame 模式"]').click()`)
    await waitFor(galWide, '.galgame-stage')
    await sleep(120)
    phase = 'capturing wide Galgame'
    const galWideLayout = await inspectGalgame(galWide, 'two-column')
    const galWideScreenshot = await screenshot(galWide, 'galgame-wide.png')

    phase = 'loading narrow Galgame'
    const galNarrow = await createWindow(420, 650)
    windows.push(galNarrow)
    await galNarrow.loadURL(`${rendererUrl}/?preview=ready`)
    await waitFor(galNarrow, '.controls-island [aria-expanded]')
    await galNarrow.webContents.executeJavaScript(`document.querySelector('.controls-island [aria-expanded]').click()`)
    await waitFor(galNarrow, '[aria-label="进入 Galgame 模式"]')
    await galNarrow.webContents.executeJavaScript(`document.querySelector('[aria-label="进入 Galgame 模式"]').click()`)
    await waitFor(galNarrow, '.galgame-stage')
    await sleep(120)
    phase = 'capturing narrow Galgame'
    const galNarrowLayout = await inspectGalgame(galNarrow, 'stacked')
    const galNarrowScreenshot = await screenshot(galNarrow, 'galgame-narrow.png')

    const studioOk = studioWideLayout.workspaceDisplay === 'grid'
      && studioWideLayout.shellInsideViewport
      && studioWideLayout.workspaceInsideViewport
      && studioWideLayout.libraryVisible
      && studioWideLayout.inspectorVisible
      && studioWideLayout.previewVisible
      && !studioWideLayout.horizontalOverflow
      && studioNarrowLayout.workspaceDisplay === 'block'
      && studioNarrowLayout.libraryVisible
      && studioNarrowLayout.inspectorVisible
      && studioNarrowLayout.previewVisible
      && !studioNarrowLayout.horizontalOverflow
    const galOk = galWideLayout.stageInsideViewport
      && galWideLayout.dialogueInsideViewport
      && galWideLayout.historyBeforeComposer
      && galWideLayout.composerInsideDialogue
      && galWideLayout.toolbarDoesNotCoverDialogue
      && galWideLayout.composerFocused
      && !galWideLayout.horizontalOverflow
      && galNarrowLayout.stageInsideViewport
      && galNarrowLayout.dialogueInsideViewport
      && galNarrowLayout.historyBeforeComposer
      && galNarrowLayout.composerInsideDialogue
      && galNarrowLayout.toolbarDoesNotCoverDialogue
      && galNarrowLayout.composerFocused
      && !galNarrowLayout.horizontalOverflow
    const ok = studioOk && galOk && consoleErrors.length === 0
    console.log(JSON.stringify({
      ok,
      studio: { wide: studioWideLayout, narrow: studioNarrowLayout },
      galgame: { wide: galWideLayout, narrow: galNarrowLayout },
      screenshots: [studioWideScreenshot, studioNarrowScreenshot, galWideScreenshot, galNarrowScreenshot],
      consoleErrors,
    }))
    process.exitCode = ok ? 0 : 1
  }
  finally {
    clearTimeout(timeout)
    for (const window of windows)
      window.destroy()
    app.exit(process.exitCode ?? 0)
  }
}).catch(error => {
  clearTimeout(timeout)
  console.error(JSON.stringify({ ok: false, phase, error: error instanceof Error ? error.stack || error.message : String(error) }))
  app.exit(1)
})
