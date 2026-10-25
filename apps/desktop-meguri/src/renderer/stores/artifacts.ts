import { defineStore } from 'pinia'

import { coreUrl, desktopFetch, gatewayOrigin } from '../services/desktop-fetch'
import { appendArtifactBubbles, collectNewArtifacts, type FeedArtifact } from '../utils/artifact-feed'
import { useConversationStore } from './conversation'
import { useCompanionDirectorStore } from './companion-director'
import { deliverCompanionNotice } from '../services/companion-notice-delivery'

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
    newsCheckedAt: 0,
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
      void this.pollAiNews(sessionId)
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
    async pollAiNews(sessionId: string) {
      if (Date.now() - this.newsCheckedAt < 60_000)
        return
      this.newsCheckedAt = Date.now()
      try {
        const response = await desktopFetch(sessionId, `${coreUrl}/v1/daily/reports/latest?kind=ai_news`, { cache: 'no-store' })
        if (!response.ok)
          return
        const report = await response.json() as { report_id?: string, date?: string, delivery_text?: string }
        const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
        if (report.report_id !== `ai_news:${today}` || report.date !== today
          || typeof report.delivery_text !== 'string' || report.delivery_text.length > 8_000)
          return
        const createdAt = Date.now()
        const notice = {
          id: report.report_id,
          kind: 'news' as const,
          text: report.delivery_text,
          createdAt,
          expiresAt: createdAt + 24 * 60 * 60 * 1000,
        }
        const decision = useCompanionDirectorStore().enqueueNotice(notice, createdAt)
        if (decision.action === 'deliver')
          void deliverCompanionNotice(notice, decision)
      }
      catch { /* A later poll retries when Core has restarted or the news becomes ready. */ }
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
