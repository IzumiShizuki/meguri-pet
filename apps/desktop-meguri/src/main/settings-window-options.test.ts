import { describe, expect, it } from 'vitest'

import { createSettingsWindowOptions, resolveSettingsBounds } from './settings-window-options.mjs'

describe('settings window', () => {
  it('centers a 600x800 settings surface in the available work area', () => {
    expect(resolveSettingsBounds({ x: 100, y: 40, width: 1600, height: 1000 })).toEqual({
      x: 600,
      y: 140,
      width: 600,
      height: 800,
    })
  })

  it('uses a frameless reusable application surface', () => {
    const options = createSettingsWindowOptions({
      bounds: resolveSettingsBounds({ x: 0, y: 0, width: 1920, height: 1040 }),
      preloadPath: 'C:\\meguri\\preload.cjs',
    })
    expect(options).toMatchObject({ frame: false, transparent: false, show: false })
    expect(options.webPreferences.additionalArguments).toEqual(['--meguri-view=settings'])
  })
})
