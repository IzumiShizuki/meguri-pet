import { describe, expect, it, vi } from 'vitest'
import { compareSemanticVersions, createDesktopUpdateChecker, DESKTOP_RELEASE_API, parseSemanticVersion, verifiedReleaseUrl } from './desktop-updates.mjs'

const released = (overrides = {}) => new Response(JSON.stringify({ tag_name: 'v0.2.0', name: 'Meguri 0.2', html_url: 'https://github.com/IzumiShizuki/meguri-pet/releases/tag/v0.2.0', draft: false, prerelease: false, ...overrides }), { status: 200 })
describe('desktop release checking', () => {
  it('compares numeric components, prerelease identifiers and build metadata', () => {
    expect(compareSemanticVersions('v1.10.0', '1.9.0')).toBe(1)
    expect(compareSemanticVersions('1.0.0-beta.10', '1.0.0-beta.2')).toBe(1)
    expect(compareSemanticVersions('1.0.0', '1.0.0-rc.1')).toBe(1)
    expect(compareSemanticVersions('1.0.0+build.2', '1.0.0+build.1')).toBe(0)
    for (const version of ['01.0.0', '1.2', '1.0.0-01', 'latest']) expect(parseSemanticVersion(version)).toBeUndefined()
  })
  it('limits opening to the exact HTTPS repository release URL', () => {
    expect(verifiedReleaseUrl('https://github.com/IzumiShizuki/meguri-pet/releases/tag/v0.2.0')).toBeDefined()
    for (const url of ['https://github.com/evil/repo/releases/tag/v1', 'javascript:alert(1)', 'https://github.com.evil.test/IzumiShizuki/meguri-pet/releases/tag/v1', 'https://github.com/IzumiShizuki/meguri-pet/releases/tag/v1?token=secret', 'https://user:secret@github.com/IzumiShizuki/meguri-pet/releases/tag/v1']) expect(verifiedReleaseUrl(url)).toBeUndefined()
  })
  it('checks one fixed endpoint and opens only a verified release', async () => {
    const fetchImpl = vi.fn(async (_url: string | URL | Request, _init?: RequestInit) => released())
    const openExternal = vi.fn(async () => undefined)
    const checker = createDesktopUpdateChecker({ currentVersion: '0.1.0', fetchImpl, openExternal })
    expect((await checker.openRelease()).ok).toBe(false)
    expect((await checker.check()).status).toBe('available')
    expect(fetchImpl.mock.calls[0]?.[0]).toBe(DESKTOP_RELEASE_API)
    expect((await checker.openRelease()).ok).toBe(true)
    expect(openExternal).toHaveBeenCalledWith('https://github.com/IzumiShizuki/meguri-pet/releases/tag/v0.2.0')
  })
  it('reports current, missing release, unavailable and rate limits accurately', async () => {
    for (const [response, status] of [[released(), 'current'], [new Response('', { status: 404 }), 'no_release'], [new Response('', { status: 503 }), 'unavailable'], [new Response('', { status: 429 }), 'rate_limited'], [new Response('', { status: 403, headers: { 'x-ratelimit-remaining': '0' } }), 'rate_limited']] as const) {
      const checker = createDesktopUpdateChecker({ currentVersion: '0.2.0', fetchImpl: async () => response })
      expect((await checker.check()).status).toBe(status)
    }
  })
  it('preserves the previous verified version across network failures without claiming current', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(released()).mockRejectedValueOnce(new Error('secret token'))
    const checker = createDesktopUpdateChecker({ currentVersion: '0.1.0', fetchImpl })
    await checker.check()
    const state = await checker.check()
    expect(state.status).toBe('unavailable')
    expect(state.latestVersion).toBe('0.2.0')
    expect(state.message).not.toContain('secret')
  })
  it('rejects prereleases, invalid tags, unsafe release URLs and deduplicates concurrent requests', async () => {
    for (const overrides of [{ prerelease: true }, { tag_name: 'latest' }, { tag_name: 'v0.3.0-beta' }, { html_url: 'https://evil.test' }]) {
      const checker = createDesktopUpdateChecker({ currentVersion: '0.1.0', fetchImpl: async () => released(overrides) })
      expect((await checker.check()).status).toBe('unavailable')
    }
    const fetchImpl = vi.fn(async () => released())
    const checker = createDesktopUpdateChecker({ currentVersion: '0.1.0', fetchImpl })
    await Promise.all([checker.check(), checker.check()])
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })
})
