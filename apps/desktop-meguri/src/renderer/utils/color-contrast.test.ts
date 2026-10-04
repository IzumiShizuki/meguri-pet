import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { contrastRatio } from './color-contrast'

const styles = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../styles.css'), 'utf8')

function darkToken(name: string) {
  const match = styles.match(new RegExp(`--${name}:\\s*(#[0-9a-f]{6})`, 'i'))
  if (!match?.[1])
    throw new Error(`Missing dark theme token: ${name}`)
  return match[1]
}

describe('dark theme contrast', () => {
  it('keeps body and input text readable on every dark surface', () => {
    expect(contrastRatio(darkToken('dark-text'), darkToken('dark-surface'))).toBeGreaterThanOrEqual(4.5)
    expect(contrastRatio(darkToken('dark-text-secondary'), darkToken('dark-surface'))).toBeGreaterThanOrEqual(4.5)
    expect(contrastRatio(darkToken('dark-placeholder'), darkToken('dark-input'))).toBeGreaterThanOrEqual(4.5)
  })

  it('separates the dark canvas, surfaces, borders, and amber controls', () => {
    expect(contrastRatio(darkToken('dark-surface'), darkToken('dark-canvas'))).toBeGreaterThanOrEqual(1.3)
    expect(contrastRatio(darkToken('dark-border'), darkToken('dark-surface'))).toBeGreaterThanOrEqual(1.3)
    expect(contrastRatio('#fbbf24', darkToken('dark-input'))).toBeGreaterThanOrEqual(3)
  })
})
