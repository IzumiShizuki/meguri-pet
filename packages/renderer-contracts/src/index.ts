export type ExpressionIntensity = 'low' | 'medium' | 'high'

export interface LipSyncCue {
  text?: string
  audioUrl?: string
  durationMs?: number
}

export interface CharacterRenderer {
  loadCharacter(characterId: string): Promise<void>
  setOutfit(outfitCode: string): Promise<void>
  setExpression(tag: string, intensity: ExpressionIntensity): Promise<void>
  speak(cue: LipSyncCue): Promise<void>
  playMotion(motionTag: string): Promise<void>
  resetToIdle(): Promise<void>
}

export interface PngAssetEntry {
  characterId: string
  outfitCode: string
  expressionTag: string
  intensity: ExpressionIntensity
  spriteFile: string
}

export interface CanonicalExpressionRow {
  outfit_code?: unknown
  expression_tag?: unknown
  expression_intensity?: unknown
  project_path?: unknown
  size?: unknown
  excluded_default?: unknown
  build_id?: unknown
}

export interface CanonicalCatalogOptions {
  expectedBuildId: string
  resolveSpritePath?: (projectPath: string) => string
  assetExists?: (spritePath: string) => boolean
}

export function pngCatalogFromExpressionMap(
  value: unknown,
  options: CanonicalCatalogOptions,
): PngAssetEntry[] {
  if (!Array.isArray(value))
    throw new TypeError('canonical expression map must be an array')
  const catalog: PngAssetEntry[] = []
  for (const item of value) {
    if (!isExpressionRow(item) || item.excluded_default === true || item.size !== 'l')
      continue
    if (item.build_id !== options.expectedBuildId)
      throw new Error(`expression map build_id mismatch: ${String(item.build_id)}`)
    if (!['01', '02', '03', '04', '05', '06'].includes(item.outfit_code))
      continue
    if (!isExpressionIntensity(item.expression_intensity))
      continue
    const spritePath = options.resolveSpritePath?.(item.project_path) ?? item.project_path
    if (options.assetExists && !options.assetExists(spritePath))
      throw new Error(`canonical PNG asset is missing: ${spritePath}`)
    catalog.push({
      characterId: 'meguri',
      outfitCode: item.outfit_code,
      expressionTag: item.expression_tag,
      intensity: item.expression_intensity,
      spriteFile: spritePath,
    })
  }
  if (catalog.length === 0)
    throw new Error('canonical expression map contains no enabled large PNG assets')
  for (const outfitCode of ['01', '02', '03', '04']) {
    if (!catalog.some(entry => entry.outfitCode === outfitCode && entry.expressionTag === 'neutral'))
      throw new Error(`canonical expression map has no neutral fallback for outfit ${outfitCode}`)
  }
  return catalog
}

export interface PngRenderSnapshot {
  characterId?: string
  outfitCode?: string
  expressionTag: string
  intensity: ExpressionIntensity
  spriteFile?: string
  motionTag?: string
  speaking: boolean
}

export type RenderListener = (snapshot: Readonly<PngRenderSnapshot>) => void

export class PngRenderer implements CharacterRenderer {
  private readonly catalog: PngAssetEntry[]
  private readonly listener?: RenderListener
  private snapshotState: PngRenderSnapshot = {
    expressionTag: 'neutral',
    intensity: 'low',
    speaking: false,
  }

  constructor(catalog: PngAssetEntry[], listener?: RenderListener) {
    this.catalog = [...catalog]
    this.listener = listener
  }

  async loadCharacter(characterId: string): Promise<void> {
    if (!this.catalog.some(entry => entry.characterId === characterId))
      throw new Error(`unknown character: ${characterId}`)
    this.snapshotState.characterId = characterId
    const defaultOutfit = this.catalog.find(
      entry => entry.characterId === characterId && entry.expressionTag === 'neutral',
    )?.outfitCode
    if (!defaultOutfit)
      throw new Error(`character has no neutral PNG: ${characterId}`)
    this.snapshotState.outfitCode = defaultOutfit
    await this.setExpression('neutral', 'low')
  }

  async setOutfit(outfitCode: string): Promise<void> {
    this.requireLoaded()
    if (!['01', '02', '03', '04', '05', '06'].includes(outfitCode))
      throw new Error(`outfit is disabled or unknown: ${outfitCode}`)
    const available = this.catalog.some(
      entry => entry.characterId === this.snapshotState.characterId
        && entry.outfitCode === outfitCode,
    )
    if (!available)
      throw new Error(`outfit has no PNG assets: ${outfitCode}`)
    this.snapshotState.outfitCode = outfitCode
    await this.setExpression(this.snapshotState.expressionTag, this.snapshotState.intensity)
  }

  async setExpression(tag: string, intensity: ExpressionIntensity): Promise<void> {
    this.requireLoaded()
    const chosen = this.resolveAsset(tag, intensity)
    this.snapshotState.expressionTag = chosen.expressionTag
    this.snapshotState.intensity = chosen.intensity
    this.snapshotState.spriteFile = chosen.spriteFile
    this.emit()
  }

  async speak(_cue: LipSyncCue): Promise<void> {
    this.requireLoaded()
    this.snapshotState.speaking = true
    this.emit()
  }

  async playMotion(motionTag: string): Promise<void> {
    this.requireLoaded()
    this.snapshotState.motionTag = motionTag
    this.emit()
  }

  async resetToIdle(): Promise<void> {
    this.requireLoaded()
    this.snapshotState.motionTag = undefined
    this.snapshotState.speaking = false
    await this.setExpression('neutral', 'low')
  }

  snapshot(): Readonly<PngRenderSnapshot> {
    return { ...this.snapshotState }
  }

  private resolveAsset(tag: string, intensity: ExpressionIntensity): PngAssetEntry {
    const base = this.catalog.filter(
      entry => entry.characterId === this.snapshotState.characterId
        && entry.outfitCode === this.snapshotState.outfitCode,
    )
    return base.find(entry => entry.expressionTag === tag && entry.intensity === intensity)
      ?? base.find(entry => entry.expressionTag === tag)
      ?? base.find(entry => entry.expressionTag === 'neutral' && entry.intensity === 'low')
      ?? base.find(entry => entry.expressionTag === 'neutral')
      ?? (() => { throw new Error('current outfit has no neutral fallback') })()
  }

  private requireLoaded(): void {
    if (!this.snapshotState.characterId || !this.snapshotState.outfitCode)
      throw new Error('loadCharacter must be called first')
  }

  private emit(): void {
    this.listener?.(this.snapshot())
  }
}

/**
 * Future AIRI implementation boundary. The implementation belongs in AIRI
 * and should delegate to @proj-airi/stage-ui-live2d.
 */
export interface AiriLive2DRenderer extends CharacterRenderer {
  readonly rendererKind: 'airi-live2d'
}

function isExpressionRow(value: unknown): value is Required<Pick<
  CanonicalExpressionRow,
  'outfit_code' | 'expression_tag' | 'expression_intensity' | 'project_path' | 'size' | 'build_id'
>> & CanonicalExpressionRow {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    return false
  const row = value as Record<string, unknown>
  return typeof row.outfit_code === 'string'
    && typeof row.expression_tag === 'string'
    && typeof row.expression_intensity === 'string'
    && typeof row.project_path === 'string'
    && typeof row.size === 'string'
    && typeof row.build_id === 'string'
}

function isExpressionIntensity(value: unknown): value is ExpressionIntensity {
  return value === 'low' || value === 'medium' || value === 'high'
}
