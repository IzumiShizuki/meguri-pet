import { execFile, spawn } from 'node:child_process'
import { existsSync } from 'node:fs'

const defaultPaths = Object.freeze({
  everythingPath: 'D:\\Program Files\\Everything\\everything.exe',
  esPath: 'D:\\Program Files\\Everything\\es.exe',
  databasePath: 'D:\\Program Files\\Everything\\Everything.db',
})

function delayFor(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function probeEverythingIpc(esPath) {
  return new Promise((resolve) => {
    execFile(
      esPath,
      ['-n', '1', '-timeout', '450', '__meguri_everything_ipc_probe__'],
      { encoding: 'utf8', windowsHide: true, timeout: 1200 },
      error => resolve(!error),
    )
  })
}

/** Ensures the per-user Everything IPC endpoint exists without modifying the
 * user's service or index. The fallback reuses the installed database in
 * read-only mode and remains detached from Meguri's lifecycle.
 */
export async function ensureEverythingIpc(options = {}) {
  const platform = options.platform ?? process.platform
  const paths = { ...defaultPaths, ...options.paths }
  const exists = options.exists ?? existsSync
  const probe = options.probe ?? (() => probeEverythingIpc(paths.esPath))
  const launch = options.launch ?? ((file, args, spawnOptions) => spawn(file, args, spawnOptions))
  const delay = options.delay ?? delayFor
  const attempts = options.attempts ?? 8

  if (platform !== 'win32')
    return { ready: false, started: false, detail: 'Everything 仅在 Windows 上可用' }

  if (!exists(paths.everythingPath) || !exists(paths.esPath) || !exists(paths.databasePath))
    return { ready: false, started: false, detail: 'Everything 安装不完整，无法启动本地搜索 IPC' }

  if (await probe())
    return { ready: true, started: false, detail: 'Everything IPC 已就绪' }

  const child = launch(paths.everythingPath, [
    '-startup',
    '-first-instance',
    '-noapp-data',
    '-db', paths.databasePath,
    '-read-only',
  ], {
    detached: true,
    shell: false,
    stdio: 'ignore',
    windowsHide: true,
  })
  child.once?.('error', () => {})
  child.unref?.()

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    await delay(180)
    if (await probe())
      return { ready: true, started: true, detail: 'Everything IPC 已由 Meguri 后台唤醒' }
  }

  return { ready: false, started: true, detail: 'Everything 已启动，但 IPC 尚未就绪' }
}
