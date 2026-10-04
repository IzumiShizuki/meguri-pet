import { mkdtempSync, rmSync } from 'node:fs'
import { join } from 'node:path'

import { afterEach, describe, expect, it } from 'vitest'

import { createReceipts, publicReceipt, validPlan, validTaskText } from './receipts.mjs'

const roots: string[] = []

function receiptsForTest() {
  const root = mkdtempSync(join(process.cwd(), '.meguri-receipts-test-'))
  roots.push(root)
  return createReceipts({ filename: join(root, 'data', 'receipts.sqlite') })
}

afterEach(() => {
  while (roots.length)
    rmSync(roots.pop()!, { recursive: true, force: true })
})

describe('forwarding receipts', () => {
  it('freezes an unconfirmed card and never marks it dispatched', () => {
    const receipts = receiptsForTest()
    const record = receipts.create({
      text: '整理相机模块',
      executor: 'codex',
      target: { targetId: '01a0b06e-c160-7a11-bfa5-f72bf76ca7ea' },
    })

    expect(record.phase).toBe('awaiting_confirmation')
    expect(record.dispatchAttempted).toBe(false)
    expect(record.version).toBe(1)
    expect(receipts.get(record.id)).toMatchObject({ text: '整理相机模块' })
    receipts.close()
  })

  it('bumps the version on every accepted mutation and leaves it alone on refusal', () => {
    const receipts = receiptsForTest()
    const record = receipts.create({ text: 'task', executor: 'harness' })

    const claimed = receipts.mutate(record.id, (draft: Record<string, unknown>) => {
      draft.phase = 'forwarding'
      return true
    })
    expect(claimed.version).toBe(2)

    const refused = receipts.mutate(record.id, () => false)
    expect(refused.version).toBe(2)
    expect(refused.phase).toBe('forwarding')
    receipts.close()
  })

  it('persists the dispatch boundary across a reopen', () => {
    const receipts = receiptsForTest()
    const record = receipts.create({ text: 'task', executor: 'codex' })
    receipts.mutate(record.id, (draft: Record<string, unknown>) => {
      draft.dispatchAttempted = true
      return true
    })
    receipts.close()

    const reopened = createReceipts({ filename: join(roots[0], 'data', 'receipts.sqlite') })
    expect(reopened.get(record.id).dispatchAttempted).toBe(true)
    reopened.close()
  })

  it('refuses to overwrite the original user input on a draft', () => {
    const receipts = receiptsForTest()
    const draft = receipts.createDraft({ originalText: '原始输入', text: '原始输入' })

    expect(() => receipts.mutateDraft(draft.id, (next: Record<string, unknown>) => {
      next.originalText = '被改写'
      return true
    })).toThrow(/原始输入/u)
    expect(receipts.draft().originalText).toBe('原始输入')
    receipts.close()
  })

  it('strips internal bookkeeping before a receipt leaves the main process', () => {
    const record = {
      id: 'x',
      version: 3,
      phase: 'accepted',
      dispatchAttempted: true,
      requestId: 'secret-request-id',
    }
    const projected = publicReceipt(record)
    expect(projected).not.toHaveProperty('dispatchAttempted')
    expect(JSON.stringify(projected)).not.toContain('secret-request-id')
  })

  it('validates task text and plan bounds', () => {
    expect(validTaskText('  写一个测试  ')).toBe('写一个测试')
    expect(validTaskText('   ')).toBe('')
    expect(validTaskText('x'.repeat(20001))).toBe('')
    expect(validTaskText('bad\u0000text')).toBe('')
    expect(validPlan({ title: '标题' })).toEqual({ title: '标题', reason: '' })
    expect(validPlan({ title: '' })).toBeUndefined()
    expect(validPlan('not-a-plan')).toBeUndefined()
  })
})
