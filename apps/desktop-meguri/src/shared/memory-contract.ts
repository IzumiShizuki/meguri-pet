export interface DesktopMemoryStatus {
  available: boolean
  authorized: boolean
  provider: string
  reason: string
  capabilities?: string[]
  userId?: string
  clientId?: string
  sessionId?: string
}

export interface DesktopMemoryRecord {
  memory_id: string
  canonical_text: string
  memory_type: string
  status: 'active' | 'deleted' | 'superseded'
  updated_at: string
}

export interface DesktopMemoryCandidate {
  candidate_id: string
  content_text: string
  memory_type: string
  status: string
  review_reason?: string | null
  updated_at: string
  merge_policy?: string
  base_version_id?: string | null
}

export interface DesktopMemoryDetail {
  memory_id: string
  memory_type: string
  status: string
  updated_at: string
  current_version_id: string
  content_text: string
  version_no: number | null
  change_reason: string | null
  source: { client_id: string | null, session_id: string | null, kind: string | null }
  can_correct: boolean
}

export interface DesktopMemoryCorrection {
  base_version_id: string
  content_text: string
  change_reason: string
}

export interface DesktopMemorySummary {
  status: string
  summary: string
  message_count: number
  candidate_ids: string[]
  checkpoint: number
}
