import { describe, expect, it } from 'vitest'

import { createStageLayoutController, fitStageLayoutBounds, resolveInitialStageLayout, stageLayoutMode } from './stage-layout.mjs'
import { ambientPortraitCenter, ambientPortraitTop, compactStageSize, expandedStageSize, galgameStageSize, stageGeometryVersion } from '../shared/stage-geometry.mjs'

const workArea = { x: 0, y: 0, width: 1920, height: 1040 }
const compactPreferences = { chat: { visible: true, expanded: false } }
const initialBounds: { x: number, y: number, width: number, height: number } = { x: 400, y: 160, ...compactStageSize }

function harness(start = initialBounds, area = workArea, panelSize: { width: number, height: number } = expandedStageSize) {
  let bounds = { ...start }
  const writes: Record<string, unknown>[] = []
  const controller = createStageLayoutController({
    window: {
      isDestroyed: () => false,
      getBounds: () => ({ ...bounds }),
      setBounds: (next: typeof bounds) => { bounds = { ...next } },
      setIgnoreMouseEvents: () => {},
    },
    initial: { mode: 'compact', expandedSize: { ...panelSize }, galgameSize: { ...galgameStageSize } },
    matchingWorkArea: () => area,
    writeState: (state: Record<string, unknown>) => { writes.push(state) },
  })
  return { controller, writes, bounds: () => bounds, resize: (next: typeof bounds) => { bounds = next } }
}

describe('desktop bubble window layout', () => {
  it('fits temporary tools without changing the saved collapsed-history preference', () => {
    expect(stageLayoutMode(compactPreferences, 'ambient-panel')).toBe('expanded')
    expect(stageLayoutMode({ chat: { visible: false, expanded: true } })).toBe('compact')
    const host = harness()
    host.controller.fit(compactPreferences, 'ambient-panel')
    expect(host.bounds()).toEqual({ ...initialBounds, ...expandedStageSize })
    host.controller.fit(compactPreferences)
    expect(host.bounds()).toEqual(initialBounds)
  })

  it('does not accumulate scale, position, or empty expanded bounds over repeated toggles', () => {
    const host = harness()
    for (let index = 0; index < 12; index++) {
      host.controller.fit(compactPreferences, 'ambient-panel')
      expect(host.bounds()).toEqual({ ...initialBounds, ...expandedStageSize })
      host.controller.fit(compactPreferences)
      expect(host.bounds()).toEqual(initialBounds)
    }
    expect(host.writes.at(-1)?.stageGeometryVersion).toBe(stageGeometryVersion)
  })

  it.each([
    { name: 'right edge', start: { ...initialBounds, x: 1480 }, area: workArea, size: expandedStageSize },
    { name: 'bottom edge', start: { ...initialBounds, y: 470 }, area: workArea, size: { width: 800, height: 800 } },
    { name: 'negative display', start: { ...initialBounds, x: -450 }, area: { x: -1280, y: 0, width: 1280, height: 1040 }, size: expandedStageSize },
    { name: 'narrow display', start: { ...initialBounds, x: -1110 }, area: { x: -1280, y: 0, width: 600, height: 800 }, size: expandedStageSize },
  ])('restores compact placement at the $name over repeated panel and Galgame cycles', ({ start, area, size }) => {
    const host = harness(start, area, size)
    for (let index = 0; index < 12; index++) {
      host.controller.fit(compactPreferences, 'ambient-panel')
      if (index % 2 === 0) {
        host.controller.fit(compactPreferences, 'galgame')
        host.controller.fit(compactPreferences, 'ambient-panel')
      }
      host.controller.fit(compactPreferences)
      expect(host.bounds()).toEqual(start)
    }
  })

  it('retains deliberate movement while undoing automatic screen-edge fitting', () => {
    const start = { ...initialBounds, x: 1480 }
    const host = harness(start)
    host.controller.fit(compactPreferences, 'ambient-panel')
    host.resize({ ...host.bounds(), x: host.bounds().x - 90, y: host.bounds().y + 25 })
    host.controller.fit(compactPreferences)
    expect(host.bounds()).toEqual({ ...start, x: start.x - 90, y: start.y + 25 })
  })

  it('does not compound native compact-size rounding after edge-clamped panels', () => {
    const start = { ...initialBounds, x: 1480, width: compactStageSize.width + 1 }
    const host = harness(start)
    for (let index = 0; index < 12; index++) {
      host.controller.fit(compactPreferences, 'ambient-panel')
      host.controller.fit(compactPreferences)
      expect(host.bounds().x).toBe(start.x)
      host.resize({ ...host.bounds(), width: compactStageSize.width + 1 })
    }
  })

  it('does not refit an already-compact window for fractional-DPI rounding', () => {
    const host = harness()
    const rounded = { ...initialBounds, width: 392, height: 561 }
    host.resize(rounded)
    host.controller.fit(compactPreferences)
    expect(host.bounds()).toEqual(rounded)
    expect(host.writes).toHaveLength(0)
  })

  it('preserves the portrait anchor and expanded size after a manual resize', () => {
    const host = harness()
    host.controller.fit(compactPreferences, 'ambient-panel')
    host.resize({ ...initialBounds, width: 940, height: 760 })
    const before = host.bounds()
    host.controller.fit(compactPreferences)
    const collapsed = host.bounds()
    expect(collapsed.x + ambientPortraitCenter(collapsed.width)).toBe(before.x + ambientPortraitCenter(before.width, true))
    expect(collapsed.y + ambientPortraitTop(collapsed.height)).toBe(before.y + ambientPortraitTop(before.height))
    expect([collapsed.width, collapsed.height]).toEqual([390, 560])
    host.controller.fit(compactPreferences, 'ambient-panel')
    expect(host.bounds()).toEqual(before)
  })

  it('migrates tall pre-bubble dimensions while keeping chat compact', () => {
    const legacy = { stageBounds: { ...initialBounds, width: 440, height: 900 }, stageLayoutMode: 'expanded', stageExpandedSize: { width: 440, height: 900 } }
    const migrated = resolveInitialStageLayout(legacy, compactPreferences, [workArea], workArea, () => workArea)
    expect(migrated.expandedSize).toEqual(expandedStageSize)
    expect([migrated.bounds.width, migrated.bounds.height]).toEqual([390, 560])
    const restored = resolveInitialStageLayout({ ...legacy, stageGeometryVersion, stageExpandedSize: { width: 920, height: 640 } }, compactPreferences, [workArea], workArea, () => workArea)
    expect([restored.bounds.width, restored.bounds.height]).toEqual([390, 560])
  })

  it('clamps panels on negative-coordinate and narrow displays without applying display scale twice', () => {
    const narrow = { x: -1280, y: 0, width: 600, height: 800 }
    const result = fitStageLayoutBounds({ ...initialBounds, x: -900 }, { from: 'compact', to: 'expanded', size: expandedStageSize, workArea: narrow })
    expect(result).toEqual({ x: -1280, y: 160, width: 600, height: 560 })
    expect(ambientPortraitCenter(result.width, true)).toBe(300)
    expect(ambientPortraitTop(result.height)).toBe(10)
  })

  it('restores compact and temporary expanded ambient windows when exiting Galgame', () => {
    for (const surface of ['ambient', 'ambient-panel']) {
      const host = harness()
      host.controller.fit(compactPreferences, surface)
      const before = { ...host.bounds() }
      host.controller.fit(compactPreferences, 'galgame')
      expect([host.bounds().width, host.bounds().height]).toEqual([860, 720])
      host.controller.fit(compactPreferences, surface)
      expect(host.bounds()).toEqual(before)
    }
  })
})
