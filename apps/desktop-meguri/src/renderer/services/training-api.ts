import { parseTrainingComparison, parseTrainingReceipt, type TrainingSubmission } from '../../shared/training-contract'
import { coreUrl, desktopFetch } from './desktop-fetch'

const prefix = `${coreUrl}/v1/training/desktop`
const reasons: Record<string, string> = {
  desktop_auth_not_configured: '反馈服务身份验证尚未配置，请使用最新启动器重启 Core。',
  desktop_auth_required: '反馈身份验证失败，请检查桌面网关与 Core 的配置。',
  desktop_identity_mismatch: '反馈身份不匹配，请重新打开桌面应用。',
  training_scope_mismatch: '这条反馈不属于当前对话身份。',
  feedback_idempotency_conflict: '这次提交已保存为另一份内容，请刷新回执后查看。',
  training_comparison_unavailable: '这轮反馈候选已不可用，请刷新状态。',
  training_feedback_unavailable: '没有找到这条反馈，请刷新回执。',
  training_feedback_invalid: '请选择回复或填写有效的行为纠正，并明确同意训练。',
  training_feedback_storage_unavailable: '反馈未能保存；内容已保留，请稍后重试。',
}

async function checked(sessionId: string, route: string, init?: RequestInit): Promise<unknown> {
  const response = await desktopFetch(sessionId, `${prefix}${route}`, init)
  if (!response.ok) {
    let code = ''
    try {
      const error = await response.json() as { detail?: { code?: string }, code?: string }
      code = error.detail?.code ?? error.code ?? ''
    }
    catch { /* Do not display raw gateway or server errors, paths or credentials. */ }
    throw new Error(reasons[code] ?? '反馈服务暂不可用，请刷新状态或检查 Core 是否已更新。')
  }
  return response.json()
}

function mutation(body: object, requestId: string, signal?: AbortSignal): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId },
    body: JSON.stringify(body), signal }
}

export async function pendingTraining(sessionId: string, signal?: AbortSignal) {
  const value = await checked(sessionId, '/pending', { signal }) as { pending?: unknown[], receipts?: unknown[] }
  if (!value || !Array.isArray(value.pending) || !Array.isArray(value.receipts))
    throw new Error('反馈状态无效，请刷新后重试。')
  return { pending: value.pending.map(item => parseTrainingComparison(item)), receipts: value.receipts.map(parseTrainingReceipt) }
}

export async function submitTraining(sessionId: string, body: TrainingSubmission, requestId: string, signal?: AbortSignal) {
  return parseTrainingReceipt(await checked(sessionId, '/feedback', mutation(body, requestId, signal)))
}

export async function revokeTraining(sessionId: string, feedbackId: string, requestId: string, signal?: AbortSignal) {
  return parseTrainingReceipt(await checked(sessionId, `/feedback/${encodeURIComponent(feedbackId)}/revoke`, mutation({}, requestId, signal)))
}
