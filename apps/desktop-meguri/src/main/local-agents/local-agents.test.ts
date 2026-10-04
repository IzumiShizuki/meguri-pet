import { describe, expect, it } from 'vitest'

import { foldHarnessReceipt, harnessLauncherUrl, parseHarnessLauncherUrl } from './dsh-bridge.mjs'
import { WORK_PRESET_CONTENTS, installWorkPreset, workPresetFiles, workPresetReady } from './dsh-preset.mjs'
import { isForwardableThread, codexWorkState } from './codex-bridge.mjs'
import { isAbsoluteLocalPath, samePath } from './paths.mjs'

const REQUEST_ID = '241528cd-6699-40d7-8046-b9ccdb738f61'
const OTHER_REQUEST_ID = '77f3acde-0000-4000-8000-000000000000'

function event(seq: number, type: string, data: Record<string, unknown> = {}) {
  return { seq, type, data }
}

function completedStream(requestId = REQUEST_ID) {
  return [
    event(0, 'permission/preset', { preset: 'workspace-write' }),
    event(1, 'turn/start', { turn: 1 }),
    event(2, 'user/message', { turn: 1, source: { kind: 'user', rpcId: requestId } }),
    event(3, 'assistant/message', {
      turn: 1,
      message: { role: 'assistant', content: [{ type: 'text', text: 'MEGURI_PROBE_OK' }] },
    }),
    event(4, 'turn/end', { turn: 1, reason: { kind: 'completed' } }),
  ]
}

describe('DeepSeek Harness receipt folding', () => {
  it('reports completion only for the exact request turn', () => {
    expect(foldHarnessReceipt(completedStream(), REQUEST_ID, { throughSeq: 4, running: false })).toEqual({
      status: 'completed',
      result: 'MEGURI_PROBE_OK',
      detail: 'DeepSeek Harness 任务已完成。',
    })
  })

  it('never attributes another request turn to this card', () => {
    const result = foldHarnessReceipt(completedStream(OTHER_REQUEST_ID), REQUEST_ID, { throughSeq: 4, running: false })
    expect(result.status).toBe('unknown')
  })

  it('stays unknown while the request is still queued', () => {
    const events = [event(0, 'turn/start', { turn: 1 }), event(1, 'user/message', { turn: 1, source: { kind: 'user', rpcId: REQUEST_ID } })]
    expect(foldHarnessReceipt(events, REQUEST_ID, { throughSeq: 1, running: true, queued: true }).status).toBe('unknown')
  })

  it('reports a queued request as working when no turn is bound yet', () => {
    const result = foldHarnessReceipt([event(0, 'agent/inbox/spliced', {})], REQUEST_ID, { throughSeq: 0, running: true, queued: true })
    expect(result.status).toBe('working')
  })

  it('refuses a non-contiguous sequence instead of guessing', () => {
    const events = [event(0, 'turn/start', { turn: 1 }), event(2, 'turn/end', { turn: 1, reason: { kind: 'completed' } })]
    expect(foldHarnessReceipt(events, REQUEST_ID, { throughSeq: 2, running: false }).status).toBe('unknown')
  })

  it('reports a pending approval as approval, never completion', () => {
    const events = [
      event(0, 'turn/start', { turn: 1 }),
      event(1, 'user/message', { turn: 1, source: { kind: 'user', rpcId: REQUEST_ID } }),
      event(2, 'approval/asked', { id: 'approval-1', toolName: 'pwsh' }),
    ]
    const result = foldHarnessReceipt(events, REQUEST_ID, { throughSeq: 2, running: true })
    expect(result).toMatchObject({ status: 'approval', approvalTools: ['pwsh'] })
  })

  it('never claims completion when an approval is still unbalanced', () => {
    const events = [
      event(0, 'turn/start', { turn: 1 }),
      event(1, 'user/message', { turn: 1, source: { kind: 'user', rpcId: REQUEST_ID } }),
      event(2, 'approval/asked', { id: 'approval-1', toolName: 'pwsh' }),
      event(3, 'turn/end', { turn: 1, reason: { kind: 'completed' } }),
    ]
    expect(foldHarnessReceipt(events, REQUEST_ID, { throughSeq: 3, running: false }).status).toBe('unknown')
  })

  it('maps terminal failure reasons to a failure without inventing a result', () => {
    const events = [
      event(0, 'turn/start', { turn: 1 }),
      event(1, 'user/message', { turn: 1, source: { kind: 'user', rpcId: REQUEST_ID } }),
      event(2, 'turn/end', { turn: 1, reason: { kind: 'error' } }),
    ]
    const result = foldHarnessReceipt(events, REQUEST_ID, { throughSeq: 2, running: false })
    expect(result.status).toBe('failed')
    expect(result).not.toHaveProperty('result')
  })

  it('flags a turn that mixes two requests as unknown', () => {
    const events = [
      event(0, 'turn/start', { turn: 1 }),
      event(1, 'user/message', { turn: 1, source: { kind: 'user', rpcId: REQUEST_ID } }),
      event(2, 'user/message', { turn: 1, source: { kind: 'user', rpcId: OTHER_REQUEST_ID } }),
      event(3, 'turn/end', { turn: 1, reason: { kind: 'completed' } }),
    ]
    expect(foldHarnessReceipt(events, REQUEST_ID, { throughSeq: 3, running: false }).status).toBe('unknown')
  })

  it('reads the launcher line out of the Harness web log', () => {
    const log = [
      'noise',
      'dsh web: http://127.0.0.1:19372/?token=abc123',
      'more noise',
    ].join('\n')
    expect(parseHarnessLauncherUrl(log)).toBe('http://127.0.0.1:19372/?token=abc123')
    expect(harnessLauncherUrl('web.log', { readFile: () => log })).toBe('http://127.0.0.1:19372/?token=abc123')
    expect(harnessLauncherUrl('web.log', { readFile: () => { throw new Error('missing') } })).toBe('')
    expect(parseHarnessLauncherUrl('dsh web: http://0.0.0.0:19372/?token=abc')).toBe('')
  })
})

describe('work preset installation', () => {
  it('writes the exact composition once and never overwrites a user preset', () => {
    const files = workPresetFiles('D:\\home\\.dsh')
    const written = new Map<string, string>()
    const options = {
      exists: (path: string) => written.has(path),
      readFile: (path: string) => {
        if (!written.has(path))
          throw Object.assign(new Error('missing'), { code: 'ENOENT' })
        return written.get(path)!
      },
      writeFile: (path: string, value: string) => { written.set(path, value) },
      mkdir: () => {},
    }

    const first = installWorkPreset('D:\\home\\.dsh', options)
    expect(first).toMatchObject({ installed: true, reason: 'created' })
    expect(written.get(files.composition)).toBe(WORK_PRESET_CONTENTS)
    expect(workPresetReady('D:\\home\\.dsh', { readFile: options.readFile })).toBe(true)

    const second = installWorkPreset('D:\\home\\.dsh', options)
    expect(second).toMatchObject({ installed: false, reason: 'present' })
    expect(written.size).toBe(2)
  })

  it('treats a differing preset as the user\'s own file', () => {
    const files = workPresetFiles('D:\\home\\.dsh')
    const options = {
      exists: (path: string) => path === files.composition,
      readFile: () => '# user edited\n',
      writeFile: () => { throw new Error('must not write') },
      mkdir: () => {},
    }
    expect(installWorkPreset('D:\\home\\.dsh', options)).toMatchObject({ installed: false, reason: 'user_preset' })
  })
})

describe('Codex target filtering', () => {
  const base = {
    id: '01a0b06e-c160-7a11-bfa5-f72bf76ca7ea',
    ephemeral: false,
    parentThreadId: null,
    agentNickname: null,
    agentRole: null,
    source: 'vscode',
    cwd: 'D:\\program\\meguri-pet',
    status: { type: 'notLoaded' },
  }

  it('accepts a user-started thread in a local project', () => {
    expect(isForwardableThread(base)).toBe(true)
    expect(codexWorkState(base)).toBe('idle')
  })

  it('rejects sub-agents, ephemeral and non-local threads', () => {
    expect(isForwardableThread({ ...base, parentThreadId: base.id })).toBe(false)
    expect(isForwardableThread({ ...base, agentNickname: 'scout' })).toBe(false)
    expect(isForwardableThread({ ...base, source: 'exec' })).toBe(false)
    expect(isForwardableThread({ ...base, ephemeral: true })).toBe(false)
    expect(isForwardableThread({ ...base, cwd: '/home/user/project' })).toBe(false)
    expect(isForwardableThread({ ...base, cwd: 'https://example.com/repo' })).toBe(false)
  })

  it('reports an active thread as active', () => {
    expect(codexWorkState({ ...base, status: { type: 'active', activeFlags: [] } })).toBe('active')
    expect(codexWorkState({ ...base, status: { type: 'systemError' } })).toBe('error')
  })
})

describe('local path policy', () => {
  it('accepts absolute local paths and rejects URL or UNC shapes', () => {
    expect(isAbsoluteLocalPath('D:\\program\\meguri-pet')).toBe(true)
    expect(isAbsoluteLocalPath('C:/program/meguri-pet')).toBe(true)
    expect(isAbsoluteLocalPath('D:program')).toBe(false)
    expect(isAbsoluteLocalPath('\\\\server\\share')).toBe(false)
    expect(isAbsoluteLocalPath('https://example.com/x')).toBe(false)
    expect(isAbsoluteLocalPath('relative/path')).toBe(false)
    expect(isAbsoluteLocalPath('')).toBe(false)
  })

  it('compares local paths case-insensitively on Windows', () => {
    expect(samePath('D:\\program\\meguri-pet', 'd:/program/meguri-pet')).toBe(true)
    expect(samePath('D:\\program\\meguri-pet', 'D:\\program\\other')).toBe(false)
    expect(samePath('D:\\program\\meguri-pet', 'not-a-path')).toBe(false)
  })
})
