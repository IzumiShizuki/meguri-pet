import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createCompanionReachOutScheduler, type ReachOutCandidate, type ReachOutOptions, type ReachOutState } from './companion-reach-outs'

const minute = 60_000
const start = new Date(2026, 9, 22, 10).getTime()
let state: ReachOutState
const schedulers: ReturnType<typeof createCompanionReachOutScheduler>[] = []

function setup(overrides: Partial<ReachOutOptions> = {}) {
  const options: ReachOutOptions = {
    getState: () => state,
    generate: vi.fn(async () => ({ id: 'reachout:12345678-1234-1234-1234-123456789012',
      text: '兄长，要不要一起聊聊刚才提到的那本书？', createdAt: Date.now(), expiresAt: Date.now() + 10 * minute })),
    deliver: vi.fn(async (candidate, current) => {
      if (!current()) return false
      state.messages.push({ id: candidate.id, role: 'meguri', createdAt: Date.now(), status: 'complete' })
      return true
    }),
    acknowledge: vi.fn(async () => {}),
    ...overrides,
  }
  const scheduler = createCompanionReachOutScheduler(options)
  schedulers.push(scheduler)
  return { scheduler, options }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(start)
  state = { userId: 'local-airi-user', sessionId: 'session-1', initiative: 'high', allowed: true,
    available: true, visible: true, editing: false, lastDeliveredAt: 0,
    messages: [{ id: 'user-1', role: 'user', createdAt: start, status: 'complete' },
      { id: 'assistant-1', role: 'meguri', createdAt: start, status: 'complete', turnId: 'turn-1' }] }
})

afterEach(() => {
  for (const scheduler of schedulers.splice(0)) scheduler.dispose()
  vi.useRealTimers()
})

describe('idle companion reach-outs', () => {
  it.each([['low', 120], ['normal', 45], ['high', 15]] as const)('waits for %s idle time and acknowledges only the displayed message', async (initiative, wait) => {
    state.initiative = initiative
    const { scheduler, options } = setup()
    vi.setSystemTime(start + (wait - 1) * minute)
    await scheduler.tick()
    expect(options.generate).not.toHaveBeenCalled()
    vi.setSystemTime(start + wait * minute)
    await scheduler.tick()
    expect(options.generate).toHaveBeenCalledOnce()
    expect(options.deliver).toHaveBeenCalledOnce()
    expect(options.acknowledge).toHaveBeenCalledOnce()
    vi.setSystemTime(start + (wait + 250) * minute)
    await scheduler.tick()
    expect(options.generate).toHaveBeenCalledOnce()
  })

  it.each([
    { allowed: false }, { available: false }, { visible: false }, { editing: true }, { initiative: 'off' as const },
    { messages: [] }, { messages: [{ id: 'u', role: 'user', createdAt: start }] },
  ])('stays silent for an ineligible state %j', async (patch) => {
    Object.assign(state, patch)
    const { scheduler, options } = setup()
    vi.setSystemTime(start + 16 * minute)
    await scheduler.tick()
    expect(options.generate).not.toHaveBeenCalled()
  })

  it('observes local quiet hours, recent notice cooldown and stage reload waiting', async () => {
    const { scheduler, options } = setup()
    vi.setSystemTime(new Date(2026, 9, 22, 22, 30))
    await scheduler.tick()
    vi.setSystemTime(new Date(2026, 9, 23, 7, 59))
    await scheduler.tick()
    expect(options.generate).not.toHaveBeenCalled()
    vi.setSystemTime(new Date(2026, 9, 23, 8, 0))
    state.lastDeliveredAt = Date.now() - 5 * minute
    await scheduler.tick()
    expect(options.generate).not.toHaveBeenCalled()
    state.lastDeliveredAt = 0
    const reloaded = setup()
    await reloaded.scheduler.tick()
    expect(reloaded.options.generate).not.toHaveBeenCalled()
  })

  it('restarts the idle clock on interaction', async () => {
    const { scheduler, options } = setup()
    vi.setSystemTime(start + 14 * minute)
    scheduler.activity()
    vi.setSystemTime(start + 16 * minute)
    await scheduler.tick()
    expect(options.generate).not.toHaveBeenCalled()
    vi.setSystemTime(start + 29 * minute)
    await scheduler.tick()
    expect(options.generate).toHaveBeenCalledOnce()
  })

  it.each(['session', 'consent', 'focus', 'initiative', 'interaction'] as const)('discards generation on %s changes, including temporary changes', async (change) => {
    let finish!: (value: ReachOutCandidate) => void
    let signal!: AbortSignal
    const { scheduler, options } = setup({ generate: vi.fn((_state, currentSignal) => {
      signal = currentSignal
      return new Promise<ReachOutCandidate>(resolve => { finish = resolve })
    }) })
    vi.setSystemTime(start + 16 * minute)
    const pending = scheduler.tick()
    await Promise.resolve()
    if (change === 'session') state.sessionId = 'other-session'
    if (change === 'consent') state.allowed = false
    if (change === 'focus') state.available = false
    if (change === 'initiative') state.initiative = 'off'
    if (change === 'interaction') scheduler.activity()
    else scheduler.contextChanged()
    if (change === 'consent') state.allowed = true
    if (change === 'focus') state.available = true
    if (change === 'initiative') state.initiative = 'high'
    finish({ id: 'reachout:one', text: '一句话', createdAt: Date.now(), expiresAt: Date.now() + minute })
    await pending
    if (change !== 'session') expect(signal.aborted).toBe(true)
    expect(options.deliver).not.toHaveBeenCalled()
    expect(options.acknowledge).not.toHaveBeenCalled()
  })

  it('backs off empty/errors without failing ordinary chat, and allows only one in-flight request', async () => {
    const { scheduler, options } = setup({ generate: vi.fn(async () => { throw new Error('offline') }) })
    vi.setSystemTime(start + 16 * minute)
    await Promise.all([scheduler.tick(), scheduler.tick()])
    await scheduler.tick()
    expect(options.generate).toHaveBeenCalledOnce()
    expect(options.deliver).not.toHaveBeenCalled()
    vi.setSystemTime(start + 31 * minute)
    await scheduler.tick()
    expect(options.generate).toHaveBeenCalledTimes(2)
  })

  it('counts previous starters toward the daily limit even in a new exchange', async () => {
    state.messages.unshift(...[1, 2, 3].map(n => ({ id: `reachout:old-${n}`, role: 'meguri', createdAt: start })))
    const { scheduler, options } = setup()
    vi.setSystemTime(start + 16 * minute)
    await scheduler.tick()
    expect(options.generate).not.toHaveBeenCalled()
  })

  it('allows the third reserved delivery and then suppresses generation across a new exchange', async () => {
    state.dailyReachOutCount = 2
    const { scheduler, options } = setup({ deliver: vi.fn(async (_candidate, current) => {
      state.dailyReachOutCount = 3
      return current()
    }) })
    vi.setSystemTime(start + 16 * minute)
    await scheduler.tick()
    expect(options.acknowledge).toHaveBeenCalledOnce()
    vi.setSystemTime(start + 60 * minute)
    await scheduler.tick()
    expect(options.generate).toHaveBeenCalledOnce()
  })

  it('retries acknowledgement without showing or generating another message', async () => {
    const acknowledge = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    const { scheduler, options } = setup({ acknowledge })
    vi.setSystemTime(start + 16 * minute)
    await scheduler.tick()
    vi.setSystemTime(start + 17 * minute)
    await scheduler.tick()
    expect(acknowledge).toHaveBeenCalledTimes(2)
    expect(options.deliver).toHaveBeenCalledOnce()
    expect(options.generate).toHaveBeenCalledOnce()
  })

  it('ignores a pending candidate after disposal', async () => {
    let finish!: (value: ReachOutCandidate) => void
    const { scheduler, options } = setup({ generate: vi.fn(() => new Promise<ReachOutCandidate>(resolve => { finish = resolve })) })
    vi.setSystemTime(start + 16 * minute)
    const pending = scheduler.tick()
    scheduler.dispose()
    finish({ id: 'reachout:one', text: '一句话', createdAt: Date.now(), expiresAt: Date.now() + minute })
    await pending
    expect(options.deliver).not.toHaveBeenCalled()
  })
})
