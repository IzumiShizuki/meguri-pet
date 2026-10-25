import { app, powerMonitor } from 'electron'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createCompanionActivity } from '../src/main/companion-activity.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
app.setPath('userData', join(app.getPath('temp'), `meguri-companion-activity-smoke-${process.pid}`))
const exec = promisify(execFile)
const timeout = setTimeout(() => app.exit(2), 20_000)
app.whenReady().then(async () => {
  try {
    const idleSeconds = powerMonitor.getSystemIdleTime()
    const nativeState = powerMonitor.getSystemIdleState(600)
    let captures = 0
    const blocked = createCompanionActivity({ idleTime: () => 600, idleState: () => 'active', enabled: () => true,
      observe: async () => { captures++; return { ok: true, text: 'must not capture' } } })
    const result = await blocked.observe()
    const powershell = join(process.env.SystemRoot ?? 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe')
    const { stdout } = await exec(powershell, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', join(root, 'src/main/foreground-ocr.ps1'), '-Fixture'], { windowsHide: true, timeout: 10_000, encoding: 'utf8' })
    const ocr = JSON.parse(stdout.trim().replace(/^\uFEFF/u, ''))
    const ok = Number.isFinite(idleSeconds) && idleSeconds >= 0 && ['active', 'idle', 'locked', 'unknown'].includes(nativeState)
      && captures === 0 && result === undefined && ocr.ok && /MEGURI ACTIVITY TEST 2026/u.test(ocr.text)
    console.log(JSON.stringify({ ok, native: { idleSeconds, state: nativeState }, boundary600BlocksCapture: captures === 0,
      syntheticOcrFixturePassed: Boolean(ocr.ok && /MEGURI ACTIVITY TEST 2026/u.test(ocr.text)), realScreenCaptures: 0 }))
    app.exit(ok ? 0 : 1)
  }
  catch { console.error(JSON.stringify({ ok: false, reason: 'native_activity_or_ocr_smoke_failed' })); app.exit(1) }
  finally { clearTimeout(timeout) }
})
