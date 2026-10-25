import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCompanionDirectorStore } from '../stores/companion-director'
import { useConversationStore } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { clearPlaybackLedger } from './playback-ledger'
import { deliverCompanionNotice, registerReminderPresenter } from './companion-notice-delivery'

const values = new Map<string, string>()
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, String(value)),
    removeItem: (key: string) => values.delete(key),
  },
})

const notice = {
  id: 'ai_news:2026-10-08',
  kind: 'news' as const,
  text: '今天的简报。',
  createdAt: 100,
  expiresAt: 10_000,
}

describe('companion notice delivery', () => {
  beforeEach(() => {
    values.clear()
    clearPlaybackLedger()
    vi.useFakeTimers()
    vi.setSystemTime(5_000)
    Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined })
    setActivePinia(createPinia())
    useCompanionDirectorStore().context.userActiveUntil = Number.MAX_SAFE_INTEGER
    const preferences = useDesktopPreferencesStore()
    preferences.value.companion.initiative = 'normal'
    preferences.value.privacy.saveHistory = true
  })

  afterEach(() => vi.useRealTimers())

  it('claims before delivering a notice and suppresses it after renderer reload', async () => {
    const director = useCompanionDirectorStore()
    const decision = director.enqueueNotice(notice, 200)
    expect(await deliverCompanionNotice(notice, decision)).toBe(true)
    expect(useConversationStore().messages.map(message => message.text)).toContain(notice.text)

    setActivePinia(createPinia())
    const restartedDirector = useCompanionDirectorStore()
    const repeated = restartedDirector.enqueueNotice(notice, 300)
    expect(await deliverCompanionNotice(notice, repeated)).toBe(false)
    expect(useConversationStore().messages).toHaveLength(1)
  })

  it('keeps an eligible notice quiet when speech is muted', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.speech.ttsEnabled = false
    const director = useCompanionDirectorStore()
    const decision = director.enqueueNotice(notice, 200)
    expect(decision.allowSpeech).toBe(false)
    expect(decision.allowText).toBe(true)
    expect(await deliverCompanionNotice(notice, decision)).toBe(true)
    expect(useConversationStore().messages[0].text).toBe(notice.text)
  })

  it('rechecks reach-out context after an asynchronous claim without deferring stale text', async () => {
    let release!: () => void
    const wait = new Promise<void>(resolve => { release = resolve })
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: { request: async (_name: string, _options: unknown, action: () => Promise<unknown>) => { await wait; return action() } },
    })
    const director = useCompanionDirectorStore()
    const candidate = { ...notice, id: 'reachout:one', kind: 'reach_out' as const }
    let current = true
    const pending = deliverCompanionNotice(candidate, director.evaluateNotice(candidate), () => current)
    current = false
    release()
    expect(await pending).toBe(false)
    expect(useConversationStore().messages.some(message => message.id === candidate.id)).toBe(false)
    expect(director.deferred).toHaveLength(0)
  })

  it('rechecks the native deadline when a news claim waits across the inactivity boundary', async () => {
    let release!: () => void
    const wait = new Promise<void>(resolve => { release = resolve })
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: { request: async (_name: string, _options: unknown, action: () => Promise<unknown>) => { await wait; return action() } },
    })
    const director = useCompanionDirectorStore()
    director.context.userActiveUntil = 5_001
    const pending = deliverCompanionNotice(notice, director.evaluateNotice(notice))
    vi.setSystemTime(5_001)
    release()
    expect(await pending).toBe(false)
    expect(useConversationStore().messages).toHaveLength(0)
    expect(director.deferred.map(item => item.id)).toContain(notice.id)
  })

  it('requeues when focus changes during the durable claim and delivers after focus clears', async () => {
    let release!: () => void
    const wait = new Promise<void>(resolve => { release = resolve })
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: { request: async (_name: string, _options: unknown, action: () => Promise<unknown>) => { await wait; return action() } },
    })
    const director = useCompanionDirectorStore()
    const decision = director.enqueueNotice(notice, 200)
    const firstAttempt = deliverCompanionNotice(notice, decision)
    director.context.focus = 'focused'
    release()
    expect(await firstAttempt).toBe(false)
    expect(director.deferred).toHaveLength(1)

    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_n: string, _o: unknown, action: () => Promise<unknown>) => action() } })
    const dispose = director.onDelivery(({ notice: pending, decision: next }) => deliverCompanionNotice(pending, next))
    await director.setRuntimeContext({ focus: 'available' })
    dispose()
    expect(director.deferred).toHaveLength(0)
    expect(useConversationStore().messages.map(message => message.text)).toContain(notice.text)
  })

  it('uses the same path for a reminder and passes its original display metadata', async () => {
    const reminder = {
      id: 'reminder:42:100', kind: 'reminder' as const, text: '提醒你，喝水。', createdAt: 100, expiresAt: 10_000,
      reminder: { todoId: 42, title: '喝水', triggerAt: 100, mainAccount: false, late: false },
    }
    let deliveredTitle = ''
    const dispose = registerReminderPresenter((value) => { deliveredTitle = value.reminder?.title ?? ''; return true })
    try {
      const director = useCompanionDirectorStore()
      const decision = director.enqueueNotice(reminder, 200)
      expect(await deliverCompanionNotice(reminder, decision)).toBe(true)
      expect(deliveredTitle).toBe('喝水')
    }
    finally { dispose() }
  })
})
