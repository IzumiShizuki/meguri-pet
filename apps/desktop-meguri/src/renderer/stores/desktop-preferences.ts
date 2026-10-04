import type { DesktopPreferences, DesktopPreferencesPatch } from '../../shared/desktop-contract'

import { defineStore } from 'pinia'

export const fallbackDesktopPreferences: DesktopPreferences = {
  version: 1,
  appearance: 'system',
  chat: { visible: true, expanded: false },
  character: { outfitCode: '04', reducedMotion: false },
  window: { alwaysOnTop: true, fadeOnHover: true },
  speech: { ttsEnabled: true, volume: 0.9 },
  shortcuts: { toggleVisibility: 'CommandOrControl+Shift+M' },
  startup: { openAtLogin: true, runAsAdministrator: true },
}

const legacyStorageKey = 'meguri.desktop.presentation.v1'
const legacyMigrationMarker = 'meguri.desktop.presentation.host-migrated.v1'

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
}

export const useDesktopPreferencesStore = defineStore('desktop-preferences', {
  state: () => ({
    value: structuredClone(fallbackDesktopPreferences),
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
    dispose() {
      this.disposeHostListener?.()
      this.disposeMediaListener?.()
      this.disposeHostListener = undefined
      this.disposeMediaListener = undefined
      this.loaded = false
    },
  },
})
