export interface TrainingResponse {
  reply: string
  expression_tag?: string
  expression_intensity?: string
  voice_style?: string
}

export interface TrainingCandidate {
  candidate_id: string
  response: TrainingResponse
}

export interface TrainingComparison {
  turn_id: string
  comparison_id: string
  training_mode: boolean
  candidates: TrainingCandidate[]
}

export interface TrainingReceipt {
  turn_id: string
  feedback_id: string
  status: 'training_candidate_saved' | 'revoked'
  selected_candidate_id?: string | null
  response?: TrainingResponse | null
}

export interface TrainingSubmission {
  turn_id: string
  selected_candidate_id?: string
  feedback_text: string
  consent_to_training: true
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function parseTrainingResponse(value: unknown): TrainingResponse {
  if (!record(value) || typeof value.reply !== 'string' || !value.reply.trim())
    throw new Error('反馈回复内容无效，请刷新后重试。')
  return {
    reply: value.reply,
    ...(typeof value.expression_tag === 'string' ? { expression_tag: value.expression_tag } : {}),
    ...(typeof value.expression_intensity === 'string' ? { expression_intensity: value.expression_intensity } : {}),
    ...(typeof value.voice_style === 'string' ? { voice_style: value.voice_style } : {}),
  }
}

export function parseTrainingComparison(value: unknown, turnId?: string): TrainingComparison {
  if (!record(value) || typeof value.comparison_id !== 'string' || !value.comparison_id
    || typeof (turnId ?? value.turn_id) !== 'string' || !(turnId ?? value.turn_id)
    || !Array.isArray(value.candidates) || value.candidates.length < 1 || value.candidates.length > 2)
    throw new Error('反馈候选无效，请刷新后重试。')
  const candidates = value.candidates.map(candidate => {
    if (!record(candidate) || typeof candidate.candidate_id !== 'string' || !candidate.candidate_id)
      throw new Error('反馈候选无效，请刷新后重试。')
    return { candidate_id: candidate.candidate_id, response: parseTrainingResponse(candidate.response) }
  })
  if (new Set(candidates.map(candidate => candidate.candidate_id)).size !== candidates.length)
    throw new Error('反馈候选重复，请刷新后重试。')
  return {
    turn_id: (turnId ?? value.turn_id) as string,
    comparison_id: value.comparison_id,
    training_mode: value.training_mode === true,
    candidates,
  }
}

export function parseTrainingReceipt(value: unknown): TrainingReceipt {
  if (!record(value) || typeof value.turn_id !== 'string' || !value.turn_id
    || typeof value.feedback_id !== 'string' || !value.feedback_id
    || !['training_candidate_saved', 'revoked'].includes(String(value.status)))
    throw new Error('没有收到有效的反馈回执，请刷新后查看。')
  return {
    turn_id: value.turn_id,
    feedback_id: value.feedback_id,
    status: value.status as TrainingReceipt['status'],
    ...(typeof value.selected_candidate_id === 'string' ? { selected_candidate_id: value.selected_candidate_id } : {}),
    ...(value.response ? { response: parseTrainingResponse(value.response) } : {}),
  }
}
