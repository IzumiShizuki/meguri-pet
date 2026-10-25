import type {
  CompanionDeliveryDecision,
  CompanionInitiative,
  CompanionNoticeInput,
  CompanionRuntimeContext,
} from '../../shared/companion-contract'

export interface CompanionPolicyInput {
  initiative: CompanionInitiative
  context: CompanionRuntimeContext
  notice: CompanionNoticeInput
  now: number
  lastDeliveredAt: number
  pendingCount: number
  speechEnabled: boolean
  volume: number
  surfaceEnabled: boolean
  expressionsEnabled: boolean
  motionEnabled: boolean
}

const hour = 60 * 60 * 1000

export function getCompanionLimits(initiative: CompanionInitiative) {
  switch (initiative) {
    case 'off':
      return { maxDeferred: 1, maxAgeMs: 24 * hour, cooldownMs: 0 }
    case 'low':
      return { maxDeferred: 2, maxAgeMs: 24 * hour, cooldownMs: 4 * hour }
    case 'normal':
      return { maxDeferred: 4, maxAgeMs: 12 * hour, cooldownMs: 90 * 60 * 1000 }
    case 'high':
      return { maxDeferred: 6, maxAgeMs: 8 * hour, cooldownMs: 30 * 60 * 1000 }
  }
}

function defer(input: CompanionPolicyInput, reason: CompanionDeliveryDecision['reason'], retryAt?: number): CompanionDeliveryDecision {
  const canQueue = input.pendingCount < getCompanionLimits(input.initiative).maxDeferred
  return canQueue
    ? { action: 'defer', allowText: false, allowSpeech: false, allowMotion: false, reason, ...(retryAt ? { retryAt } : {}) }
    : { action: 'drop', allowText: false, allowSpeech: false, allowMotion: false, reason: 'queue_full' }
}

export function decideCompanionNotice(input: CompanionPolicyInput): CompanionDeliveryDecision {
  const { notice, now, initiative, context } = input
  if (!notice.id
    || typeof notice.text !== 'string'
    || !notice.text.trim()
    || !Number.isFinite(notice.createdAt)
    || !Number.isFinite(notice.expiresAt)
    || notice.expiresAt <= now)
    return { action: 'drop', allowText: false, allowSpeech: false, allowMotion: false, reason: 'expired' }

  if (initiative === 'off' && (notice.kind === 'news' || notice.kind === 'reach_out'))
    return { action: 'drop', allowText: false, allowSpeech: false, allowMotion: false, reason: 'initiative_off' }

  if (context.focus === 'focused')
    return defer(input, 'focus')
  if (context.activeTurn || context.speaking || context.voiceActive)
    return defer(input, 'active_turn')

  const cooldown = getCompanionLimits(initiative).cooldownMs
  const usesCooldown = notice.kind === 'news' || notice.kind === 'commitment_followup' || notice.kind === 'reach_out'
  const retryAt = input.lastDeliveredAt + cooldown
  if (usesCooldown && cooldown > 0 && input.lastDeliveredAt > 0 && now < retryAt)
    return defer(input, 'cooldown', retryAt)

  const allowSpeech = notice.kind !== 'commitment_followup'
    && input.speechEnabled
    && input.volume > 0
    && input.surfaceEnabled
  const allowMotion = notice.kind !== 'commitment_followup'
    && initiative !== 'off'
    && input.expressionsEnabled
    && input.motionEnabled

  return {
    action: 'deliver',
    allowText: true,
    allowSpeech,
    allowMotion,
    reason: allowSpeech ? 'eligible' : 'muted',
  }
}
