import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

import { afterEach, describe, expect, it } from 'vitest'

import {
  createClipboardImageAttachment,
  inspectDroppedAttachment,
  maxDocumentBytes,
  maxMultimodalBytes,
  stableLocalReference,
} from './attachment-policy.mjs'

const roots: string[] = []

function testRoot(): string {
  const root = mkdtempSync(join(process.cwd(), '.meguri-attachment-test-'))
  roots.push(root)
  return root
}

afterEach(() => {
  while (roots.length)
    rmSync(roots.pop()!, { recursive: true, force: true })
})

describe('dropped attachment policy', () => {
  it('classifies safe images and documents with Core-compatible stable references', () => {
    const root = testRoot()
    const image = join(root, 'capture.png')
    const document = join(root, 'notes.md')
    writeFileSync(image, Buffer.from([1, 2, 3]))
    writeFileSync(document, '# Notes')

    const inspectedImage = inspectDroppedAttachment(image)
    const inspectedDocument = inspectDroppedAttachment(document)

    expect(inspectedImage).toMatchObject({
      ok: true,
      attachment: {
        reference_id: stableLocalReference(image),
        name: 'capture.png',
        kind: 'image',
        content_access: 'multimodal_read',
      },
    })
    expect(inspectedDocument).toMatchObject({
      ok: true,
      attachment: {
        reference_id: stableLocalReference(document),
        kind: 'document',
        content_access: 'document_read',
      },
    })
  })

  it('rejects denied paths, secret-like files, folders, unsupported types, and oversized content', () => {
    const root = testRoot()
    const deniedRoot = join(root, 'node_modules')
    mkdirSync(deniedRoot)
    const denied = join(deniedRoot, 'notes.md')
    const secret = join(root, '.env')
    const executable = join(root, 'run.exe')
    const largeDocument = join(root, 'large.txt')
    const largeImage = join(root, 'large.png')
    writeFileSync(denied, '# denied')
    writeFileSync(secret, 'secret')
    writeFileSync(executable, 'MZ')
    writeFileSync(largeDocument, Buffer.alloc(maxDocumentBytes + 1))
    writeFileSync(largeImage, Buffer.alloc(maxMultimodalBytes + 1))

    expect(inspectDroppedAttachment(denied)).toMatchObject({ ok: false })
    expect(inspectDroppedAttachment(secret)).toMatchObject({ ok: false })
    expect(inspectDroppedAttachment(root)).toMatchObject({ ok: false })
    expect(inspectDroppedAttachment(executable)).toMatchObject({ ok: false })
    expect(inspectDroppedAttachment(largeDocument)).toMatchObject({ ok: false, error: expect.stringContaining('1 MB') })
    expect(inspectDroppedAttachment(largeImage)).toMatchObject({ ok: false, error: expect.stringContaining('5 MB') })
  })

  it('creates a bounded inline PNG only from explicit clipboard bytes', () => {
    const capturedAt = new Date('2026-08-19T08:09:10.000Z')
    const result = createClipboardImageAttachment(Buffer.from([137, 80, 78, 71]), capturedAt)

    expect(result).toMatchObject({
      ok: true,
      attachment: {
        type: 'inline_attachment',
        source: 'airi_inline',
        name: '截图-20260819T080910Z.png',
        content_access: 'multimodal_read',
        mime_type: 'image/png',
        data_url: 'data:image/png;base64,iVBORw==',
      },
    })
    expect(createClipboardImageAttachment(Buffer.alloc(0))).toMatchObject({ ok: false })
    expect(createClipboardImageAttachment(Buffer.alloc(maxMultimodalBytes + 1)))
      .toMatchObject({ ok: false, error: expect.stringContaining('5 MB') })
  })

  it('gives each PixPin capture a distinct reference even when the pixels are identical', () => {
    const bytes = Buffer.from([137, 80, 78, 71])
    const first = createClipboardImageAttachment(bytes, new Date('2026-08-19T08:09:10.000Z'), '00000000-0000-4000-8000-000000000001')
    const second = createClipboardImageAttachment(bytes, new Date('2026-08-19T08:09:10.000Z'), '00000000-0000-4000-8000-000000000002')

    expect(first).toHaveProperty('attachment')
    expect(second).toHaveProperty('attachment')
    if (!('attachment' in first) || !('attachment' in second))
      throw new Error('expected valid screenshot attachments')
    expect(first.attachment.reference_id).not.toBe(second.attachment.reference_id)
  })
})
