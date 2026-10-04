import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { createStudioWindowOptions, resolveStudioBounds } from './studio-window-options.mjs'

describe('character studio window', () => {
  it('centers a work-focused Studio surface within the available display', () => {
    expect(resolveStudioBounds({ x: 100, y: 40, width: 1600, height: 1000 })).toEqual({
      x: 310,
      y: 140,
      width: 1180,
      height: 800,
    })
  })

  it('uses a framed-by-renderer, opaque Studio view with the narrow preload', () => {
    const options = createStudioWindowOptions({
      bounds: resolveStudioBounds({ x: 0, y: 0, width: 1920, height: 1040 }),
      preloadPath: 'C:\\meguri\\preload.cjs',
    })
    expect(options).toMatchObject({ frame: false, transparent: false, show: false })
    expect(options.webPreferences.additionalArguments).toEqual(['--meguri-view=studio'])
  })

  it('locks the Studio renderer to its expected view before accepting package IPC', () => {
    const main = readFileSync(resolve(import.meta.dirname, 'index.mjs'), 'utf8')
    expect(main).toContain("lockWindowNavigation(studioWindow, 'studio')")
    expect(main).toContain("isExpectedRendererLocation(url, 'studio')")
  })
})
