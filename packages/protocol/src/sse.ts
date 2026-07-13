import { parseTurnEventEnvelope, type TurnEventEnvelope } from './turn-events.ts'

export class SseTurnEventParser {
  private buffer = ''

  push(chunk: string): TurnEventEnvelope[] {
    this.buffer += chunk
    this.buffer = this.buffer.replaceAll('\r\n', '\n')
    const events: TurnEventEnvelope[] = []
    let boundary = this.buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = this.buffer.slice(0, boundary)
      this.buffer = this.buffer.slice(boundary + 2)
      const parsed = this.parseBlock(block)
      if (parsed)
        events.push(parsed)
      boundary = this.buffer.indexOf('\n\n')
    }
    return events
  }

  finish(): void {
    if (this.buffer.trim().length > 0)
      throw new Error('incomplete SSE event at end of stream')
    this.buffer = ''
  }

  private parseBlock(block: string): TurnEventEnvelope | undefined {
    if (block.length === 0 || block.startsWith(':'))
      return undefined
    let id: number | undefined
    let eventName: string | undefined
    const data: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('id:'))
        id = Number.parseInt(line.slice(3).trim(), 10)
      else if (line.startsWith('event:'))
        eventName = line.slice(6).trim()
      else if (line.startsWith('data:'))
        data.push(line.slice(5).trimStart())
    }
    if (data.length === 0)
      return undefined
    const envelope = parseTurnEventEnvelope(JSON.parse(data.join('\n')))
    if (id !== undefined && id !== envelope.sequence)
      throw new Error('SSE id does not match event sequence')
    if (eventName !== undefined && eventName !== envelope.type)
      throw new Error('SSE event name does not match envelope type')
    return envelope
  }
}
