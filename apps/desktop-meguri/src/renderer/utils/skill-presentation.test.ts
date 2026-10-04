import { describe, expect, it } from 'vitest'

import type { SkillRevisionView } from '../services/skills-api'
import { canActivateRevision } from './skill-presentation'

function revision(overrides: Partial<SkillRevisionView> = {}): SkillRevisionView {
  return {
    skill_id: 'skill_pdf',
    digest: 'a'.repeat(64),
    source_revision: 'revision-1',
    report: {
      state: 'VALID',
      compatible: true,
      redistributable: true,
      issues: [],
      checked_at: '2026-08-13T00:00:00Z',
    },
    imported_at: '2026-08-13T00:00:00Z',
    active: false,
    ...overrides,
  }
}

describe('Skill revision activation guard', () => {
  it('allows a valid compatible revision with a declared license', () => {
    const candidate = revision({
      manifest: {
        id: 'skill_pdf',
        source_id: 'modelscope',
        external_id: '@MiniMax-AI/minimax-pdf',
        trust: 'UNTRUSTED_EXTERNAL',
        name: 'PDF Skill',
        description: 'PDF workflow',
        license: 'MIT',
        tags: ['pdf'],
        requires: { tools: [], env: [] },
        always: false,
        source_revision: 'revision-1',
      },
    })

    expect(canActivateRevision(candidate, false)).toBe(true)
  })

  it('requires explicit confirmation when the revision has no license', () => {
    const candidate = revision()

    expect(canActivateRevision(candidate, false)).toBe(false)
    expect(canActivateRevision(candidate, true)).toBe(true)
  })

  it.each([
    revision({ active: true }),
    revision({ report: { ...revision().report, state: 'QUARANTINED' } }),
    revision({ report: { ...revision().report, state: 'INVALID' } }),
    revision({ report: { ...revision().report, compatible: false } }),
  ])('rejects active, quarantined, invalid, or incompatible revisions', (candidate) => {
    expect(canActivateRevision(candidate, true, 'MIT')).toBe(false)
  })
})
