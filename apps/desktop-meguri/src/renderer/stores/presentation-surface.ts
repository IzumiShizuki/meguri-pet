import { defineStore } from 'pinia'

import type { CharacterPresentationSnapshot } from '../../shared/desktop-contract'

export type PresentationSurface = 'ambient' | 'galgame'
export type LocalPresentationSnapshot = Pick<CharacterPresentationSnapshot, 'revision' | 'packageId'> & {
  spriteDataUrl?: string
  backgroundDataUrl?: string
}

/**
 * Keeps display-only recovery data outside the authoritative conversation
 * stream. The two Stage surfaces read the same presentation store; this
 * store only owns surface and snapshot/interruption state.
 */
export const usePresentationSurfaceStore = defineStore('presentation-surface', {
  state: () => ({
    surface: 'ambient' as PresentationSurface,
    snapshotKey: 'builtin:default',
    snapshotRevision: 0,
    resourceSnapshot: { revision: 'builtin:default' } as LocalPresentationSnapshot,
    interruptionGeneration: 0,
  }),
  getters: {
    isGalgame: state => state.surface === 'galgame',
  },
  actions: {
    enterGalgame() {
      this.surface = 'galgame'
    },
    exitGalgame() {
      this.surface = 'ambient'
    },
    rememberSnapshot(snapshotKey: string) {
      if (!snapshotKey || snapshotKey === this.snapshotKey)
        return this.snapshotRevision
      this.snapshotKey = snapshotKey
      this.snapshotRevision += 1
      return this.snapshotRevision
    },
    setResourceSnapshot(snapshot: LocalPresentationSnapshot) {
      this.resourceSnapshot = snapshot
      return this.rememberSnapshot(snapshot.revision)
    },
    interruptPresentation() {
      this.interruptionGeneration += 1
      return this.interruptionGeneration
    },
  },
})
