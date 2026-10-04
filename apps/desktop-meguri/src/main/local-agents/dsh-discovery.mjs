/**
 * Discovers the local DeepSeek Harness CLI.
 *
 * Meguri never downloads, updates or installs Harness. It only looks for an
 * already installed CLI, in the same places the Harness desktop app and the
 * documented npm install put it.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

import { isAbsoluteLocalPath, textValue } from './paths.mjs'

const DSH_ENTRY = join('node_modules', '@deepseek-ai', 'dsh', 'lib', 'bin.js')

function candidateCliRoots(environment, home) {
  const appData = environment.APPDATA || join(home, 'AppData', 'Roaming')
  const localAppData = environment.LOCALAPPDATA || join(home, 'AppData', 'Local')
  const roots = [
    join(home, '.dsh', 'profiles'),
    join(home, '.dsh-community', 'profiles'),
    join(appData, 'npm'),
    join(localAppData, 'Programs', 'DeepSeek Harness Desktop', 'resources', 'app.asar.unpacked'),
  ]
  return roots
}

/** Returns every installed Harness CLI entry point, newest first. */
export function discoverDshCliCandidates(options = {}) {
  const environment = options.environment ?? process.env
  const home = options.home ?? homedir()
  const exists = options.exists ?? existsSync
  const statFile = options.statFile ?? ((path) => statSync(path))

  const explicit = textValue(environment.PET_DSH_CLI, 4096)
  const candidates = []
  if (explicit && isAbsoluteLocalPath(explicit) && exists(explicit))
    candidates.push({ path: explicit, changed: Number.MAX_SAFE_INTEGER, source: 'explicit' })

  for (const root of candidateCliRoots(environment, home)) {
    const entry = join(root, DSH_ENTRY)
    try {
      const info = statFile(entry)
      if (info.isFile())
        candidates.push({ path: entry, changed: info.mtimeMs ?? 0, source: root })
    }
    catch {
      // Most roots do not contain an install; that is a normal state.
    }
  }

  // Explicit configuration always wins; otherwise the most recently updated install.
  return candidates
    .sort((left, right) => right.changed - left.changed)
    .map(candidate => candidate.path)
    .filter((path, index, all) => all.indexOf(path) === index)
}

export function findDshCli(options = {}) {
  return discoverDshCliCandidates(options)[0] ?? ''
}

/** Reads the Harness settings file just to report whether the user already signed in. */
export function harnessCredentialState(options = {}) {
  const environment = options.environment ?? process.env
  const home = options.home ?? homedir()
  const exists = options.exists ?? existsSync
  const readFile = options.readFile ?? ((path) => readFileSync(path, { encoding: 'utf8' }))
  const roots = [
    environment.DSH_HOME || join(home, '.dsh'),
    join(home, '.dsh-community'),
  ]
  for (const root of roots) {
    const credentials = join(root, '.credentials.yaml')
    if (!exists(credentials))
      continue
    try {
      const value = readFile(credentials)
      // Only the presence of a DeepSeek key ref is reported; the value never leaves this module.
      return /DEEPSEEK_API_KEY:/u.test(value) ? 'configured' : 'missing'
    }
    catch {
      return 'unavailable'
    }
  }
  return 'missing'
}

export function codexHomeState(options = {}) {
  const environment = options.environment ?? process.env
  const home = options.home ?? homedir()
  const exists = options.exists ?? existsSync
  const readdir = options.readdir ?? (path => readdirSync(path))
  const codexHome = environment.CODEX_HOME || join(home, '.codex')
  if (!exists(codexHome))
    return { home: codexHome, signedIn: false }
  try {
    const entries = readdir(codexHome)
    return { home: codexHome, signedIn: entries.includes('auth.json') }
  }
  catch {
    return { home: codexHome, signedIn: false }
  }
}
