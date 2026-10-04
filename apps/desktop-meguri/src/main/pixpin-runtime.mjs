import { lstatSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { basename, isAbsolute, parse, resolve } from 'node:path'

const captureScript = 'pixpin.screenShot(ShotAction.Copy)'
const powershellCommand = 'powershell.exe'

const installDiscoveryScript = String.raw`
$ErrorActionPreference = 'SilentlyContinue'
$roots = @(
  'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
  'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
  'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
$paths = @()
foreach ($root in $roots) {
  foreach ($entry in (Get-ItemProperty -Path $root -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -match '^PixPin(\s|$)' })) {
    if ($entry.InstallLocation) { $paths += (Join-Path $entry.InstallLocation 'PixPin.exe') }
    if ($entry.DisplayIcon) { $paths += (($entry.DisplayIcon -replace ',\d+$','').Trim('"')) }
  }
}
[Console]::WriteLine((ConvertTo-Json -Compress -InputObject @($paths | Select-Object -Unique)))
`

const processDiscoveryScript = String.raw`
$ErrorActionPreference = 'SilentlyContinue'
$items = @(Get-CimInstance -ClassName Win32_Process -Filter "Name = 'PixPin.exe'" | ForEach-Object {
  [pscustomobject]@{ pid = [int]$_.ProcessId; executablePath = [string]$_.ExecutablePath }
})
[Console]::WriteLine((ConvertTo-Json -Compress -InputObject @($items)))
`

const clipboardWatcherScript = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class MeguriClipboardNative {
  [DllImport("user32.dll")] public static extern uint GetClipboardSequenceNumber();
  [DllImport("user32.dll")] public static extern IntPtr GetClipboardOwner();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
}
'@
function Write-ClipboardState([string]$kind, [uint32]$sequence) {
  $owner = [MeguriClipboardNative]::GetClipboardOwner()
  $ownerPid = [uint32]0
  if ($owner -ne [IntPtr]::Zero) { [void][MeguriClipboardNative]::GetWindowThreadProcessId($owner, [ref]$ownerPid) }
  $ownerPath = ''
  if ($ownerPid -gt 0) {
    try { $ownerPath = (Get-Process -Id ([int]$ownerPid) -ErrorAction Stop).Path } catch { }
  }
  [Console]::Out.WriteLine((ConvertTo-Json -Compress -InputObject @{ type = $kind; sequence = $sequence; ownerPid = $ownerPid; ownerPath = $ownerPath }))
}
$last = [MeguriClipboardNative]::GetClipboardSequenceNumber()
Write-ClipboardState 'ready' $last
while ($true) {
  Start-Sleep -Milliseconds 35
  $current = [MeguriClipboardNative]::GetClipboardSequenceNumber()
  if ($current -ne $last) {
    $last = $current
    Write-ClipboardState 'change' $current
  }
}
`

function powershellPath(env = process.env) {
  const root = env.SystemRoot || env.WINDIR || 'C:\\Windows'
  return `${root}\\System32\\WindowsPowerShell\\v1.0\\powershell.exe`
}

function runPowerShellJson(script, { spawnImpl = spawn, env = process.env, timeoutMs = 8_000 } = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawnImpl(powershellPath(env), ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', script], {
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let stdout = ''
    let stderr = ''
    let settled = false
    const timeout = setTimeout(() => {
      child.kill()
      finish(new Error('查询 PixPin 进程超时。'))
    }, timeoutMs)
    const finish = (error, value) => {
      if (settled)
        return
      settled = true
      clearTimeout(timeout)
      if (error)
        rejectPromise(error)
      else {
        try {
          resolvePromise(JSON.parse(value.trim() || '[]'))
        }
        catch {
          rejectPromise(new Error(`Windows PowerShell 返回无效数据。${stderr.trim() ? ` ${stderr.trim()}` : ''}`))
        }
      }
    }
    child.stdout?.setEncoding('utf8')
    child.stderr?.setEncoding('utf8')
    child.stdout?.on('data', chunk => { stdout += chunk })
    child.stderr?.on('data', chunk => { stderr += chunk })
    child.once('error', error => finish(new Error(`无法启动 Windows PowerShell：${error.message}`)))
    child.once('exit', code => {
      if (code !== 0)
        finish(new Error(`查询 PixPin 失败。${stderr.trim()}`))
      else
        finish(undefined, stdout)
    })
  })
}

export function validatePixPinExecutable(value, { lstat = lstatSync } = {}) {
  if (typeof value !== 'string' || !value.trim() || !isAbsolute(value.trim()))
    return { ok: false, error: 'PixPin 路径必须是本机绝对路径。' }
  const executable = resolve(value.trim())
  const root = parse(executable).root
  if (root.startsWith('\\\\') || root.startsWith('//'))
    return { ok: false, error: 'PixPin.exe 必须位于本机磁盘，不能从网络共享运行。' }
  if (basename(executable).toLowerCase() !== 'pixpin.exe')
    return { ok: false, error: '请选择 PixPin.exe。' }
  try {
    const attributes = lstat(executable)
    if (!attributes.isFile() || attributes.isSymbolicLink())
      return { ok: false, error: 'PixPin.exe 必须是本机磁盘上的普通文件。' }
  }
  catch {
    return { ok: false, error: '找不到所选 PixPin.exe，请检查安装路径。' }
  }
  return { ok: true, executable }
}

export async function discoverPixPinExecutable({ configuredPath, env = process.env, runQuery = runPowerShellJson } = {}) {
  if (process.platform !== 'win32')
    return { ok: false, error: 'PixPin 截图目前只支持 Windows。' }

  const candidates = [configuredPath, env.MEGURI_PIXPIN_PATH]
  if (env.ProgramFiles)
    candidates.push(`${env.ProgramFiles}\\PixPin\\PixPin.exe`)
  if (env['ProgramFiles(x86)'])
    candidates.push(`${env['ProgramFiles(x86)']}\\PixPin\\PixPin.exe`)
  if (env.LOCALAPPDATA)
    candidates.push(`${env.LOCALAPPDATA}\\Programs\\PixPin\\PixPin.exe`)

  try {
    const installedPaths = await runQuery(installDiscoveryScript, { env })
    candidates.push(...(Array.isArray(installedPaths) ? installedPaths : [installedPaths]))
  }
  catch {
    // Registry discovery is optional; an explicit or standard path may still work.
  }

  const seen = new Set()
  for (const candidate of candidates) {
    if (typeof candidate !== 'string' || !candidate.trim())
      continue
    const normalized = candidate.trim().replace(/,\d+$/u, '').replace(/^"|"$/gu, '')
    const key = normalized.toLowerCase()
    if (seen.has(key))
      continue
    seen.add(key)
    const checked = validatePixPinExecutable(normalized)
    if (checked.ok)
      return checked
  }
  return { ok: false, error: '没有找到 PixPin.exe。请在打开的文件选择框中指定 PixPin 安装目录里的 PixPin.exe。' }
}

function sameWindowsPath(left, right) {
  return typeof left === 'string'
    && typeof right === 'string'
    && resolve(left).replaceAll('/', '\\').toLowerCase() === resolve(right).replaceAll('/', '\\').toLowerCase()
}

export async function findPixPinProcessIds(executable, { env = process.env, runQuery = runPowerShellJson } = {}) {
  const rows = await runQuery(processDiscoveryScript, { env })
  const list = Array.isArray(rows) ? rows : [rows]
  return [...new Set(list
    .filter(row => row && sameWindowsPath(row.executablePath, executable))
    .map(row => Number(row.pid))
    .filter(pid => Number.isSafeInteger(pid) && pid > 0))]
}

export async function ensurePixPinRunning(executable, {
  env = process.env,
  spawnImpl = spawn,
  findProcesses = findPixPinProcessIds,
  wait = ms => new Promise(resolvePromise => setTimeout(resolvePromise, ms)),
  timeoutMs = 15_000,
} = {}) {
  const existing = await findProcesses(executable, { env })
  if (existing.length)
    return { executable, processIds: existing }

  let child
  try {
    child = spawnImpl(executable, [], { shell: false, detached: true, windowsHide: false, stdio: 'ignore' })
    await new Promise((resolvePromise, rejectPromise) => {
      child.once('spawn', resolvePromise)
      child.once('error', rejectPromise)
    })
    child.unref?.()
  }
  catch (error) {
    throw new Error(`无法启动 PixPin：${error instanceof Error ? error.message : String(error)}`)
  }

  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const processIds = await findProcesses(executable, { env }).catch(() => [])
    if (processIds.length)
      return { executable, processIds }
    await wait(250)
  }
  throw new Error('PixPin 没有在 15 秒内启动。请手动打开 PixPin 后重试。')
}

export function createWindowsClipboardWatcher({ onChange, onError, spawnImpl = spawn, env = process.env } = {}) {
  if (process.platform !== 'win32')
    throw new Error('Windows 剪贴板监听器不可用。')

  const child = spawnImpl(powershellPath(env), ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', clipboardWatcherScript], {
    shell: false,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let stdout = ''
  let stderr = ''
  let stopped = false
  let readyResolved = false
  let resolveReady
  let rejectReady
  const ready = new Promise((resolvePromise, rejectPromise) => {
    resolveReady = resolvePromise
    rejectReady = rejectPromise
  })
  child.stdout?.setEncoding('utf8')
  child.stderr?.setEncoding('utf8')
  child.stdout?.on('data', chunk => {
    stdout += chunk
    const lines = stdout.split(/\r?\n/u)
    stdout = lines.pop() ?? ''
    for (const line of lines) {
      if (!line.trim())
        continue
      let message
      try {
        message = JSON.parse(line)
      }
      catch {
        continue
      }
      if (message.type === 'ready' && !readyResolved) {
        readyResolved = true
        resolveReady(message)
      }
      else if (message.type === 'change') {
        onChange?.(message)
      }
    }
  })
  child.stderr?.on('data', chunk => { stderr = `${stderr}${chunk}`.slice(-2000) })
  child.once('error', error => {
    const failure = new Error(`无法启动 Windows 剪贴板监听器：${error.message}`)
    if (!readyResolved) {
      readyResolved = true
      rejectReady(failure)
    }
    else if (!stopped) {
      onError?.(failure)
    }
  })
  child.once('exit', code => {
    if (stopped)
      return
    const failure = new Error(`Windows 剪贴板监听器已停止（${code ?? 'unknown'}）。${stderr.trim()}`)
    if (!readyResolved) {
      readyResolved = true
      rejectReady(failure)
    }
    else {
      onError?.(failure)
    }
  })

  return {
    ready,
    stop() {
      if (stopped)
        return
      stopped = true
      if (!readyResolved) {
        readyResolved = true
        rejectReady(new Error('Windows 剪贴板监听器已取消。'))
      }
      if (child.exitCode === null)
        child.kill()
    },
  }
}

export function invokePixPinCapture(executable, { onError, spawnImpl = spawn } = {}) {
  const child = spawnImpl(executable, ['-r', captureScript], {
    shell: false,
    windowsHide: true,
    stdio: 'ignore',
  })
  let stopped = false
  let exitObserved = false
  let resolveStarted
  let rejectStarted
  const started = new Promise((resolvePromise, rejectPromise) => {
    resolveStarted = resolvePromise
    rejectStarted = rejectPromise
  })
  child.once('spawn', () => resolveStarted())
  child.once('error', error => {
    if (!exitObserved)
      rejectStarted(new Error(`无法调用 PixPin 截图脚本：${error.message}`))
  })
  child.once('exit', (code, signal) => {
    exitObserved = true
    if (!stopped && code !== null && code !== 0)
      onError?.(new Error(`PixPin 截图脚本启动失败（退出码 ${code}${signal ? `，信号 ${signal}` : ''}）。请确认 PixPin 正在运行。`))
  })

  return {
    started,
    stop() {
      stopped = true
      if (child.exitCode === null)
        child.kill()
    },
  }
}

export const pixpinRuntimeInternals = Object.freeze({
  captureScript,
  installDiscoveryScript,
  processDiscoveryScript,
  clipboardWatcherScript,
  powershellPath,
  sameWindowsPath,
})
