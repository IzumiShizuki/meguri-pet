const capabilities = new Set(['gateway', 'core', 'tts', 'assets', 'renderer'])
const readinessStatuses = new Set(['pending', 'checking', 'ready', 'failed'])
const failureCodes = new Set(['STARTUP_FAILED', 'IPC_FAILED', 'RESOURCE_FAILED', 'UPDATE_FAILED', 'VOICE_FAILED', 'MEMORY_UNAVAILABLE', 'UNKNOWN'])
const failureSources = new Set(['startup', 'ipc', 'resource', 'updates', 'voice', 'memory', 'runtime'])
const safeVersion = value => typeof value === 'string' && /^[0-9A-Za-z.+_-]{1,64}$/u.test(value) ? value : 'unknown'

/** Deliberately project a whitelist. Never serialize raw exceptions or account/configuration documents.
 * @param {{appVersion?: string, runtimeVersions?: Record<string, unknown>, platform?: string, readiness?: any, preferences?: any, failures?: Array<Record<string, unknown>>, now?: Date}} [options]
 */
export function createDesktopDiagnosticsReport({ appVersion, runtimeVersions = {}, platform, readiness, preferences, failures = [], now = new Date() } = {}) {
  const report = {
    schemaVersion: 1,
    createdAt: now.toISOString(),
    application: { name: 'Meguri Desktop', version: safeVersion(appVersion) },
    runtime: {
      platform: ['win32', 'darwin', 'linux'].includes(platform) ? platform : 'unknown',
      electron: safeVersion(runtimeVersions.electron), chrome: safeVersion(runtimeVersions.chrome), node: safeVersion(runtimeVersions.node),
    },
    readiness: {
      status: ['checking', 'ready', 'failed'].includes(readiness?.status) ? readiness.status : 'unknown',
      items: (Array.isArray(readiness?.items) ? readiness.items : []).slice(0, 8).filter(item => capabilities.has(item.capability)).map(item => ({
        capability: item.capability, status: readinessStatuses.has(item.status) ? item.status : 'unknown',
      })),
    },
    preferences: {
      appearance: ['system', 'light', 'dark'].includes(preferences?.appearance) ? preferences.appearance : 'unknown',
      hasWallpaper: Boolean(preferences?.chat?.wallpaperDataUrl),
      reducedMotion: preferences?.character?.reducedMotion === true,
      speechEnabled: preferences?.speech?.ttsEnabled === true,
      formalMemoryEnabled: preferences?.memory?.enabled === true,
      remoteChatAllowed: preferences?.privacy?.remoteChatAllowed === true,
      localHistoryEnabled: preferences?.privacy?.saveHistory === true,
    },
    failures: (Array.isArray(failures) ? failures : []).slice(-30).map(item => ({
      source: failureSources.has(item?.source) ? item.source : 'runtime',
      code: failureCodes.has(item?.code) ? item.code : 'UNKNOWN',
      ...(typeof item?.at === 'string' && Number.isFinite(Date.parse(item.at)) ? { at: new Date(item.at).toISOString() } : {}),
    })),
    excluded: ['conversation text', 'credentials', 'account identifiers', 'private configuration', 'file paths', 'raw exception messages'],
  }
  return report
}
