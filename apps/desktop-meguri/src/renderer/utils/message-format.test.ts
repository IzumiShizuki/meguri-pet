import { describe, expect, it } from 'vitest'
import { renderMessageMarkdown, safeMessageLink } from './message-format'

describe('safe rich conversation', () => {
  it('renders headings, lists, tables, code and both math delimiters', () => {
    const result = renderMessageMarkdown('# 回复\n\n- 第一项\n- 第二项\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\n行内 $x^2$ 与 \\(y+1\\)\n\n$$\n\\frac{a}{b}\n$$')
    expect(result).toContain('<h1>回复</h1>')
    expect(result).toContain('<ul>')
    expect(result).toContain('<table>')
    expect(result).toContain('katex')
    expect(result).toContain('message-math-block')
  })
  it('escapes raw HTML and prevents all image network requests', () => {
    const result = renderMessageMarkdown('<script>alert(1)</script>\n<img src="https://evil.test/a" onerror="alert(1)">\n\n![图片](https://evil.test/tracker.png)')
    expect(result).not.toMatch(/<(script|img)\b/u)
    expect(result).toContain('&lt;script&gt;')
    expect(result).toContain('图片未加载')
  })
  it('accepts only absolute HTTP(S) links without embedded credentials', () => {
    for (const value of ['javascript:alert(1)', 'data:text/html,a', 'file:///C:/a', '/local', '//evil.test', 'https://user:password@evil.test']) expect(safeMessageLink(value)).toBe(false)
    expect(safeMessageLink('https://example.com/docs')).toBe(true)
    const result = renderMessageMarkdown('[安全](https://example.com) [危险](javascript:alert(1))')
    expect(result).toContain('target="_blank"')
    expect(result).toContain('noopener noreferrer')
    expect(result).not.toContain('href="javascript:')
  })
  it('keeps formulas inside code as source and malformed math readable', () => {
    const code = renderMessageMarkdown('`$x$`\n\n```tex\n$$x$$\n```')
    expect(code).not.toContain('class="katex"')
    expect(code).toContain('<code>$x$</code>')
    expect(renderMessageMarkdown('$\\notAValidCommand{x}$')).toContain('math-fallback')
    expect(renderMessageMarkdown('unfinished $x')).toContain('unfinished $x')
  })
  it('blocks trusted math links, image injection and persistent macros', () => {
    const result = renderMessageMarkdown('$\\href{javascript:alert(1)}{x}$\n\n$$\\includegraphics{https://evil.test/a.png}$$')
    expect(result).not.toMatch(/<(a|img)\b/u)
    renderMessageMarkdown('$\\gdef\\attack{x}$')
    expect(renderMessageMarkdown('$\\attack$')).toContain('math-fallback')
  })
})
