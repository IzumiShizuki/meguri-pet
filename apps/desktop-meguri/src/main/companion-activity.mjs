export const activityWindowMs = 10 * 60_000

/** Native timing only: no key logging, renderer activity claims or saved screen text. */
export function createCompanionActivity({ idleTime, idleState, observe, enabled, now = Date.now }) {
  let locked = false
  let suspended = false
  let inputAfter = -Infinity
  let generation = 0
  let observing = false
  let observation = { state: 'idle', observedAt: 0 }

  function presence() {
    const sampledAt = now()
    const unavailable = reason => ({ active: false, reason, sampledAt, lastInputAt: 0, activeUntil: 0 })
    if (suspended) return unavailable('suspended')
    if (locked) return unavailable('locked')
    try {
      const seconds = idleTime()
      const state = idleState(600)
      if (state === 'locked') return unavailable('locked')
      if (!Number.isFinite(seconds) || seconds < 0 || !['active', 'idle'].includes(state)) return unavailable('unknown')
      const lastInputAt = sampledAt - seconds * 1000
      const activeUntil = lastInputAt + activityWindowMs
      if (lastInputAt <= inputAfter) return unavailable('awaiting_input')
      return { active: state === 'active' && sampledAt < activeUntil, reason: sampledAt < activeUntil && state === 'active' ? 'active' : 'idle',
        sampledAt, lastInputAt, activeUntil }
    }
    catch { return unavailable('unknown') }
  }

  function invalidate(reason) {
    generation++
    observation = { ...observation, state: reason }
  }

  return {
    presence,
    status: () => ({ presence: presence(), observation: { ...observation }, screenEnabled: Boolean(enabled()) }),
    lock() { locked = true; invalidate('paused') },
    unlock() { locked = false; inputAfter = now(); invalidate('paused') },
    suspend() { suspended = true; invalidate('paused') },
    resume() { suspended = false; inputAfter = now(); invalidate('paused') },
    cancel() { invalidate('idle') },
    async observe() {
      if (observing || !enabled() || !presence().active) return undefined
      observing = true
      const version = generation
      observation = { ...observation, state: 'observing' }
      try {
        const result = await observe()
        if (version !== generation || !enabled() || !presence().active) {
          if (version === generation) observation = { ...observation, state: 'paused' }
          return undefined
        }
        if (!result?.ok || typeof result.text !== 'string' || !result.text.trim()) {
          observation = { ...observation, state: result?.reason === 'excluded' ? 'excluded' : 'unavailable' }
          return undefined
        }
        const text = result.text.trim().slice(0, 1000)
        // Suppress likely credentials locally; no rejected text is logged or retained.
        if (/(?:password|passphrase|api[_ -]?key|access[_ -]?token|private[_ -]?key|密码|密钥|验证码|身份证|银行卡)/iu.test(text)) {
          observation = { ...observation, state: 'excluded' }
          return undefined
        }
        const observedAt = now()
        observation = { state: 'observed', observedAt }
        return { text, app: typeof result.app === 'string' ? result.app.slice(0, 80) : 'foreground', observedAt }
      }
      catch { observation = { ...observation, state: 'unavailable' }; return undefined }
      finally { observing = false }
    },
  }
}
