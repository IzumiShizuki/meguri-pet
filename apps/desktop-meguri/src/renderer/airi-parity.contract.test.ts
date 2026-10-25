import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

const rendererRoot = dirname(fileURLToPath(import.meta.url))
const stage = readFileSync(resolve(rendererRoot, 'views/StageView.vue'), 'utf8')
const ambientStage = readFileSync(resolve(rendererRoot, 'views/AmbientStage.vue'), 'utf8')
const controls = readFileSync(resolve(rendererRoot, 'components/ControlsIsland.vue'), 'utf8')
const chat = readFileSync(resolve(rendererRoot, 'components/ChatDock.vue'), 'utf8')
const settings = readFileSync(resolve(rendererRoot, 'views/SettingsView.vue'), 'utf8')
const main = readFileSync(resolve(rendererRoot, '../main/index.mjs'), 'utf8')

describe('AIRI desktop UI parity contract', () => {
  // Bubble/window geometry is exercised by bubble-layout-smoke.mjs; palette
  // readability is covered by conversation-palette.test.ts. The former CSS
  // snapshots of a compressed leg-overlay dock no longer describe the product.

  it('has no persistent stage title or status chrome', () => {
    expect(stage).not.toContain('stage-status')
    expect(stage).not.toContain('stage-drag-region')
    expect(stage).not.toContain('presence-halo')
    expect(stage).toContain('<AmbientStage')
    expect(stage).toContain('<GalgameStage')
    // chat-interaction-smoke.mjs verifies one active rail in either presentation.
    expect(ambientStage).toContain('<ControlsIsland')
  })

  it('exposes the supported AIRI-familiar controls and omits inert AIRI settings', () => {
    for (const action of ['openSettings', 'centerStage', 'reloadStage', 'setAlwaysOnTop', 'hide', 'quit'])
      expect(controls).toContain(action)
    for (const unsupported of ['provider', 'plugin', 'updater', 'Live2D', 'VRM'])
      expect(settings).not.toContain(`id: '${unsupported}'`)
  })

  it('inherits AIRI global shortcuts and pinned-stage click-through', () => {
    const shortcuts = readFileSync(resolve(rendererRoot, '../main/global-shortcuts.mjs'), 'utf8')
    for (const accelerator of ['Ctrl+Alt+H', 'Ctrl+Alt+C', 'Ctrl+Alt+Shift+M', 'Ctrl+Alt+[', 'Ctrl+Alt+]'])
      expect(shortcuts).toContain(accelerator)
    expect(main).toContain('registerMeguriGlobalShortcuts')
    expect(stage).toContain('resolveSurfacePointerBehavior')
    expect(stage).toContain('setClickThrough')
    expect(stage).toContain('pointerPosition')
  })

  it('restores the Everything-backed @ resource picker in the native composer', () => {
    expect(chat).toContain('@文件名')
    expect(chat).toContain('resource-candidate')
    expect(chat).toContain('resource-chip')
    expect(chat).toContain('metadata-only')
  })

  it('accepts trusted dropped files and explicitly pasted clipboard screenshots', () => {
    expect(chat).toContain('@dragenter.prevent="onDragEnter"')
    expect(chat).toContain('@drop.prevent.stop="onDrop"')
    expect(chat).toContain('@paste="onComposerPaste"')
    expect(chat).toContain('normalizeReadableAttachment')
    expect(chat).toContain('发送后读取')
    expect(main).toContain('inspectDroppedAttachment')
    expect(main).toContain('clipboard.readImage()')
    expect(main).toContain('image.toPNG()')
  })

  it('offers one-shot PixPin ask/direct-send actions and keeps the explicit paste fallback', () => {
    const capture = readFileSync(resolve(rendererRoot, '../main/pixpin-capture.mjs'), 'utf8')
    const runtime = readFileSync(resolve(rendererRoot, '../main/pixpin-runtime.mjs'), 'utf8')
    expect(chat).toContain('截图问 Meguri')
    expect(chat).toContain('截图直接发送')
    expect(chat).toContain("startPixPinCapture('draft')")
    expect(chat).toContain("startPixPinCapture('direct_send')")
    expect(chat).toContain('conversation.send(screenshotQuestion, [attachment])')
    expect(chat).toContain('原有文字和附件均已保留')
    expect(chat).toContain('Ctrl+Alt+Shift+P')
    expect(capture).toContain('pixpinCaptureTimeoutMs')
    expect(capture).toContain('session.initialSequence')
    expect(runtime).toContain('GetClipboardOwner')
    expect(runtime).toContain('GetWindowThreadProcessId')
    expect(runtime).toContain('pixpin.screenShot(ShotAction.Copy)')
  })

  it('exposes a trusted multimodal relay settings entry without hydrating saved secrets', () => {
    expect(settings).toContain("id: 'multimodal'")
    expect(settings).toContain('window.meguriDesktop?.multimodalRelay.get()')
    expect(settings).toContain('type="password"')
    expect(settings).toContain('留空不会覆盖')
    expect(settings).toContain('完整重启 Meguri 后生效')
    expect(main).toContain('trustedSettingsRenderer')
    expect(main).toContain('saveMultimodalRelayConfig')
  })
})
