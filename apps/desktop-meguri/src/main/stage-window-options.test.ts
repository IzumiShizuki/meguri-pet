import { describe, expect, it } from 'vitest'

import { createStageWindowOptions } from './stage-window-options.mjs'

describe('createStageWindowOptions', () => {
  it('keeps the Windows host composited while allowing transparent page regions', () => {
    const options = createStageWindowOptions({
      bounds: { x: 100, y: 40, width: 440, height: 720 },
      preloadPath: 'C:\\meguri\\preload.cjs',
    })

    expect(options.transparent).toBe(true)
    expect(options.alwaysOnTop).toBe(true)
    expect(options).not.toHaveProperty('backgroundColor')
    expect(options.show).toBe(false)
    expect(options.webPreferences).toMatchObject({
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false,
      additionalArguments: ['--meguri-view=stage'],
    })
  })
})
