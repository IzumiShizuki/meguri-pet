import { describe, expect, it, vi } from 'vitest'

import { showWindowAndFocus, toggleWindowVisibility } from './window-visibility.mjs'

function createWindow({ visible, focused, destroyed = false } = {}) {
  return {
    isDestroyed: vi.fn(() => destroyed),
    isVisible: vi.fn(() => visible),
    isFocused: vi.fn(() => focused),
    setAlwaysOnTop: vi.fn(),
    show: vi.fn(),
    focus: vi.fn(),
    hide: vi.fn(),
  }
}

describe('toggleWindowVisibility', () => {
  it('can raise a visible window without toggling it closed', () => {
    const window = createWindow({ visible: true, focused: false })

    expect(showWindowAndFocus(window, { alwaysOnTop: true })).toBe(true)

    expect(window.setAlwaysOnTop).toHaveBeenCalledWith(true, 'floating')
    expect(window.show).toHaveBeenCalledOnce()
    expect(window.focus).toHaveBeenCalledOnce()
    expect(window.hide).not.toHaveBeenCalled()
  })

  it('raises and focuses a visible but obscured window instead of hiding it', () => {
    const window = createWindow({ visible: true, focused: false })

    expect(toggleWindowVisibility(window, { alwaysOnTop: true })).toBe(true)

    expect(window.setAlwaysOnTop).toHaveBeenCalledOnce()
    expect(window.setAlwaysOnTop).toHaveBeenCalledWith(true, 'floating')
    expect(window.show).toHaveBeenCalledOnce()
    expect(window.focus).toHaveBeenCalledOnce()
    expect(window.hide).not.toHaveBeenCalled()
  })

  it('shows and focuses a hidden window', () => {
    const window = createWindow({ visible: false, focused: false })

    expect(toggleWindowVisibility(window, { alwaysOnTop: true })).toBe(true)

    expect(window.setAlwaysOnTop).toHaveBeenCalledWith(true, 'floating')
    expect(window.show).toHaveBeenCalledOnce()
    expect(window.focus).toHaveBeenCalledOnce()
    expect(window.hide).not.toHaveBeenCalled()
  })

  it('hides only a currently focused window', () => {
    const window = createWindow({ visible: true, focused: true })

    expect(toggleWindowVisibility(window, { alwaysOnTop: true })).toBe(false)

    expect(window.hide).toHaveBeenCalledOnce()
    expect(window.setAlwaysOnTop).not.toHaveBeenCalled()
    expect(window.show).not.toHaveBeenCalled()
    expect(window.focus).not.toHaveBeenCalled()
  })

  it('preserves a disabled always-on-top preference while recovering focus', () => {
    const window = createWindow({ visible: true, focused: false })

    expect(toggleWindowVisibility(window, { alwaysOnTop: false })).toBe(true)

    expect(window.setAlwaysOnTop).not.toHaveBeenCalled()
    expect(window.show).toHaveBeenCalledOnce()
    expect(window.focus).toHaveBeenCalledOnce()
  })

  it('does nothing if the window is missing or destroyed', () => {
    expect(toggleWindowVisibility(undefined)).toBe(false)

    const window = createWindow({ visible: true, focused: false, destroyed: true })
    expect(toggleWindowVisibility(window)).toBe(false)
    expect(window.hide).not.toHaveBeenCalled()
    expect(window.show).not.toHaveBeenCalled()
  })
})
