import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  list: vi.fn(),
  importDirectory: vi.fn(),
  addResource: vi.fn(),
  publish: vi.fn(),
  activate: vi.fn(),
  disable: vi.fn(),
  restore: vi.fn(),
  preview: vi.fn(),
}))

vi.mock('../services/character-packs', async importOriginal => ({
  ...(await importOriginal<typeof import('../services/character-packs')>()),
  characterPacksApi: api,
}))

import type { CharacterPackCatalog, CharacterPackSummary } from '../../shared/desktop-contract'
import { useCharacterPacksStore } from './character-packs'

function pack(overrides: Partial<CharacterPackSummary> = {}): CharacterPackSummary {
  return {
    id: 'draft-meguri',
    displayName: 'Meguri Draft',
    version: 'v1',
    status: 'draft',
    active: false,
    importedAt: '2026-09-18T00:00:00.000Z',
    resources: [{ id: 'sprite:01', name: 'ce04001m.png', kind: 'sprite', mediaType: 'image/png', status: 'ready' }],
    validationMessages: [],
    ...overrides,
  }
}

function catalog(drafts: CharacterPackSummary[] = [pack()], packages: CharacterPackSummary[] = []): CharacterPackCatalog {
  return { version: 1, activePackageId: packages.find(item => item.active)?.id, drafts, packages }
}

describe('Character Studio store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const mock of Object.values(api)) mock.mockReset()
    api.preview.mockResolvedValue({ ok: true, dataUrl: 'data:image/png;base64,AA==', mediaType: 'image/png' })
  })

  it('loads a draft catalog and selects its first validated resource for preview', async () => {
    api.list.mockResolvedValue(catalog())
    const store = useCharacterPacksStore()

    await store.load()

    expect(store.selectedPackId).toBe('draft-meguri')
    expect(store.selectedResourceId).toBe('sprite:01')
    expect(store.previewDataUrl).toBe('data:image/png;base64,AA==')
    expect(api.preview).toHaveBeenCalledWith('draft-meguri', 'sprite:01')
  })

  it('keeps an unavailable resource as a recoverable fallback instead of requesting a preview', async () => {
    api.list.mockResolvedValue(catalog([pack({
      resources: [{ id: 'background:missing', name: 'missing.jpg', kind: 'background', mediaType: 'image/jpeg', status: 'unavailable', validationMessage: '文件缺失。' }],
    })]))
    const store = useCharacterPacksStore()

    await store.load()
    await store.selectResource('background:missing')

    expect(store.previewDataUrl).toBe('')
    expect(store.previewError).toBe('文件缺失。')
    expect(api.preview).not.toHaveBeenCalled()
  })

  it('marks only a preview-failed resource unavailable while preserving the rest of the catalog', async () => {
    api.list.mockResolvedValue(catalog([pack({
      resources: [
        { id: 'sprite:01', name: 'ready.png', kind: 'sprite', mediaType: 'image/png', status: 'ready' },
        { id: 'sprite:02', name: 'broken.png', kind: 'sprite', mediaType: 'image/png', status: 'ready' },
      ],
    })]))
    api.preview.mockResolvedValueOnce({ ok: true, dataUrl: 'data:image/png;base64,AA==', mediaType: 'image/png' })
    api.preview.mockRejectedValueOnce(new Error('资源文件不可读取。'))
    const store = useCharacterPacksStore()

    await store.load()
    await store.selectResource('sprite:02')

    expect(store.selectedResource?.status).toBe('unavailable')
    expect(store.selectedResource?.validationMessage).toBe('资源文件不可读取。')
    expect(store.selectedPack?.resources[0].status).toBe('ready')
  })

  it('moves an imported directory into the draft review state with feedback', async () => {
    const imported = pack({ id: 'draft-imported', displayName: 'Imported Pack' })
    api.importDirectory.mockResolvedValue({ ok: true, catalog: catalog([imported]), pack: imported })
    const store = useCharacterPacksStore()

    await store.importDirectory()

    expect(store.selectedPackId).toBe('draft-imported')
    expect(store.selectedPack?.status).toBe('draft')
    expect(store.notice).toContain('草稿导入')
  })

  it('adopts the published active package returned by the host without changing a failed operation into success', async () => {
    const published = pack({ id: 'pack-meguri-v1', status: 'published', active: true, publishedAt: '2026-09-18T00:02:00.000Z' })
    api.publish.mockResolvedValue({ ok: true, catalog: catalog([], [published]), pack: published })
    const store = useCharacterPacksStore()

    await store.publish('draft-meguri')

    expect(store.catalog.activePackageId).toBe('pack-meguri-v1')
    expect(store.selectedPack?.status).toBe('published')
    expect(store.notice).toContain('已发布并启用')

    api.disable.mockRejectedValueOnce(new Error('主进程拒绝停用。'))
    await store.disable('pack-meguri-v1')
    expect(store.selectedPack?.status).toBe('published')
    expect(store.error).toBe('主进程拒绝停用。')
  })
})
