import type { CompanionDeliveryDecision, CompanionNoticeInput } from '../../shared/companion-contract'

import { useCompanionDirectorStore } from '../stores/companion-director'
import { useConversationStore } from '../stores/conversation'
import { claimPlaybackEffect, playbackReceiptsForSession, releaseUnstartedPlaybackEffect, updatePlaybackReceipt } from './playback-ledger'
import { remoteChatConsented } from '../../shared/experience-contract'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'

type ReminderPresenter = (notice: CompanionNoticeInput) => boolean
let reminderPresenter: ReminderPresenter | undefined

export function registerReminderPresenter(presenter: ReminderPresenter) {
  reminderPresenter = presenter
  return () => {
    if (reminderPresenter === presenter)
      reminderPresenter = undefined
  }
}

/** Shared immediate and deferred delivery path for unsolicited desktop notices. */
export async function deliverCompanionNotice(notice: CompanionNoticeInput, decision: CompanionDeliveryDecision,
  isCurrent: () => boolean = () => true): Promise<boolean> {
  if (decision.action !== 'deliver') return false
  const conversation = useConversationStore()
  const director = useCompanionDirectorStore()
  const sessionId = conversation.sessionId
  const identity = { sessionId, effectId: notice.id, kind: 'notice' as const }
  const canClaim = () => isCurrent() && director.evaluateNotice(notice).action === 'deliver'
    && (!notice.commitmentId || director.isCommitmentPending(notice.commitmentId))
    && sessionId === conversation.sessionId
  if (!await claimPlaybackEffect(identity, Date.now(), canClaim)) {
    if (notice.commitmentId && playbackReceiptsForSession(sessionId).some(receipt => receipt.effectId === notice.id))
      await director.recordDelivered(notice.id)
    if (notice.kind !== 'reach_out' && director.evaluateNotice(notice).action === 'defer')
      director.enqueueNotice(notice)
    return false
  }

  const currentDecision = director.evaluateNotice(notice)
  const commitmentStillPending = notice.commitmentId
    ? director.isCommitmentPending(notice.commitmentId)
    : true
  if (isCurrent() && notice.kind !== 'reach_out' && currentDecision.action === 'defer' && commitmentStillPending && sessionId === conversation.sessionId) {
    await releaseUnstartedPlaybackEffect(identity)
    director.enqueueNotice(notice)
    return false
  }
  if (!isCurrent() || currentDecision.action !== 'deliver' || !commitmentStillPending || sessionId !== conversation.sessionId) {
    await updatePlaybackReceipt(identity, 'skipped')
    return false
  }

  try {
    let accepted = false
    if (currentDecision.allowText) {
      if (notice.kind === 'reminder')
        accepted = reminderPresenter?.(notice) ?? false
      else
        accepted = conversation.addCompanionNoticeMessage(notice.id, notice.text, notice.kind === 'news')
    }
    if (currentDecision.allowSpeech && notice.reminder?.mainAccount !== false) {
      const canStart = notice.kind === 'news' || notice.kind === 'reach_out' ? async () => {
        try {
          const status = await window.meguriDesktop?.companionActivity?.status()
          const preferences = useDesktopPreferencesStore().value
          return Boolean(status?.presence.active && status.presence.activeUntil > Date.now()
            && sessionId === conversation.sessionId && remoteChatConsented(preferences.privacy)
            && preferences.companion.initiative !== 'off' && preferences.companion.focus === 'available')
        }
        catch { return false }
      } : undefined
      accepted = await conversation.playNoticeSpeech(notice.text, 'neutral', 'medium', notice.id, canStart) || accepted
    }
    if (!accepted) {
      await updatePlaybackReceipt(identity, 'skipped')
      return false
    }
    await director.recordDelivered(notice.id)
    await updatePlaybackReceipt(identity, 'completed')
    return true
  }
  catch {
    await updatePlaybackReceipt(identity, 'interrupted')
    return false
  }
}
