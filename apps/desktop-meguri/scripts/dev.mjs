import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const electron = require('electron')
const pnpmCli = process.env.npm_execpath

if (!pnpmCli)
  throw new Error('Run the development command through pnpm so npm_execpath is available.')

const renderer = spawn(process.execPath, [pnpmCli, 'run', 'dev:renderer'], {
  cwd: new URL('..', import.meta.url),
  stdio: 'inherit',
  shell: false,
})

async function waitForRenderer() {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    try {
      const response = await fetch('http://127.0.0.1:4174')
      if (response.ok)
        return
    }
    catch {
      // Vite has not bound the port yet.
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('Meguri renderer did not become ready on port 4174.')
}

let desktop

try {
  await waitForRenderer()
  desktop = spawn(electron, ['src/main/index.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: { ...process.env, MEGURI_RENDERER_URL: 'http://127.0.0.1:4174' },
    stdio: 'inherit',
    shell: false,
  })
  desktop.once('exit', code => {
    renderer.kill()
    process.exitCode = code ?? 0
  })
}
catch (error) {
  renderer.kill()
  throw error
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    desktop?.kill()
    renderer.kill()
  })
}
