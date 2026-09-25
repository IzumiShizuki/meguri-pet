import { readdirSync, statSync } from 'node:fs'
import { extname, join } from 'node:path'

// Passive document types only. Executable or script-like extensions must never
// be added here: feed entries become one-click "open with default app" targets.
export const artifactFeedExtensions = new Set([
  '.md', '.pdf', '.jpg', '.jpeg', '.png', '.gif', '.webp',
  '.txt', '.csv', '.json', '.html',
])

// The desktop notice channel rewrites this file on every publication; the
// linked Markdown report is the artifact worth announcing, not the notice.
const excludedNames = new Set(['latest.json'])
const excludedDirectories = new Set(['node_modules', '__pycache__', '.git'])

export const artifactFeedDefaults = {
  maxDepth: 4,
  maxEntries: 4000,
  limit: 20,
  maxAgeMs: 48 * 60 * 60 * 1000,
}

/**
 * Collects generated files below the allowed roots that were modified at or
 * after the client cursor. Roots are `{ root, hrefPrefix }` records; the cursor
 * contract is inclusive (`modified_ms >= after`), so clients de-duplicate by
 * `local_path + modified_ms` and advance their cursor to the newest entry.
 */
export function collectRecentArtifacts(roots, after, options = {}) {
  const { maxDepth, maxEntries, limit, maxAgeMs } = { ...artifactFeedDefaults, ...options }
  const now = Number.isFinite(options.now) ? options.now : Date.now()
  const cursor = Number.isFinite(after) ? after : Number.POSITIVE_INFINITY
  // A stale cursor (app closed for days) must not replay weeks of history.
  const effectiveAfter = Math.max(cursor, now - maxAgeMs)
  const found = []
  let visited = 0

  const walk = (directory, hrefSegments, depth) => {
    if (depth > maxDepth || visited >= maxEntries)
      return
    let entries
    try { entries = readdirSync(directory, { withFileTypes: true }) } catch { return }
    for (const entry of entries) {
      if (visited >= maxEntries)
        return
      visited += 1
      const name = entry.name
      if (name.startsWith('.'))
        continue
      if (entry.isDirectory()) {
        if (!excludedDirectories.has(name))
          walk(join(directory, name), [...hrefSegments, name], depth + 1)
        continue
      }
      if (!entry.isFile())
        continue // Symbolic links stay out of the feed by design.
      if (excludedNames.has(name) || !artifactFeedExtensions.has(extname(name).toLowerCase()))
        continue
      const filePath = join(directory, name)
      let stats
      try { stats = statSync(filePath) } catch { continue }
      if (!stats.isFile() || stats.mtimeMs < effectiveAfter)
        continue
      found.push({
        name,
        href: [...hrefSegments, name].map(encodeURIComponent).join('/'),
        local_path: filePath,
        modified_ms: Math.round(stats.mtimeMs),
        size_bytes: stats.size,
      })
    }
  }

  for (const { root, hrefPrefix } of roots) {
    const prefix = String(hrefPrefix ?? '').replace(/\/+$/, '')
    const before = found.length
    walk(root, [], 0)
    for (let index = before; index < found.length; index += 1)
      found[index].href = `${prefix}/${found[index].href}`
  }

  found.sort((left, right) => left.modified_ms - right.modified_ms || (left.local_path < right.local_path ? -1 : 1))
  return { now, artifacts: found.slice(Math.max(0, found.length - limit)) }
}
