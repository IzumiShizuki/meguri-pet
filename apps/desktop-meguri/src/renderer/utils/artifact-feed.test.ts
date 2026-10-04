import { describe, expect, it } from 'vitest'

import { appendArtifactBubbles, artifactKey, collectNewArtifacts, type FeedArtifact } from './artifact-feed'

function artifact(index: number): FeedArtifact {
  return {
    name: `report-${index}.md`,
    href: `/artifacts/report-${index}.md`,
    local_path: `D:/program/meguri-pet/output/report-${index}.md`,
    modified_ms: index,
    size_bytes: index * 10,
  }
}

describe('artifact feed presentation', () => {
  it('deduplicates artifacts by path and modification timestamp', () => {
    const seen = new Set<string>([artifactKey(artifact(1))])
    const added = collectNewArtifacts([artifact(1), artifact(2)], seen, 'http://127.0.0.1:15173')

    expect(added).toHaveLength(1)
    expect(added[0]).toMatchObject({ name: 'report-2.md', modifiedMs: 2 })
    expect(added[0]?.href).toBe('http://127.0.0.1:15173/artifacts/report-2.md')
  })

  it('keeps only the three newest bubbles', () => {
    const items = collectNewArtifacts([artifact(1), artifact(2), artifact(3), artifact(4)], new Set(), 'http://127.0.0.1:15173')

    expect(appendArtifactBubbles([], items).map(item => item.name)).toEqual([
      'report-2.md',
      'report-3.md',
      'report-4.md',
    ])
  })
})
