import { randomUUID } from 'node:crypto'
import { copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { extname, join, resolve, sep } from 'node:path'

import { inspectCharacterPackDirectory, inspectCharacterPackDraftResource } from './character-pack-policy.mjs'

const CATALOG_VERSION = 1

function emptyCatalog() {
  return { version: CATALOG_VERSION, activePackageId: undefined, drafts: [], packages: [] }
}

function catalogPath(root) {
  return join(root, 'catalog.json')
}

function readCatalog(root) {
  try {
    const value = JSON.parse(readFileSync(catalogPath(root), 'utf8'))
    if (value && value.version === CATALOG_VERSION && Array.isArray(value.drafts) && Array.isArray(value.packages))
      return value
  }
  catch {
    // An absent or invalid catalog starts empty; imported directories remain isolated.
  }
  return emptyCatalog()
}

function writeCatalog(root, catalog) {
  mkdirSync(root, { recursive: true })
  const target = catalogPath(root)
  const temporary = `${target}.${process.pid}.${randomUUID()}.tmp`
  writeFileSync(temporary, `${JSON.stringify(catalog, null, 2)}\n`, 'utf8')
  renameSync(temporary, target)
}

function toSummary(entry, activePackageId) {
  return {
    id: entry.id,
    displayName: entry.displayName,
    version: entry.version,
    status: entry.status,
    active: entry.id === activePackageId,
    importedAt: entry.importedAt,
    publishedAt: entry.publishedAt,
    resources: entry.resources.map(({ sourcePath, ...resource }) => resource),
    validationMessages: [...entry.validationMessages],
  }
}

function publicCatalog(catalog) {
  return {
    version: CATALOG_VERSION,
    activePackageId: catalog.activePackageId,
    drafts: catalog.drafts.map(entry => toSummary(entry, catalog.activePackageId)),
    packages: catalog.packages.map(entry => toSummary(entry, catalog.activePackageId)),
  }
}

function findEntry(catalog, id) {
  return catalog.packages.find(entry => entry.id === id) ?? catalog.drafts.find(entry => entry.id === id)
}

function uniqueId(catalog, base) {
  const taken = new Set([...catalog.drafts, ...catalog.packages].map(entry => entry.id))
  if (!taken.has(base))
    return base
  let suffix = 2
  while (taken.has(`${base}-${suffix}`))
    suffix += 1
  return `${base}-${suffix}`
}

function requireDraft(catalog, id) {
  const draft = catalog.drafts.find(entry => entry.id === id)
  if (!draft)
    throw new Error('未找到角色包草稿。')
  return draft
}

function requirePublished(catalog, id) {
  const pack = catalog.packages.find(entry => entry.id === id)
  if (!pack)
    throw new Error('未找到已发布角色包。')
  return pack
}

function previewResource(storeRoot, entry, resource) {
  const path = resolve(storeRoot, entry.rootName, resource.sourcePath)
  const rootPath = resolve(storeRoot, entry.rootName)
  if (!path.startsWith(`${rootPath}${sep}`))
    throw new Error('角色资源路径无效。')
  const bytes = readFileSync(path)
  if (bytes.length <= 0 || bytes.length > 8 * 1024 * 1024)
    throw new Error('角色预览资源不可读取。')
  return {
    dataUrl: `data:${resource.mediaType};base64,${bytes.toString('base64')}`,
    mediaType: resource.mediaType,
  }
}

export function createCharacterPackStore({ root }) {
  const storeRoot = resolve(root)
  const draftsRoot = join(storeRoot, 'drafts')
  const packagesRoot = join(storeRoot, 'packages')

  function getCatalog() {
    return publicCatalog(readCatalog(storeRoot))
  }

  function importDirectory(sourceDirectory) {
    const inspected = inspectCharacterPackDirectory(sourceDirectory)
    if (!inspected.ok)
      throw new Error(inspected.error)
    const catalog = readCatalog(storeRoot)
    const id = uniqueId(catalog, `draft-${inspected.manifest.release_id}`)
    const destination = join(draftsRoot, id)
    mkdirSync(draftsRoot, { recursive: true })
    cpSync(inspected.root, destination, { recursive: true, errorOnExist: true, dereference: false })
    const copied = inspectCharacterPackDirectory(destination)
    if (!copied.ok) {
      rmSync(destination, { recursive: true, force: true })
      throw new Error(copied.error)
    }
    const importedAt = new Date().toISOString()
    const draft = {
      id,
      displayName: copied.manifest.release_id,
      version: copied.manifest.build_id,
      status: 'draft',
      importedAt,
      publishedAt: undefined,
      rootName: join('drafts', id),
      resources: copied.resources,
      validationMessages: [],
    }
    catalog.drafts.push(draft)
    writeCatalog(storeRoot, catalog)
    return { catalog: publicCatalog(catalog), pack: toSummary(draft, catalog.activePackageId) }
  }

  function addResource(draftId, sourcePath) {
    const inspected = inspectCharacterPackDraftResource(sourcePath)
    if (!inspected.ok)
      throw new Error(inspected.error)
    const catalog = readCatalog(storeRoot)
    const draft = requireDraft(catalog, draftId)
    const draftRoot = join(storeRoot, draft.rootName)
    const extrasRoot = join(draftRoot, 'extras')
    mkdirSync(extrasRoot, { recursive: true })
    const resourceId = `extra:${randomUUID()}`
    const targetName = `${resourceId.slice('extra:'.length)}${extname(inspected.name).toLowerCase()}`
    const sourceTarget = join(extrasRoot, targetName)
    copyFileSync(inspected.path, sourceTarget)
    draft.resources.push({
      id: resourceId,
      name: inspected.name,
      kind: 'extra',
      mediaType: inspected.mediaType,
      status: 'ready',
      sourcePath: `extras/${targetName}`,
    })
    writeCatalog(storeRoot, catalog)
    return { catalog: publicCatalog(catalog), pack: toSummary(draft, catalog.activePackageId) }
  }

  function publish(draftId) {
    const catalog = readCatalog(storeRoot)
    const draft = requireDraft(catalog, draftId)
    const source = join(storeRoot, draft.rootName)
    const inspected = inspectCharacterPackDirectory(source, { allowDraftExtras: true })
    if (!inspected.ok)
      throw new Error(inspected.error)
    const id = uniqueId(catalog, `pack-${draft.displayName}-${draft.version}`.replace(/[^a-zA-Z0-9._-]/gu, '-'))
    const target = join(packagesRoot, id)
    mkdirSync(packagesRoot, { recursive: true })
    cpSync(source, target, { recursive: true, errorOnExist: true, dereference: false })
    const publishedAt = new Date().toISOString()
    const published = {
      ...draft,
      id,
      status: 'published',
      publishedAt,
      rootName: join('packages', id),
    }
    catalog.drafts = catalog.drafts.filter(entry => entry.id !== draftId)
    catalog.packages.push(published)
    catalog.activePackageId = id
    writeCatalog(storeRoot, catalog)
    return { catalog: publicCatalog(catalog), pack: toSummary(published, catalog.activePackageId) }
  }

  function activate(packageId) {
    const catalog = readCatalog(storeRoot)
    const pack = requirePublished(catalog, packageId)
    if (pack.status !== 'published')
      throw new Error('已停用的角色包必须先恢复后才能启用。')
    catalog.activePackageId = pack.id
    writeCatalog(storeRoot, catalog)
    return { catalog: publicCatalog(catalog), pack: toSummary(pack, catalog.activePackageId) }
  }

  function disable(packageId) {
    const catalog = readCatalog(storeRoot)
    const pack = requirePublished(catalog, packageId)
    pack.status = 'disabled'
    if (catalog.activePackageId === pack.id)
      catalog.activePackageId = undefined
    writeCatalog(storeRoot, catalog)
    return { catalog: publicCatalog(catalog), pack: toSummary(pack, catalog.activePackageId) }
  }

  function restore(packageId) {
    const catalog = readCatalog(storeRoot)
    const pack = requirePublished(catalog, packageId)
    pack.status = 'published'
    catalog.activePackageId = pack.id
    writeCatalog(storeRoot, catalog)
    return { catalog: publicCatalog(catalog), pack: toSummary(pack, catalog.activePackageId) }
  }

  function preview(packageId, resourceId) {
    const catalog = readCatalog(storeRoot)
    const entry = findEntry(catalog, packageId)
    if (!entry)
      throw new Error('未找到角色包。')
    const resource = entry.resources.find(candidate => candidate.id === resourceId)
    if (!resource || resource.status !== 'ready')
      throw new Error('该角色资源不可预览。')
    return previewResource(storeRoot, entry, resource)
  }

  function getActivePresentation() {
    const catalog = readCatalog(storeRoot)
    const pack = catalog.packages.find(entry => entry.id === catalog.activePackageId && entry.status === 'published')
    if (!pack)
      return { revision: 'builtin:default' }
    const toAsset = (kind) => {
      const resource = pack.resources.find(candidate => candidate.kind === kind && candidate.status === 'ready')
      if (!resource)
        return undefined
      try {
        const preview = previewResource(storeRoot, pack, resource)
        return {
          resourceId: resource.id,
          name: resource.name,
          mediaType: preview.mediaType,
          dataUrl: preview.dataUrl,
        }
      }
      catch {
        return undefined
      }
    }
    return {
      revision: `${pack.id}:${pack.publishedAt ?? pack.importedAt}`,
      packageId: pack.id,
      sprite: toAsset('sprite'),
      background: toAsset('background'),
    }
  }

  return { getCatalog, importDirectory, addResource, publish, activate, disable, restore, preview, getActivePresentation }
}
