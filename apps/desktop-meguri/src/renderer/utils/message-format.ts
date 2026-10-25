import MarkdownIt from 'markdown-it'
import katex from 'katex'

const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: false })

export function safeMessageLink(value: string): boolean {
  try {
    const url = new URL(value)
    return (url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password
  }
  catch { return false }
}

markdown.validateLink = safeMessageLink
markdown.renderer.rules.link_open = (tokens, index, options, environment, renderer) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  tokens[index].attrSet('referrerpolicy', 'no-referrer')
  return renderer.renderToken(tokens, index, options)
}
markdown.renderer.rules.image = (tokens, index) => {
  const alt = tokens[index].content || '图片'
  return `<span class="message-image-placeholder">[${markdown.utils.escapeHtml(alt)} · 图片未加载]</span>`
}

function mathMarkup(source: string, display: boolean): string {
  if (source.length > 8192) return `<code class="math-fallback">${markdown.utils.escapeHtml(source)}</code>`
  try {
    return katex.renderToString(source, {
      displayMode: display, throwOnError: true, trust: false, strict: 'ignore',
      maxSize: 20, maxExpand: 500, output: 'htmlAndMathml', macros: {},
    })
  }
  catch { return `<code class="math-fallback">${markdown.utils.escapeHtml(source)}</code>` }
}

markdown.inline.ruler.before('escape', 'meguri_math', (state, silent) => {
  const start = state.pos
  let opening = ''
  let closing = ''
  if (state.src.startsWith('\\(', start)) { opening = '\\('; closing = '\\)' }
  else if (state.src[start] === '$' && state.src[start + 1] !== '$') { opening = '$'; closing = '$' }
  else return false
  const contentStart = start + opening.length
  if (/\s/u.test(state.src[contentStart] ?? '')) return false
  let end = state.src.indexOf(closing, contentStart)
  while (end !== -1 && state.src[end - 1] === '\\') end = state.src.indexOf(closing, end + closing.length)
  if (end === -1 || end >= state.posMax || /\n/u.test(state.src.slice(contentStart, end))) return false
  if (opening === '$' && (/\s/u.test(state.src[end - 1] ?? '') || /\d/u.test(state.src[end + 1] ?? ''))) return false
  if (!silent) {
    const token = state.push('meguri_math_inline', '', 0)
    token.content = state.src.slice(contentStart, end)
  }
  state.pos = end + closing.length
  return true
})
markdown.renderer.rules.meguri_math_inline = (tokens, index) => mathMarkup(tokens[index].content, false)

markdown.block.ruler.before('fence', 'meguri_math_block', (state, startLine, endLine, silent) => {
  if (state.sCount[startLine] - state.blkIndent >= 4) return false
  const start = state.bMarks[startLine] + state.tShift[startLine]
  const firstLine = state.src.slice(start, state.eMarks[startLine]).trim()
  const opening = firstLine.startsWith('$$') ? '$$' : firstLine.startsWith('\\[') ? '\\[' : ''
  if (!opening) return false
  const closing = opening === '$$' ? '$$' : '\\]'
  const lines: string[] = []
  let nextLine = startLine
  let closed = false
  for (; nextLine < endLine; nextLine++) {
    const content = state.src.slice(state.bMarks[nextLine] + state.tShift[nextLine], state.eMarks[nextLine])
    const piece = nextLine === startLine ? content.slice(opening.length) : content
    const closeAt = piece.indexOf(closing)
    if (closeAt !== -1) {
      if (piece.slice(closeAt + closing.length).trim()) return false
      lines.push(piece.slice(0, closeAt))
      closed = true
      break
    }
    lines.push(piece)
  }
  if (!closed) return false
  if (silent) return true
  const token = state.push('meguri_math_block', '', 0)
  token.block = true
  token.content = lines.join('\n').trim()
  token.map = [startLine, nextLine + 1]
  state.line = nextLine + 1
  return true
})
markdown.renderer.rules.meguri_math_block = (tokens, index) => `<div class="message-math-block">${mathMarkup(tokens[index].content, true)}</div>\n`

/** Only parser-produced HTML is rendered. HTML input, images and unsafe links are disabled. */
export function renderMessageMarkdown(source: string): string {
  if (source.length > 120_000) return `<p>${markdown.utils.escapeHtml(source).replace(/\n/gu, '<br>')}</p>`
  try { return markdown.render(source) }
  catch { return `<p>${markdown.utils.escapeHtml(source).replace(/\n/gu, '<br>')}</p>` }
}
