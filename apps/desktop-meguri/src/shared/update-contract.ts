export type DesktopUpdateStatus = 'idle' | 'checking' | 'available' | 'current' | 'no_release' | 'unavailable' | 'rate_limited'

export interface DesktopUpdateState {
  status: DesktopUpdateStatus
  currentVersion: string
  latestVersion?: string
  releaseName?: string
  releaseUrl?: string
  publishedAt?: string
  checkedAt?: string
  message: string
}
