import type { CompanionInitiative } from '../../shared/companion-contract'
import type { CompanionScreenContext } from '../../shared/companion-contract'
import { coreUrl, desktopFetch } from './desktop-fetch'

interface Message {
  id: string
  role: string
  createdAt: number
  status?: string
  turnId?: string
}

export interface ReachOutCandidate {
  id: string
  text: string
  createdAt: number
  expiresAt: number
}

export interface ReachOutState {
  userId: string
  sessionId: string
  initiative: CompanionInitiative
  allowed: boolean
  available: boolean
  visible: boolean
  editing: boolean
  lastDeliveredAt: number
  dailyReachOutCount?: number
  messages: Message[]
  userActiveUntil: number
  screenObservationEnabled?: boolean
}

export interface ReachOutOptions {
  getState(): ReachOutState
  generate(state: ReachOutState, signal: AbortSignal): Promise<ReachOutCandidate | undefined>
  deliver(candidate: ReachOutCandidate, isCurrent: () => boolean): Promise<boolean>
  acknowledge(candidate: ReachOutCandidate, state: ReachOutState): Promise<void>
  now?: () => number
  refreshPresence?(): Promise<void>
}

const minute = 60_000
const idleMinutes = { off: Infinity, low: 120, normal: 45, high: 15 }
const cooldownMinutes = { off: Infinity, low: 240, normal: 90, high: 30 }

function localDay(at: number) {
  const date = new Date(at)
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
}

function exchange(state: ReachOutState) {
  let userIndex = state.messages.length - 1
  while (userIndex >= 0 && state.messages[userIndex].role !== 'user') userIndex--
  if (userIndex < 0) return undefined
  const following = state.messages.slice(userIndex + 1)
  if (following.some(message => message.id.startsWith('reachout:'))) return undefined
  const reply = [...following].reverse().find(message => message.role === 'meguri' && message.turnId)
  if (!reply || reply.status !== 'complete') return undefined
  return { key: `${state.userId}|${state.sessionId}|${state.messages[userIndex].id}|${reply.turnId}`, at: reply.createdAt }
}

/** One stage-owned producer; neither settings windows nor deferred drains produce starters. */
export function createCompanionReachOutScheduler(options: ReachOutOptions) {
  const now = options.now ?? Date.now
  let lastActivityAt = now()
  let retryAt = 0
  let generation = 0
  let controller: AbortController | undefined
  let running = false
  let disposed = false
  let timer: ReturnType<typeof setInterval> | undefined
  let pendingAck: { candidate: ReachOutCandidate, state: ReachOutState, retryAt: number } | undefined
  const delivered = new Map<string, number>()

  function eligible(state: ReachOutState, at: number, checkBudget = true) {
    const hour = new Date(at).getHours()
    const latest = exchange(state)
    const deliveredToday = Math.max(state.dailyReachOutCount ?? 0, delivered.get(localDay(at)) ?? 0,
      state.messages.filter(message => message.id.startsWith('reachout:') && localDay(message.createdAt) === localDay(at)).length)
    return latest && state.allowed && state.available && state.visible && !state.editing
      && Number.isFinite(state.userActiveUntil) && state.userActiveUntil > at
      && state.initiative !== 'off' && hour >= 8 && hour < 22 && (!checkBudget || deliveredToday < 3)
      && at >= retryAt
      && at - Math.max(lastActivityAt, latest.at) >= idleMinutes[state.initiative] * minute
      && (!state.lastDeliveredAt || at - state.lastDeliveredAt >= cooldownMinutes[state.initiative] * minute)
      ? latest : undefined
  }

  function activity() {
    lastActivityAt = now()
    generation++
    controller?.abort()
  }

  function contextChanged() {
    const state = options.getState()
    if (!eligible(state, now())) {
      generation++
      controller?.abort()
    }
  }

  async function tick() {
    if (disposed || running) return
    running = true
    try {
      if (pendingAck && now() >= pendingAck.retryAt) {
        const pending = pendingAck
        if (pending.candidate.expiresAt <= now() || !options.getState().allowed
          || options.getState().sessionId !== pending.state.sessionId) pendingAck = undefined
        else {
          pending.retryAt = now() + minute
          try { await options.acknowledge(pending.candidate, pending.state); pendingAck = undefined }
          catch { /* A displayed message can be acknowledged again without duplicating context. */ }
        }
      }
      if (options.refreshPresence) await options.refreshPresence()
      const currentState = options.getState()
      const latest = eligible(currentState, now())
      if (!latest) return
      const version = ++generation
      controller = new AbortController()
      const timeout = setTimeout(() => controller?.abort(), 25_000)
      let candidate: ReachOutCandidate | undefined
      try { candidate = await options.generate(currentState, controller.signal) }
      finally { clearTimeout(timeout) }
      if (options.refreshPresence) await options.refreshPresence()
      const isCurrent = () => !disposed && version === generation && !controller?.signal.aborted
        // The notice ledger reserves the last daily slot before its final guard.
        // Its atomic claim owns the cap; that reservation must not invalidate itself.
        && eligible(options.getState(), now(), false)?.key === latest.key
      if (!candidate || !isCurrent() || candidate.expiresAt <= now()) {
        retryAt = now() + 15 * minute
        return
      }
      if (await options.deliver(candidate, isCurrent)) {
        const day = localDay(now())
        delivered.set(day, (delivered.get(day) ?? 0) + 1)
        for (const key of delivered.keys()) if (key !== day) delivered.delete(key)
        pendingAck = { candidate, state: currentState, retryAt: now() + minute }
        try { await options.acknowledge(candidate, currentState); pendingAck = undefined }
        catch { /* Retry the acknowledgement, never the displayed message. */ }
      }
      retryAt = now() + 15 * minute
    }
    catch { retryAt = now() + 15 * minute }
    finally { controller = undefined; running = false }
  }

  return {
    tick, activity, contextChanged,
    start() {
      if (disposed || timer) return
      lastActivityAt = now()
      timer = setInterval(() => { void tick() }, minute)
    },
    dispose() {
      disposed = true
      generation++
      controller?.abort()
      clearInterval(timer)
      pendingAck = undefined
    },
  }
}

function identity(state: ReachOutState) {
  return JSON.stringify({ user_id: state.userId, client_id: 'desktop_pet', session_id: state.sessionId })
}

export async function requestReachOut(state: ReachOutState, signal: AbortSignal): Promise<ReachOutCandidate | undefined> {
  let screenContext: CompanionScreenContext | undefined
  if (state.screenObservationEnabled) {
    try { screenContext = await window.meguriDesktop?.companionActivity?.observe?.() }
    catch { /* OCR is optional; an ordinary grounded starter can still be generated. */ }
  }
  if (signal.aborted) return undefined
  const activity = await window.meguriDesktop?.companionActivity?.status()
  if (signal.aborted || !activity?.presence.active || activity.presence.activeUntil <= Date.now()) return undefined
  const response = await desktopFetch(state.sessionId, `${coreUrl}/v1/companion/reach-outs`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...JSON.parse(identity(state)), active_until: activity.presence.activeUntil,
      ...(screenContext ? { screen_context: screenContext } : {}) }), signal,
  })
  if (response.status === 204) return undefined
  if (!response.ok) throw new Error('Reach-out unavailable')
  const candidate = await response.json() as ReachOutCandidate
  if (!/^reachout:[a-f0-9-]{36}$/.test(candidate?.id) || typeof candidate.text !== 'string'
    || !candidate.text.trim() || candidate.text.length > 240
    || !Number.isFinite(candidate.createdAt) || !Number.isFinite(candidate.expiresAt)) return undefined
  return candidate
}

export async function acknowledgeReachOut(candidate: ReachOutCandidate, state: ReachOutState) {
  const response = await desktopFetch(state.sessionId,
    `${coreUrl}/v1/companion/reach-outs/${encodeURIComponent(candidate.id)}/delivered`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: identity(state),
      signal: AbortSignal.timeout(5_000),
    })
  if (!response.ok) throw new Error('Reach-out acknowledgement unavailable')
}
