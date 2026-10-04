import type { ArtifactItem } from '../stores/artifacts'

export interface FeedArtifact {
  name: string
  href: string
  local_path: string
  modified_ms: number
  size_bytes: number
}

export function artifactKey(artifact: FeedArtifact): string {
  return `${artifact.local_path}:${artifact.modified_ms}`
}

export function collectNewArtifacts(
  artifacts: FeedArtifact[],
  seen: Set<string>,
  gatewayOrigin: string,
): ArtifactItem[] {
  const added: ArtifactItem[] = []
  for (const artifact of artifacts) {
    const key = artifactKey(artifact)
    if (seen.has(key))
      continue
    seen.add(key)
    added.push({
      key,
      name: artifact.name,
      href: new URL(artifact.href, gatewayOrigin).href,
      localPath: artifact.local_path,
      modifiedMs: artifact.modified_ms,
      sizeBytes: artifact.size_bytes,
    })
  }
  return added
}

export function appendArtifactBubbles(current: ArtifactItem[], added: ArtifactItem[]): ArtifactItem[] {
  return [...current, ...added].slice(-3)
}
