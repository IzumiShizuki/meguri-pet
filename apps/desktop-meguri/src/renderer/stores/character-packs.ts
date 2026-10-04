import type {
  CharacterPackCatalog,
  CharacterPackResource,
  CharacterPackResult,
  CharacterPackSummary,
} from '../../shared/desktop-contract'
import { defineStore } from 'pinia'

import { CharacterPackApiError, characterPacksApi } from '../services/character-packs'

export type CharacterPackOperation = '' | 'import' | 'add-resource' | 'publish' | 'activate' | 'disable' | 'restore'

const emptyCatalog = (): CharacterPackCatalog => ({ version: 1, drafts: [], packages: [] })

function feedback(error: unknown): string {
  return error instanceof CharacterPackApiError || error instanceof Error
    ? error.message
    : String(error)
}

function packsFrom(catalog: CharacterPackCatalog): CharacterPackSummary[] {
  return [...catalog.drafts, ...catalog.packages]
}

function firstPreviewable(pack: CharacterPackSummary | undefined): CharacterPackResource | undefined {
  return pack?.resources.find(resource => resource.status === 'ready')
}

export const useCharacterPacksStore = defineStore('character-packs', {
  state: () => ({
    catalog: emptyCatalog(),
    selectedPackId: '' as string,
    selectedResourceId: '' as string,
    previewDataUrl: '' as string,
    previewError: '' as string,
    loading: false,
    operation: '' as CharacterPackOperation,
    operationTarget: '' as string,
    error: '' as string,
    notice: '' as string,
  }),
  getters: {
    packs: state => packsFrom(state.catalog),
    selectedPack(state): CharacterPackSummary | undefined {
      return packsFrom(state.catalog).find(pack => pack.id === state.selectedPackId)
    },
    selectedResource(): CharacterPackResource | undefined {
      return this.selectedPack?.resources.find(resource => resource.id === this.selectedResourceId)
    },
    busy: state => Boolean(state.operation),
  },
  actions: {
    clearFeedback() {
      this.error = ''
      this.notice = ''
    },
    applyCatalog(catalog: CharacterPackCatalog, preferredPackId = '') {
      this.catalog = catalog
      const packs = packsFrom(catalog)
      const selected = packs.find(pack => pack.id === preferredPackId)
        ?? packs.find(pack => pack.id === this.selectedPackId)
        ?? catalog.drafts[0]
        ?? packs[0]
      this.selectedPackId = selected?.id ?? ''
      const resource = selected?.resources.find(item => item.id === this.selectedResourceId)
        ?? firstPreviewable(selected)
      this.selectedResourceId = resource?.id ?? ''
      this.previewDataUrl = ''
      this.previewError = ''
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        this.applyCatalog(await characterPacksApi.list())
        await this.loadPreview()
      }
      catch (error) {
        this.error = feedback(error)
      }
      finally {
        this.loading = false
      }
    },
    async selectPack(packageId: string) {
      this.selectedPackId = packageId
      const resource = firstPreviewable(this.selectedPack)
      this.selectedResourceId = resource?.id ?? ''
      this.previewDataUrl = ''
      this.previewError = ''
      await this.loadPreview()
    },
    async selectResource(resourceId: string) {
      this.selectedResourceId = resourceId
      this.previewDataUrl = ''
      this.previewError = ''
      await this.loadPreview()
    },
    async loadPreview() {
      const pack = this.selectedPack
      const resource = this.selectedResource
      this.previewDataUrl = ''
      this.previewError = ''
      if (!pack || !resource)
        return
      if (resource.status !== 'ready') {
        this.previewError = resource.validationMessage || '该资源暂不可预览。'
        return
      }
      try {
        const preview = await characterPacksApi.preview(pack.id, resource.id)
        if (!preview.dataUrl) {
          this.markSelectedResourceUnavailable('资源没有提供可用预览。')
          return
        }
        this.previewDataUrl = preview.dataUrl
      }
      catch (error) {
        this.markSelectedResourceUnavailable(feedback(error))
      }
    },
    markPreviewUnavailable() {
      this.markSelectedResourceUnavailable('资源无法在当前桌面环境中显示。')
    },
    markSelectedResourceUnavailable(message: string) {
      this.previewDataUrl = ''
      this.previewError = message
      const resource = this.selectedResource
      if (!resource)
        return
      resource.status = 'unavailable'
      resource.validationMessage = message
    },
    async importDirectory() {
      await this.mutate('import', '', async () => {
        const result = await characterPacksApi.importDirectory()
        this.applyResult(result)
        this.notice = '角色包已作为草稿导入。请预览资源后再发布。'
        await this.loadPreview()
      })
    },
    async addResource(draftId: string) {
      await this.mutate('add-resource', draftId, async () => {
        const result = await characterPacksApi.addResource(draftId)
        this.applyResult(result)
        this.notice = '资源已加入草稿，尚未影响当前已发布角色包。'
        await this.loadPreview()
      })
    },
    async publish(draftId: string) {
      await this.mutate('publish', draftId, async () => {
        const result = await characterPacksApi.publish(draftId)
        this.applyResult(result)
        this.notice = '角色包已发布并启用；新的表现将使用这个版本。'
        await this.loadPreview()
      })
    },
    async activate(packageId: string) {
      await this.mutate('activate', packageId, async () => {
        const result = await characterPacksApi.activate(packageId)
        this.applyResult(result)
        this.notice = '角色包已启用；正在进行的表现不会被替换。'
      })
    },
    async disable(packageId: string) {
      await this.mutate('disable', packageId, async () => {
        const result = await characterPacksApi.disable(packageId)
        this.applyResult(result)
        this.notice = '角色包已停用；已解析的表现资源会保留到当前交互结束。'
      })
    },
    async restore(packageId: string) {
      await this.mutate('restore', packageId, async () => {
        const result = await characterPacksApi.restore(packageId)
        this.applyResult(result)
        this.notice = '已恢复并启用选定角色包。'
      })
    },
    applyResult(result: CharacterPackResult) {
      if (!result.catalog)
        throw new CharacterPackApiError('角色包目录没有返回。')
      this.applyCatalog(result.catalog, result.pack?.id)
    },
    async mutate(kind: CharacterPackOperation, target: string, action: () => Promise<void>) {
      if (this.operation)
        return
      this.operation = kind
      this.operationTarget = target
      this.clearFeedback()
      try {
        await action()
      }
      catch (error) {
        this.error = feedback(error)
      }
      finally {
        this.operation = ''
        this.operationTarget = ''
      }
    },
  },
})
