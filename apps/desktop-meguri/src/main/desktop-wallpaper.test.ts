import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { loadDesktopWallpaper } from './desktop-wallpaper.mjs'

const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9J7KsAAAAASUVORK5CYII=', 'base64')
function file(name: string, bytes: Buffer) {
  const root = mkdtempSync(join(tmpdir(), 'meguri-wallpaper-'))
  roots.push(root)
  const path = join(root, name)
  writeFileSync(path, bytes)
  return path
}

describe('wallpaper import boundary', () => {
  it('returns an image data URL without exposing the selected local path', () => {
    const result = loadDesktopWallpaper(file('wallpaper.png', png), () => ({ width: 1, height: 1 }))
    expect(result).toEqual({ ok: true, dataUrl: `data:image/png;base64,${png.toString('base64')}` })
  })
  it('rejects disguised bytes, remote paths, oversized files and oversized decoded images', () => {
    expect(loadDesktopWallpaper(file('secret.png', Buffer.from('not an image')), () => ({ width: 1, height: 1 })).ok).toBe(false)
    expect(loadDesktopWallpaper('https://example.com/a.png', () => ({ width: 1, height: 1 })).ok).toBe(false)
    expect(loadDesktopWallpaper(file('large.png', Buffer.alloc(3 * 1024 * 1024 + 1)), () => ({ width: 1, height: 1 })).ok).toBe(false)
    expect(loadDesktopWallpaper(file('huge.png', png), () => ({ width: 6000, height: 1 })).ok).toBe(false)
  })
})
