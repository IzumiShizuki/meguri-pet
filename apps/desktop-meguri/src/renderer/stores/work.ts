import { defineStore } from 'pinia'

import type {
  ForwardRequest,
  WorkPrepareInput,
  WorkProject,
  WorkSnapshot,
  WorkTarget,
} from '../../shared/desktop-contract'

function bridge() {
  return window.meguriDesktop?.work
}

const emptySnapshot = (): WorkSnapshot => ({
  requests: [],
  draft: null,
  connection: { codex: 'disabled', harness: 'disabled' },
  executionAvailable: false,
})

export const useWorkStore = defineStore('work', {
  state: () => ({
    ...emptySnapshot(),
    targets: [] as WorkTarget[],
    projects: [] as WorkProject[],
    loaded: false,
    busy: false,
    searching: false,
    error: '',
    notice: '',
    open: false,
    unsubscribe: undefined as (() => void) | undefined,
  }),
  getters: {
    /** Count shown on the control island badge for cards that still need attention. */
    outstanding(state): number {
      return state.requests.filter(record =>
        record.phase === 'awaiting_confirmation'
        || (record.confirmedAt && ['forwarding', 'accepted', 'unknown'].includes(record.phase))).length
    },
  },
  actions: {
    async start() {
      if (this.unsubscribe)
        return
      const api = bridge()
      if (!api)
        return
      this.unsubscribe = api.onChanged(snapshot => this.apply(snapshot))
      await this.load()
    },
    stop() {
      this.unsubscribe?.()
      this.unsubscribe = undefined
    },
    apply(snapshot: WorkSnapshot) {
      this.requests = snapshot.requests ?? []
      this.draft = snapshot.draft ?? null
      this.connection = snapshot.connection ?? { codex: 'disabled', harness: 'disabled' }
      this.executionAvailable = snapshot.executionAvailable === true
      this.loaded = true
    },
    async load() {
      const api = bridge()
      if (!api)
        return
      try {
        this.apply(await api.snapshot())
      }
      catch {
        this.error = '无法读取本机任务状态。'
      }
    },
    async refreshTargets(query = '') {
      const api = bridge()
      if (!api)
        return
      this.searching = true
      try {
        const result = await api.targets(query)
        this.targets = result.items ?? []
      }
      catch {
        this.targets = []
      }
      finally {
        this.searching = false
      }
    },
    async refreshProjects() {
      const api = bridge()
      if (!api)
        return
      try {
        this.projects = await api.projects()
      }
      catch {
        this.projects = []
      }
    },
    async prepare(input: WorkPrepareInput): Promise<boolean> {
      const api = bridge()
      if (!api)
        return false
      this.busy = true
      this.error = ''
      this.notice = ''
      try {
        const result = await api.prepare(input)
        if (!result.ok || !result.request) {
          this.error = result.error ?? '任务卡没有准备好。'
          return false
        }
        await this.load()
        return true
      }
      catch {
        this.error = '任务卡没有准备好，请稍后重试。'
        return false
      }
      finally {
        this.busy = false
      }
    },
    /**
     * The single consequential action. The version guard means a card that
     * changed since the user looked at it is never sent blindly.
     */
    async confirm(id: string, version: number): Promise<boolean> {
      const api = bridge()
      if (!api)
        return false
      this.busy = true
      this.error = ''
      this.notice = ''
      try {
        const result = await api.confirm(id, version)
        await this.load()
        if (!result.ok) {
          this.error = result.error ?? '这次转交没有完成。'
          return false
        }
        const phase = result.request?.phase
        this.notice = phase === 'unknown'
          ? '尚未确认转发结果；不会自动重复发送。'
          : '任务已经转交，可以在本地执行器中查看进度。'
        return true
      }
      catch {
        this.error = '这次转交没有完成，请稍后重试。'
        return false
      }
      finally {
        this.busy = false
      }
    },
    async refresh(id: string) {
      const api = bridge()
      if (!api)
        return
      try {
        await api.refresh(id)
        await this.load()
      }
      catch {
        // Observation failures never change a recorded receipt.
      }
    },
    async dismiss(id: string, version: number) {
      const api = bridge()
      if (!api)
        return
      try {
        await api.dismiss(id, version)
        await this.load()
      }
      catch {
        this.error = '无法隐藏这条提醒。'
      }
    },
    async openNative(id: string) {
      const api = bridge()
      if (!api)
        return
      const result = await api.openNative(id)
      if (!result.ok)
        this.error = result.error ?? '无法打开本地执行器。'
    },
    clearFeedback() {
      this.error = ''
      this.notice = ''
    },
  },
})
