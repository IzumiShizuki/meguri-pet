import { defineStore } from 'pinia'

import { desktopFetch, gatewayOrigin } from '../services/desktop-fetch'
import { appendArtifactBubbles, collectNewArtifacts, type FeedArtifact } from '../utils/artifact-feed'
import { useConversationStore } from './conversation'

export interface ArtifactItem {
  key: string
  name: string
  href: string
  localPath: string
  modifiedMs: number
  sizeBytes: number
  iconDataUrl?: string
}

interface ArtifactFeed {
  now: number
  artifacts: FeedArtifact[]
}

const cursorKey = 'meguri.desktop.artifact-cursor.v1'

export const useArtifactStore = defineStore('artifacts', {
  state: () => ({
    cursor: Number(localStorage.getItem(cursorKey) ?? Date.now()),
    bubbles: [] as ArtifactItem[],
    seen: new Set<string>(),
    timer: undefined as number | undefined,
  }),
  actions: {
    start(sessionId: string) {
      if (this.timer)
        return
      if (import.meta.env.DEV && new URLSearchParams(window.location.search).get('preview') === 'artifact') {
        const preview = {
          name: 'meguri-briefing.md',
          href: '/artifacts/meguri-briefing.md',
          local_path: 'D:/program/meguri-pet/output/meguri-briefing.md',
          modified_ms: Date.now(),
          size_bytes: 2_048,
        }
        const added = collectNewArtifacts([preview], this.seen, gatewayOrigin)
        this.bubbles = appendArtifactBubbles(this.bubbles, added)
        useConversationStore().addArtifactMessage(preview)
        return
      }
      void this.poll(sessionId)
      this.timer = window.setInterval(() => void this.poll(sessionId), 5_000)
    },
    stop() {
      if (this.timer)
        clearInterval(this.timer)
      this.timer = undefined
    },
    async poll(sessionId: string) {
      try {
        const url = new URL('/artifacts/recent', gatewayOrigin)
        url.searchParams.set('after', String(this.cursor))
        const response = await desktopFetch(sessionId, url, { cache: 'no-store' })
        if (!response.ok)
          return
        const feed = await response.json() as ArtifactFeed
        const added = collectNewArtifacts(feed.artifacts ?? [], this.seen, gatewayOrigin)
        this.bubbles = appendArtifactBubbles(this.bubbles, added)
        for (const item of added) {
          const artifact = feed.artifacts.find(candidate => `${candidate.local_path}:${candidate.modified_ms}` === item.key)!
          useConversationStore().addArtifactMessage(artifact)
          void this.loadIcon(item)
          this.cursor = Math.max(this.cursor, artifact.modified_ms)
        }
        this.cursor = Math.max(this.cursor, Number(feed.now) || 0)
        localStorage.setItem(cursorKey, String(this.cursor))
      }
      catch {
        // The gateway can restart independently; the next interval retries.
      }
    },
    async loadIcon(item: ArtifactItem) {
      const result = await window.meguriDesktop?.artifacts.icon(item.localPath)
      if (result?.ok && result.dataUrl)
        item.iconDataUrl = result.dataUrl
    },
    dismiss(key: string) {
      this.bubbles = this.bubbles.filter(item => item.key !== key)
    },
    async open(item: ArtifactItem) {
      const result = await window.meguriDesktop?.artifacts.open(item.localPath)
      if (result?.ok) {
        this.dismiss(item.key)
        return true
      }
      window.open(item.href, '_blank', 'noopener')
      return false
    },
  },
})
