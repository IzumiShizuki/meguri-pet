import { defineStore } from 'pinia'

import {
  SkillApiError,
  skillsApi,
  type SkillCatalogView,
  type SkillSearchPage,
  type SkillSourceDetail,
  type SkillSummary,
  type SkillUpdateStatus,
} from '../services/skills-api'

export type SkillsTab = 'discover' | 'installed'
export type SkillOperation = '' | 'import' | 'activate' | 'disable' | 'check-update' | 'import-update'

function message(error: unknown): string {
  if (error instanceof SkillApiError) {
    if (error.code === 'SKILL_SOURCE_DISABLED')
      return '魔搭来源尚未启用。本地已安装 Skill 仍可查看、停用和回滚。'
    return `${error.message}（${error.code}）`
  }
  return error instanceof Error ? error.message : String(error)
}

export const useSkillsStore = defineStore('skills', {
  state: () => ({
    tab: 'discover' as SkillsTab,
    query: '',
    searchPage: undefined as SkillSearchPage | undefined,
    selectedRemoteId: undefined as string | undefined,
    remoteDetail: undefined as SkillSourceDetail | undefined,
    selectedInstalledId: undefined as string | undefined,
    installed: [] as SkillCatalogView[],
    updates: {} as Record<string, SkillUpdateStatus>,
    searching: false,
    loadingDetail: false,
    loadingInstalled: false,
    operation: '' as SkillOperation,
    operationTarget: '',
    error: '',
    notice: '',
  }),
  getters: {
    results: state => state.searchPage?.items ?? [],
    selectedInstalled(state): SkillCatalogView | undefined {
      return state.installed.find(skill => skill.manifest.id === state.selectedInstalledId)
    },
    selectedRemote(state): SkillSummary | undefined {
      return state.searchPage?.items.find(skill => skill.external_id === state.selectedRemoteId)
    },
    busy: state => Boolean(state.operation),
  },
  actions: {
    clearFeedback() {
      this.error = ''
      this.notice = ''
    },
    async loadInstalled(sessionId: string) {
      this.loadingInstalled = true
      this.error = ''
      try {
        this.installed = await skillsApi.list(sessionId)
        if (this.selectedInstalledId && !this.installed.some(skill => skill.manifest.id === this.selectedInstalledId))
          this.selectedInstalledId = undefined
      }
      catch (error) {
        this.error = message(error)
      }
      finally {
        this.loadingInstalled = false
      }
    },
    async search(sessionId: string, page = 1) {
      this.searching = true
      this.error = ''
      try {
        this.searchPage = await skillsApi.search(sessionId, this.query.trim(), page)
        const first = this.searchPage.items[0]
        if (!this.selectedRemoteId || !this.searchPage.items.some(item => item.external_id === this.selectedRemoteId))
          this.selectedRemoteId = first?.external_id
        if (this.selectedRemoteId)
          await this.loadRemoteDetail(sessionId, this.selectedRemoteId)
      }
      catch (error) {
        this.searchPage = undefined
        this.remoteDetail = undefined
        this.error = message(error)
      }
      finally {
        this.searching = false
      }
    },
    async loadRemoteDetail(sessionId: string, externalId: string) {
      this.selectedRemoteId = externalId
      this.loadingDetail = true
      this.error = ''
      try {
        this.remoteDetail = await skillsApi.detail(sessionId, externalId)
      }
      catch (error) {
        this.remoteDetail = undefined
        this.error = message(error)
      }
      finally {
        this.loadingDetail = false
      }
    },
    async importRemote(sessionId: string, externalId: string) {
      await this.mutate('import', externalId, async () => {
        const imported = await skillsApi.importSkill(sessionId, externalId)
        this.replaceInstalled(imported)
        this.selectedInstalledId = imported.manifest.id
        this.tab = 'installed'
        this.notice = '导入与安全校验完成。请检查报告后再启用。'
      })
    },
    async activate(sessionId: string, skillId: string, digest: string, licenseConfirmed: boolean) {
      await this.mutate('activate', digest, async () => {
        const updated = await skillsApi.activate(sessionId, skillId, digest, licenseConfirmed)
        this.replaceInstalled(updated)
        this.notice = updated.revisions.find(revision => revision.digest === digest)?.active
          ? '已启用所选版本。新 AGENT Turn 可按相关性读取它。'
          : '版本状态已更新。'
      })
    },
    async disable(sessionId: string, skillId: string) {
      await this.mutate('disable', skillId, async () => {
        this.replaceInstalled(await skillsApi.disable(sessionId, skillId))
        this.notice = 'Skill 已停用；进行中的 Turn 仍保留原冻结版本。'
      })
    },
    async checkUpdate(sessionId: string, skillId: string) {
      await this.mutate('check-update', skillId, async () => {
        this.updates[skillId] = await skillsApi.checkUpdate(sessionId, skillId)
        this.notice = this.updates[skillId].update_available ? '发现新版本，导入后仍需手动启用。' : '当前已是最新版本。'
      })
    },
    async importUpdate(sessionId: string, skillId: string) {
      await this.mutate('import-update', skillId, async () => {
        this.replaceInstalled(await skillsApi.importUpdate(sessionId, skillId))
        this.notice = '新版本已导入并完成校验，当前启用版本未改变。'
      })
    },
    replaceInstalled(skill: SkillCatalogView) {
      const index = this.installed.findIndex(item => item.manifest.id === skill.manifest.id)
      if (index < 0)
        this.installed.push(skill)
      else
        this.installed.splice(index, 1, skill)
      this.installed.sort((left, right) => left.manifest.name.localeCompare(right.manifest.name, 'zh-CN'))
    },
    async mutate(kind: SkillOperation, target: string, action: () => Promise<void>) {
      if (this.operation)
        return
      this.operation = kind
      this.operationTarget = target
      this.clearFeedback()
      try {
        await action()
      }
      catch (error) {
        this.error = message(error)
      }
      finally {
        this.operation = ''
        this.operationTarget = ''
      }
    },
  },
})
