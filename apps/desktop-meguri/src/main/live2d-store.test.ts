import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { createLive2DStore, inspectLive2DDirectory } from './live2d-store.mjs'

const temporary: string[] = []
function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'meguri-live2d-'))
  temporary.push(root)
  const source = join(root, 'source')
  mkdirSync(source)
  writeFileSync(join(source, 'pet.moc3'), Buffer.concat([Buffer.from('MOC3'), Buffer.alloc(128)]))
  writeFileSync(join(source, 'texture.png'), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
  writeFileSync(join(source, 'pet.model3.json'), JSON.stringify({ Version: 3, FileReferences: { Moc: 'pet.moc3', Textures: ['texture.png'], Motions: { TapBody: [{ File: 'tap.motion3.json' }] }, Expressions: [{ Name: 'happy', File: 'happy.exp3.json' }] }, HitAreas: [{ Id: 'body', Name: 'Body' }] }))
  writeFileSync(join(source, 'tap.motion3.json'), '{}')
  writeFileSync(join(source, 'happy.exp3.json'), '{}')
  writeFileSync(join(source, 'meguri-interactions.json'), JSON.stringify({ name: 'Test pet', reactions: { Body: { motion: 'TapBody', expression: 'happy', speech: '你好。' } } }))
  return { root, source, store: createLive2DStore({ root: join(root, 'managed') }) }
}
afterEach(() => { for (const root of temporary.splice(0)) rmSync(root, { recursive: true, force: true }) })

describe('managed Live2D imports', () => {
  it('copies, activates, serves and removes a complete model while preserving source', async () => {
    const { source, store } = fixture()
    const imported = await store.importDirectory(source)
    expect(imported.models[0].name).toBe('Test pet')
    expect(imported.models[0].reactions.Body.speech).toBe('你好。')
    store.activate(imported.models[0].id)
    expect(store.active()?.modelUrl).toContain('meguri-live2d://model/')
    expect(store.asset(imported.models[0].id, 'pet.moc3').bytes.subarray(0, 4).toString()).toBe('MOC3')
    expect(() => store.asset(imported.models[0].id, '../catalog.json')).toThrow('相对路径')
    expect(() => store.remove('../source')).toThrow('未找到')
    expect(store.remove(imported.models[0].id).models).toEqual([])
    expect(readFileSync(join(source, 'pet.model3.json'), 'utf8')).toContain('FileReferences')
  })
  it('rejects external references, incomplete textures and executable resources', () => {
    const { source } = fixture()
    writeFileSync(join(source, 'pet.model3.json'), JSON.stringify({ Version: 3, FileReferences: { Moc: 'https://example.com/pet.moc3', Textures: ['texture.png'] } }))
    expect(() => inspectLive2DDirectory(source)).toThrow('相对路径')
    writeFileSync(join(source, 'pet.model3.json'), JSON.stringify({ Version: 3, FileReferences: { Moc: 'pet.moc3', Textures: ['missing.png'] } }))
    expect(() => inspectLive2DDirectory(source)).toThrow('缺少模型资源')
    writeFileSync(join(source, 'execute.js'), 'alert(1)')
    expect(() => inspectLive2DDirectory(source)).toThrow('资源类型')
  })
  it('imports a complete ZIP and rejects traversal without changing active resources', async () => {
    const { root, source, store } = fixture()
    const require = createRequire('D:/program/airi-meguri/packages/stage-ui-live2d/package.json')
    const JSZip = require('jszip')
    const zip = new JSZip()
    for (const name of readdirSync(source)) zip.file(`pet/${name}`, readFileSync(join(source, name)))
    const archive = join(root, 'model.zip')
    writeFileSync(archive, await zip.generateAsync({ type: 'nodebuffer' }))
    const imported = await store.importArchive(archive)
    store.activate(imported.models[0].id)
    expect(store.active()?.name).toBe('Test pet')
    const unsafe = new JSZip()
    unsafe.file('../escape.txt', 'must not escape')
    writeFileSync(archive, await unsafe.generateAsync({ type: 'nodebuffer' }))
    await expect(store.importArchive(archive)).rejects.toThrow('越界')
    expect(store.catalog().models).toHaveLength(1)
    expect(store.active()?.id).toBe(imported.models[0].id)
  })
})
