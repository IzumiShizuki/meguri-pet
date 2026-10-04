import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { afterEach, describe, expect, it } from 'vitest'

import { resolveGeneratedArtifact } from './artifact-policy.mjs'

const roots: string[] = []

afterEach(() => {
  while (roots.length)
    rmSync(roots.pop()!, { recursive: true, force: true })
})

describe('generated artifact policy', () => {
  it('accepts passive files only under an allowed root', () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-artifacts-'))
    roots.push(root)
    const output = join(root, 'nested')
    mkdirSync(output)
    const report = join(output, 'report.md')
    writeFileSync(report, '# Report')

    expect(resolveGeneratedArtifact(report, [root])).toBe(report)
  })

  it('rejects active extensions and files outside allowed roots', () => {
    const root = mkdtempSync(join(tmpdir(), 'meguri-artifacts-'))
    const outside = mkdtempSync(join(tmpdir(), 'meguri-outside-'))
    roots.push(root, outside)
    const executable = join(root, 'run.exe')
    const report = join(outside, 'report.md')
    writeFileSync(executable, 'not executable')
    writeFileSync(report, '# Outside')

    expect(resolveGeneratedArtifact(executable, [root])).toBeUndefined()
    expect(resolveGeneratedArtifact(report, [root])).toBeUndefined()
  })
})
