import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { collectRecentArtifacts } from './artifact-feed.mjs'

function createTree() {
  const base = mkdtempSync(join(tmpdir(), 'meguri-artifact-feed-'))
  const reports = join(base, 'reports')
  const output = join(base, 'output')
  mkdirSync(join(reports, 'daily'), { recursive: true })
  mkdirSync(output, { recursive: true })
  return { base, reports, output }
}

function writeFileAt(path, epochMs, content = 'x') {
  writeFileSync(path, content)
  utimesSync(path, new Date(epochMs), new Date(epochMs))
}

test('returns only allow-listed files modified at or after the cursor', () => {
  const { reports, output } = createTree()
  const now = 1_800_000_000_000
  writeFileAt(join(reports, 'daily', 'bilibili-2026-07-25.md'), now - 1_000)
  writeFileAt(join(reports, 'daily', 'billing-2026-07-20.jpg'), now - 500)
  writeFileAt(join(reports, 'old-report.md'), now - 3_600_000)
  writeFileAt(join(reports, 'daily', 'report.exe'), now - 100)
  writeFileAt(join(output, 'summary.txt'), now - 200)

  const feed = collectRecentArtifacts(
    [{ root: reports, hrefPrefix: '/reports' }, { root: output, hrefPrefix: '/output' }],
    now - 2_000,
    { now },
  )

  assert.equal(feed.now, now)
  assert.deepEqual(feed.artifacts.map(item => item.name), [
    'bilibili-2026-07-25.md',
    'billing-2026-07-20.jpg',
    'summary.txt',
  ])
  assert.equal(feed.artifacts[0].href, '/reports/daily/bilibili-2026-07-25.md')
  assert.equal(feed.artifacts[2].href, '/output/summary.txt')
  assert.ok(feed.artifacts.every(item => Number.isFinite(item.modified_ms) && item.size_bytes >= 0))
})

test('cursor is inclusive so equal timestamps are not silently dropped', () => {
  const { reports } = createTree()
  const now = 1_800_000_000_000
  writeFileAt(join(reports, 'same-instant.md'), now - 1_000)

  const feed = collectRecentArtifacts([{ root: reports, hrefPrefix: '/reports' }], now - 1_000, { now })
  assert.deepEqual(feed.artifacts.map(item => item.name), ['same-instant.md'])
})

test('excludes the latest.json notice, dotfiles, and excluded directories', () => {
  const { reports } = createTree()
  const now = 1_800_000_000_000
  writeFileAt(join(reports, 'daily', 'latest.json'), now - 100)
  writeFileAt(join(reports, '.hidden.md'), now - 100)
  mkdirSync(join(reports, 'node_modules'), { recursive: true })
  writeFileAt(join(reports, 'node_modules', 'stray.md'), now - 100)
  writeFileAt(join(reports, 'daily', 'kept.md'), now - 100)

  const feed = collectRecentArtifacts([{ root: reports, hrefPrefix: '/reports' }], 0, { now })
  assert.deepEqual(feed.artifacts.map(item => item.name), ['kept.md'])
})

test('a stale cursor cannot replay history beyond the age window', () => {
  const { reports } = createTree()
  const now = 1_800_000_000_000
  writeFileAt(join(reports, 'ancient.md'), now - 72 * 60 * 60 * 1000)
  writeFileAt(join(reports, 'recent.md'), now - 60 * 60 * 1000)

  const feed = collectRecentArtifacts([{ root: reports, hrefPrefix: '/reports' }], 0, { now })
  assert.deepEqual(feed.artifacts.map(item => item.name), ['recent.md'])
})

test('bounds the result to the newest entries in chronological order', () => {
  const { reports } = createTree()
  const now = 1_800_000_000_000
  for (let index = 0; index < 7; index += 1)
    writeFileAt(join(reports, `report-${index}.md`), now - 7_000 + index * 1_000)

  const feed = collectRecentArtifacts([{ root: reports, hrefPrefix: '/reports' }], 0, { now, limit: 3 })
  assert.deepEqual(feed.artifacts.map(item => item.name), ['report-4.md', 'report-5.md', 'report-6.md'])
})

test('symbolic links are not followed as artifacts', { skip: process.platform === 'win32' }, () => {
  const { base, reports } = createTree()
  const now = 1_800_000_000_000
  const secret = join(base, 'secret.md')
  writeFileAt(secret, now - 100)
  try { symlinkSync(secret, join(reports, 'linked.md')) } catch { return }

  const feed = collectRecentArtifacts([{ root: reports, hrefPrefix: '/reports' }], 0, { now })
  assert.deepEqual(feed.artifacts, [])
})

test('missing roots and non-numeric cursors fail closed', () => {
  const { reports } = createTree()
  const now = 1_800_000_000_000
  writeFileAt(join(reports, 'kept.md'), now - 100)

  const missing = collectRecentArtifacts([{ root: join(reports, 'absent'), hrefPrefix: '/reports' }], 0, { now })
  assert.deepEqual(missing.artifacts, [])

  const noCursor = collectRecentArtifacts([{ root: reports, hrefPrefix: '/reports' }], Number.NaN, { now })
  assert.deepEqual(noCursor.artifacts, [], 'without a cursor the feed only reports the server clock')
  assert.equal(noCursor.now, now)
})

test('href segments are URI-encoded per path segment', () => {
  const { reports } = createTree()
  const now = 1_800_000_000_000
  mkdirSync(join(reports, '日报 目录'), { recursive: true })
  writeFileAt(join(reports, '日报 目录', '账单 2026.md'), now - 100)

  const feed = collectRecentArtifacts([{ root: reports, hrefPrefix: '/reports' }], 0, { now })
  assert.equal(feed.artifacts.length, 1)
  assert.equal(feed.artifacts[0].href, `/reports/${encodeURIComponent('日报 目录')}/${encodeURIComponent('账单 2026.md')}`)
})
