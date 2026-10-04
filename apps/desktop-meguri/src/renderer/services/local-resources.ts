import type { DesktopReadableAttachment } from '../../shared/desktop-contract'
import { coreUrl, desktopFetch } from './desktop-fetch'

export interface LocalResourceCandidate {
  id: string
  name: string
  path: string
  kind: string
  size_bytes: number | null
  modified_at: string | null
}

export interface MetadataLocalResourceAttachment {
  type: 'local_file_reference'
  source: 'everything'
  reference_id: string
  name: string
  path: string
  kind: string
  size_bytes: number | null
  modified_at: string | null
  content_access: 'not_read'
}

export type LocalResourceAttachment = MetadataLocalResourceAttachment | DesktopReadableAttachment

export const maxStagedAttachments = 6
export const maxReadableAttachmentBytes = 10 * 1024 * 1024

export interface LocalResourceResolution {
  kind: string
  message: string
  arguments: string
  command?: string | null
  error?: string
}

export interface LocalResourceSearchResult {
  query: string
  available: boolean
  message: string
  candidates: LocalResourceCandidate[]
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function nullableNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function nullableString(value: unknown): string | null {
  return typeof value === 'string' && value ? value : null
}

function normalizeCandidate(value: unknown): LocalResourceCandidate | null {
  if (!value || typeof value !== 'object')
    return null
  const record = value as Record<string, unknown>
  const id = stringValue(record.id).trim()
  const name = stringValue(record.name).trim()
  const path = stringValue(record.path).trim()
  if (!id || !name || !path)
    return null
  return {
    id,
    name,
    path,
    kind: stringValue(record.kind).trim() || 'file',
    size_bytes: nullableNumber(record.size_bytes),
    modified_at: nullableString(record.modified_at),
  }
}

async function responseJson(response: Response, failure: string): Promise<Record<string, unknown>> {
  if (!response.ok)
    throw new Error(`${failure}（${response.status}）`)
  try {
    const value = await response.json()
    if (!value || typeof value !== 'object' || Array.isArray(value))
      throw new Error('invalid response')
    return value as Record<string, unknown>
  }
  catch (error) {
    if (error instanceof Error && error.message.startsWith(failure))
      throw error
    throw new Error(`${failure}（返回格式无效）`)
  }
}

export function resourceMarkerPresent(value: string): boolean {
  return /^\s*@/.test(value)
    || /(^|\s)@(?=["{]|[^\s@])/.test(value)
    || /(^|\s)@\s*$/.test(value)
}

export async function resolveLocalResourceInput(
  sessionId: string,
  message: string,
  signal?: AbortSignal,
): Promise<LocalResourceResolution> {
  const response = await desktopFetch(sessionId, `${coreUrl}/v1/input/resolve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
    signal,
  })
  const value = await responseJson(response, '无法解析本地资源引用')
  const kind = stringValue(value.kind)
  if (!kind)
    throw new Error('无法解析本地资源引用（返回格式无效）')
  return {
    kind,
    message: stringValue(value.message),
    arguments: stringValue(value.arguments),
    command: value.command === null ? null : stringValue(value.command) || undefined,
    error: stringValue(value.error) || undefined,
  }
}

export async function searchLocalResources(
  sessionId: string,
  query: string,
  signal?: AbortSignal,
): Promise<LocalResourceSearchResult> {
  const url = `${coreUrl}/v1/resources/search?q=${encodeURIComponent(query.trim())}&limit=8`
  const response = await desktopFetch(sessionId, url, { signal })
  const value = await responseJson(response, 'Everything 搜索失败')
  const candidates = Array.isArray(value.candidates)
    ? value.candidates.map(normalizeCandidate).filter((candidate): candidate is LocalResourceCandidate => candidate !== null).slice(0, 8)
    : []
  return {
    query: stringValue(value.query) || query.trim(),
    available: value.available === true,
    message: stringValue(value.message),
    candidates,
  }
}

export function toLocalResourceAttachment(candidate: Partial<LocalResourceCandidate>): LocalResourceAttachment | null {
  const normalized = normalizeCandidate(candidate)
  if (!normalized)
    return null
  return {
    type: 'local_file_reference',
    source: 'everything',
    reference_id: normalized.id,
    name: normalized.name,
    path: normalized.path,
    kind: normalized.kind,
    size_bytes: normalized.size_bytes,
    modified_at: normalized.modified_at,
    content_access: 'not_read',
  }
}

export function isReadableAttachment(attachment: LocalResourceAttachment): attachment is DesktopReadableAttachment {
  return attachment.content_access === 'multimodal_read' || attachment.content_access === 'document_read'
}

export function normalizeReadableAttachment(value: unknown): DesktopReadableAttachment | null {
  if (!value || typeof value !== 'object')
    return null
  const record = value as Record<string, unknown>
  const referenceId = stringValue(record.reference_id).trim()
  const name = stringValue(record.name).trim()
  const kind = stringValue(record.kind).trim()
  const sizeBytes = nullableNumber(record.size_bytes)
  const modifiedAt = nullableString(record.modified_at)
  if (!referenceId || !name || sizeBytes === null || sizeBytes < 0 || !modifiedAt)
    return null

  if (record.type === 'local_file_reference' && record.source === 'everything') {
    const path = stringValue(record.path).trim()
    const contentAccess = record.content_access
    const supported = (contentAccess === 'document_read' && kind === 'document')
      || (contentAccess === 'multimodal_read' && (kind === 'image' || kind === 'pdf'))
    if (!path || !supported)
      return null
    return {
      type: 'local_file_reference',
      source: 'everything',
      reference_id: referenceId,
      name,
      path,
      kind,
      size_bytes: sizeBytes,
      modified_at: modifiedAt,
      content_access: contentAccess,
    }
  }

  if (record.type === 'inline_attachment'
    && record.source === 'airi_inline'
    && record.content_access === 'multimodal_read'
    && kind === 'image'
    && record.mime_type === 'image/png') {
    const dataUrl = stringValue(record.data_url)
    if (!dataUrl.startsWith('data:image/png;base64,'))
      return null
    return {
      type: 'inline_attachment',
      source: 'airi_inline',
      reference_id: referenceId,
      name,
      kind: 'image',
      size_bytes: sizeBytes,
      modified_at: modifiedAt,
      content_access: 'multimodal_read',
      mime_type: 'image/png',
      data_url: dataUrl,
    }
  }
  return null
}

export function stagedReadableBytes(attachments: LocalResourceAttachment[]): number {
  return attachments.reduce((total, attachment) => total + (isReadableAttachment(attachment) ? attachment.size_bytes : 0), 0)
}

export function attachmentAccessLabel(attachment: LocalResourceAttachment): string {
  if (attachment.content_access === 'not_read')
    return '仅引用，不读取正文'
  if (attachment.type === 'inline_attachment')
    return '截图，将请求视觉读取'
  return attachment.content_access === 'document_read' ? '将读取文档内容' : '将读取文件内容'
}
