export type ExpressionIntensity = 'low' | 'medium' | 'high'

export interface CharacterPresentation {
  expressionTag: string
  intensity: ExpressionIntensity
  outfitCode: string
  spriteUrl: string
  motionTag?: string
}

const gatewayOrigin = import.meta.env.VITE_MEGURI_GATEWAY_ORIGIN ?? 'http://127.0.0.1:15173'
const safeSpriteFile = /^ce\d{5}[lm]\.png$/i
const expressionCodes: Record<string, Record<ExpressionIntensity, string>> = {
  neutral: { low: '001', medium: '001', high: '004' },
  happy: { low: '002', medium: '003', high: '202' },
  affectionate: { low: '022', medium: '010', high: '102' },
  excited: { low: '003', medium: '202', high: '206' },
  angry: { low: '012', medium: '014', high: '006' },
  confused: { low: '104', medium: '015', high: '211' },
  embarrassed: { low: '010', medium: '102', high: '211' },
  sad: { low: '016', medium: '015', high: '013' },
  sleepy: { low: '023', medium: '013', high: '005' },
  surprised: { low: '022', medium: '011', high: '211' },
  teasing: { low: '020', medium: '021', high: '006' },
  worried: { low: '009', medium: '016', high: '015' },
}

export const defaultCharacterState: CharacterPresentation = {
  expressionTag: 'neutral',
  intensity: 'low',
  outfitCode: '04',
  spriteUrl: spriteUrl('ce04001l.png'),
}

/** Maps a canonical cue to a safe gateway asset, retaining a neutral fallback. */
export function presentationFromCue(
  current: CharacterPresentation,
  cue: Record<string, unknown>,
): CharacterPresentation {
  const outfitCode = typeof cue.outfit_code === 'string' && /^0[1-6]$/.test(cue.outfit_code)
    ? cue.outfit_code
    : current.outfitCode
  const expressionTag = typeof cue.expression_tag === 'string' && expressionCodes[cue.expression_tag]
    ? cue.expression_tag
    : current.expressionTag in expressionCodes ? current.expressionTag : 'neutral'
  const intensity = isIntensity(cue.expression_intensity) ? cue.expression_intensity : current.intensity
  const resolvedFile = typeof cue.sprite_file === 'string' && safeSpriteFile.test(cue.sprite_file)
    ? cue.sprite_file
    : `ce${outfitCode}${expressionCodes[expressionTag]?.[intensity] ?? '001'}l.png`
  return {
    expressionTag,
    intensity,
    outfitCode,
    spriteUrl: spriteUrl(resolvedFile),
    motionTag: typeof cue.motion_tag === 'string' && cue.motion_tag.trim() ? cue.motion_tag.trim() : current.motionTag,
  }
}

export function spriteUrl(file: string): string {
  const safeFile = safeSpriteFile.test(file) ? file : 'ce04001l.png'
  return `${gatewayOrigin}/assets/sprites/meguri/${encodeURIComponent(safeFile)}`
}

function isIntensity(value: unknown): value is ExpressionIntensity {
  return value === 'low' || value === 'medium' || value === 'high'
}
