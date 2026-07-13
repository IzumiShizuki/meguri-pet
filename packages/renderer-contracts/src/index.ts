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
