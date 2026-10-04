import { createHash } from 'node:crypto'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { afterEach, describe, expect, it } from 'vitest'

import { inspectCharacterPackDirectory, inspectCharacterPackDraftResource } from './character-pack-policy.mjs'

const roots: string[] = []

afterEach(() => {
  while (roots.length)
    rmSync(roots.pop()!, { recursive: true, force: true })
})

function createPack({ hash = true } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'meguri-character-pack-'))
  roots.push(root)
  const sprites = join(root, 'sprites')
  mkdirSync(sprites)
  const sprite = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3])
  writeFileSync(join(sprites, 'neutral.png'), sprite)
  writeFileSync(join(root, 'manifest.json'), JSON.stringify({
    schema_version: 1,
    release_id: 'meguri-fixture',
    build_id: 'v1',
    sprite: { directory: 'sprites', files: ['neutral.png'] },
    files: [{
      path: 'sprites/neutral.png',
      bytes: sprite.length,
      sha256: hash ? createHash('sha256').update(sprite).digest('hex') : '0'.repeat(64),
    }],
  }, null, 2))
  return root
}

describe('character pack policy', () => {
  it('accepts a declared, hashed PNG presentation directory with an optional UTF-8 BOM manifest', () => {
    const root = createPack()
    const manifest = join(root, 'manifest.json')
    writeFileSync(manifest, `\uFEFF${readFileSync(manifest, 'utf8')}`)
    const result = inspectCharacterPackDirectory(root)

    expect(result.ok).toBe(true)
    if (!('resources' in result))
      throw new Error('expected validated character pack resources')
    expect(result.resources).toEqual([expect.objectContaining({
      kind: 'sprite',
      mediaType: 'image/png',
      sourcePath: 'sprites/neutral.png',
    })])
  })

  it('rejects a manifest hash mismatch without accepting the pack', () => {
    expect(inspectCharacterPackDirectory(createPack({ hash: false }))).toEqual(expect.objectContaining({
      ok: false,
      error: expect.stringContaining('hash mismatch'),
    }))
  })

  it('rejects undeclared files without accepting the pack', () => {
    const root = createPack()
    writeFileSync(join(root, 'unexpected.exe'), 'not allowed')
    expect(inspectCharacterPackDirectory(root)).toEqual(expect.objectContaining({
      ok: false,
      error: expect.stringContaining('未声明文件'),
    }))
  })

  it('rejects duplicated declared paths and duplicated derived resource identifiers', () => {
    const root = createPack()
    const manifestPath = join(root, 'manifest.json')
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
    manifest.files.push({ ...manifest.files[0] })
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))
    expect(inspectCharacterPackDirectory(root)).toEqual(expect.objectContaining({
      ok: false,
      error: expect.stringContaining('duplicate file'),
    }))

    manifest.files.pop()
    manifest.sprite.files.push('neutral.png')
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))
    expect(inspectCharacterPackDirectory(root)).toEqual(expect.objectContaining({
      ok: false,
      error: expect.stringContaining('duplicate resource'),
    }))
  })

  it('rejects renamed non-images and declared images larger than the preview safety limit', () => {
    const invalidRoot = createPack()
    writeFileSync(join(invalidRoot, 'sprites', 'neutral.png'), 'not a PNG')
    expect(inspectCharacterPackDirectory(invalidRoot)).toEqual(expect.objectContaining({
      ok: false,
      error: expect.stringContaining('invalid .png signature'),
    }))

    const oversizedRoot = createPack()
    const spritePath = join(oversizedRoot, 'sprites', 'neutral.png')
    const oversized = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), Buffer.alloc(8 * 1024 * 1024)])
    writeFileSync(spritePath, oversized)
    const manifestPath = join(oversizedRoot, 'manifest.json')
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
    manifest.files[0].bytes = oversized.length
    manifest.files[0].sha256 = createHash('sha256').update(oversized).digest('hex')
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))
    expect(inspectCharacterPackDirectory(oversizedRoot)).toEqual(expect.objectContaining({
      ok: false,
      error: expect.stringContaining('exceeds'),
    }))
  })

  it('rejects manifest traversal before it can inspect a file outside the selected package', () => {
    const root = createPack()
    const manifestPath = join(root, 'manifest.json')
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
    manifest.files[0].path = '../outside.png'
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))

    expect(inspectCharacterPackDirectory(root)).toEqual(expect.objectContaining({
      ok: false,
      error: expect.stringContaining('unsafe'),
    }))
  })

  it('only accepts small regular PNG or JPG draft resources', () => {
    const root = createPack()
    const png = join(root, 'sprites', 'neutral.png')
    const executable = join(root, 'run.exe')
    writeFileSync(executable, 'not allowed')

    expect(inspectCharacterPackDraftResource(png)).toEqual(expect.objectContaining({ ok: true, mediaType: 'image/png' }))
    expect(inspectCharacterPackDraftResource(executable)).toEqual(expect.objectContaining({ ok: false }))
  })
})
