/**
 * Local agent settings.
 *
 * Only two facts live here: whether task forwarding is enabled at all, and
 * whether Meguri may start the Harness web host for the user. No credential,
 * model name, path or task text is ever stored in this file.
 */

import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

import { isAbsoluteLocalPath, textValue } from './local-agents/paths.mjs'

export const configVersion = 1

export function defaultLocalAgentConfig() {
  return {
    version: configVersion,
    enabled: false,
    harnessAutoStart: false,
    dshHome: '',
  }
}

export function normalizeLocalAgentConfig(value) {
  const source = value && typeof value === 'object' ? value : {}
  const dshHome = textValue(source.dshHome, 4096)
  return {
    version: configVersion,
    enabled: source.enabled === true,
    // Starting an external host process is an explicit, separate opt-in.
    harnessAutoStart: source.enabled === true && source.harnessAutoStart === true,
    // An empty override means "use the default Harness home for this user".
    dshHome: isAbsoluteLocalPath(dshHome) ? dshHome : '',
  }
}

function atomicWrite(path, value) {
  mkdirSync(dirname(path), { recursive: true })
  const temporary = `${path}.${process.pid}.tmp`
  try {
    writeFileSync(temporary, value, { encoding: 'utf8', mode: 0o600 })
    renameSync(temporary, path)
  }
  finally {
    rmSync(temporary, { force: true })
  }
}

export function readLocalAgentConfig(path, options = {}) {
  const exists = options.exists ?? existsSync
  const readFile = options.readFile ?? ((file) => readFileSync(file, 'utf8'))
  if (!exists(path))
    return defaultLocalAgentConfig()
  try {
    return normalizeLocalAgentConfig(JSON.parse(readFile(path)))
  }
  catch {
    return defaultLocalAgentConfig()
  }
}

export function saveLocalAgentConfig(path, patch, options = {}) {
  const current = readLocalAgentConfig(path, options)
  const next = normalizeLocalAgentConfig({ ...current, ...(patch ?? {}) })
  try {
    atomicWrite(path, `${JSON.stringify(next, null, 2)}\n`)
    return { ok: true, config: next }
  }
  catch {
    return { ok: false, error: '无法保存本地 Agent 配置，请检查配置目录权限。', config: current }
  }
}
