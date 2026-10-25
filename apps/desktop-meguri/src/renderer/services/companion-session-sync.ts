import type { CompanionCommitment } from '../../shared/companion-contract'

const channelName = 'meguri-companion-session-v1'
const maxCommitments = 100

export interface CompanionSessionMessage {
  version: 1
  senderId: string
  type: 'snapshot_request' | 'snapshot' | 'upsert' | 'clear'
  commitments?: CompanionCommitment[]
  commitment?: CompanionCommitment
}

export interface CompanionSessionTransport {
  postMessage(message: CompanionSessionMessage): void
  onMessage(listener: (message: unknown) => void): () => void
  close(): void
}

export interface CompanionSessionSyncOptions {
  transport: CompanionSessionTransport
  isEnabled(): boolean
  getCommitments(): CompanionCommitment[]
  setCommitments(commitments: CompanionCommitment[]): void
}

function validCommitment(value: unknown): value is CompanionCommitment {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  return typeof item.id === 'string'
    && /^[a-zA-Z0-9:_-]{1,100}$/.test(item.id)
    && typeof item.text === 'string'
    && item.text.trim().length > 0
    && item.text.length <= 240
    && Number.isFinite(item.dueAt)
    && Number.isFinite(item.createdAt)
    && (item.updatedAt === undefined || Number.isFinite(item.updatedAt))
    && ['pending', 'completed', 'cancelled'].includes(String(item.status))
    && (item.followUpDeliveredAt === undefined || Number.isFinite(item.followUpDeliveredAt))
}

function validMessage(value: unknown): value is CompanionSessionMessage {
  if (!value || typeof value !== 'object') return false
  const message = value as Record<string, unknown>
  return message.version === 1
    && typeof message.senderId === 'string'
    && ['snapshot_request', 'snapshot', 'upsert', 'clear'].includes(String(message.type))
}

function normalizedCommitments(values: unknown): CompanionCommitment[] {
  if (!Array.isArray(values)) return []
  return values.filter(validCommitment).slice(-maxCommitments).map(plainCommitment)
}

function plainCommitment(item: CompanionCommitment): CompanionCommitment {
  return {
    id: item.id,
    text: item.text.trim(),
    dueAt: item.dueAt,
    createdAt: item.createdAt,
    ...(item.updatedAt === undefined ? {} : { updatedAt: item.updatedAt }),
    status: item.status,
    ...(item.followUpDeliveredAt === undefined ? {} : { followUpDeliveredAt: item.followUpDeliveredAt }),
  }
}

function mergeCommitment(current: CompanionCommitment | undefined, incoming: CompanionCommitment) {
  if (!current) return plainCommitment(incoming)
  const currentVersion = current.updatedAt ?? current.createdAt
  const incomingVersion = incoming.updatedAt ?? incoming.createdAt
  if (incomingVersion > currentVersion) return plainCommitment(incoming)
  if (incomingVersion < currentVersion) return plainCommitment(current)
  const rank = (item: CompanionCommitment) => item.status !== 'pending' ? 3 : item.followUpDeliveredAt ? 2 : 1
  return plainCommitment(rank(incoming) >= rank(current) ? incoming : current)
}

export function createCompanionSessionSync(options: CompanionSessionSyncOptions) {
  const senderId = crypto.randomUUID()
  let closed = false
  const send = (message: Omit<CompanionSessionMessage, 'version' | 'senderId'>) => {
    if (!closed)
      options.transport.postMessage({ version: 1, senderId, ...message })
  }
  const upsert = (current: CompanionCommitment[], incoming: CompanionCommitment[]) => {
    const next = new Map(current.map(item => [item.id, plainCommitment(item)]))
    for (const item of incoming)
      next.set(item.id, mergeCommitment(next.get(item.id), item))
    return [...next.values()].slice(-maxCommitments)
  }
  const unsubscribe = options.transport.onMessage((value) => {
    if (!validMessage(value) || value.senderId === senderId || closed)
      return
    if (value.type === 'snapshot_request') {
      if (options.isEnabled())
        send({ type: 'snapshot', commitments: normalizedCommitments(options.getCommitments()) })
      return
    }
    if (!options.isEnabled())
      return
    if (value.type === 'clear') {
      options.setCommitments([])
    }
    else if (value.type === 'snapshot') {
      options.setCommitments(upsert(options.getCommitments(), normalizedCommitments(value.commitments)))
    }
    else if (value.type === 'upsert' && validCommitment(value.commitment)) {
      options.setCommitments(upsert(options.getCommitments(), [value.commitment]))
    }
  })

  send({ type: 'snapshot_request' })

  return {
    publishCommitment(commitment: CompanionCommitment) {
      if (options.isEnabled() && validCommitment(commitment))
        send({ type: 'upsert', commitment: plainCommitment(commitment) })
    },
    publishClear() {
      send({ type: 'clear' })
    },
    dispose() {
      if (closed) return
      closed = true
      unsubscribe()
      options.transport.close()
    },
  }
}

export function createBrowserCompanionSessionTransport(): CompanionSessionTransport | undefined {
  if (typeof window === 'undefined' || typeof BroadcastChannel === 'undefined')
    return undefined
  const channel = new BroadcastChannel(channelName)
  return {
    postMessage: message => channel.postMessage(message),
    onMessage(listener) {
      const handler = (event: MessageEvent<unknown>) => listener(event.data)
      channel.addEventListener('message', handler)
      return () => channel.removeEventListener('message', handler)
    },
    close: () => channel.close(),
  }
}
