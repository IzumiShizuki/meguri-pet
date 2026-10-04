const appearanceModes = new Set(['system', 'light', 'dark'])
const visibilityShortcuts = new Set([
  'CommandOrControl+Shift+M',
  'CommandOrControl+Alt+M',
  'CommandOrControl+Shift+Space',
])

export const defaultDesktopPreferences = Object.freeze({
  version: 1,
  appearance: 'system',
  chat: Object.freeze({ visible: true, expanded: false }),
  character: Object.freeze({ outfitCode: '04', reducedMotion: false }),
  window: Object.freeze({ alwaysOnTop: true, fadeOnHover: true }),
  speech: Object.freeze({ ttsEnabled: true, volume: 0.9 }),
  shortcuts: Object.freeze({ toggleVisibility: 'CommandOrControl+Shift+M' }),
  startup: Object.freeze({ openAtLogin: true, runAsAdministrator: true }),
})

export function normalizeDesktopPreferences(value) {
  const candidate = value && typeof value === 'object' ? value : {}
  const chat = candidate.chat && typeof candidate.chat === 'object' ? candidate.chat : {}
  const character = candidate.character && typeof candidate.character === 'object' ? candidate.character : {}
  const window = candidate.window && typeof candidate.window === 'object' ? candidate.window : {}
  const speech = candidate.speech && typeof candidate.speech === 'object' ? candidate.speech : {}
  const shortcuts = candidate.shortcuts && typeof candidate.shortcuts === 'object' ? candidate.shortcuts : {}
  const startup = candidate.startup && typeof candidate.startup === 'object' ? candidate.startup : {}
  const volume = Number(speech.volume)

  return {
    version: 1,
    appearance: appearanceModes.has(candidate.appearance) ? candidate.appearance : defaultDesktopPreferences.appearance,
    chat: {
      visible: typeof chat.visible === 'boolean' ? chat.visible : defaultDesktopPreferences.chat.visible,
      expanded: typeof chat.expanded === 'boolean' ? chat.expanded : defaultDesktopPreferences.chat.expanded,
    },
    character: {
      outfitCode: typeof character.outfitCode === 'string' && /^0[1-6]$/.test(character.outfitCode)
        ? character.outfitCode
        : defaultDesktopPreferences.character.outfitCode,
      reducedMotion: typeof character.reducedMotion === 'boolean'
        ? character.reducedMotion
        : defaultDesktopPreferences.character.reducedMotion,
    },
    window: {
      alwaysOnTop: typeof window.alwaysOnTop === 'boolean'
        ? window.alwaysOnTop
        : defaultDesktopPreferences.window.alwaysOnTop,
      fadeOnHover: typeof window.fadeOnHover === 'boolean'
        ? window.fadeOnHover
        : defaultDesktopPreferences.window.fadeOnHover,
    },
    speech: {
      ttsEnabled: typeof speech.ttsEnabled === 'boolean'
        ? speech.ttsEnabled
        : defaultDesktopPreferences.speech.ttsEnabled,
      volume: Number.isFinite(volume)
        ? Math.round(Math.min(1, Math.max(0, volume)) * 100) / 100
        : defaultDesktopPreferences.speech.volume,
    },
    shortcuts: {
      toggleVisibility: visibilityShortcuts.has(shortcuts.toggleVisibility)
        ? shortcuts.toggleVisibility
        : defaultDesktopPreferences.shortcuts.toggleVisibility,
    },
    startup: {
      openAtLogin: typeof startup.openAtLogin === 'boolean'
        ? startup.openAtLogin
        : defaultDesktopPreferences.startup.openAtLogin,
      runAsAdministrator: typeof startup.runAsAdministrator === 'boolean'
        ? startup.runAsAdministrator
        : defaultDesktopPreferences.startup.runAsAdministrator,
    },
  }
}

export function mergeDesktopPreferences(current, patch) {
  const safeCurrent = normalizeDesktopPreferences(current)
  const safePatch = patch && typeof patch === 'object' ? patch : {}
  return normalizeDesktopPreferences({
    ...safeCurrent,
    ...safePatch,
    chat: { ...safeCurrent.chat, ...(safePatch.chat && typeof safePatch.chat === 'object' ? safePatch.chat : {}) },
    character: { ...safeCurrent.character, ...(safePatch.character && typeof safePatch.character === 'object' ? safePatch.character : {}) },
    window: { ...safeCurrent.window, ...(safePatch.window && typeof safePatch.window === 'object' ? safePatch.window : {}) },
    speech: { ...safeCurrent.speech, ...(safePatch.speech && typeof safePatch.speech === 'object' ? safePatch.speech : {}) },
    shortcuts: { ...safeCurrent.shortcuts, ...(safePatch.shortcuts && typeof safePatch.shortcuts === 'object' ? safePatch.shortcuts : {}) },
    startup: { ...safeCurrent.startup, ...(safePatch.startup && typeof safePatch.startup === 'object' ? safePatch.startup : {}) },
  })
}

/** Converts the presentation localStorage document used by the first native
 * renderer into a host-owned preference patch. Invalid values are ignored so
 * migration cannot replace host defaults with corrupt browser data.
 */
export function legacyPresentationPatch(value) {
  const legacy = value && typeof value === 'object' ? value : {}
  const patch = {}
  const chat = {}
  const character = {}

  if (typeof legacy.chatVisible === 'boolean')
    chat.visible = legacy.chatVisible
  if (typeof legacy.chatExpanded === 'boolean')
    chat.expanded = legacy.chatExpanded
  if (Object.keys(chat).length)
    patch.chat = chat

  if (typeof legacy.outfitCode === 'string' && /^0[1-6]$/.test(legacy.outfitCode))
    character.outfitCode = legacy.outfitCode
  if (typeof legacy.reducedMotion === 'boolean')
    character.reducedMotion = legacy.reducedMotion
  if (Object.keys(character).length)
    patch.character = character

  return patch
}
