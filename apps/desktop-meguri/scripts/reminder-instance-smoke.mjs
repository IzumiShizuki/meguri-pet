import { app } from 'electron'
import { spawn } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

// Use an isolated profile so this check never locks or changes the user's Meguri profile.
app.setPath('userData', process.env.MEGURI_INSTANCE_SMOKE_PROFILE || join(tmpdir(), `meguri-instance-smoke-${process.pid}`))
app.whenReady().then(async () => {
  const owner = app.requestSingleInstanceLock()
  if (process.env.MEGURI_INSTANCE_SMOKE_CHILD === '1') { app.exit(owner ? 2 : 0); return }
  if (!owner) throw new Error('isolated parent failed to acquire instance lock')
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url)], {
    env: { ...process.env, MEGURI_INSTANCE_SMOKE_CHILD: '1', MEGURI_INSTANCE_SMOKE_PROFILE: app.getPath('userData') },
    shell: false, windowsHide: true, stdio: 'ignore',
  })
  const timer = setTimeout(() => { child.kill(); app.exit(2) }, 8000)
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', resolve) })
    if (code !== 0) throw new Error(`second process unexpectedly acquired lock (${code})`)
    console.log(JSON.stringify({ ok: true, check: 'reminder profile has only one runtime owner after ready' }))
    app.exit(0)
  }
  finally { clearTimeout(timer) }
}).catch(error => { console.error(error.message); app.exit(1) })
