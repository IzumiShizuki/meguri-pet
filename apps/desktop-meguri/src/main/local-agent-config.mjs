/**
 * Local agent settings.
 *
 * Only forwarding, optional host startup and the Harness home override live
 * here. No credential, model name or task text is stored in this file.
 */

import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { randomUUID } from 'node:crypto'

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

function patchError(patch) {
  if (!patch || typeof patch !== 'object' || Array.isArray(patch))
    return '配置格式无效，请重新打开工作 Agent 设置。'
  for (const key of ['enabled', 'harnessAutoStart']) {
    if (patch[key] !== undefined && typeof patch[key] !== 'boolean')
      return '任务转交和宿主启动选项必须为开关值。'
  }
  if (patch.dshHome !== undefined
    && (typeof patch.dshHome !== 'string'
      || (patch.dshHome.trim() !== '' && !isAbsoluteLocalPath(patch.dshHome))))
    return 'Harness 数据目录必须为本机绝对路径，或留空使用默认目录。'
  return ''
}

function atomicWrite(path, value, options) {
  const makeDirectory = options.mkdir ?? mkdirSync
  const writeFile = options.writeFile ?? writeFileSync
  const rename = options.rename ?? renameSync
  const remove = options.rm ?? rmSync
  makeDirectory(dirname(path), { recursive: true })
  const temporary = `${path}.${process.pid}.${randomUUID()}.tmp`
  try {
    writeFile(temporary, value, { encoding: 'utf8', mode: 0o600, flag: 'wx' })
    rename(temporary, path)
  }
  finally {
    // Cleanup must not turn a successful atomic commit into a reported failure.
    try { remove(temporary, { force: true }) } catch { /* only our temporary file */ }
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
  const error = patchError(patch)
  if (error)
    return { ok: false, code: 'invalid_settings', error, config: current }
  const next = normalizeLocalAgentConfig({ ...current, ...(patch ?? {}) })
  try {
    atomicWrite(path, `${JSON.stringify(next, null, 2)}\n`, options)
    return { ok: true, config: next }
  }
  catch {
    return { ok: false, code: 'write_failed', error: '配置文件未能写入，原配置保持不变。请检查配置目录是否可写及磁盘空间。', config: current }
  }
}
