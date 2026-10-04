function extensionBadge(document, name) {
  const badge = document.createElement('span')
  badge.className = 'ext-badge'
  const ext = String(name || '').split('.').pop() || ''
  badge.textContent = (ext.length > 0 && ext.length <= 5 ? ext : 'FILE').toUpperCase()
  return badge
}

// This is the renderer seam for the generated-file bubble. The caller owns
// the artifact opener so this module stays independent from Electron IPC.
export function createArtifactBubble({ document, item, onOpen }) {
  const bubbleEl = document.createElement('div')
  bubbleEl.className = 'artifact-bubble'
  const name = item.name || '文件'
  const openButton = document.createElement('button')
  openButton.type = 'button'
  openButton.className = 'artifact-open'
  openButton.title = name + ' — 点击用默认方式打开'
  openButton.setAttribute('aria-label', '打开 ' + name)
  const face = extensionBadge(document, item.name)
  openButton.append(face)
  const close = document.createElement('button')
  close.type = 'button'
  close.className = 'bubble-close'
  close.title = '关闭'
  close.setAttribute('aria-label', '关闭 ' + name + ' 泡泡')
  close.textContent = '×'
  close.addEventListener('click', event => {
    event.stopPropagation()
    bubbleEl.remove()
  })
  bubbleEl.append(openButton, close)
  let opening = false
  const activate = async () => {
    if (opening) return
    opening = true
    openButton.disabled = true
    try {
      if (await onOpen(item)) bubbleEl.remove()
    } catch { /* The page opener reports failures through its existing error surface. */
    } finally {
      opening = false
      if (bubbleEl.isConnected !== false) openButton.disabled = false
    }
  }
  openButton.addEventListener('click', () => { void activate() })
  openButton.addEventListener('keydown', event => {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    void activate()
  })
  return { element: bubbleEl, face, openButton, closeButton: close }
}

export function replaceArtifactBubbleFace({ document, face, iconDataUrl }) {
  if (!iconDataUrl || !face?.parentNode) return undefined
  const image = document.createElement('img')
  image.src = iconDataUrl
  image.alt = ''
  face.replaceWith(image)
  return image
}
