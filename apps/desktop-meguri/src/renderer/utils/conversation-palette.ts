import type { DesktopColorPalette } from '../../shared/experience-contract'
import { contrastRatio } from './color-contrast'

const accents = {
  amber: ['#a65312', '#fbbf24'], rose: ['#b52c62', '#fda4af'], mint: ['#24735f', '#6ee7b7'],
  blue: ['#0369a1', '#7dd3fc'], violet: ['#6d28d9', '#c4b5fd'],
} as const

function mix(color: string, base: string, weight: number): string {
  const channels = [1, 3, 5].map(index => Math.round(parseInt(color.slice(index, index + 2), 16) * weight + parseInt(base.slice(index, index + 2), 16) * (1 - weight)))
  return `#${channels.map(value => value.toString(16).padStart(2, '0')).join('')}`
}

// System accent colors may be arbitrarily bright or dark. Keep text roles
// readable on every opaque companion surface while preserving their hue.
function readable(color: string, surfaces: string[], dark: boolean): string {
  let result = color
  for (let step = 0; step < 24 && surfaces.some(surface => contrastRatio(result, surface) < 4.5); step++)
    result = mix(result, dark ? '#ffffff' : '#000000', .9)
  return result
}

export function conversationPalette(palette: DesktopColorPalette, dark: boolean, systemAccent?: string) {
  const preset = accents[palette === 'system' ? 'blue' : palette] ?? accents.mint
  const accent = palette === 'system' && /^#[0-9a-f]{6}$/iu.test(systemAccent ?? '') ? systemAccent! : preset[0]
  const surface = mix(accent, dark ? '#202a27' : '#f8fcfa', dark ? .08 : .035)
  const bubble = dark ? '#2b3732' : '#ffffff'
  const input = dark ? '#26342e' : '#ffffff'
  const file = mix(accent, dark ? '#293b33' : '#edf6f1', dark ? .13 : .08)
  const userBubble = mix(accent, dark ? '#293b33' : '#eff7f3', dark ? .18 : .13)
  const hover = mix(accent, dark ? '#2d4037' : '#e8f3ed', dark ? .16 : .1)
  const surfaces = [surface, bubble, input, file, userBubble, hover]
  const color = readable(dark ? mix(accent, '#ffffff', .42) : accent, surfaces, dark)
  const text = readable(dark ? '#edf7f1' : '#243b33', surfaces, dark)
  const muted = readable(dark ? '#becfc5' : '#53675e', surfaces, dark)
  const actionText = contrastRatio('#ffffff', color) >= contrastRatio('#172a21', color) ? '#ffffff' : '#172a21'
  return {
    accent: color,
    surface,
    bubble,
    userBubble,
    text,
    muted,
    input,
    placeholder: readable(dark ? '#b3c8bb' : '#5a6c62', [input], dark),
    file,
    fileText: color,
    hover,
    actionText,
    border: mix(accent, '#81958a', .12),
    shadow: dark ? 'rgba(0, 0, 0, .28)' : 'rgba(38, 72, 53, .16)',
    danger: dark ? '#ffb9b5' : '#a52c34',
    dangerSurface: dark ? '#4b292b' : '#ffeded',
    dangerAction: dark ? '#ffb9b5' : '#a52c34',
    dangerActionText: dark ? '#352023' : '#ffffff',
    success: readable(dark ? '#a9dfbf' : '#276346', surfaces, dark),
  }
}
