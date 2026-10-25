export type PlaybackEffectKind = 'speech' | 'motion' | 'notice'
export type PlaybackReceiptState = 'claimed' | 'completed' | 'interrupted' | 'skipped'

export interface PlaybackEffectIdentity {
  sessionId: string
  turnId?: string
  effectId: string
  kind: PlaybackEffectKind
}

export interface PlaybackReceipt extends PlaybackEffectIdentity {
  state: PlaybackReceiptState
  claimedAt: number
  updatedAt: number
}

const storageKey = 'meguri.desktop.playback-ledger.v1'
const maxReceipts = 512
const lockName = 'meguri.desktop.playback-ledger.v1'
const reachOutBudgetKey = 'meguri.desktop.reach-out-budget.v1'

function budgetDay(at: number) {
  const date = new Date(at)
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
}

/** Content-free daily cap survives conversation switches and receipt pruning. */
export function reachOutDeliveryCount(at = Date.now()): number {
  try {
    const raw = localStorage.getItem(reachOutBudgetKey)
    if (!raw) return 0
    const budget = JSON.parse(raw) as { day: string, count: number }
    if (typeof budget?.day !== 'string' || !Number.isInteger(budget.count) || budget.count < 0) return 3
    return budget.day === budgetDay(at) ? Math.min(3, budget.count) : 0
  }
  catch { return 3 }
}

function validIdentity(identity: PlaybackEffectIdentity): boolean {
  return typeof identity.sessionId === 'string' && identity.sessionId.length > 0 && identity.sessionId.length <= 180
    && (identity.turnId === undefined || (typeof identity.turnId === 'string' && identity.turnId.length <= 180))
    && typeof identity.effectId === 'string' && identity.effectId.length > 0 && identity.effectId.length <= 240
    && ['speech', 'motion', 'notice'].includes(identity.kind)
}

function receiptKey(receipt: PlaybackEffectIdentity): string {
  return JSON.stringify([receipt.sessionId, receipt.turnId ?? '', receipt.effectId, receipt.kind])
}

function validReceipt(value: unknown): value is PlaybackReceipt {
  if (!value || typeof value !== 'object') return false
  const receipt = value as Partial<PlaybackReceipt>
  return validIdentity(receipt as PlaybackEffectIdentity)
    && ['claimed', 'completed', 'interrupted', 'skipped'].includes(String(receipt.state))
    && Number.isFinite(receipt.claimedAt) && Number.isFinite(receipt.updatedAt)
}

function projectReceipt(value: PlaybackReceipt): PlaybackReceipt {
  return {
    sessionId: value.sessionId,
    ...(value.turnId ? { turnId: value.turnId } : {}),
    effectId: value.effectId,
    kind: value.kind,
    state: value.state,
    claimedAt: value.claimedAt,
    updatedAt: value.updatedAt,
  }
}

function readReceipts(): PlaybackReceipt[] {
  const raw = localStorage.getItem(storageKey)
  if (!raw) return []
  const parsed: unknown = JSON.parse(raw)
  if (!Array.isArray(parsed)) return []
  return parsed.filter(validReceipt).slice(-maxReceipts).map(projectReceipt)
}

function writeReceipts(receipts: PlaybackReceipt[]): boolean {
  try {
    localStorage.setItem(storageKey, JSON.stringify(receipts.slice(-maxReceipts).map(projectReceipt)))
    return true
  }
  catch {
    return false
  }
}

async function withLedgerLock<T>(action: () => T | Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  if (locks?.request)
    return locks.request(lockName, { mode: 'exclusive' }, action)
  return action()
}

/** Persist an automatic effect claim before performing the effect. */
export async function claimPlaybackEffect(identity: PlaybackEffectIdentity, now = Date.now(), preClaim?: () => boolean): Promise<boolean> {
  if (!validIdentity(identity)) return false
  try {
    return await withLedgerLock(() => {
      if (preClaim && !preClaim()) return false
      const receipts = readReceipts()
      const key = receiptKey(identity)
      if (receipts.some(receipt => receiptKey(receipt) === key)) return false
      const reachOut = identity.kind === 'notice' && identity.effectId.startsWith('reachout:')
      const usedReachOuts = reachOut ? reachOutDeliveryCount(now) : 0
      if (reachOut && usedReachOuts >= 3) return false
      const next: PlaybackReceipt = {
        sessionId: identity.sessionId,
        ...(identity.turnId ? { turnId: identity.turnId } : {}),
        effectId: identity.effectId,
        kind: identity.kind,
        state: 'claimed',
        claimedAt: now,
        updatedAt: now,
      }
      if (!writeReceipts([...receipts, next])) return false
      if (reachOut) {
        try { localStorage.setItem(reachOutBudgetKey, JSON.stringify({ day: budgetDay(now), count: usedReachOuts + 1 })) }
        catch { writeReceipts(receipts); return false }
      }
      return true
    })
  }
  catch {
    return false
  }
}

/** Release only an untouched deferred claim after its policy becomes ineligible. */
export async function releaseUnstartedPlaybackEffect(identity: PlaybackEffectIdentity): Promise<boolean> {
  if (!validIdentity(identity)) return false
  try {
    return await withLedgerLock(() => {
      const receipts = readReceipts()
      const key = receiptKey(identity)
      const index = receipts.findIndex(receipt => receiptKey(receipt) === key)
      if (index < 0 || receipts[index].state !== 'claimed') return false
      receipts.splice(index, 1)
      return writeReceipts(receipts)
    })
  }
  catch {
    return false
  }
}

/** Update receipt metadata; failure never makes an existing claim replayable. */
export async function updatePlaybackReceipt(identity: PlaybackEffectIdentity, state: PlaybackReceiptState, now = Date.now()): Promise<boolean> {
  if (!validIdentity(identity)) return false
  try {
    return await withLedgerLock(() => {
      const receipts = readReceipts()
      const key = receiptKey(identity)
      const index = receipts.findIndex(receipt => receiptKey(receipt) === key)
      if (index < 0) return false
      receipts[index] = { ...receipts[index], state, updatedAt: now }
      return writeReceipts(receipts)
    })
  }
  catch {
    return false
  }
}

export function playbackReceiptsForSession(sessionId: string): PlaybackReceipt[] {
  try {
    return readReceipts().filter(receipt => receipt.sessionId === sessionId).map(receipt => ({ ...receipt }))
  }
  catch {
    return []
  }
}

export function clearPlaybackLedger(): void {
  try { localStorage.removeItem(storageKey); localStorage.removeItem(reachOutBudgetKey) }
  catch { /* Storage can be unavailable in restricted renderer contexts. */ }
}
