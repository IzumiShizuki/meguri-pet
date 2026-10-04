import { beforeEach, describe, expect, it, vi } from 'vitest'

const desktopFetch = vi.hoisted(() => vi.fn())

vi.mock('./desktop-fetch', () => ({
  coreUrl: 'http://127.0.0.1:15173/core',
  desktopFetch,
}))

import { SkillApiError, skillsApi } from './skills-api'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('Skills management API client', () => {
  beforeEach(() => desktopFetch.mockReset())

  it('encodes ModelScope search and detail parameters', async () => {
    desktopFetch.mockResolvedValueOnce(jsonResponse({ items: [], page: 2, page_size: 20, total: 0 }))
    desktopFetch.mockResolvedValueOnce(jsonResponse({
      summary: { external_id: '@owner/pdf skill', name: 'PDF', description: '', tags: [], revision: '1' },
      requirements: { tools: [], env: [] },
      metadata: {},
    }))

    await skillsApi.search('session-1', 'PDF 表格', 2)
    await skillsApi.detail('session-1', '@owner/pdf skill')

    expect(desktopFetch.mock.calls[0][1]).toContain('/internal/v1/skill-sources/modelscope/skills?')
    expect(new URL(desktopFetch.mock.calls[0][1]).searchParams.get('q')).toBe('PDF 表格')
    expect(new URL(desktopFetch.mock.calls[0][1]).searchParams.get('page')).toBe('2')
    expect(new URL(desktopFetch.mock.calls[1][1]).searchParams.get('skill_id')).toBe('@owner/pdf skill')
  })

  it('sends explicit import and license-confirmed activation commands', async () => {
    desktopFetch.mockResolvedValue(jsonResponse({ manifest: {}, revisions: [], binding: {} }))

    await skillsApi.importSkill('session-2', '@MiniMax-AI/minimax-pdf')
    await skillsApi.activate('session-2', 'skill/pdf', 'a'.repeat(64), true)

    expect(desktopFetch.mock.calls[0][2]).toMatchObject({
      method: 'POST',
      body: JSON.stringify({ source_id: 'modelscope', external_id: '@MiniMax-AI/minimax-pdf' }),
    })
    expect(desktopFetch.mock.calls[1][1]).toContain('/skills/skill%2Fpdf/revisions/')
    expect(desktopFetch.mock.calls[1][2]).toMatchObject({
      method: 'POST',
      body: JSON.stringify({ license_confirmed: true }),
    })
  })

  it('preserves structured failure codes for unavailable sources', async () => {
    desktopFetch.mockResolvedValueOnce(jsonResponse({
      code: 'SKILL_SOURCE_DISABLED',
      message: 'ModelScope source is disabled',
      retryable: false,
    }, 503))

    await expect(skillsApi.search('session-3', 'pdf')).rejects.toEqual(expect.objectContaining<Partial<SkillApiError>>({
      name: 'SkillApiError',
      code: 'SKILL_SOURCE_DISABLED',
      retryable: false,
      status: 503,
    }))
  })
})
