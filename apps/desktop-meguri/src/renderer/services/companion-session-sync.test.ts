import { describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import type { CompanionCommitment } from '../../shared/companion-contract'
import type { CompanionSessionMessage, CompanionSessionTransport } from './companion-session-sync'
import { createCompanionSessionSync } from './companion-session-sync'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'

class SessionBus {
  readonly peers = new Set<SessionTransport>()

  createTransport(): CompanionSessionTransport {
    const peer = new SessionTransport(this)
    this.peers.add(peer)
    return peer
  }

  publish(sender: SessionTransport, message: CompanionSessionMessage) {
    for (const peer of this.peers) {
      if (peer !== sender && !peer.closed)
        queueMicrotask(() => peer.receive(message))
    }
  }
}

class SessionTransport implements CompanionSessionTransport {
  readonly listeners = new Set<(message: unknown) => void>()
  closed = false

  constructor(private readonly bus: SessionBus) {}

  postMessage(message: CompanionSessionMessage) {
    this.bus.publish(this, structuredClone(message))
  }

  onMessage(listener: (message: unknown) => void) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  receive(message: unknown) {
    for (const listener of this.listeners)
      listener(message)
  }

  close() {
    this.closed = true
    this.listeners.clear()
    this.bus.peers.delete(this)
  }
}

const item: CompanionCommitment = {
  id: 'local:session-one',
  text: '给朋友回信',
  dueAt: 2_000,
  createdAt: 1_000,
  updatedAt: 1_000,
  status: 'pending',
}

describe('companion session sync', () => {
  it('syncs ephemeral snapshots, follow-up state and consent withdrawal between renderer consumers', async () => {
    setActivePinia(createPinia())
    const settingsPreferences = useDesktopPreferencesStore()
    settingsPreferences.value.privacy.saveHistory = false
    settingsPreferences.value.companion.commitments = [item]
    const bus = new SessionBus()
    let stageContent: CompanionCommitment[] = []
    const settings = createCompanionSessionSync({
      transport: bus.createTransport(),
      isEnabled: () => true,
      getCommitments: () => settingsPreferences.value.companion.commitments,
      setCommitments: (items) => { settingsPreferences.value.companion.commitments = items },
    })
    const stage = createCompanionSessionSync({
      transport: bus.createTransport(),
      isEnabled: () => true,
      getCommitments: () => stageContent,
      setCommitments: (items) => { stageContent = items },
    })
    await vi.waitFor(() => expect(stageContent).toEqual([item]))

    const completed: CompanionCommitment = { ...item, status: 'completed', updatedAt: 2_000 }
    settingsPreferences.value.companion.commitments.splice(0, 1, completed)
    settings.publishCommitment(settingsPreferences.value.companion.commitments[0])
    await vi.waitFor(() => expect(stageContent[0].status).toBe('completed'))
    settings.publishCommitment(item)
    await Promise.resolve()
    expect(stageContent[0].status).toBe('completed')

    settings.publishClear()
    await vi.waitFor(() => expect(stageContent).toEqual([]))
    stage.dispose()
    settingsPreferences.value.companion.commitments.splice(0, 1, item)
    settings.publishCommitment(item)
    await Promise.resolve()
    expect(stageContent).toEqual([])
    settings.dispose()
  })

  it('validates incoming ephemeral content and never synchronizes while history is enabled', async () => {
    const bus = new SessionBus()
    let current: CompanionCommitment[] = []
    let enabled = false
    const receiver = createCompanionSessionSync({
      transport: bus.createTransport(),
      isEnabled: () => enabled,
      getCommitments: () => current,
      setCommitments: (items) => { current = items },
    })
    const senderTransport = bus.createTransport()
    const sender = createCompanionSessionSync({
      transport: senderTransport,
      isEnabled: () => true,
      getCommitments: () => [item],
      setCommitments: () => {},
    })
    sender.publishCommitment(item)
    await Promise.resolve()
    expect(current).toEqual([])

    enabled = true
    senderTransport.postMessage({
      version: 1,
      senderId: 'forged-peer',
      type: 'upsert',
      commitment: { ...item, text: 'x'.repeat(241) },
    })
    await Promise.resolve()
    await vi.waitFor(() => expect(current).toEqual([]))
    receiver.dispose()
    sender.dispose()
  })
})
