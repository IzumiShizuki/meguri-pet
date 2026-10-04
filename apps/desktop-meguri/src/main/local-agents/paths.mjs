/**
 * Local agent bridge: path resolution and small shared validators.
 *
 * Meguri connects to separately installed work agents on this machine. It never
 * installs, upgrades or authenticates them, and it never reads their model
 * credentials: the Codex app-server and the DeepSeek Harness own both.
 */

import { homedir } from 'node:os'
import { isAbsolute, join, normalize, resolve } from 'node:path'

export const WORK_PRESET_ID = 'meguri-work-v1'
export const FORWARD_TARGET_LIMIT = 1000
export const MAX_TASK_TEXT_LENGTH = 20000
export const MAX_TITLE_LENGTH = 120
export const MAX_TARGET_ID_LENGTH = 200
export const MAX_PATH_LENGTH = 4096
export const MAX_REPLY_LENGTH = 4000

/** Control characters never survive into a task card, a receipt or a log line. */
export function hasControlCharacters(value) {
  return typeof value === 'string' && /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/u.test(value)
}

export function textValue(value, max = MAX_PATH_LENGTH) {
  if (typeof value !== 'string')
    return ''
  const trimmed = value.trim()
  return [...trimmed].length > max ? '' : trimmed
}

/** Absolute local filesystem path. Rejects URLs, UNC shares and drive-relative paths. */
export function isAbsoluteLocalPath(value) {
  const path = textValue(value, MAX_PATH_LENGTH)
  if (!path || hasControlCharacters(path))
    return false
  if (/^[a-z][a-z0-9+.-]*:\/\//iu.test(path))
    return false
  if (path.startsWith('\\\\') || path.startsWith('//'))
    return false
  if (!isAbsolute(path))
    return false
  // A Windows-rooted POSIX path is not a local path on this platform.
  if (process.platform === 'win32')
    return /^[a-z]:[\\/]/iu.test(path)
  return path.startsWith('/')
}

export function canonicalPath(value) {
  if (!isAbsoluteLocalPath(value))
    return ''
  const normalizedPath = normalize(resolve(value))
  return process.platform === 'win32' ? normalizedPath.toLowerCase() : normalizedPath
}

export function samePath(left, right) {
  const canonicalLeft = canonicalPath(left)
  const canonicalRight = canonicalPath(right)
  return Boolean(canonicalLeft) && canonicalLeft === canonicalRight
}

export function defaultPaths(environment = process.env, home = homedir()) {
  const localAppData = environment.LOCALAPPDATA || join(home, 'AppData', 'Local')
  const appData = environment.APPDATA || join(home, 'AppData', 'Roaming')
  return {
    codexHome: environment.CODEX_HOME || join(home, '.codex'),
    codexExecutable: environment.PET_CODEX_EXECUTABLE || '',
    codexInstallRoot: join(localAppData, 'OpenAI', 'Codex', 'bin'),
    dshHome: environment.DSH_HOME || join(home, '.dsh'),
    dshCliPath: environment.PET_DSH_CLI || '',
    harnessServiceHome: environment.PET_HARNESS_HOME || join(appData, 'Meguri', 'harness-service'),
    configPath: environment.MEGURI_LOCAL_AGENT_CONFIG_FILE || join(appData, 'Meguri', 'local-agents.json'),
    receiptDatabasePath:
      environment.MEGURI_LOCAL_AGENT_RECEIPT_FILE || join(appData, 'Meguri', 'local-agent-receipts.sqlite'),
  }
}
