import type {
  CompanionCommitment,
  CompanionDeliveryDecision,
  CompanionNoticeInput,
  CompanionRuntimeContext,
} from '../../shared/companion-contract'

import { defineStore } from 'pinia'
import { toRaw, watch } from 'vue'
import { createBrowserCompanionSessionTransport, createCompanionSessionSync } from '../services/companion-session-sync'
import type { CompanionSessionTransport } from '../services/companion-session-sync'
import { decideCompanionNotice, getCompanionLimits } from '../services/companion-policy'
import { useDesktopPreferencesStore } from './desktop-preferences'

export interface CompanionDeliveryEvent {
  notice: CompanionNoticeInput
  decision: CompanionDeliveryDecision
}

type DeliveryListener = (event: CompanionDeliveryEvent) => unknown | Promise<unknown>

const queueCapacity = 12
const maxCommitments = 100
const scheduleIntervalMs = 15_000
const deliveryListeners = new WeakMap<object, Set<DeliveryListener>>()
const schedulerHandles = new WeakMap<object, ReturnType<typeof setInterval>>()
const consentWatchers = new WeakMap<object, () => void>()
const flushes = new WeakMap<object, Promise<void>>()
const pendingFlushRequests = new WeakSet<object>()
const sessionSyncs = new WeakMap<object, ReturnType<typeof createCompanionSessionSync>>()

// Pinia's devtools action-grouping wrapper invokes actions with a fresh Proxy
// each time. Normalize those proxies so state registered in one action remains
// available to later actions on the same store.
function runtimeKey(store: object) {
  return toRaw(store)
}

function listenersFor(store: object) {
  let listeners = deliveryListeners.get(store)
  if (!listeners) {
    listeners = new Set()
    deliveryListeners.set(store, listeners)
  }
  return listeners
}

function validCommitmentInput(text: string, dueAt: number, now: number) {
  return typeof text === 'string'
    && text.trim().length > 0
    && text.trim().length <= 240
    && Number.isFinite(dueAt)
    && dueAt > now
}

export const useCompanionDirectorStore = defineStore('companion-director', {
  state: () => ({
    context: { focus: 'available', activeTurn: false, speaking: false, surface: 'ambient' } as CompanionRuntimeContext,
    deferred: [] as CompanionNoticeInput[],
    ephemeralCommitments: [] as CompanionCommitment[],
    lastDeliveredAt: 0,
  }),
  getters: {
    commitments(state): CompanionCommitment[] {
      const preferences = useDesktopPreferencesStore()
      return [...preferences.value.companion.commitments, ...state.ephemeralCommitments]
        .sort((left, right) => left.dueAt - right.dueAt)
    },
    pendingCommitments(): CompanionCommitment[] {
      return this.commitments.filter(item => item.status === 'pending')
    },
  },
  actions: {
    initialize(transport?: CompanionSessionTransport) {
      const key = runtimeKey(this)
      consentWatchers.get(key)?.()
      if (transport || !sessionSyncs.has(key)) {
        sessionSyncs.get(key)?.dispose()
        const preferences = useDesktopPreferencesStore()
        const activeTransport = transport ?? createBrowserCompanionSessionTransport()
        if (activeTransport) {
          sessionSyncs.set(key, createCompanionSessionSync({
            transport: activeTransport,
            isEnabled: () => !preferences.value.privacy.saveHistory,
            getCommitments: () => this.ephemeralCommitments,
            setCommitments: (commitments) => {
              this.ephemeralCommitments = commitments
              const deliveredIds = new Set(commitments
                .filter(item => item.status !== 'pending' || item.followUpDeliveredAt)
                .map(item => item.id))
              this.deferred = this.deferred.filter(item => !item.commitmentId || !deliveredIds.has(item.commitmentId))
            },
          }))
        }
      }
      const preferences = useDesktopPreferencesStore()
      const stop = watch(() => preferences.value.privacy.saveHistory, (enabled, wasEnabled) => {
        if (!enabled && wasEnabled) {
          this.ephemeralCommitments = []
          this.deferred = this.deferred.filter(item => item.kind !== 'commitment_followup')
          sessionSyncs.get(key)?.publishClear()
        }
      }, { flush: 'sync' })
      consentWatchers.set(key, stop)
    },
    onDelivery(listener: DeliveryListener) {
      const listeners = listenersFor(runtimeKey(this))
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    setRuntimeContext(context: Partial<CompanionRuntimeContext>) {
      const preferences = useDesktopPreferencesStore()
      this.context = {
        ...this.context,
        ...context,
        focus: context.focus ?? preferences.value.companion.focus,
      }
      return this.flushDeferred()
    },
    async setFocus(focus: CompanionRuntimeContext['focus']) {
      const preferences = useDesktopPreferencesStore()
      await preferences.updateCompanion({ focus })
      this.context.focus = focus
      await this.flushDeferred()
    },
    evaluateNotice(notice: CompanionNoticeInput, now = Date.now()): CompanionDeliveryDecision {
      const preferences = useDesktopPreferencesStore().value
      const companion = preferences.companion
      const surface = notice.surface ?? this.context.surface
      const surfaceEnabled = surface === 'ambient'
        ? preferences.speech.ambientEnabled
        : preferences.speech.galgameEnabled
      return decideCompanionNotice({
        initiative: companion.initiative,
        context: { ...this.context, surface },
        notice,
        now,
        lastDeliveredAt: this.lastDeliveredAt,
        pendingCount: this.deferred.filter(item => item.id !== notice.id).length,
        speechEnabled: preferences.speech.ttsEnabled,
        volume: preferences.speech.volume,
        surfaceEnabled,
        expressionsEnabled: companion.expressionsEnabled,
        motionEnabled: companion.motionEnabled && !preferences.character.reducedMotion,
      })
    },
    enqueueNotice(notice: CompanionNoticeInput, now = Date.now()): CompanionDeliveryDecision {
      const existing = this.deferred.find(item => item.id === notice.id)
      if (existing)
        return { action: 'defer', allowText: false, allowSpeech: false, allowMotion: false, reason: 'active_turn' }
      const decision = this.evaluateNotice(notice, now)
      if (decision.action === 'defer') {
        if (this.deferred.length >= queueCapacity)
          return { action: 'drop', allowText: false, allowSpeech: false, allowMotion: false, reason: 'queue_full' }
        const limits = getCompanionLimits(useDesktopPreferencesStore().value.companion.initiative)
        const expiresAt = Math.min(notice.expiresAt, now + limits.maxAgeMs)
        this.deferred.push({ ...notice, text: notice.text.trim(), createdAt: notice.createdAt || now, expiresAt })
        return decision
      }
      return decision
    },
    flushDeferred(now = Date.now()) {
      const key = runtimeKey(this)
      const current = flushes.get(key)
      if (current) {
        pendingFlushRequests.add(key)
        return current
      }
      const pending = this.drainDeferred(now).finally(() => {
        flushes.delete(key)
        if (pendingFlushRequests.delete(key))
          queueMicrotask(() => { void this.flushDeferred() })
      })
      flushes.set(key, pending)
      return pending
    },
    async drainDeferred(now = Date.now()) {
      if (await this.offerDueCommitments(now))
        return
      for (let index = 0; index < this.deferred.length;) {
        const notice = this.deferred[index]
        const decision = this.evaluateNotice(notice, now)
        if (decision.action === 'drop') {
          this.deferred.splice(index, 1)
          continue
        }
        if (decision.action === 'defer') {
          index += 1
          continue
        }
        const listener = [...listenersFor(runtimeKey(this))][0]
        if (!listener) {
          index += 1
          continue
        }
        this.deferred.splice(index, 1)
        try {
          await listener({ notice, decision })
        }
        catch {
          if (notice.expiresAt > now && this.deferred.length < queueCapacity)
            this.deferred.unshift(notice)
        }
        return
      }
    },
    async offerDueCommitments(now = Date.now()) {
      for (const commitment of this.pendingCommitments) {
        if (commitment.dueAt > now || commitment.followUpDeliveredAt)
          continue
        const noticeId = `commitment:${commitment.id}`
        if (this.deferred.some(item => item.id === noticeId))
          continue
        const notice: CompanionNoticeInput = {
          id: noticeId,
          kind: 'commitment_followup',
          text: `之前你记下了：「${commitment.text}」。想继续处理它吗？`,
          createdAt: commitment.dueAt,
          expiresAt: commitment.dueAt + 7 * 24 * 60 * 60 * 1000,
          commitmentId: commitment.id,
        }
        const decision = this.enqueueNotice(notice, now)
        if (decision.action === 'deliver') {
          const listener = [...listenersFor(runtimeKey(this))][0]
          if (listener) {
            try {
              await listener({ notice, decision })
            }
            catch {
              if (notice.expiresAt > now && this.deferred.length < queueCapacity)
                this.deferred.unshift(notice)
            }
            return true
          }
          if (this.deferred.length < queueCapacity) {
            this.deferred.push(notice)
          }
        }
      }
      return false
    },
    startScheduler() {
      this.stopScheduler()
      this.initialize()
      void this.flushDeferred()
      const handle = setInterval(() => { void this.flushDeferred() }, scheduleIntervalMs)
      schedulerHandles.set(runtimeKey(this), handle)
    },
    stopScheduler() {
      const key = runtimeKey(this)
      const handle = schedulerHandles.get(key)
      if (handle)
        clearInterval(handle)
      schedulerHandles.delete(key)
    },
    async recordDelivered(noticeId: string, at = Date.now()) {
      const commitmentId = noticeId.startsWith('commitment:') ? noticeId.slice('commitment:'.length) : undefined
      if (!commitmentId) {
        this.lastDeliveredAt = at
        return
      }
      if (await this.updateCommitment(commitmentId, item => ({ ...item, followUpDeliveredAt: at })))
        this.lastDeliveredAt = at
    },
    isCommitmentPending(id: string) {
      return this.commitments.some(item => item.id === id && item.status === 'pending' && !item.followUpDeliveredAt)
    },
    async addCommitment(text: string, dueAt: number, now = Date.now()) {
      const normalizedText = text.trim()
      if (!validCommitmentInput(normalizedText, dueAt, now) || this.commitments.length >= maxCommitments)
        return undefined
      const commitment: CompanionCommitment = {
        id: crypto.randomUUID(),
        text: normalizedText,
        dueAt,
        createdAt: now,
        updatedAt: now,
        status: 'pending',
      }
      const preferences = useDesktopPreferencesStore()
      if (preferences.value.privacy.saveHistory) {
        const updated = await preferences.update({ companion: { commitments: [...preferences.value.companion.commitments, commitment] } })
        if (!updated.companion.commitments.some(item => item.id === commitment.id))
          return undefined
      }
      else {
        this.ephemeralCommitments.push(commitment)
        sessionSyncs.get(runtimeKey(this))?.publishCommitment(commitment)
      }
      return commitment
    },
    async completeCommitment(id: string) {
      await this.updateCommitment(id, item => ({ ...item, status: 'completed' }))
      this.removeDeferredCommitment(id)
    },
    async cancelCommitment(id: string) {
      await this.updateCommitment(id, item => ({ ...item, status: 'cancelled' }))
      this.removeDeferredCommitment(id)
    },
    removeDeferredCommitment(id: string) {
      const noticeId = `commitment:${id}`
      this.deferred = this.deferred.filter(item => item.id !== noticeId)
    },
    async updateCommitment(id: string, update: (item: CompanionCommitment) => CompanionCommitment) {
      const preferences = useDesktopPreferencesStore()
      const persistentIndex = preferences.value.companion.commitments.findIndex(item => item.id === id)
      if (persistentIndex >= 0) {
        const current = preferences.value.companion.commitments[persistentIndex]
        const updatedItem = { ...update(current), updatedAt: Math.max(Date.now(), (current.updatedAt ?? current.createdAt) + 1) }
        const commitments = preferences.value.companion.commitments.map(item => item.id === id ? updatedItem : item)
        const updated = await preferences.update({ companion: { commitments } })
        const stored = updated.companion.commitments.find(item => item.id === id)
        return stored?.updatedAt === updatedItem.updatedAt
          && stored.followUpDeliveredAt === updatedItem.followUpDeliveredAt
          && stored.status === updatedItem.status
      }
      if (!this.ephemeralCommitments.some(item => item.id === id))
        return false
      const current = this.ephemeralCommitments.find(item => item.id === id)!
      const updatedItem = { ...update(current), updatedAt: Math.max(Date.now(), (current.updatedAt ?? current.createdAt) + 1) }
      this.ephemeralCommitments = this.ephemeralCommitments.map(item => item.id === id ? updatedItem : item)
      const updated = this.ephemeralCommitments.find(item => item.id === id)
      if (updated)
        sessionSyncs.get(runtimeKey(this))?.publishCommitment(updated)
      return true
    },
    dispose() {
      const key = runtimeKey(this)
      this.stopScheduler()
      consentWatchers.get(key)?.()
      consentWatchers.delete(key)
      sessionSyncs.get(key)?.dispose()
      sessionSyncs.delete(key)
      this.deferred = []
      deliveryListeners.delete(key)
    },
  },
})
