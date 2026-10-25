import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { useConversationStore } from './conversation'
import { usePresentationSurfaceStore } from './presentation-surface'

describe('presentation surface recovery ledger', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('changes only the surface while retaining its visual snapshot and active conversation', () => {
    const surface = usePresentationSurfaceStore()
    const conversation = useConversationStore()
    conversation.sessionId = 'session-in-progress'
    conversation.checkpoint = {
      session_id: 'session-in-progress',
      last_sequence: 42,
      processed_event_ids: ['event-42'],
    }
    conversation.messages = [{
      id: 'reply-1',
      role: 'meguri',
      text: '仍在继续的回复。',
      createdAt: 1,
      status: 'streaming',
      turnId: 'turn-1',
    }]
    surface.rememberSnapshot('builtin:ce04003l.png')
    const revision = surface.snapshotRevision

    surface.enterGalgame()
    expect(surface.surface).toBe('galgame')
    expect(surface.snapshotKey).toBe('builtin:ce04003l.png')
    expect(surface.snapshotRevision).toBe(revision)
    expect(conversation.sessionId).toBe('session-in-progress')
    expect(conversation.checkpoint?.last_sequence).toBe(42)
    expect(conversation.messages).toHaveLength(1)

    surface.exitGalgame()
    expect(surface.surface).toBe('ambient')
    expect(surface.snapshotRevision).toBe(revision)
    expect(conversation.messages[0].text).toBe('仍在继续的回复。')
  })

  it('tracks interruption generations for pending presentation work', () => {
    const surface = usePresentationSurfaceStore()

    expect(surface.interruptPresentation()).toBe(1)
    expect(surface.interruptPresentation()).toBe(2)
  })

  it('keeps a resolved package snapshot stable while surfaces change', () => {
    const surface = usePresentationSurfaceStore()
    surface.setResourceSnapshot({
      revision: 'pack-meguri-v3:2026-09-18T00:05:00.000Z',
      packageId: 'pack-meguri-v3',
      spriteDataUrl: 'data:image/png;base64,AA==',
      backgroundDataUrl: 'data:image/jpeg;base64,AA==',
    })
    const revision = surface.snapshotRevision

    surface.enterGalgame()
    surface.exitGalgame()

    expect(surface.resourceSnapshot).toEqual(expect.objectContaining({
      packageId: 'pack-meguri-v3',
      spriteDataUrl: 'data:image/png;base64,AA==',
      backgroundDataUrl: 'data:image/jpeg;base64,AA==',
    }))
    expect(surface.snapshotRevision).toBe(revision)
  })
})
