import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const source = readFileSync(new URL('./SkillsWorkspace.vue', import.meta.url), 'utf8')
const stageSource = readFileSync(new URL('../views/StageView.vue', import.meta.url), 'utf8')
const controlsSource = readFileSync(new URL('./ControlsIsland.vue', import.meta.url), 'utf8')

describe('Skills workspace interaction contract', () => {
  it('exposes search, import, update, disable, activation, and historical rollback actions', () => {
    expect(source).toContain('skills.search(sessionId)')
    expect(source).toContain('skills.importRemote(sessionId')
    expect(source).toContain('skills.checkUpdate(sessionId')
    expect(source).toContain('skills.importUpdate(sessionId')
    expect(source).toContain('skills.disable(sessionId')
    expect(source).toContain('skills.activate(sessionId')
    expect(source).toContain('licenseConfirmations[revision.digest]')
    expect(source).toContain('[...selectedInstalled.revisions].reverse()')
  })

  it('announces progress and supports keyboard dismissal and search focus', () => {
    expect(source).toContain('aria-live="polite"')
    expect(source).toContain("event.key === 'Escape'")
    expect(source).toContain("event.key === '/'")
    expect(source).toContain('aria-label="')
  })

  it('is reachable from the native stage by button and keyboard shortcut', () => {
    expect(stageSource).toContain("import SkillsWorkspace from '../components/SkillsWorkspace.vue'")
    expect(stageSource).toContain("event.key.toLowerCase() === 'k'")
    expect(stageSource).toContain('<SkillsWorkspace')
    expect(stageSource).toContain('@skills="skillsOpen = true"')
    expect(controlsSource).toContain('aria-label="打开技能卡册"')
    expect(controlsSource).toContain("emit('skills')")
  })
})
