/** A/B selection never implies consent; retries reuse the submitted payload key. */
export function prepareTrainingFeedback(turn, selectedCandidateId, feedbackText, consent) {
  if (!turn?.turnId || !turn?.sessionId) throw new Error('请先完成一轮对话。')
  if (consent !== true) throw new Error('请明确同意本次数据用于离线训练。')
  const text = String(feedbackText ?? '').trim()
  if (text.length > 2000) throw new Error('纠错说明最多 2000 字。')
  if (!selectedCandidateId && !text) throw new Error('请选择一个回答，或填写具体纠错说明。')
  const body = { turn_id: turn.turnId, selected_candidate_id: selectedCandidateId || null,
    feedback_text: text, consent_to_training: true }
  const fingerprint = JSON.stringify(body)
  if (turn.feedbackFingerprint !== fingerprint || !turn.feedbackRequestId) {
    turn.feedbackFingerprint = fingerprint
    turn.feedbackRequestId = 'airi-feedback-' + crypto.randomUUID()
  }
  return { body, requestId: turn.feedbackRequestId, sessionId: turn.sessionId }
}
