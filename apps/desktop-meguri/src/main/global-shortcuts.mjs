export const meguriGlobalShortcutBindings = Object.freeze({
  toggleChat: 'Ctrl+Alt+H',
  centerStage: 'Ctrl+Alt+C',
  centerStageLegacy: 'Ctrl+Alt+Shift+M',
  previousOutfit: 'Ctrl+Alt+[',
  nextOutfit: 'Ctrl+Alt+]',
  importClipboardImage: 'Ctrl+Alt+Shift+P',
})

const outfitCodes = Object.freeze(['01', '02', '03', '04', '05', '06'])

export function cycleOutfitCode(current, direction) {
  const index = outfitCodes.indexOf(current)
  const safeIndex = index >= 0 ? index : outfitCodes.indexOf('04')
  return outfitCodes[(safeIndex + direction + outfitCodes.length) % outfitCodes.length]
}

/**
 * Registers the shortcuts that exist in the Meguri AIRI fork. Each binding is
 * independent so an OS-level conflict does not disable the remaining actions.
 */
export function registerMeguriGlobalShortcuts({ register, toggleChat, centerStage, cycleOutfit, importClipboardImage = () => undefined }) {
  const actions = [
    [meguriGlobalShortcutBindings.toggleChat, toggleChat],
    [meguriGlobalShortcutBindings.centerStage, centerStage],
    [meguriGlobalShortcutBindings.centerStageLegacy, centerStage],
    [meguriGlobalShortcutBindings.previousOutfit, () => cycleOutfit(-1)],
    [meguriGlobalShortcutBindings.nextOutfit, () => cycleOutfit(1)],
    [meguriGlobalShortcutBindings.importClipboardImage, importClipboardImage],
  ]
  const registered = []
  for (const [accelerator, action] of actions) {
    try {
      if (register(accelerator, action))
        registered.push(accelerator)
    }
    catch {
      // A platform-specific accelerator failure must not disable the rest.
    }
  }
  return registered
}
