import { randomUUID } from 'node:crypto'
import { cpSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { basename, dirname, extname, join, resolve, sep } from 'node:path'

const MAX_FILES = 2048
const MAX_BYTES = 512 * 1024 * 1024
const MAX_SINGLE = 100 * 1024 * 1024
const allowed = new Set(['.json', '.moc3', '.png', '.jpg', '.jpeg', '.wav', '.mp3', '.ogg', '.txt', '.md'])
const media = { '.json': 'application/json', '.moc3': 'application/octet-stream', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg' }

function relativePath(value) {
  if (typeof value !== 'string' || !value || value.includes('\\') || value.includes(':') || value.includes('\0') || value.startsWith('/') || value.split('/').some(part => !part || part === '..' || part === '.'))
    throw new Error('Live2D 资源必须使用模型目录内的相对路径。')
  if (!allowed.has(extname(value).toLowerCase()))
    throw new Error(`Live2D 不支持此资源类型：${extname(value)}`)
  return value
}

function walk(root) {
  const files = new Map()
  let total = 0
  function visit(directory, prefix) {
    for (const item of readdirSync(directory, { withFileTypes: true })) {
      const name = prefix ? `${prefix}/${item.name}` : item.name
      const path = join(directory, item.name)
      const stat = lstatSync(path)
      if (stat.isSymbolicLink()) throw new Error('Live2D 导入不支持符号链接。')
      if (stat.isDirectory()) { visit(path, name); continue }
      if (!stat.isFile()) throw new Error('Live2D 包含不支持的文件。')
      relativePath(name)
      if (stat.size > MAX_SINGLE || files.size >= MAX_FILES || (total += stat.size) > MAX_BYTES)
        throw new Error('Live2D 模型超过文件数量或大小限制（512 MB）。')
      const collision = [...files.keys()].some(key => key.toLowerCase() === name.toLowerCase())
      if (collision) throw new Error('Live2D 文件名称存在大小写冲突。')
      files.set(name, path)
    }
  }
  visit(root, '')
  return files
}

/** Validate every standard Cubism reference before any model enters managed storage. */
export function inspectLive2DDirectory(root) {
  const source = resolve(root)
  if (lstatSync(source).isSymbolicLink() || !lstatSync(source).isDirectory()) throw new Error('请选择本地模型目录。')
  const files = walk(source)
  const entries = [...files.keys()].filter(path => path.toLowerCase().endsWith('.model3.json'))
  if (entries.length !== 1) throw new Error('模型目录必须包含且仅包含一个 .model3.json。')
  const entryPoint = entries[0]
  const document = JSON.parse(readFileSync(files.get(entryPoint), 'utf8'))
  const refs = document.FileReferences
  if (document.Version !== 3 || !refs || typeof refs.Moc !== 'string' || !Array.isArray(refs.Textures) || !refs.Textures.length)
    throw new Error('不是完整的 Cubism 3/4/5 导出模型：缺少 Moc 或 Textures。')
  const base = dirname(entryPoint) === '.' ? '' : `${dirname(entryPoint).replaceAll('\\', '/')}/`
  function requireFile(value) {
    const path = `${base}${relativePath(value)}`
    if (!files.has(path)) throw new Error(`缺少模型资源（名称区分大小写）：${path}`)
    return files.get(path)
  }
  const moc = readFileSync(requireFile(refs.Moc))
  if (moc.length < 64 || moc.subarray(0, 4).toString() !== 'MOC3') throw new Error('MOC3 模型头无效。')
  for (const texture of refs.Textures) {
    const image = readFileSync(requireFile(texture))
    const png = image.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
    const jpeg = image[0] === 255 && image[1] === 216
    if (!png && !jpeg) throw new Error(`模型纹理无法识别：${texture}`)
  }
  for (const key of ['Physics', 'Pose', 'DisplayInfo', 'UserData']) if (refs[key]) requireFile(refs[key])
  const expressions = []
  for (const expression of refs.Expressions ?? []) {
    requireFile(expression.File)
    if (typeof expression.Name !== 'string') throw new Error('Live2D 表情名称无效。')
    expressions.push(expression.Name)
  }
  const motions = Object.keys(refs.Motions ?? {})
  for (const group of Object.values(refs.Motions ?? {})) {
    if (!Array.isArray(group)) throw new Error('Live2D 动作列表无效。')
    for (const motion of group) { requireFile(motion.File); if (motion.Sound) requireFile(motion.Sound) }
  }
  // Unknown reference keys are rejected to avoid silently accepting external resources.
  for (const key of Object.keys(refs)) if (!['Moc', 'Textures', 'Physics', 'Pose', 'DisplayInfo', 'UserData', 'Expressions', 'Motions'].includes(key))
    throw new Error(`不支持的 Live2D 资源字段：${key}`)
  const hitAreas = (document.HitAreas ?? []).map(area => area.Name).filter(name => typeof name === 'string')
  const reactionFile = files.get(`${base}meguri-interactions.json`)
  const settings = reactionFile ? JSON.parse(readFileSync(reactionFile, 'utf8')) : {}
  const reactions = {}
  for (const [area, reaction] of Object.entries(settings.reactions ?? {})) {
    if (!hitAreas.includes(area) || !reaction || typeof reaction !== 'object') throw new Error('触摸映射使用了不存在的模型区域。')
    if (reaction.motion && !motions.includes(reaction.motion)) throw new Error('触摸映射使用了不存在的动作。')
    if (reaction.expression && !expressions.includes(reaction.expression)) throw new Error('触摸映射使用了不存在的表情。')
    if (reaction.speech && (typeof reaction.speech !== 'string' || reaction.speech.length > 200)) throw new Error('触摸语音最多 200 字。')
    reactions[area] = { motion: reaction.motion, expression: reaction.expression, speech: reaction.speech }
  }
  return { source, entryPoint, name: typeof settings.name === 'string' ? settings.name.slice(0, 100) : basename(source), hitAreas, expressions, motions, reactions, license: typeof settings.license === 'string' ? settings.license.slice(0, 300) : undefined }
}

export function createLive2DStore({ root, airiRoot = process.env.MEGURI_AIRI_ROOT ?? 'D:/program/airi-meguri' }) {
  const managed = resolve(root)
  const catalogFile = join(managed, 'catalog.json')
  const validId = id => typeof id === 'string' && /^[a-z0-9-]{1,80}$/.test(id)
  function modelRoot(id) {
    if (!validId(id)) throw new Error('Live2D 模型标识无效。')
    return join(managed, id)
  }
  function catalog() {
    if (!existsSync(catalogFile)) return { version: 1, models: [] }
    const value = JSON.parse(readFileSync(catalogFile, 'utf8'))
    if (value.version !== 1 || !Array.isArray(value.models)) throw new Error('Live2D 模型目录索引损坏。')
    return value
  }
  function save(value) {
    mkdirSync(managed, { recursive: true })
    const temporary = `${catalogFile}.${randomUUID()}.tmp`
    writeFileSync(temporary, JSON.stringify(value, null, 2))
    renameSync(temporary, catalogFile)
    return value
  }
  async function importDirectory(path) {
    const inspected = inspectLive2DDirectory(path)
    const id = randomUUID()
    const target = modelRoot(id)
    mkdirSync(managed, { recursive: true })
    try {
      cpSync(inspected.source, target, { recursive: true, dereference: false, errorOnExist: true })
      inspectLive2DDirectory(target)
      const { source, ...summary } = inspected
      const value = catalog()
      value.models.push({ ...summary, id, importedAt: new Date().toISOString() })
      return save(value)
    }
    catch (error) { rmSync(target, { recursive: true, force: true }); throw error }
  }
  async function importArchive(path) {
    const stat = lstatSync(path)
    if (stat.isSymbolicLink() || !stat.isFile() || stat.size > MAX_BYTES) throw new Error('请选择小于 512 MB 的 Live2D ZIP。')
    const require = createRequire(join(resolve(airiRoot), 'packages/stage-ui-live2d/package.json'))
    const JSZip = require('jszip')
    const zip = await JSZip.loadAsync(readFileSync(path))
    const entries = Object.values(zip.files)
    if (entries.length > MAX_FILES) throw new Error('ZIP 文件数量超过限制。')
    const temporary = join(managed, `import-${randomUUID()}`)
    mkdirSync(temporary, { recursive: true })
    try {
      let total = 0
      for (const entry of entries) {
        const raw = entry.unsafeOriginalName ?? entry.name
        if (raw.includes('..') || raw.startsWith('/') || raw.includes('\\') || raw.includes(':')) throw new Error('ZIP 包含越界资源路径。')
        if (((Number(entry.unixPermissions) >> 12) & 15) === 10) throw new Error('ZIP 包含符号链接。')
        if (entry.dir) continue
        relativePath(raw)
        const advertised = entry._data?.uncompressedSize
        if (advertised > MAX_SINGLE || total + advertised > MAX_BYTES) throw new Error('ZIP 解压资源超过大小限制。')
        const bytes = await entry.async('nodebuffer')
        total += bytes.length
        if (bytes.length > MAX_SINGLE || total > MAX_BYTES) throw new Error('ZIP 解压资源超过大小限制。')
        const target = resolve(temporary, raw)
        if (!target.startsWith(`${temporary}${sep}`)) throw new Error('ZIP 资源路径无效。')
        mkdirSync(dirname(target), { recursive: true })
        writeFileSync(target, bytes, { flag: 'wx' })
      }
      return await importDirectory(temporary)
    }
    finally { rmSync(temporary, { recursive: true, force: true }) }
  }
  function activate(id) {
    const value = catalog()
    if (!value.models.some(model => model.id === id)) throw new Error('未找到此 Live2D 模型。')
    inspectLive2DDirectory(modelRoot(id))
    value.activeId = id
    return save(value)
  }
  function remove(id) {
    const value = catalog()
    if (!value.models.some(model => model.id === id)) throw new Error('未找到此 Live2D 模型。')
    const target = resolve(modelRoot(id))
    if (!target.startsWith(`${managed}${sep}`)) throw new Error('Live2D 删除路径无效。')
    rmSync(target, { recursive: true, force: true })
    value.models = value.models.filter(model => model.id !== id)
    if (value.activeId === id) delete value.activeId
    return save(value)
  }
  function active() {
    const value = catalog()
    const model = value.models.find(model => model.id === value.activeId)
    return model ? { ...model, modelUrl: `meguri-live2d://model/${model.id}/${model.entryPoint.split('/').map(encodeURIComponent).join('/')}` } : null
  }
  function asset(id, path) {
    const value = catalog()
    if (!value.models.some(model => model.id === id)) throw new Error('未找到此 Live2D 模型。')
    relativePath(path)
    const directory = modelRoot(id)
    const target = resolve(directory, path)
    if (!target.startsWith(`${directory}${sep}`)) throw new Error('模型资源路径无效。')
    const pieces = path.split('/')
    for (let i = 1; i <= pieces.length; i++) if (lstatSync(join(directory, ...pieces.slice(0, i))).isSymbolicLink()) throw new Error('资源链接不可读取。')
    if (!lstatSync(target).isFile() || lstatSync(target).size > MAX_SINGLE) throw new Error('模型资源大小无效。')
    return { bytes: readFileSync(target), mimeType: media[extname(target).toLowerCase()] ?? 'text/plain' }
  }
  return { catalog, importDirectory, importArchive, activate, remove, active, asset }
}
