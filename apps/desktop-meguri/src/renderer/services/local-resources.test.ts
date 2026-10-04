import { beforeEach, describe, expect, it, vi } from 'vitest'

const { desktopFetchMock } = vi.hoisted(() => ({ desktopFetchMock: vi.fn() }))

vi.mock('./desktop-fetch', () => ({
  coreUrl: 'http://127.0.0.1:15173/core',
  desktopFetch: desktopFetchMock,
}))

import {
  attachmentAccessLabel,
  isReadableAttachment,
  normalizeReadableAttachment,
  resourceMarkerPresent,
  resolveLocalResourceInput,
  searchLocalResources,
  stagedReadableBytes,
  toLocalResourceAttachment,
} from './local-resources'

describe('local Everything resources', () => {
  beforeEach(() => desktopFetchMock.mockReset())

  it('detects AIRI-compatible resource markers without mistaking email addresses for one', () => {
    expect(resourceMarkerPresent('@日报')).toBe(true)
    expect(resourceMarkerPresent('帮我看看 @{季度 报告}')).toBe(true)
    expect(resourceMarkerPresent('帮我看看 @"季度 报告"')).toBe(true)
    expect(resourceMarkerPresent('帮我看看 @')).toBe(true)
    expect(resourceMarkerPresent('请发到 me@example.com')).toBe(false)
  })

  it('uses the canonical input resolver and preserves its message and query', async () => {
    desktopFetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      kind: 'resource',
      message: '帮我总结 的重点',
      arguments: '季度 报告',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const resolution = await resolveLocalResourceInput('session-1', '帮我总结 @{季度 报告} 的重点')

    expect(resolution).toMatchObject({ kind: 'resource', message: '帮我总结 的重点', arguments: '季度 报告' })
    expect(desktopFetchMock).toHaveBeenCalledWith(
      'session-1',
      'http://127.0.0.1:15173/core/v1/input/resolve',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ message: '帮我总结 @{季度 报告} 的重点' }),
      }),
    )
  })

  it('bounds Everything results and rejects malformed candidates at the renderer boundary', async () => {
    desktopFetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      query: '日报',
      available: true,
      message: '请选择要引用的资源',
      candidates: [
        { id: 'r-1', name: '日报.md', path: 'D:\\notes\\日报.md', kind: 'file', size_bytes: 42, modified_at: '2026-08-13T01:00:00Z' },
        { id: '', name: 'bad.md', path: 'D:\\bad.md' },
        { id: 'r-3', name: '', path: 'D:\\bad-2.md' },
      ],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const result = await searchLocalResources('session-1', '日报')

    expect(result.candidates).toHaveLength(1)
    expect(result.candidates[0]).toMatchObject({ id: 'r-1', name: '日报.md' })
    expect(String(desktopFetchMock.mock.calls[0][1])).toContain('/v1/resources/search?q=%E6%97%A5%E6%8A%A5&limit=8')
    expect(toLocalResourceAttachment(result.candidates[0])).toEqual({
      type: 'local_file_reference',
      source: 'everything',
      reference_id: 'r-1',
      name: '日报.md',
      path: 'D:\\notes\\日报.md',
      kind: 'file',
      size_bytes: 42,
      modified_at: '2026-08-13T01:00:00Z',
      content_access: 'not_read',
    })
    expect(toLocalResourceAttachment({ id: 'r-2', name: 'missing-path', path: '' })).toBeNull()
  })

  it('surfaces a specific recoverable error without swallowing the draft', async () => {
    desktopFetchMock.mockResolvedValueOnce(new Response('offline', { status: 503 }))

    await expect(resolveLocalResourceInput('session-1', '@日报'))
      .rejects.toThrow('无法解析本地资源引用（503）')
  })

  it('normalizes only the readable attachment shapes accepted by Core', () => {
    const document = normalizeReadableAttachment({
      type: 'local_file_reference',
      source: 'everything',
      reference_id: 'local-1',
      name: 'notes.md',
      path: 'D:\\notes\\notes.md',
      kind: 'document',
      size_bytes: 42,
      modified_at: '2026-08-19T08:00:00.000Z',
      content_access: 'document_read',
    })
    const screenshot = normalizeReadableAttachment({
      type: 'inline_attachment',
      source: 'airi_inline',
      reference_id: 'inline-1',
      name: '截图.png',
      kind: 'image',
      size_bytes: 4,
      modified_at: '2026-08-19T08:00:00.000Z',
      content_access: 'multimodal_read',
      mime_type: 'image/png',
      data_url: 'data:image/png;base64,iVBORw==',
    })

    expect(document && isReadableAttachment(document)).toBe(true)
    expect(screenshot && attachmentAccessLabel(screenshot)).toBe('截图，将请求视觉读取')
    expect(stagedReadableBytes([document!, screenshot!])).toBe(46)
    expect(normalizeReadableAttachment({ ...document, source: 'renderer_forged' })).toBeNull()
    expect(normalizeReadableAttachment({ ...screenshot, data_url: 'https://example.com/image.png' })).toBeNull()
  })
})
