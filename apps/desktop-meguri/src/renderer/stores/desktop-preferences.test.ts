import { describe, expect, it } from 'vitest'

import { parseLegacyPresentation } from './desktop-preferences'

describe('legacy presentation preferences', () => {
  it('maps the first native renderer preferences to the host schema', () => {
    expect(parseLegacyPresentation(JSON.stringify({
      chatVisible: false,
      chatExpanded: true,
      outfitCode: '03',
      reducedMotion: true,
    }))).toEqual({
      chat: { visible: false, expanded: true },
      character: { outfitCode: '03', reducedMotion: true },
    })
  })

  it('ignores malformed legacy values', () => {
    expect(parseLegacyPresentation('{bad json')).toEqual({})
    expect(parseLegacyPresentation(JSON.stringify({ outfitCode: '../bad' }))).toEqual({})
  })
})
