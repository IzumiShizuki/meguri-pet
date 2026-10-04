import { describe, expect, it, vi } from 'vitest'

import {
  cycleOutfitCode,
  meguriGlobalShortcutBindings,
  registerMeguriGlobalShortcuts,
} from './global-shortcuts.mjs'

describe('Meguri AIRI global shortcuts', () => {
  it('keeps the shortcut bindings inherited from the AIRI fork', () => {
    expect(meguriGlobalShortcutBindings).toEqual({
      toggleChat: 'Ctrl+Alt+H',
      centerStage: 'Ctrl+Alt+C',
      centerStageLegacy: 'Ctrl+Alt+Shift+M',
      previousOutfit: 'Ctrl+Alt+[',
      nextOutfit: 'Ctrl+Alt+]',
      importClipboardImage: 'Ctrl+Alt+Shift+P',
    })
  })

  it('wraps outfit selection across all six Meguri portraits', () => {
    expect(cycleOutfitCode('01', -1)).toBe('06')
    expect(cycleOutfitCode('06', 1)).toBe('01')
    expect(cycleOutfitCode('04', -1)).toBe('03')
    expect(cycleOutfitCode('04', 1)).toBe('05')
  })

  it('registers every binding independently and routes its action', () => {
    const callbacks = new Map<string, () => void>()
    const register = vi.fn((accelerator: string, callback: () => void) => {
      callbacks.set(accelerator, callback)
      return accelerator !== 'Ctrl+Alt+C'
    })
    const toggleChat = vi.fn()
    const centerStage = vi.fn()
    const cycleOutfit = vi.fn()
    const importClipboardImage = vi.fn()

    const registered = registerMeguriGlobalShortcuts({
      register,
      toggleChat,
      centerStage,
      cycleOutfit,
      importClipboardImage,
    })

    expect(register).toHaveBeenCalledTimes(6)
    expect(registered).not.toContain('Ctrl+Alt+C')
    expect(registered).toContain('Ctrl+Alt+Shift+M')

    callbacks.get('Ctrl+Alt+H')?.()
    callbacks.get('Ctrl+Alt+Shift+M')?.()
    callbacks.get('Ctrl+Alt+[')?.()
    callbacks.get('Ctrl+Alt+]')?.()
    callbacks.get('Ctrl+Alt+Shift+P')?.()

    expect(toggleChat).toHaveBeenCalledOnce()
    expect(centerStage).toHaveBeenCalledOnce()
    expect(cycleOutfit).toHaveBeenNthCalledWith(1, -1)
    expect(cycleOutfit).toHaveBeenNthCalledWith(2, 1)
    expect(importClipboardImage).toHaveBeenCalledOnce()
  })
})
