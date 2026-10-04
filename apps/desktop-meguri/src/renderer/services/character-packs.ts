import type {
  CharacterPackCatalog,
  CharacterPackPreviewResult,
  CharacterPackResult,
} from '../../shared/desktop-contract'

export class CharacterPackApiError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'CharacterPackApiError'
  }
}

function bridge() {
  const api = window.meguriDesktop?.characterPacks
  if (!api)
    throw new CharacterPackApiError('角色工作室桥接不可用，请重启 Meguri。')
  return api
}

function requireCatalog(result: CharacterPackResult): CharacterPackResult {
  if (!result.ok || !result.catalog)
    throw new CharacterPackApiError(result.error || '角色包操作没有完成。')
  return result
}

export const characterPacksApi = {
  async list(): Promise<CharacterPackCatalog> {
    return bridge().list()
  },
  async importDirectory(): Promise<CharacterPackResult> {
    return requireCatalog(await bridge().importDirectory())
  },
  async addResource(draftId: string): Promise<CharacterPackResult> {
    return requireCatalog(await bridge().addResource(draftId))
  },
  async publish(draftId: string): Promise<CharacterPackResult> {
    return requireCatalog(await bridge().publish(draftId))
  },
  async activate(packageId: string): Promise<CharacterPackResult> {
    return requireCatalog(await bridge().activate(packageId))
  },
  async disable(packageId: string): Promise<CharacterPackResult> {
    return requireCatalog(await bridge().disable(packageId))
  },
  async restore(packageId: string): Promise<CharacterPackResult> {
    return requireCatalog(await bridge().restore(packageId))
  },
  async preview(packageId: string, resourceId: string): Promise<CharacterPackPreviewResult> {
    const result = await bridge().preview(packageId, resourceId)
    if (!result.ok)
      throw new CharacterPackApiError(result.error || '角色资源预览不可用。')
    return result
  },
}
