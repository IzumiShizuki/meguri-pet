import { readFileSync } from 'node:fs'

/** Main-process client: file paths and the host credential never enter the renderer. */
export function createSpeechResources({ projectRoot, tokenFile = process.env.MEGURI_TTS_MANAGEMENT_TOKEN_FILE ?? 'D:/environment/secrets/meguri/tts-management-token.txt', fetchImpl = fetch }) {
  async function request(path, method = 'GET', body) {
    let token
    try { token = readFileSync(tokenFile, 'utf8').trim() }
    catch { throw new Error('本地语音服务尚未启动或未配置管理授权；请重启本地 TTS。') }
    if (token.length < 32) throw new Error('本地语音管理授权无效。')
    const response = await fetchImpl(`http://127.0.0.1:9880${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(method === 'GET' ? 5000 : 30 * 60 * 1000),
    })
    const result = await response.json()
    if (!response.ok) throw new Error(typeof result.detail === 'string' ? result.detail.slice(0, 400) : '本地语音操作失败。')
    return result
  }
  async function mutate(path, method, body) {
    try { return await request(path, method, body) }
    catch (error) { return { ok: false, error: error instanceof Error ? error.message : '本地语音操作失败。' } }
  }
  function idPath(id) {
    if (typeof id !== 'string' || !/^(default|[a-f0-9-]{36})$/.test(id)) throw new Error('语音模型标识无效。')
    return `/tts/models/${id}`
  }
  return {
    async list() {
      try { return await request('/tts/models') }
      catch (error) { return { engine: 'GPT-SoVITS', ready: false, activeId: 'default', profiles: [], error: error instanceof Error ? error.message : '本地语音不可用。' } }
    },
    importPackage: directory => mutate('/tts/models/import', 'POST', { directory }),
    download: manifestUrl => mutate('/tts/models/download', 'POST', { manifest_url: manifestUrl }),
    activate: id => mutate(`${idPath(id)}/activate`, 'POST'),
    remove: id => mutate(idPath(id), 'DELETE'),
  }
}
