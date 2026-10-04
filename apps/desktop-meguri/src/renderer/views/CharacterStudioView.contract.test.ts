import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const view = readFileSync(new URL('./CharacterStudioView.vue', import.meta.url), 'utf8')
const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const controls = readFileSync(new URL('../components/ControlsIsland.vue', import.meta.url), 'utf8')
const preload = readFileSync(new URL('../../preload/index.cjs', import.meta.url), 'utf8')

describe('Character Studio interaction contract', () => {
  it('is routed as a dedicated desktop view and can be opened from the stage controls', () => {
    expect(app).toContain("import CharacterStudioView from './views/CharacterStudioView.vue'")
    expect(app).toContain("activeView === 'studio'")
    expect(controls).toContain("runWindowAction('openStudio')")
    expect(controls).toContain('打开角色工作室')
  })

  it('uses only named Studio package operations and previews safe returned data', () => {
    for (const action of ['importDirectory()', 'addResource(selectedPack.id)', 'publish(selectedPack.id)', 'activate(selectedPack.id)', 'disable(selectedPack.id)', 'restore(selectedPack.id)'])
      expect(view).toContain(action)
    expect(view).toContain('previewDataUrl')
    expect(view).toContain('markPreviewUnavailable()')
    expect(view).not.toContain('pathForFile')
    expect(view).not.toContain('type="file"')
  })

  it('only exposes package management operations in the Studio preload view', () => {
    expect(preload).toContain("view === 'studio'")
    expect(preload).toContain('characterPacksImportDirectory')
    expect(preload).toContain('characterPacksPreview')
    expect(preload).not.toContain('readFile:')
    expect(preload).not.toContain('writeFile:')
    expect(preload).toContain('const studioBridge')
    expect(preload).toContain("view === 'studio' ? studioBridge : desktopBridge")
  })
})
