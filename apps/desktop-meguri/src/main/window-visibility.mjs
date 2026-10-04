export function showWindowAndFocus(window, { alwaysOnTop = false } = {}) {
  if (!window || window.isDestroyed())
    return false

  if (alwaysOnTop)
    window.setAlwaysOnTop(true, 'floating')
  window.show()
  window.focus()
  return true
}

export function toggleWindowVisibility(window, { alwaysOnTop = false } = {}) {
  if (!window || window.isDestroyed())
    return false

  if (window.isVisible() && window.isFocused()) {
    window.hide()
    return false
  }

  return showWindowAndFocus(window, { alwaysOnTop })
}
