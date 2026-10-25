export const PRIVACY_POLICY_VERSION = '2026-10-05'

export type DesktopColorPalette = 'system' | 'amber' | 'rose' | 'mint' | 'blue' | 'violet'

export interface DesktopMemoryPreferences {
  enabled: boolean
  autoSummarize: boolean
  summaryEveryTurns: number
}

export interface DesktopPrivacyPreferences {
  acceptedPolicyVersion: string
  remoteChatAllowed: boolean
  saveHistory: boolean
  checkUpdatesOnStartup: boolean
}

export interface DesktopWallpaperResult {
  ok: boolean
  dataUrl?: string
  error?: string
}

export interface DesktopDiagnosticResult {
  ok: boolean
  path?: string
  error?: string
}

export function remoteChatConsented(privacy: DesktopPrivacyPreferences): boolean {
  return privacy.acceptedPolicyVersion === PRIVACY_POLICY_VERSION && privacy.remoteChatAllowed
}
