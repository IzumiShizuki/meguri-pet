import { app, BrowserWindow } from 'electron'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const projectRoot = resolve(appRoot, '../..')
const outputRoot = resolve(projectRoot, 'tmp/skills-visual-smoke')
const rendererUrl = process.env.MEGURI_RENDERER_URL ?? 'http://127.0.0.1:4174'
const consoleErrors = []

app.setPath('userData', resolve(app.getPath('temp'), `meguri-skills-visual-${process.pid}`))
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

async function installFixtureFetch(window) {
  const digest = 'a'.repeat(64)
  const manifest = {
    id: 'skill_visual_pdf',
    source_id: 'modelscope',
    external_id: '@MiniMax-AI/minimax-pdf',
    trust: 'UNTRUSTED_EXTERNAL',
    name: 'Minimax PDF',
    description: '提取 PDF 文档中的表格、章节与可引用文本。',
    license: 'MIT License',
    tags: ['pdf', 'document'],
    requires: { tools: ['web_search'], env: [] },
    always: false,
    source_revision: '2026-08-12T12:00:00Z',
    source_updated_at: '2026-08-12T12:00:00Z',
  }
  const catalog = {
    manifest,
    revisions: [{
      skill_id: manifest.id,
      digest,
      source_revision: manifest.source_revision,
      manifest,
      report: {
        state: 'VALID',
        compatible: true,
        redistributable: true,
        issues: [{
          code: 'SKILL_ALWAYS_IGNORED',
          severity: 'WARNING',
          message: 'always is ignored',
          path: 'SKILL.md',
        }],
        checked_at: '2026-08-13T00:00:00Z',
      },
      imported_at: '2026-08-13T00:00:00Z',
      active: true,
    }],
    binding: {
      skill_id: manifest.id,
      state: 'ENABLED',
      active_digest: digest,
      updated_at: '2026-08-13T00:00:00Z',
    },
  }
  const fixtures = { manifest, catalog }
  await window.webContents.executeJavaScript(`(() => {
    const fixtures = ${JSON.stringify(fixtures)}
    window.fetch = async (input, init = {}) => {
      const url = new URL(String(input), window.location.href)
      let payload
      if (url.pathname.endsWith('/skill-sources/modelscope/skills/detail')) {
        payload = {
          summary: {
            external_id: fixtures.manifest.external_id,
            name: fixtures.manifest.name,
            description: fixtures.manifest.description,
            license: fixtures.manifest.license,
            tags: fixtures.manifest.tags,
            revision: fixtures.manifest.source_revision,
            updated_at: fixtures.manifest.source_updated_at,
          },
          requirements: fixtures.manifest.requires,
          metadata: { developer: 'MiniMax-AI', category: 'document' },
        }
      }
      else if (url.pathname.endsWith('/skill-sources/modelscope/skills')) {
        payload = {
          items: [{
            external_id: fixtures.manifest.external_id,
            name: fixtures.manifest.name,
            description: fixtures.manifest.description,
            license: fixtures.manifest.license,
            tags: fixtures.manifest.tags,
            revision: fixtures.manifest.source_revision,
            updated_at: fixtures.manifest.source_updated_at,
          }],
          page: 1,
          page_size: 20,
          total: 1,
        }
      }
      else if (url.pathname.endsWith('/internal/v1/skills')) payload = [fixtures.catalog]
      else if (url.pathname.includes('/internal/v1/skills/')) payload = fixtures.catalog
      else payload = { code: 'VISUAL_FIXTURE_NOT_FOUND', message: url.pathname }
      return new Response(JSON.stringify(payload), {
        status: payload.code ? 404 : (init.method === 'POST' ? 200 : 200),
        headers: { 'Content-Type': 'application/json' },
      })
    }
  })()`)
}

async function inspectLayout(window, expectedMode) {
  return window.webContents.executeJavaScript(`(() => {
    const workspace = document.querySelector('.skills-workspace')
    const layout = document.querySelector('.skills-layout')
    const close = document.querySelector('.skills-close')
    const rect = workspace?.getBoundingClientRect()
    const closeRect = close?.getBoundingClientRect()
    const style = layout ? getComputedStyle(layout) : undefined
    return {
      expectedMode: ${JSON.stringify(expectedMode)},
      viewport: { width: innerWidth, height: innerHeight },
      workspaceRect: rect ? {
        left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
        width: rect.width, height: rect.height,
      } : undefined,
      workspaceVisible: Boolean(rect && rect.width > 0 && rect.height > 0),
      workspaceInsideViewport: Boolean(rect && rect.left >= 0 && rect.top >= 0
        && rect.right <= innerWidth && rect.bottom <= innerHeight),
      closeVisible: Boolean(closeRect && closeRect.width > 0 && closeRect.height > 0),
      horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      layoutDisplay: style?.display,
      layoutColumns: style?.gridTemplateColumns,
      trustVisible: document.body.textContent.includes('UNTRUSTED_EXTERNAL'),
    }
  })()`)
}

async function settleHiddenWindowTransition(window) {
  await window.webContents.executeJavaScript(`(() => {
    const workspace = document.querySelector('.skills-workspace')
    if (!workspace) return
    for (const name of Array.from(workspace.classList)) {
      if (name.startsWith('skills-sheet-')) workspace.classList.remove(name)
    }
  })()`)
  await sleep(50)
}

async function screenshot(window, filename) {
  // Hidden Electron windows may retain one stale compositor frame. Discard a
  // warm-up capture before writing the visual evidence.
  await window.webContents.capturePage()
  await sleep(100)
  const image = await window.webContents.capturePage()
  const path = resolve(outputRoot, filename)
  writeFileSync(path, image.toPNG())
  return path
}

const timeout = setTimeout(() => {
  console.error(JSON.stringify({ ok: false, error: 'visual smoke timed out' }))
  app.exit(2)
}, 20_000)

app.whenReady().then(async () => {
  const window = new BrowserWindow({
    show: false,
    width: 820,
    height: 720,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false,
    },
  })
  window.webContents.on('console-message', event => {
    if (event.level >= 'error' && !event.message.includes('Electron Security Warning')) {
      consoleErrors.push(event.message)
    }
  })
  try {
    await window.loadURL(`${rendererUrl}/?preview=ready`)
    await waitFor(window, '.controls-island')
    await installFixtureFetch(window)

    await window.webContents.executeJavaScript(`window.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'k', ctrlKey: true, shiftKey: true, bubbles: true,
    }))`)
    await waitFor(window, '.skills-workspace')
    await window.webContents.executeJavaScript(`(() => {
      const input = document.querySelector('#skill-search')
      input.value = 'pdf'
      input.dispatchEvent(new Event('input', { bubbles: true }))
      document.querySelector('.skills-search').requestSubmit()
    })()`)
    await waitFor(window, '.skill-card-list button')
    await window.webContents.executeJavaScript(
      `document.querySelector('.skill-card-list button').click()`,
    )
    await waitFor(window, '.skill-facts')
    await sleep(300)
    await settleHiddenWindowTransition(window)
    const wide = await inspectLayout(window, 'two-pane')
    const wideScreenshot = await screenshot(window, 'wide-discover.png')

    await window.webContents.executeJavaScript(`document.querySelector('.skills-close').click()`)
    await sleep(300)
    window.setSize(380, 650)
    await sleep(200)
    await window.webContents.executeJavaScript(`document.querySelector('.controls-island [aria-expanded]').click()`)
    await waitFor(window, '[aria-label="打开技能卡册"]')
    await window.webContents.executeJavaScript(
      `document.querySelector('[aria-label="打开技能卡册"]').click()`,
    )
    await waitFor(window, '.skills-workspace')
    await window.webContents.executeJavaScript(
      `Array.from(document.querySelectorAll('.skills-tabs button')).find(button => button.textContent.includes('本地卡册')).click()`,
    )
    await waitFor(window, '.revision-card')
    await sleep(300)
    await settleHiddenWindowTransition(window)
    const narrow = await inspectLayout(window, 'single-column')
    const narrowScreenshot = await screenshot(window, 'narrow-installed.png')

    const ok = wide.workspaceVisible && wide.workspaceInsideViewport && wide.closeVisible
      && !wide.horizontalOverflow && wide.layoutDisplay === 'grid'
      && wide.layoutColumns.split(' ').length >= 2 && wide.trustVisible
      && narrow.workspaceVisible && narrow.workspaceInsideViewport && narrow.closeVisible
      && !narrow.horizontalOverflow && narrow.layoutDisplay === 'block'
      && narrow.trustVisible && consoleErrors.length === 0
    console.log(JSON.stringify({
      ok,
      wide,
      narrow,
      screenshots: [wideScreenshot, narrowScreenshot],
      consoleErrors,
    }))
    process.exitCode = ok ? 0 : 1
  }
  finally {
    clearTimeout(timeout)
    window.destroy()
    app.exit(process.exitCode ?? 0)
  }
}).catch(error => {
  clearTimeout(timeout)
  console.error(JSON.stringify({ ok: false, error: error instanceof Error ? error.message : String(error) }))
  app.exit(1)
})
