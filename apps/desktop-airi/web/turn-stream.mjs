const terminalEventByStatus = {
  completed: 'turn.completed',
  cancelled: 'turn.cancelled',
  failed: 'turn.failed',
}

export async function followDesktopTurn({
  coreUrl,
  sessionId,
  turnId,
  fetchImpl = fetch,
  maxReconnects = 3,
  onEvent,
  onSnapshot,
}) {
  let lastSequence = 0

  for (let reconnects = 0; ; reconnects += 1) {
    let response
    try {
      const url = coreUrl.replace(/\/$/, '') + '/v1/sessions/'
        + encodeURIComponent(sessionId) + '/events?after_sequence='
        + encodeURIComponent(String(lastSequence))
      response = await fetchImpl(url, {
        headers: {
          Accept: 'text/event-stream',
          'Last-Event-ID': String(lastSequence),
        },
      })
    }
    catch (error) {
      if (reconnects >= maxReconnects)
        throw error
      continue
    }

    if (!response.ok || !response.body)
      throw new Error('SSE ' + response.status)

    const consumed = await consumeDesktopEventStream(
      response.body,
      lastSequence,
      turnId,
      onEvent,
    )
    lastSequence = consumed.lastSequence
    if (consumed.terminal)
      return consumed
    if (reconnects < maxReconnects)
      continue

    return await recoverDesktopTurnFromSnapshot({
      coreUrl,
      sessionId,
      turnId,
      lastSequence,
      fetchImpl,
      onSnapshot,
    })
  }
}

async function consumeDesktopEventStream(body, initialSequence, turnId, onEvent) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let lastSequence = initialSequence
  let terminal = undefined

  const acceptFrame = async frame => {
    const parsed = parseSseFrame(frame)
    if (!parsed) return
    const sequence = parsed.envelope?.sequence
    if (!Number.isSafeInteger(sequence) || sequence <= lastSequence) return
    lastSequence = sequence
    if (parsed.envelope.turn_id !== turnId) return
    await onEvent?.(parsed)
    if (isTerminalEvent(parsed.eventName))
      terminal = parsed.eventName
  }

  try {
    while (!terminal) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() ?? ''
      for (const frame of frames)
        await acceptFrame(frame)
      if (done) break
    }
    buffer += decoder.decode()
    if (!terminal && buffer.trim())
      await acceptFrame(buffer)
  }
  finally {
    reader.releaseLock()
  }
  return { lastSequence, terminal }
}

async function recoverDesktopTurnFromSnapshot({
  coreUrl,
  sessionId,
  turnId,
  lastSequence,
  fetchImpl,
  onSnapshot,
}) {
  const response = await fetchImpl(
    coreUrl.replace(/\/$/, '') + '/v1/sessions/' + encodeURIComponent(sessionId) + '/snapshot',
    { headers: { Accept: 'application/json' } },
  )
  if (!response.ok)
    throw new Error('SSE ended before a terminal event after reconnects; snapshot ' + response.status)
  const snapshot = await response.json()
  const turn = Array.isArray(snapshot?.turns)
    ? snapshot.turns.find(candidate => candidate?.turn_id === turnId)
    : undefined
  const terminal = terminalEventByStatus[turn?.status]
  if (!terminal)
    throw new Error('SSE ended before a terminal event after reconnects')
  await onSnapshot?.(turn, snapshot)
  return {
    lastSequence: Number.isSafeInteger(snapshot?.sequence)
      ? Math.max(lastSequence, snapshot.sequence)
      : lastSequence,
    terminal,
  }
}

function parseSseFrame(frame) {
  let eventName = ''
  const dataLines = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }
  if (!eventName || dataLines.length === 0) return undefined
  try {
    return { eventName, envelope: JSON.parse(dataLines.join('\n')) }
  }
  catch {
    return undefined
  }
}

function isTerminalEvent(eventName) {
  return eventName === 'turn.completed'
    || eventName === 'turn.cancelled'
    || eventName === 'turn.failed'
}
