const appearanceModes = new Set(['system', 'light', 'dark'])
const colorPalettes = new Set(['system', 'amber', 'rose', 'mint', 'blue', 'violet'])
const companionInitiatives = new Set(['off', 'low', 'normal', 'high'])
const companionFocusModes = new Set(['available', 'focused'])
const commitmentStatuses = new Set(['pending', 'completed', 'cancelled'])
const maxCompanionCommitments = 100
export const privacyPolicyVersion = '2026-10-05'
export const maxWallpaperBytes = 3 * 1024 * 1024

const bounded = (value, minimum, maximum, fallback) => {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(maximum, Math.max(minimum, number)) : fallback
}
const boolean = (value, fallback) => typeof value === 'boolean' ? value : fallback

export function validatedWallpaperDataUrl(value) {
  if (typeof value !== 'string' || value.length > Math.ceil(maxWallpaperBytes / 3) * 4 + 40)
    return ''
  const match = /^data:image\/(png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$/.exec(value)
  if (!match) return ''
  const bytes = Buffer.from(match[2], 'base64')
  if (!bytes.length || bytes.length > maxWallpaperBytes || bytes.toString('base64') !== match[2]) return ''
  const format = bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) ? 'png'
    : bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 ? 'jpeg'
      : bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP' ? 'webp' : ''
  return format === match[1] ? value : ''
}
const visibilityShortcuts = new Set([
  'CommandOrControl+Shift+M',
  'CommandOrControl+Alt+M',
  'CommandOrControl+Shift+Space',
])

export const defaultDesktopPreferences = Object.freeze({
  version: 1,
  appearance: 'light',
  chat: Object.freeze({ visible: true, expanded: false, wallpaperDataUrl: '', wallpaperBlur: 0, bubbleOpacity: 1, colorPalette: 'mint' }),
  character: Object.freeze({ outfitCode: '04', reducedMotion: false, renderer: 'png', live2dModelId: '' }),
  window: Object.freeze({ alwaysOnTop: true, fadeOnHover: true }),
  speech: Object.freeze({ ttsEnabled: true, volume: 0.9, speed: 1, ambientEnabled: true, galgameEnabled: true }),
  memory: Object.freeze({ enabled: false, autoSummarize: true, summaryEveryTurns: 10 }),
  companion: Object.freeze({ initiative: 'normal', screenObservationEnabled: false, focus: 'available', expressionsEnabled: true, motionEnabled: true, commitments: Object.freeze([]) }),
  training: Object.freeze({ enabled: false }),
  privacy: Object.freeze({ acceptedPolicyVersion: '', remoteChatAllowed: false, saveHistory: true, checkUpdatesOnStartup: true }),
  shortcuts: Object.freeze({ toggleVisibility: 'CommandOrControl+Shift+M' }),
  startup: Object.freeze({ openAtLogin: true, runAsAdministrator: true }),
})

export function normalizeDesktopPreferences(value) {
  const candidate = value && typeof value === 'object' ? value : {}
  const chat = candidate.chat && typeof candidate.chat === 'object' ? candidate.chat : {}
  const character = candidate.character && typeof candidate.character === 'object' ? candidate.character : {}
  const window = candidate.window && typeof candidate.window === 'object' ? candidate.window : {}
  const speech = candidate.speech && typeof candidate.speech === 'object' ? candidate.speech : {}
  const memory = candidate.memory && typeof candidate.memory === 'object' ? candidate.memory : {}
  const training = candidate.training && typeof candidate.training === 'object' ? candidate.training : {}
  const companion = candidate.companion && typeof candidate.companion === 'object' ? candidate.companion : {}
  const privacy = candidate.privacy && typeof candidate.privacy === 'object' ? candidate.privacy : {}
  const shortcuts = candidate.shortcuts && typeof candidate.shortcuts === 'object' ? candidate.shortcuts : {}
  const startup = candidate.startup && typeof candidate.startup === 'object' ? candidate.startup : {}
  const volume = Number(speech.volume)
  const privacySaveHistory = boolean(privacy.saveHistory, true)
  const commitments = Array.isArray(companion.commitments) && privacySaveHistory
    ? companion.commitments
      .filter(item => item && typeof item === 'object'
        && typeof item.id === 'string' && /^[a-zA-Z0-9:_-]{1,100}$/.test(item.id)
        && typeof item.text === 'string' && item.text.trim().length > 0 && item.text.length <= 240
        && Number.isFinite(Number(item.dueAt)) && Number.isFinite(Number(item.createdAt))
        && commitmentStatuses.has(item.status))
      .slice(-maxCompanionCommitments)
      .map(item => ({
        id: item.id,
        text: item.text.trim(),
        dueAt: Number(item.dueAt),
        createdAt: Number(item.createdAt),
        ...(Number.isFinite(Number(item.updatedAt)) && Number(item.updatedAt) > 0
          ? { updatedAt: Number(item.updatedAt) }
          : {}),
        status: item.status,
        ...(Number.isFinite(Number(item.followUpDeliveredAt)) && Number(item.followUpDeliveredAt) > 0
          ? { followUpDeliveredAt: Number(item.followUpDeliveredAt) }
          : {}),
      }))
    : []

  return {
    version: 1,
    appearance: appearanceModes.has(candidate.appearance) ? candidate.appearance : defaultDesktopPreferences.appearance,
    chat: {
      visible: typeof chat.visible === 'boolean' ? chat.visible : defaultDesktopPreferences.chat.visible,
      expanded: typeof chat.expanded === 'boolean' ? chat.expanded : defaultDesktopPreferences.chat.expanded,
      wallpaperDataUrl: validatedWallpaperDataUrl(chat.wallpaperDataUrl),
      wallpaperBlur: Math.round(bounded(chat.wallpaperBlur, 0, 100, 0)),
      bubbleOpacity: Math.round(bounded(chat.bubbleOpacity, 0.2, 1, 1) * 100) / 100,
      colorPalette: colorPalettes.has(chat.colorPalette) ? chat.colorPalette : defaultDesktopPreferences.chat.colorPalette,
    },
    character: {
      outfitCode: typeof character.outfitCode === 'string' && /^0[1-6]$/.test(character.outfitCode)
        ? character.outfitCode
        : defaultDesktopPreferences.character.outfitCode,
      reducedMotion: typeof character.reducedMotion === 'boolean'
        ? character.reducedMotion
        : defaultDesktopPreferences.character.reducedMotion,
      renderer: character.renderer === 'live2d' ? 'live2d' : 'png',
      live2dModelId: typeof character.live2dModelId === 'string' && /^[a-zA-Z0-9_-]{1,100}$/.test(character.live2dModelId) ? character.live2dModelId : '',
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
      speed: Math.round(bounded(speech.speed, 0.5, 2, 1) * 100) / 100,
      ambientEnabled: boolean(speech.ambientEnabled, true),
      galgameEnabled: boolean(speech.galgameEnabled, true),
    },
    memory: {
      enabled: boolean(memory.enabled, false),
      autoSummarize: boolean(memory.autoSummarize, true),
      summaryEveryTurns: Math.round(bounded(memory.summaryEveryTurns, 3, 30, 10)),
    },
    companion: {
      initiative: companionInitiatives.has(companion.initiative) ? companion.initiative : defaultDesktopPreferences.companion.initiative,
      screenObservationEnabled: boolean(companion.screenObservationEnabled, false),
      focus: companionFocusModes.has(companion.focus) ? companion.focus : defaultDesktopPreferences.companion.focus,
      expressionsEnabled: boolean(companion.expressionsEnabled, true),
      motionEnabled: boolean(companion.motionEnabled, true),
      commitments,
    },
    privacy: {
      acceptedPolicyVersion: privacy.acceptedPolicyVersion === privacyPolicyVersion ? privacyPolicyVersion : '',
      remoteChatAllowed: privacy.acceptedPolicyVersion === privacyPolicyVersion && privacy.remoteChatAllowed === true,
      saveHistory: boolean(privacy.saveHistory, true),
      checkUpdatesOnStartup: boolean(privacy.checkUpdatesOnStartup, true),
    },
    training: {
      enabled: privacy.acceptedPolicyVersion === privacyPolicyVersion && privacy.remoteChatAllowed === true
        && training.enabled === true,
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
    memory: { ...safeCurrent.memory, ...(safePatch.memory && typeof safePatch.memory === 'object' ? safePatch.memory : {}) },
    companion: { ...safeCurrent.companion, ...(safePatch.companion && typeof safePatch.companion === 'object' ? safePatch.companion : {}) },
    training: { ...safeCurrent.training, ...(safePatch.training && typeof safePatch.training === 'object' ? safePatch.training : {}) },
    privacy: { ...safeCurrent.privacy, ...(safePatch.privacy && typeof safePatch.privacy === 'object' ? safePatch.privacy : {}) },
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
