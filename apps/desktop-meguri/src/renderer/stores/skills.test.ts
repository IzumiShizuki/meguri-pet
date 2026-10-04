import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  search: vi.fn(),
  detail: vi.fn(),
  list: vi.fn(),
  get: vi.fn(),
  importSkill: vi.fn(),
  activate: vi.fn(),
  disable: vi.fn(),
  checkUpdate: vi.fn(),
  importUpdate: vi.fn(),
}))

vi.mock('../services/skills-api', async importOriginal => ({
  ...(await importOriginal<typeof import('../services/skills-api')>()),
  skillsApi: api,
}))

import type { SkillCatalogView } from '../services/skills-api'
import { useSkillsStore } from './skills'

function catalog(active = false, digest = 'a'.repeat(64)): SkillCatalogView {
  const manifest = {
    id: 'skill_pdf',
    source_id: 'modelscope',
    external_id: '@MiniMax-AI/minimax-pdf',
    trust: 'UNTRUSTED_EXTERNAL' as const,
    name: 'PDF Skill',
    description: 'PDF workflow',
    license: 'MIT',
    tags: ['pdf'],
    requires: { tools: [], env: [] },
    always: false,
    source_revision: 'revision-1',
  }
  return {
    manifest,
    revisions: [{
      skill_id: manifest.id,
      digest,
      source_revision: 'revision-1',
      manifest,
      report: {
        state: 'VALID', compatible: true, redistributable: true, issues: [],
        checked_at: '2026-08-13T00:00:00Z',
      },
      imported_at: '2026-08-13T00:00:00Z',
      active,
    }],
    binding: {
      skill_id: manifest.id,
      state: active ? 'ENABLED' : 'DISABLED',
      active_digest: active ? digest : undefined,
      updated_at: '2026-08-13T00:00:00Z',
    },
  }
}

describe('Skills workspace store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const mock of Object.values(api)) mock.mockReset()
    api.list.mockResolvedValue([])
  })

  it('moves a validated import into the installed revision ledger without enabling it', async () => {
    api.importSkill.mockResolvedValue(catalog(false))
    const store = useSkillsStore()

    await store.importRemote('session-1', '@MiniMax-AI/minimax-pdf')

    expect(store.tab).toBe('installed')
    expect(store.selectedInstalledId).toBe('skill_pdf')
    expect(store.installed).toHaveLength(1)
    expect(store.installed[0].binding.state).toBe('DISABLED')
  })

  it('passes license confirmation through activation and replaces the active revision', async () => {
    const enabled = catalog(true)
    api.activate.mockResolvedValue(enabled)
    const store = useSkillsStore()
    store.installed = [catalog(false)]

    await store.activate('session-2', 'skill_pdf', 'a'.repeat(64), true)

    expect(api.activate).toHaveBeenCalledWith('session-2', 'skill_pdf', 'a'.repeat(64), true)
    expect(store.installed[0].binding.state).toBe('ENABLED')
    expect(store.installed[0].revisions[0].active).toBe(true)
  })

  it('checks, imports, and preserves an update as a separate disabled revision', async () => {
    const current = catalog(true)
    const updated = catalog(true)
    updated.revisions.push({
      ...updated.revisions[0],
      digest: 'b'.repeat(64),
      source_revision: 'revision-2',
      active: false,
    })
    api.checkUpdate.mockResolvedValue({
      update_available: true,
      current_revision: 'revision-1',
      latest_revision: 'revision-2',
    })
    api.importUpdate.mockResolvedValue(updated)
    const store = useSkillsStore()
    store.installed = [current]

    await store.checkUpdate('session-3', 'skill_pdf')
    await store.importUpdate('session-3', 'skill_pdf')

    expect(store.updates.skill_pdf.update_available).toBe(true)
    expect(store.installed[0].binding.active_digest).toBe('a'.repeat(64))
    expect(store.installed[0].revisions).toHaveLength(2)
  })
})
