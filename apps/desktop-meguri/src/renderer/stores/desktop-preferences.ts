import type { DesktopPreferences, DesktopPreferencesPatch } from '../../shared/desktop-contract'
import type { CompanionPreferences, CompanionPreferencesPatch } from '../../shared/companion-contract'

import { defineStore } from 'pinia'
import { conversationPalette } from '../utils/conversation-palette'

export const fallbackDesktopPreferences: DesktopPreferences = {
  version: 1,
  appearance: 'light',
  chat: { visible: true, expanded: false, wallpaperDataUrl: '', wallpaperBlur: 0, bubbleOpacity: 1, colorPalette: 'mint' },
  character: { outfitCode: '04', reducedMotion: false, renderer: 'png', live2dModelId: '' },
  window: { alwaysOnTop: true, fadeOnHover: true },
  speech: { ttsEnabled: true, volume: 0.9, speed: 1, ambientEnabled: true, galgameEnabled: true },
  memory: { enabled: false, autoSummarize: true, summaryEveryTurns: 10 },
  companion: { initiative: 'normal', screenObservationEnabled: false, focus: 'available', expressionsEnabled: true, motionEnabled: true, commitments: [] },
  training: { enabled: false },
  privacy: { acceptedPolicyVersion: '', remoteChatAllowed: false, saveHistory: true, checkUpdatesOnStartup: true },
  shortcuts: { toggleVisibility: 'CommandOrControl+Shift+M' },
  startup: { openAtLogin: true, runAsAdministrator: true },
}

const legacyStorageKey = 'meguri.desktop.presentation.v1'
const legacyMigrationMarker = 'meguri.desktop.presentation.host-migrated.v1'

type ReversibleCompanionPreferences = Pick<CompanionPreferences, 'initiative' | 'screenObservationEnabled' | 'focus' | 'expressionsEnabled' | 'motionEnabled'>

function presentationSnapshot(companion: CompanionPreferences): ReversibleCompanionPreferences {
  return {
    initiative: companion.initiative,
    screenObservationEnabled: companion.screenObservationEnabled,
    focus: companion.focus,
    expressionsEnabled: companion.expressionsEnabled,
    motionEnabled: companion.motionEnabled,
  }
}

export function parseLegacyPresentation(raw: string | null): DesktopPreferencesPatch {
  if (!raw)
    return {}
  try {
    const legacy = JSON.parse(raw) as Record<string, unknown>
    const patch: DesktopPreferencesPatch = {}
    if (typeof legacy.chatVisible === 'boolean' || typeof legacy.chatExpanded === 'boolean') {
      patch.chat = {
        ...(typeof legacy.chatVisible === 'boolean' ? { visible: legacy.chatVisible } : {}),
        ...(typeof legacy.chatExpanded === 'boolean' ? { expanded: legacy.chatExpanded } : {}),
      }
    }
    if ((typeof legacy.outfitCode === 'string' && /^0[1-6]$/.test(legacy.outfitCode)) || typeof legacy.reducedMotion === 'boolean') {
      patch.character = {
        ...(typeof legacy.outfitCode === 'string' && /^0[1-6]$/.test(legacy.outfitCode) ? { outfitCode: legacy.outfitCode } : {}),
        ...(typeof legacy.reducedMotion === 'boolean' ? { reducedMotion: legacy.reducedMotion } : {}),
      }
    }
    return patch
  }
  catch {
    return {}
  }
}

function effectiveDark(appearance: DesktopPreferences['appearance']) {
  return appearance === 'dark'
    || (appearance === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
}

function applyAppearance(preferences: DesktopPreferences) {
  document.documentElement.dataset.appearance = preferences.appearance
  document.documentElement.classList.toggle('dark', effectiveDark(preferences.appearance))
  document.documentElement.dataset.palette = preferences.chat.colorPalette
  const palette = conversationPalette(preferences.chat.colorPalette, effectiveDark(preferences.appearance), preferences.systemAccentColor)
  for (const [name, value] of Object.entries(palette))
    document.documentElement.style.setProperty(`--conversation-${name.replace(/[A-Z]/gu, letter => `-${letter.toLowerCase()}`)}`, value)
  document.documentElement.style.setProperty('--chat-wallpaper', /^data:image\/(?:png|jpeg|webp);base64,[a-z0-9+/=]+$/iu.test(preferences.chat.wallpaperDataUrl) ? `url("${preferences.chat.wallpaperDataUrl}")` : 'none')
  document.documentElement.style.setProperty('--chat-wallpaper-blur', `${preferences.chat.wallpaperBlur * .24}px`)
  document.documentElement.style.setProperty('--chat-bubble-opacity', String(preferences.chat.bubbleOpacity))
}

export const useDesktopPreferencesStore = defineStore('desktop-preferences', {
  state: () => ({
    value: structuredClone(fallbackDesktopPreferences),
    previousCompanion: undefined as ReversibleCompanionPreferences | undefined,
    loaded: false,
    disposeHostListener: undefined as (() => void) | undefined,
    disposeMediaListener: undefined as (() => void) | undefined,
  }),
  getters: {
    isDark: state => effectiveDark(state.value.appearance),
  },
  actions: {
    apply(preferences: DesktopPreferences) {
      this.value = structuredClone(preferences)
      applyAppearance(this.value)
    },
    async initialize() {
      if (this.loaded)
        return
      const bridge = window.meguriDesktop?.preferences
      if (bridge) {
        let hostPreferences = await bridge.get()
        if (localStorage.getItem(legacyMigrationMarker) !== 'done') {
          const legacyPatch = parseLegacyPresentation(localStorage.getItem(legacyStorageKey))
          if (Object.keys(legacyPatch).length)
            hostPreferences = await bridge.migrateLegacy(legacyPatch)
          localStorage.setItem(legacyMigrationMarker, 'done')
        }
        this.apply(hostPreferences)
        this.disposeHostListener = bridge.onChanged(preferences => this.apply(preferences))
      }
      else {
        this.apply(fallbackDesktopPreferences)
      }
      const media = window.matchMedia('(prefers-color-scheme: dark)')
      const listener = () => applyAppearance(this.value)
      media.addEventListener('change', listener)
      this.disposeMediaListener = () => media.removeEventListener('change', listener)
      this.loaded = true
    },
    async update(patch: DesktopPreferencesPatch) {
      const next = await window.meguriDesktop?.preferences.update(patch)
      if (next)
        this.apply(next)
      return this.value
    },
    async updateCompanion(patch: CompanionPreferencesPatch) {
      this.previousCompanion = presentationSnapshot(this.value.companion)
      return this.update({ companion: patch })
    },
    async resetCompanionPreferences() {
      this.previousCompanion = presentationSnapshot(this.value.companion)
      return this.update({
        companion: {
          initiative: fallbackDesktopPreferences.companion.initiative,
          screenObservationEnabled: false,
          focus: fallbackDesktopPreferences.companion.focus,
          expressionsEnabled: fallbackDesktopPreferences.companion.expressionsEnabled,
          motionEnabled: fallbackDesktopPreferences.companion.motionEnabled,
        },
      })
    },
    async undoCompanionPreferences() {
      if (!this.previousCompanion)
        return this.value
      const previous = this.previousCompanion
      this.previousCompanion = undefined
      return this.update({ companion: previous })
    },
    dispose() {
      this.disposeHostListener?.()
      this.disposeMediaListener?.()
      this.disposeHostListener = undefined
      this.disposeMediaListener = undefined
      this.loaded = false
    },
  },
})
