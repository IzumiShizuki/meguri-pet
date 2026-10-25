import type { TurnEventEnvelope } from '@meguri/protocol'

export type TurnProgressState = 'active' | 'complete' | 'failed' | 'cancelled'
export interface TurnProgressItem {
  label: string
  detail?: string
  state: TurnProgressState
}

const stageLabels: Record<string, string> = {
  created: '已接收请求',
  planning: '正在规划',
  retrieving: '正在检索信息',
  generating: '正在生成回复',
  finalizing: '正在整理回复',
  completed: '回复完成',
  failed: '回复失败',
  cancelled: '已取消',
}
const laneLabels: Record<string, string> = {
  web: '联网', lore: '知识库', memory: '记忆', weather: '天气', keyword: '关键词', vector: '向量', knowledge: '知识库',
}
const laneStatuses: Record<string, string> = {
  enabled: '已启用', available: '可用', succeeded: '完成', disabled: '已关闭', ok: '完成',
  not_configured: '未配置', unavailable: '不可用', empty: '无结果', failed: '失败',
  degraded: '部分受限', skipped: '已跳过',
}
const degradationLabels: Record<string, string> = {
  web_unavailable: '联网服务不可用', web_empty_results: '联网没有结果', web_timeout: '联网超时', web_search_unavailable: '联网服务不可用',
  web_provider_unavailable: '联网服务不可用', web_empty: '联网没有结果', web_search_empty: '联网没有结果',
  web_search_timeout: '联网超时', web_search_disabled: '联网已关闭', web_search_policy_skipped: '本轮无需联网',
  web_policy_skipped: '本轮无需联网', web_results_unusable: '联网结果无法使用',
  web_extract_partial_failure: '部分网页读取失败', retrieval_unavailable: '检索服务不可用',
  web_search_snippet_fallback: '仅使用搜索摘要，未读取网页正文',
  rag_bridge_unavailable: '知识库服务不可用', rag_lexical_fallback: '已使用本地知识匹配', memory_unavailable: '记忆服务不可用',
}
const MAX_ITEMS = 12
const MAX_DETAIL = 160

function push(items: TurnProgressItem[], item: TurnProgressItem): TurnProgressItem[] {
  const previous = items.map(value => ({ ...value, state: value.state === 'active' ? 'complete' as const : value.state }))
  const last = previous.at(-1)
  if (last && last.label === item.label && last.detail === item.detail && last.state === item.state)
    return previous.slice(-MAX_ITEMS)
  const bounded = [...previous, item].slice(-MAX_ITEMS)
  if (item.state === 'active') {
    let lastActive = -1
    for (let index = 0; index < bounded.length; index++) {
      if (bounded[index].state === 'active') lastActive = index
    }
    return bounded.map((value, index) => index === lastActive ? value : value.state === 'active' ? { ...value, state: 'complete' } : value)
  }
  return bounded
}

function retrievalDetail(data: Record<string, unknown>): string | undefined {
  const lanes = data.lanes
  const parts: string[] = []
  const laneDegradations: string[] = []
  if (Array.isArray(lanes)) {
    for (const lane of lanes.slice(0, 6)) {
      if (!lane || typeof lane !== 'object' || Array.isArray(lane)) continue
      const record = lane as Record<string, unknown>
      const key = typeof record.lane === 'string' ? record.lane.toLowerCase() : typeof record.source_type === 'string' ? record.source_type.toLowerCase() : ''
      const label = laneLabels[key]
      const status = typeof record.status === 'string' ? laneStatuses[record.status.toLowerCase()] : undefined
      if (label && status) parts.push(`${label}${status}`)
      if (label && Array.isArray(record.degradations)) {
        for (const code of record.degradations) {
          if (typeof code === 'string' && degradationLabels[code.toLowerCase()])
            laneDegradations.push(degradationLabels[code.toLowerCase()])
        }
      }
    }
  }
  else if (lanes && typeof lanes === 'object') {
    for (const [key, value] of Object.entries(lanes as Record<string, unknown>)) {
      const label = laneLabels[key]
      const status = typeof value === 'string' ? laneStatuses[value.toLowerCase()] : undefined
      if (label && status) parts.push(`${label}${status}`)
    }
  }
  const degradations = Array.isArray(data.degradations)
    ? data.degradations.filter((value): value is string => typeof value === 'string')
      .map(value => degradationLabels[value.toLowerCase()]).filter((value): value is string => Boolean(value)).slice(0, 3)
    : []
  const detail = [...new Set([...parts.slice(0, 6), ...laneDegradations.slice(0, 3), ...degradations])].join('；')
  return detail ? detail.slice(0, MAX_DETAIL) : undefined
}

export function progressForEvent(items: TurnProgressItem[], event: TurnEventEnvelope): TurnProgressItem[] {
  const data = event.data
  switch (event.type) {
    case 'turn.started': return push(items, { label: '已连接 Core', state: 'complete' })
    case 'turn.stage.changed': {
      const label = typeof data.stage === 'string' ? stageLabels[data.stage] : undefined
      return label ? push(items, { label, state: data.stage === 'completed' ? 'complete' : data.stage === 'failed' ? 'failed' : 'active' }) : items
    }
    case 'retrieval.completed': return push(items, { label: '检索完成', detail: retrievalDetail(data), state: 'complete' })
    case 'tool.started': return push(items, { label: '正在调用能力…', state: 'active' })
    case 'tool.completed': return push(items, { label: '能力调用完成', state: 'complete' })
    case 'tool.failed': return push(items, { label: '能力调用失败', state: 'failed' })
    case 'text.delta': return items.some(item => item.label === '正在生成回复')
      ? items : push(items, { label: '正在生成回复', state: 'active' })
    case 'turn.completed': return push(items, { label: '回复完成', state: 'complete' })
    case 'turn.failed': return push(items, { label: '回复失败', state: 'failed' })
    case 'turn.cancelled': return push(items, { label: '已取消', state: 'cancelled' })
    default: return items
  }
}

export function finishTurnProgress(items: TurnProgressItem[], state: 'complete' | 'failed' | 'cancelled'): TurnProgressItem[] {
  const label = state === 'complete' ? '回复完成' : state === 'failed' ? '回复失败' : '已取消'
  return push(items, { label, state })
}
