import type { DesktopMemoryCandidate, DesktopMemoryCorrection, DesktopMemoryDetail, DesktopMemoryRecord, DesktopMemoryStatus, DesktopMemorySummary } from '../../shared/memory-contract'
import { coreUrl, desktopFetch } from './desktop-fetch'

const prefix = `${coreUrl}/v1/memory/desktop`
const reasons: Record<string, string> = {
  bridge_not_configured: '尚未配置正式记忆桥令牌。请按正式记忆运行说明配置。',
  desktop_auth_not_configured: 'Core 尚未配置桌面记忆身份令牌，请使用最新启动器重启 Core。',
  desktop_auth_required: '桌面记忆身份验证失败，请检查受信网关与 Core 的配置。',
  memory_bridge_unavailable: '正式记忆服务无法连接，请检查 Python 记忆桥是否启动。',
  authoritative_provider_required: '正式记忆需要 native_pgvector，当前服务没有权威数据库。',
  formal_memory_not_authorized: '当前用户或桌面客户端尚未获得服务端正式记忆授权。',
  tenant_scope_mismatch: '桌面与正式记忆服务的租户不一致。',
  memory_state_conflict: '记忆版本或候选状态已变化，或内容未通过审核。请刷新列表后核对。',
  memory_consent_required: '请先开启正式记忆并完成一次对话，再整理当前会话。',
  session_history_unavailable: '当前会话还没有完整的一轮对话，或 Core 重启后历史不可用。',
  memory_not_found: '未找到属于当前用户的记忆，请刷新列表。',
  candidate_not_found: '未找到属于当前用户的候选记忆，请刷新列表。',
}

export function memoryReason(reason: string): string {
  return reasons[reason] ?? '正式记忆暂不可用，请检查运行配置或稍后重试。'
}

async function checked(sessionId: string, route: string, init?: RequestInit): Promise<Response> {
  const response = await desktopFetch(sessionId, `${prefix}${route}`, init)
  if (!response.ok) {
    let reason = ''
    try {
      const error = await response.json() as { reason?: string, detail?: { code?: string } | string }
      reason = error.reason ?? (typeof error.detail === 'object' ? error.detail?.code : error.detail) ?? ''
    }
    catch { /* The gateway may return a safe plain-text failure. */ }
    throw new Error(memoryReason(reason))
  }
  return response
}

function mutation(body: object, requestId = `desktop-memory-${crypto.randomUUID()}`): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Request-ID': requestId }, body: JSON.stringify(body) }
}

export async function memoryStatus(sessionId: string): Promise<DesktopMemoryStatus> {
  try {
    return await (await checked(sessionId, '/status')).json() as DesktopMemoryStatus
  }
  catch (error) {
    return { available: false, authorized: false, provider: 'unavailable', reason: error instanceof Error ? error.message : 'memory_bridge_unavailable' }
  }
}

export async function memoryRecords(sessionId: string): Promise<DesktopMemoryRecord[]> {
  const response = await checked(sessionId, '/records')
  return (await response.json() as { items: DesktopMemoryRecord[] }).items
}

export async function memoryCandidates(sessionId: string): Promise<DesktopMemoryCandidate[]> {
  const response = await checked(sessionId, '/candidates')
  return (await response.json() as { items: DesktopMemoryCandidate[] }).items
}

export async function memoryDetail(sessionId: string, memoryId: string, signal?: AbortSignal): Promise<DesktopMemoryDetail> {
  return (await checked(sessionId, `/memories/${encodeURIComponent(memoryId)}`, { signal })).json() as Promise<DesktopMemoryDetail>
}

export async function correctMemory(sessionId: string, memoryId: string, correction: DesktopMemoryCorrection,
  requestId: string, signal?: AbortSignal): Promise<DesktopMemoryCandidate> {
  const response = await checked(sessionId, `/memories/${encodeURIComponent(memoryId)}/corrections`, { ...mutation(correction, requestId), signal })
  return (await response.json() as { candidate: DesktopMemoryCandidate }).candidate
}

export async function reviewMemoryCandidate(sessionId: string, candidateId: string, decision: 'approve' | 'reject') {
  await checked(sessionId, `/candidates/${encodeURIComponent(candidateId)}/review`, mutation({ decision }))
}

export async function mutateMemory(sessionId: string, memoryId: string, action: 'delete' | 'restore') {
  await checked(sessionId, `/memories/${encodeURIComponent(memoryId)}/${action}`, mutation({}))
}

export async function exportMemory(sessionId: string): Promise<Blob> {
  return (await checked(sessionId, '/export', mutation({}))).blob()
}

export async function summarizeMemory(sessionId: string, installId: string, checkpoint: number, signal?: AbortSignal): Promise<DesktopMemorySummary> {
  return (await checked(sessionId, '/summary', { ...mutation({ checkpoint, client_instance_id: installId },
    `desktop-summary:${sessionId}:${checkpoint}`), signal })).json() as Promise<DesktopMemorySummary>
}
