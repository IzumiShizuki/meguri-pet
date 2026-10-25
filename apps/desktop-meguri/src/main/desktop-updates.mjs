export const DESKTOP_RELEASE_REPOSITORY = 'IzumiShizuki/meguri-pet'
export const DESKTOP_RELEASE_API = `https://api.github.com/repos/${DESKTOP_RELEASE_REPOSITORY}/releases/latest`

export function parseSemanticVersion(value) {
  const match = typeof value === 'string' && value.length <= 128 && /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/u.exec(value)
  if (!match || match[4]?.split('.').some(part => /^0\d+$/u.test(part))) return undefined
  return { major: BigInt(match[1]), minor: BigInt(match[2]), patch: BigInt(match[3]), prerelease: match[4]?.split('.') ?? [], version: value.replace(/^v/u, '') }
}

export function compareSemanticVersions(left, right) {
  const a = parseSemanticVersion(left)
  const b = parseSemanticVersion(right)
  if (!a || !b) throw new Error('Invalid semantic version')
  for (const key of ['major', 'minor', 'patch']) {
    if (a[key] !== b[key]) return a[key] > b[key] ? 1 : -1
  }
  if (!a.prerelease.length && !b.prerelease.length) return 0
  if (!a.prerelease.length) return 1
  if (!b.prerelease.length) return -1
  for (let index = 0; index < Math.max(a.prerelease.length, b.prerelease.length); index++) {
    const x = a.prerelease[index]
    const y = b.prerelease[index]
    if (x === undefined) return -1
    if (y === undefined) return 1
    if (x === y) continue
    const xNumeric = /^\d+$/u.test(x)
    const yNumeric = /^\d+$/u.test(y)
    if (xNumeric && yNumeric) return BigInt(x) > BigInt(y) ? 1 : -1
    if (xNumeric !== yNumeric) return xNumeric ? -1 : 1
    return x > y ? 1 : -1
  }
  return 0
}

export function verifiedReleaseUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === 'github.com' && !url.port && !url.username && !url.password
      && !url.search && !url.hash && url.pathname.startsWith(`/${DESKTOP_RELEASE_REPOSITORY}/releases/tag/`)
      && /^\/IzumiShizuki\/meguri-pet\/releases\/tag\/[^/]+$/u.test(url.pathname)
      ? url.href : undefined
  }
  catch { return undefined }
}

/** @param {{currentVersion?: string, fetchImpl?: typeof fetch, now?: () => Date, openExternal?: (url: string) => unknown}} [options] */
export function createDesktopUpdateChecker({ currentVersion, fetchImpl = fetch, now = () => new Date(), openExternal } = {}) {
  let state = { status: 'idle', currentVersion: String(currentVersion ?? ''), message: '尚未检查应用更新。' }
  let pending
  const get = () => structuredClone(state)
  const check = () => {
    if (pending) return pending
    state = { ...state, status: 'checking', message: '正在查询 GitHub 的最新正式版本。' }
    pending = (async () => {
      const checkedAt = now().toISOString()
      const fail = (status, message) => {
        state = { ...state, status, message, checkedAt }
        return get()
      }
      if (!parseSemanticVersion(state.currentVersion)) return fail('unavailable', '当前应用版本格式无效，无法比较更新。')
      try {
        const response = await fetchImpl(DESKTOP_RELEASE_API, {
          headers: { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'Meguri-Desktop' },
          signal: AbortSignal.timeout(10_000), redirect: 'error',
        })
        if (response.status === 404) {
          state = { currentVersion: state.currentVersion, status: 'no_release', checkedAt, message: '此仓库尚未发布可用的正式版本。' }
          return get()
        }
        if (response.status === 429 || (response.status === 403 && (response.headers.get('x-ratelimit-remaining') === '0' || response.headers.has('retry-after'))))
          return fail('rate_limited', 'GitHub 请求受到限流，请稍后重新检查。')
        if (!response.ok) return fail('unavailable', `GitHub 暂时无法提供版本信息（HTTP ${response.status}），请稍后重试。`)
        const body = await response.text()
        if (body.length > 512 * 1024) return fail('unavailable', '版本响应超过大小限制，请稍后重试。')
        const release = JSON.parse(body)
        const version = parseSemanticVersion(release.tag_name)
        const releaseUrl = verifiedReleaseUrl(release.html_url)
        if (!version || version.prerelease.length || release.draft || release.prerelease || !releaseUrl)
          return fail('unavailable', 'GitHub 返回的正式版本信息未通过校验。')
        const available = compareSemanticVersions(version.version, state.currentVersion) > 0
        state = {
          currentVersion: state.currentVersion, status: available ? 'available' : 'current', latestVersion: version.version,
          releaseUrl, releaseName: String(release.name || release.tag_name).replace(/[\u0000-\u001f\u007f]/gu, '').slice(0, 160),
          publishedAt: typeof release.published_at === 'string' && Number.isFinite(Date.parse(release.published_at)) ? new Date(release.published_at).toISOString() : undefined,
          checkedAt, message: available ? `发现新版本 ${version.version}。` : '当前应用已包含最新正式版本。',
        }
        return get()
      }
      catch { return fail('unavailable', '无法连接 GitHub 或版本信息无效，请检查网络后重试。') }
    })().finally(() => { pending = undefined })
    return pending
  }
  const openRelease = async () => {
    const url = verifiedReleaseUrl(state.releaseUrl)
    if (!url || !openExternal) return { ok: false, error: '当前没有通过校验的版本页面，请先检查更新。' }
    try { await openExternal(url); return { ok: true } }
    catch { return { ok: false, error: '无法打开版本页面，请重试。' } }
  }
  return { get, check, openRelease }
}
