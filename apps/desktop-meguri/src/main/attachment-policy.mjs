import { createHash } from 'node:crypto'
import { lstatSync, realpathSync, statSync } from 'node:fs'
import { basename, extname, isAbsolute, parse, resolve } from 'node:path'

export const maxDocumentBytes = 1_048_576
export const maxMultimodalBytes = 5 * 1024 * 1024

const deniedSegments = new Set([
  '.git', '.hg', '.svn', '.ssh', '.gnupg', '.aws', '.azure', '.kube', '.codex',
  'appdata', 'node_modules', '__pycache__', '.venv', 'venv', '.gradle', '.m2',
  'target', 'build', 'dist', 'out', 'windows', 'program files', 'program files (x86)',
  'programdata', 'system volume information', '$recycle.bin', 'recycler',
])
const deniedFileNames = new Set([
  '.env', 'auth.json', 'credentials.json', 'common-config.yaml',
  'id_rsa', 'id_ed25519', 'known_hosts', 'qianji-local-sync.secret.bat',
])
const deniedExtensions = new Set(['.pem', '.key', '.p12', '.pfx', '.kdbx', '.jks'])
const multimodalExtensions = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.pdf'])
const documentExtensions = new Set(['.txt', '.md', '.markdown', '.csv', '.json', '.yaml', '.yml', '.docx'])

function failure(error) {
  return { ok: false, error }
}

function isLocalAbsolutePath(pathValue) {
  if (!isAbsolute(pathValue))
    return false
  const root = parse(pathValue).root
  return Boolean(root) && !root.startsWith('\\\\') && !root.startsWith('//')
}

function containsDeniedSegment(pathValue) {
  const root = parse(pathValue).root
  return pathValue.slice(root.length).split(/[\\/]+/u)
    .some(segment => deniedSegments.has(segment.toLowerCase()))
}

function isDeniedFileName(name) {
  const lower = name.toLowerCase()
  return deniedFileNames.has(lower)
    || lower.startsWith('.env.')
    || deniedExtensions.has(extname(lower))
}

export function stableLocalReference(pathValue) {
  const digest = createHash('sha256').update(pathValue.toLowerCase(), 'utf8').digest('hex')
  return `local_${digest.slice(0, 24)}`
}

/** Validate and classify one user-dropped path before it becomes a readable attachment. */
export function inspectDroppedAttachment(pathValue) {
  if (typeof pathValue !== 'string' || !pathValue.trim() || !isLocalAbsolutePath(pathValue.trim()))
    return failure('无法读取这个项目：请选择本机磁盘上的普通文件。')

  let canonicalPath
  let attributes
  try {
    const candidate = resolve(pathValue.trim())
    if (lstatSync(candidate).isSymbolicLink())
      return failure('出于安全保护，Meguri 不会读取符号链接。')
    canonicalPath = realpathSync.native(candidate)
    attributes = statSync(canonicalPath)
  }
  catch {
    return failure('这个文件已不存在或无法访问，请重新选择。')
  }

  if (!isLocalAbsolutePath(canonicalPath) || !attributes.isFile())
    return failure('无法读取这个项目：请选择本机磁盘上的普通文件。')

  const name = basename(canonicalPath)
  if (containsDeniedSegment(canonicalPath) || isDeniedFileName(name))
    return failure('出于安全保护，Meguri 不能读取这个位置或凭据类文件。')

  const extension = extname(name).toLowerCase()
  const multimodal = multimodalExtensions.has(extension)
  const document = documentExtensions.has(extension)
  if (!multimodal && !document)
    return failure('暂不支持该文件类型。可读取图片、PDF、TXT、Markdown、CSV、JSON、YAML 和 DOCX。')
  if (multimodal && (attributes.size <= 0 || attributes.size > maxMultimodalBytes))
    return failure('图片或 PDF 必须大于 0 字节且不能超过 5 MB。')
  if (document && attributes.size > maxDocumentBytes)
    return failure('文档不能超过 1 MB。')

  return {
    ok: true,
    attachment: {
      type: 'local_file_reference',
      source: 'everything',
      reference_id: stableLocalReference(canonicalPath),
      name,
      path: canonicalPath,
      kind: extension === '.pdf' ? 'pdf' : (multimodal ? 'image' : 'document'),
      size_bytes: attributes.size,
      modified_at: attributes.mtime.toISOString(),
      content_access: multimodal ? 'multimodal_read' : 'document_read',
    },
  }
}

/** Convert one explicitly pasted native clipboard image into the existing inline attachment wire shape. */
export function createClipboardImageAttachment(pngBytes, capturedAt = new Date(), captureId) {
  if (!Buffer.isBuffer(pngBytes) || pngBytes.length === 0)
    return failure('剪贴板中没有可读取的图片。')
  if (pngBytes.length > maxMultimodalBytes)
    return failure('截图转换为 PNG 后超过 5 MB，请缩小截图范围后重试。')

  const digest = createHash('sha256').update(pngBytes).digest('hex')
  const stamp = capturedAt.toISOString().replace(/[-:]/gu, '').replace(/\.\d{3}Z$/u, 'Z')
  const captureReference = typeof captureId === 'string'
    && /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/iu.test(captureId)
    ? `capture_${captureId}`
    : digest.slice(0, 24)
  return {
    ok: true,
    attachment: {
      type: 'inline_attachment',
      source: 'airi_inline',
      reference_id: `inline_${captureReference}`,
      name: `截图-${stamp}.png`,
      kind: 'image',
      size_bytes: pngBytes.length,
      modified_at: capturedAt.toISOString(),
      content_access: 'multimodal_read',
      mime_type: 'image/png',
      data_url: `data:image/png;base64,${pngBytes.toString('base64')}`,
    },
  }
}
