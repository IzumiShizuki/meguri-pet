export type CompanionInitiative = 'off' | 'low' | 'normal' | 'high'
export type CompanionFocus = 'available' | 'focused'
export type CompanionSurface = 'ambient' | 'galgame'

export type CompanionNoticeKind = 'news' | 'reminder' | 'commitment_followup' | 'reach_out'

export interface CompanionNoticeInput {
  id: string
  kind: CompanionNoticeKind
  text: string
  createdAt: number
  expiresAt: number
  surface?: CompanionSurface
  commitmentId?: string
  reminder?: { todoId: number, title: string, triggerAt: number, mainAccount: boolean, late?: boolean }
}

export interface CompanionRuntimeContext {
  focus: CompanionFocus
  activeTurn: boolean
  speaking: boolean
  voiceActive?: boolean
  surface: CompanionSurface
}

export interface CompanionDeliveryDecision {
  action: 'deliver' | 'defer' | 'drop'
  allowText: boolean
  allowSpeech: boolean
  allowMotion: boolean
  reason: 'eligible' | 'muted' | 'focus' | 'active_turn' | 'cooldown' | 'initiative_off' | 'expired' | 'queue_full'
  retryAt?: number
}

export interface CompanionCommitment {
  id: string
  text: string
  dueAt: number
  createdAt: number
  updatedAt?: number
  status: 'pending' | 'completed' | 'cancelled'
  followUpDeliveredAt?: number
}

export interface CompanionPreferences {
  initiative: CompanionInitiative
  focus: CompanionFocus
  expressionsEnabled: boolean
  motionEnabled: boolean
  commitments: CompanionCommitment[]
}

export type CompanionPreferencesPatch = Partial<Omit<CompanionPreferences, 'commitments'>> & {
  commitments?: CompanionCommitment[]
}
