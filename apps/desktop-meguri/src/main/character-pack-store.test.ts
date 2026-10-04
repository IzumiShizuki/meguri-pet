import { createHash } from 'node:crypto'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { afterEach, describe, expect, it } from 'vitest'

import { createCharacterPackStore } from './character-pack-store.mjs'

const roots: string[] = []

afterEach(() => {
  while (roots.length)
    rmSync(roots.pop()!, { recursive: true, force: true })
})

function createPack(name = 'meguri-fixture') {
  const root = mkdtempSync(join(tmpdir(), 'meguri-character-package-'))
  roots.push(root)
  const sprites = join(root, 'sprites')
  mkdirSync(sprites)
  const sprite = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 4, 5, 6])
  writeFileSync(join(sprites, 'neutral.png'), sprite)
  writeFileSync(join(root, 'manifest.json'), JSON.stringify({
    schema_version: 1,
    release_id: name,
    build_id: 'v1',
    sprite: { directory: 'sprites', files: ['neutral.png'] },
    files: [{
      path: 'sprites/neutral.png',
      bytes: sprite.length,
      sha256: createHash('sha256').update(sprite).digest('hex'),
    }],
  }, null, 2))
  return { root, sprite }
}

describe('character pack store', () => {
  it('imports a draft, publishes it atomically, and returns preview bytes without source paths', () => {
    const storeRoot = mkdtempSync(join(tmpdir(), 'meguri-character-store-'))
    roots.push(storeRoot)
    const fixture = createPack()
    const store = createCharacterPackStore({ root: storeRoot })

    const draft = store.importDirectory(fixture.root)
    expect(draft.catalog.drafts).toHaveLength(1)
    expect(JSON.stringify(draft.catalog)).not.toContain(fixture.root)

    const published = store.publish(draft.pack.id)
    expect(published.catalog.drafts).toHaveLength(0)
    expect(published.catalog.activePackageId).toBe(published.pack.id)

    const resource = published.pack.resources[0]
    const preview = store.preview(published.pack.id, resource.id)
    expect(preview.dataUrl).toMatch(/^data:image\/png;base64,/u)

    const presentation = store.getActivePresentation()
    expect(presentation).toEqual(expect.objectContaining({
      packageId: published.pack.id,
      revision: expect.stringContaining(published.pack.id),
      sprite: expect.objectContaining({ resourceId: resource.id, dataUrl: expect.stringMatching(/^data:image\/png;base64,/u) }),
    }))
    expect(JSON.stringify(presentation)).not.toContain(fixture.root)
  })

  it('keeps the active package unchanged when a later import fails', () => {
    const storeRoot = mkdtempSync(join(tmpdir(), 'meguri-character-store-'))
    roots.push(storeRoot)
    const store = createCharacterPackStore({ root: storeRoot })
    const first = createPack('meguri-first')
    const published = store.publish(store.importDirectory(first.root).pack.id)

    const invalid = createPack('meguri-invalid')
    writeFileSync(join(invalid.root, 'unexpected.txt'), 'bad')
    expect(() => store.importDirectory(invalid.root)).toThrow('未声明文件')
    expect(store.getCatalog().activePackageId).toBe(published.pack.id)
  })

  it('allows a supported draft resource to be published and restored after disable', () => {
    const storeRoot = mkdtempSync(join(tmpdir(), 'meguri-character-store-'))
    roots.push(storeRoot)
    const fixture = createPack()
    const extraRoot = mkdtempSync(join(tmpdir(), 'meguri-character-resource-'))
    roots.push(extraRoot)
    const extra = join(extraRoot, 'extra.jpg')
    writeFileSync(extra, Buffer.from([255, 216, 255, 1, 2, 3]))
    const store = createCharacterPackStore({ root: storeRoot })

    const draft = store.importDirectory(fixture.root)
    store.addResource(draft.pack.id, extra)
    const published = store.publish(draft.pack.id)
    expect(published.pack.resources).toHaveLength(2)

    store.disable(published.pack.id)
    expect(store.getCatalog().activePackageId).toBeUndefined()
    store.restore(published.pack.id)
    expect(store.getCatalog().activePackageId).toBe(published.pack.id)
  })
})
