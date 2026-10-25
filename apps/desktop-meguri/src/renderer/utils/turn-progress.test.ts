import { describe, expect, it } from 'vitest'
import type { TurnEventEnvelope } from '@meguri/protocol'
import { finishTurnProgress, progressForEvent, type TurnProgressItem } from './turn-progress'

function event(type: string, data: Record<string, unknown> = {}): TurnEventEnvelope {
  return { event_id: type, type, data } as unknown as TurnEventEnvelope
}

describe('public turn progress', () => {
  it('distinguishes search-summary evidence from a successful page read', () => {
    const progress = progressForEvent([], event('retrieval.completed', { lanes: [
      { lane: 'web', status: 'degraded', degradations: ['web_extract_partial_failure', 'web_search_snippet_fallback'] },
    ] }))
    expect(progress[0].detail).toBe('联网部分受限；部分网页读取失败；仅使用搜索摘要，未读取网页正文')
  })

  it('shows degraded RAG and memory through fixed public messages', () => {
    const progress = progressForEvent([], event('retrieval.completed', { lanes: [
      { lane: 'lore', status: 'degraded', degradations: ['rag_bridge_unavailable', 'rag_lexical_fallback'] },
      { lane: 'memory', status: 'degraded', degradations: ['memory_unavailable', 'private upstream details'] },
    ] }))
    expect(progress[0].detail).toContain('知识库服务不可用；已使用本地知识匹配；记忆服务不可用')
    expect(progress[0].detail).not.toContain('private')
  })

  it('maps retrieval and lifecycle events through a fixed public-field allowlist', () => {
    let items: TurnProgressItem[] = [{ label: '正在连接 Meguri…', state: 'active' }]
    items = progressForEvent(items, event('turn.stage.changed', { stage: 'retrieving', reasoning: 'private chain' }))
    items = progressForEvent(items, event('retrieval.completed', {
      lanes: [
        { lane: 'web', status: 'DEGRADED', result_count: 0, provider: 'provider', degradations: ['web_search_empty', 'web_extract_partial_failure'], private_text: 'secret' },
        { lane: 'lore', status: 'SKIPPED' },
        { lane: 'unknown', status: 'OK' },
      ],
      degradations: ['web_timeout', 'web_extract_partial_failure', 'secret prompt text'], prompt: 'private prompt',
    }))
    items = progressForEvent(items, event('tool.started', { tool: 'private tool arguments' }))
    items = progressForEvent(items, event('text.delta', { delta: 'answer' }))
    items = progressForEvent(items, event('turn.completed'))
    items = finishTurnProgress(items, 'complete')

    expect(items.map(item => item.label)).toEqual(['正在连接 Meguri…', '正在检索信息', '检索完成', '正在调用能力…', '正在生成回复', '回复完成'])
    expect(items[2].detail).toBe('联网部分受限；知识库已跳过；联网没有结果；部分网页读取失败；联网超时')
    expect(items.filter(item => item.label === '回复完成')).toHaveLength(1)
    expect(JSON.stringify(items)).not.toMatch(/private|secret|prompt|arguments|answer/)
  })

  it('keeps progress bounded and marks failures and cancellations terminal', () => {
    let items: TurnProgressItem[] = []
    for (let index = 0; index < 30; index++)
      items = progressForEvent(items, event(index % 2 ? 'tool.completed' : 'tool.failed'))
    expect(items).toHaveLength(12)
    expect(finishTurnProgress(items, 'failed').at(-1)).toEqual({ label: '回复失败', state: 'failed' })
    expect(finishTurnProgress(items, 'cancelled').at(-1)).toEqual({ label: '已取消', state: 'cancelled' })
  })

  it('distinguishes policy-skipped lookup from disabled web search', () => {
    const progress = progressForEvent([], event('retrieval.completed', {
      degradations: ['web_policy_skipped', 'web_search_policy_skipped', 'web_search_disabled', 'web_results_unusable'],
    }))
    expect(progress[0].detail).toBe('本轮无需联网；联网已关闭')
    expect(progressForEvent([], event('retrieval.completed', { degradations: ['web_results_unusable'] }))[0].detail)
      .toBe('联网结果无法使用')
  })
})
