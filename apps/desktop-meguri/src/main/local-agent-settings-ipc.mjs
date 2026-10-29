import { saveLocalAgentConfig } from './local-agent-config.mjs'

// Keep the caller's exact settings-window and expected-location predicate.
// This registers no alternate transport or bypass for settings writes.
export function registerLocalAgentSettingsIpc(options) {
  const { ipcMain, channels, trustedSettingsRenderer, getRuntime, resolveConfigPath,
    startObservation, stopObservation, failure } = options
  ipcMain.handle(channels.localAgentsGet, event => trustedSettingsRenderer(event)
    ? getRuntime().settings()
    : { version: 1, enabled: false, harnessAutoStart: false, dshHome: '', install: { codex: { installed: false, home: '', signedIn: false }, harness: { cli: 'missing', credentials: 'missing', preset: 'unavailable', logReady: false } } })
  ipcMain.handle(channels.localAgentsSave, (event, patch) => {
    if (!trustedSettingsRenderer(event))
      return { ok: false, error: '本地 Agent 配置请求不受信任。', code: 'untrusted_source' }
    const saved = saveLocalAgentConfig(resolveConfigPath(), patch)
    if (!saved.ok)
      return saved
    try {
      const runtime = getRuntime()
      const settings = runtime.updateSettings(saved.config)
      if (settings.enabled && settings.harnessAutoStart)
        runtime.startHarnessHost()
      if (settings.enabled)
        startObservation()
      else
        stopObservation()
      return { ok: true, settings }
    }
    catch {
      return { ok: false, code: 'apply_failed', error: '配置已保存，但运行状态未能更新。请重新打开设置核对；必要时重新启动桌面应用。' }
    }
  })
  ipcMain.handle(channels.localAgentsTest, async (event) => {
    if (!trustedSettingsRenderer(event))
      return { codex: { ok: false, message: '连接测试请求不受信任。' }, harness: { ok: false, message: '连接测试请求不受信任。' } }
    try {
      return await getRuntime().connectTest()
    }
    catch (error) {
      const result = failure(error)
      return { codex: { ok: false, message: result.error }, harness: { ok: false, message: result.error } }
    }
  })
}
