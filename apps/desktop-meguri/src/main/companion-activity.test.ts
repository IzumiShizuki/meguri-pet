import { describe, expect, it, vi } from 'vitest'
import { activityWindowMs, createCompanionActivity } from './companion-activity.mjs'

function fixture() {
  let time = 1_000_000
  let seconds = 0
  let state = 'active'
  let enabled = true
  const observe = vi.fn(async () => ({ ok: true, text: 'The user is reading a programming tutorial.', app: 'editor' }))
  const runtime = createCompanionActivity({ now: () => time, idleTime: () => seconds, idleState: () => state,
    observe, enabled: () => enabled })
  return { runtime, observe, setTime: (value: number) => { time = value }, setIdle: (value: number) => { seconds = value },
    setState: (value: string) => { state = value }, setEnabled: (value: boolean) => { enabled = value } }
}

describe('native companion activity and screen boundary', () => {
  it('accepts 599 seconds and refuses exactly 600, even with the PC active', async () => {
    const f = fixture()
    f.setIdle(599)
    expect(f.runtime.presence()).toMatchObject({ active: true, activeUntil: 1_001_000 })
    f.setIdle(600)
    expect(f.runtime.presence().active).toBe(false)
    expect(await f.runtime.observe()).toBeUndefined()
    expect(f.observe).not.toHaveBeenCalled()
    expect(activityWindowMs).toBe(600_000)
  })

  it.each(['locked', 'idle', 'unknown'])('fails closed for native state %s', async (state) => {
    const f = fixture(); f.setState(state)
    expect(f.runtime.presence().active).toBe(false)
    expect(await f.runtime.observe()).toBeUndefined()
  })

  it.each([NaN, Infinity, -1])('rejects invalid native age %s', (value) => {
    const f = fixture(); f.setIdle(value)
    expect(f.runtime.presence().reason).toBe('unknown')
  })

  it('requires new input after unlock and resume', () => {
    const f = fixture()
    f.runtime.lock()
    expect(f.runtime.presence().reason).toBe('locked')
    f.runtime.unlock()
    f.setTime(1_005_000); f.setIdle(5)
    expect(f.runtime.presence().active).toBe(false)
    f.setIdle(0)
    expect(f.runtime.presence().active).toBe(true)
    f.runtime.suspend()
    expect(f.runtime.presence().reason).toBe('suspended')
    f.runtime.resume()
    expect(f.runtime.presence().active).toBe(false)
    f.setTime(1_007_000); f.setIdle(0)
    expect(f.runtime.presence().active).toBe(true)
  })

  it('never captures while the owner switch is disabled and exposes no text in status', async () => {
    const f = fixture(); f.setEnabled(false)
    await f.runtime.observe()
    expect(f.observe).not.toHaveBeenCalled()
    f.setEnabled(true)
    expect(await f.runtime.observe()).toMatchObject({ text: 'The user is reading a programming tutorial.' })
    expect(JSON.stringify(f.runtime.status())).not.toContain('tutorial')
  })

  it('discards a result when inactivity expires or the screen locks during OCR', async () => {
    const f = fixture()
    f.observe.mockImplementationOnce(async () => { f.setIdle(600); return { ok: true, text: 'private text', app: 'editor' } })
    expect(await f.runtime.observe()).toBeUndefined()
    expect(f.runtime.status().observation.state).toBe('paused')
    f.setIdle(0)
    f.observe.mockImplementationOnce(async () => { f.runtime.lock(); return { ok: true, text: 'private text', app: 'editor' } })
    expect(await f.runtime.observe()).toBeUndefined()
    expect(JSON.stringify(f.runtime.status())).not.toContain('private text')
  })

  it('suppresses credentials and provider failures without retaining raw content', async () => {
    const f = fixture()
    f.observe.mockResolvedValueOnce({ ok: true, text: 'api_key = synthetic-do-not-send', app: 'editor' })
    expect(await f.runtime.observe()).toBeUndefined()
    expect(f.runtime.status().observation.state).toBe('excluded')
    f.observe.mockRejectedValueOnce(new Error('private provider error'))
    expect(await f.runtime.observe()).toBeUndefined()
    expect(JSON.stringify(f.runtime.status())).not.toContain('private')
  })
})
