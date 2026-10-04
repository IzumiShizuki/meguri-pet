import { existsSync, realpathSync, statSync } from 'node:fs'
import { extname, isAbsolute, relative, resolve } from 'node:path'

export const passiveArtifactExtensions = new Set([
  '.md', '.pdf', '.jpg', '.jpeg', '.png', '.gif', '.webp',
  '.txt', '.csv', '.json', '.html',
])

/** Resolves an existing passive artifact only when it stays under an allowed root. */
export function resolveGeneratedArtifact(pathValue, roots) {
  if (typeof pathValue !== 'string' || !pathValue.trim())
    return undefined
  let candidate
  try {
    candidate = realpathSync(resolve(pathValue))
  }
  catch {
    return undefined
  }
  if (!passiveArtifactExtensions.has(extname(candidate).toLowerCase()))
    return undefined
  const insideAllowedRoot = roots.some(root => {
    try {
      const remainder = relative(realpathSync(root), candidate)
      return remainder === '' || (!remainder.startsWith('..') && !isAbsolute(remainder))
    }
    catch {
      return false
    }
  })
  if (!insideAllowedRoot)
    return undefined
  try {
    return existsSync(candidate) && statSync(candidate).isFile() ? candidate : undefined
  }
  catch {
    return undefined
  }
}
