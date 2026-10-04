import { createHash } from 'node:crypto'
import { lstatSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { basename, extname, isAbsolute, relative, resolve, sep } from 'node:path'

export const MAX_CHARACTER_PACK_FILES = 512
export const MAX_CHARACTER_PACK_BYTES = 120 * 1024 * 1024
export const MAX_CHARACTER_PACK_ASSET_BYTES = 8 * 1024 * 1024

const supportedExtensions = new Map([
  ['.png', { mediaType: 'image/png', kind: 'sprite' }],
  ['.jpg', { mediaType: 'image/jpeg', kind: 'background' }],
  ['.jpeg', { mediaType: 'image/jpeg', kind: 'background' }],
])

function failure(error) {
  return { ok: false, error }
}

function userSafeError(error, fallback) {
  const message = error instanceof Error ? error.message : String(error)
  return /(?:[A-Za-z]:[\\/]|\\\\)/u.test(message) ? fallback : message
}

function safeIdentifier(value, field) {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/u.test(value))
    throw new Error(`${field} is invalid`)
  return value
}

function safeRelativePath(root, value) {
  if (typeof value !== 'string' || !value.trim() || isAbsolute(value))
    throw new Error('manifest contains an invalid relative path')
  const normalized = value.replaceAll('\\', '/')
  if (normalized.split('/').some(segment => !segment || segment === '.' || segment === '..'))
    throw new Error(`manifest path is unsafe: ${value}`)
  const resolved = resolve(root, normalized)
  const remainder = relative(root, resolved)
  if (!remainder || remainder === '..' || remainder.startsWith(`..${sep}`) || isAbsolute(remainder))
    throw new Error(`manifest path escapes package root: ${value}`)
  return { normalized, resolved }
}

function inspectFile(pathValue) {
  const attributes = lstatSync(pathValue)
  if (attributes.isSymbolicLink())
    throw new Error(`symbolic links are not allowed: ${basename(pathValue)}`)
  if (!attributes.isFile())
    throw new Error(`package item is not a regular file: ${basename(pathValue)}`)
  return attributes
}

function isSupportedImage(bytes, extension) {
  if (extension === '.png')
    return bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
  return bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255
}

function walkFiles(root) {
  const files = []
  const pending = [root]
  let totalBytes = 0
  while (pending.length) {
    const directory = pending.pop()
    const directoryAttributes = lstatSync(directory)
    if (directoryAttributes.isSymbolicLink())
      throw new Error(`symbolic links are not allowed: ${basename(directory) || 'package root'}`)
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const pathValue = resolve(directory, entry.name)
      const attributes = lstatSync(pathValue)
      if (attributes.isSymbolicLink())
        throw new Error(`symbolic links are not allowed: ${entry.name}`)
      if (attributes.isDirectory()) {
        pending.push(pathValue)
        continue
      }
      if (!attributes.isFile())
        throw new Error(`package item is not a regular file: ${entry.name}`)
      files.push(pathValue)
      totalBytes += attributes.size
      if (files.length > MAX_CHARACTER_PACK_FILES)
        throw new Error(`character pack exceeds ${MAX_CHARACTER_PACK_FILES} files`)
      if (totalBytes > MAX_CHARACTER_PACK_BYTES)
        throw new Error(`character pack exceeds ${MAX_CHARACTER_PACK_BYTES} bytes`)
    }
  }
  return { files, totalBytes }
}

function parseManifest(root) {
  const manifestPath = resolve(root, 'manifest.json')
  const attributes = inspectFile(manifestPath)
  if (attributes.size > 1_048_576)
    throw new Error('manifest.json exceeds 1 MB')
  let manifest
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8').replace(/^\uFEFF/u, ''))
  }
  catch {
    throw new Error('manifest.json is not valid JSON')
  }
  if (!manifest || typeof manifest !== 'object' || manifest.schema_version !== 1)
    throw new Error('manifest.json must use schema_version 1')
  safeIdentifier(manifest.release_id, 'release_id')
  safeIdentifier(manifest.build_id, 'build_id')
  if (!Array.isArray(manifest.files) || !manifest.files.length)
    throw new Error('manifest.json must declare package files')
  return manifest
}

function declaredFiles(root, manifest) {
  const declared = new Map()
  for (const file of manifest.files) {
    if (!file || typeof file !== 'object')
      throw new Error('manifest files contains an invalid entry')
    const { normalized, resolved } = safeRelativePath(root, file.path)
    if (declared.has(normalized))
      throw new Error(`manifest declares a duplicate file: ${file.path}`)
    const extension = extname(normalized).toLowerCase()
    const supported = supportedExtensions.get(extension)
    if (!supported && normalized !== 'manifest.json' && normalized !== 'meguri_asset_manifest.json')
      throw new Error(`unsupported character pack file: ${file.path}`)
    const attributes = inspectFile(resolved)
    if (attributes.size <= 0)
      throw new Error(`package file is empty: ${file.path}`)
    if (supported && attributes.size > MAX_CHARACTER_PACK_ASSET_BYTES)
      throw new Error(`character resource exceeds ${MAX_CHARACTER_PACK_ASSET_BYTES} bytes: ${file.path}`)
    const bytes = readFileSync(resolved)
    if (supported && !isSupportedImage(bytes, extension))
      throw new Error(`character resource has an invalid ${extension} signature: ${file.path}`)
    if (Number.isInteger(file.bytes) && file.bytes !== attributes.size)
      throw new Error(`package file size mismatch: ${file.path}`)
    if (typeof file.sha256 === 'string' && file.sha256) {
      const hash = createHash('sha256').update(bytes).digest('hex')
      if (hash !== file.sha256.toLowerCase())
        throw new Error(`package file hash mismatch: ${file.path}`)
    }
    declared.set(normalized, { path: normalized, attributes, supported })
  }
  return declared
}

function declaredResourcePaths(manifest) {
  const resources = []
  const resourceIds = new Set()
  function addResource(kind, path) {
    const id = `${kind}:${path}`
    if (resourceIds.has(id))
      throw new Error(`manifest declares a duplicate resource: ${path}`)
    resourceIds.add(id)
    resources.push({ kind, path })
  }
  const sprites = manifest.sprite?.files
  const spriteDirectory = manifest.sprite?.directory
  if (Array.isArray(sprites) && typeof spriteDirectory === 'string') {
    for (const name of sprites)
      addResource('sprite', `${spriteDirectory}/${name}`)
  }
  const groups = manifest.background?.groups
  const backgroundDirectory = manifest.background?.directory
  if (groups && typeof groups === 'object' && typeof backgroundDirectory === 'string') {
    for (const values of Object.values(groups)) {
      if (!Array.isArray(values))
        continue
      for (const name of values) {
        const path = `${backgroundDirectory}/${name}`
        addResource('background', path)
      }
    }
  }
  const uiDirectory = manifest.ui?.directory
  if (typeof uiDirectory === 'string') {
    for (const field of ['dialogue_frame', 'advance_indicator']) {
      if (typeof manifest.ui?.[field] === 'string')
        addResource('ui', `${uiDirectory}/${manifest.ui[field]}`)
    }
  }
  return resources
}

/** Inspect the first supported directory pack without copying or exposing it to renderers. */
export function inspectCharacterPackDirectory(pathValue, { allowDraftExtras = false } = {}) {
  try {
    if (typeof pathValue !== 'string' || !pathValue.trim() || !isAbsolute(pathValue))
      return failure('请选择本机磁盘上的角色包目录。')
    const root = resolve(pathValue)
    const rootAttributes = lstatSync(root)
    if (rootAttributes.isSymbolicLink() || !rootAttributes.isDirectory())
      return failure('角色包必须是普通本地目录，且不能是符号链接。')
    const manifest = parseManifest(root)
    const scanned = walkFiles(root)
    const declared = declaredFiles(root, manifest)
    const scannedRelative = new Set(scanned.files.map(file => relative(root, file).replaceAll('\\', '/')))
    for (const path of scannedRelative) {
      const isDraftExtra = allowDraftExtras && path.startsWith('extras/') && supportedExtensions.has(extname(path).toLowerCase())
      if (!declared.has(path) && path !== 'manifest.json' && !isDraftExtra)
        return failure(`角色包包含未声明文件：${path}`)
    }
    const resources = []
    for (const resource of declaredResourcePaths(manifest)) {
      const entry = declared.get(resource.path)
      if (!entry?.supported)
        return failure(`角色资源缺失或格式不受支持：${resource.path}`)
      resources.push({
        id: `${resource.kind}:${resource.path}`,
        name: basename(resource.path),
        kind: resource.kind,
        mediaType: entry.supported.mediaType,
        status: 'ready',
        sourcePath: resource.path,
      })
    }
    if (!resources.length)
      return failure('角色包未声明可预览的 PNG 或 JPG 资源。')
    return {
      ok: true,
      root,
      manifest,
      files: [...declared.keys()],
      resources,
      totalBytes: scanned.totalBytes,
    }
  }
  catch (error) {
    return failure(userSafeError(error, '角色包目录无法验证。'))
  }
}

/** Validate one user-selected draft image before it is copied into the package root. */
export function inspectCharacterPackDraftResource(pathValue) {
  try {
    if (typeof pathValue !== 'string' || !pathValue.trim() || !isAbsolute(pathValue))
      return failure('请选择本机磁盘上的 PNG 或 JPG 文件。')
    const path = resolve(pathValue)
    const attributes = inspectFile(path)
    if (attributes.size <= 0 || attributes.size > MAX_CHARACTER_PACK_ASSET_BYTES)
      return failure(`资源必须大于 0 字节且不能超过 ${MAX_CHARACTER_PACK_ASSET_BYTES} 字节。`)
    const extension = extname(path).toLowerCase()
    const supported = supportedExtensions.get(extension)
    if (!supported)
      return failure('暂只支持 PNG、JPG 与 JPEG 角色资源。')
    if (!isSupportedImage(readFileSync(path), extension))
      return failure('角色资源的图片签名无效。')
    return {
      ok: true,
      path,
      name: basename(path),
      mediaType: supported.mediaType,
      kind: supported.kind,
    }
  }
  catch (error) {
    return failure(userSafeError(error, '角色资源无法验证。'))
  }
}
