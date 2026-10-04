import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { dirname } from 'node:path'

export const defaultRelayConfigPath = 'D:\\environment\\config\\meguri\\multimodal-relay.json'
export const defaultRelaySecretPath = 'D:\\environment\\secrets\\meguri\\multimodal-relay-api-key.txt'

const MODEL_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/
const MAX_URL_LENGTH = 2048
const MAX_KEY_LENGTH = 8192
const TEST_IMAGE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='

function relayPaths(overrides = {}) {
  return {
    configPath: overrides.configPath
      ?? process.env.MEGURI_MULTIMODAL_RELAY_CONFIG_FILE
      ?? defaultRelayConfigPath,
    secretPath: overrides.secretPath
      ?? process.env.MEGURI_MULTIMODAL_RELAY_SECRET_FILE
      ?? defaultRelaySecretPath,
  }
}

function text(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function normalizeBaseUrl(value) {
  const raw = text(value)
  if (!raw)
    return { value: '' }
  if (raw.length > MAX_URL_LENGTH)
    return { error: '中转站地址不能超过 2048 个字符。' }
  let parsed
  try {
    parsed = new URL(raw)
  }
  catch {
    return { error: '请输入完整的中转站 Base URL，例如 https://example.com/v1。' }
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash)
    return { error: '中转站地址不能包含账号、密码、查询参数或片段。' }
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname)
  if (parsed.protocol !== 'https:' && !(parsed.protocol === 'http:' && loopback))
    return { error: '远程中转站必须使用 HTTPS；只有本机回环地址可以使用 HTTP。' }
  parsed.pathname = parsed.pathname.replace(/\/+$/u, '')
  return { value: parsed.href.replace(/\/$/u, '') }
}

function normalizeModel(value) {
  const model = text(value)
  if (!model)
    return { value: '' }
  if (!MODEL_PATTERN.test(model))
    return { error: '模型 ID 只能包含字母、数字、点、下划线、冒号、斜杠和连字符，最长 128 个字符。' }
  return { value: model }
}

function atomicWrite(path, value, mode) {
  mkdirSync(dirname(path), { recursive: true })
  const temporary = `${path}.${process.pid}.tmp`
  try {
    writeFileSync(temporary, value, { encoding: 'utf8', mode })
    renameSync(temporary, path)
  }
  finally {
    rmSync(temporary, { force: true })
  }
}

function readStoredDocument(configPath) {
  try {
    const candidate = JSON.parse(readFileSync(configPath, 'utf8'))
    return candidate && typeof candidate === 'object' ? candidate : {}
  }
  catch {
    return {}
  }
}

function sanitizedState(document, apiKeyConfigured) {
  const normalizedUrl = normalizeBaseUrl(document?.baseUrl)
  const normalizedModel = normalizeModel(document?.model)
  return {
    version: 1,
    enabled: document?.enabled === true,
    baseUrl: normalizedUrl.error ? '' : normalizedUrl.value,
    model: normalizedModel.error ? '' : normalizedModel.value,
    apiKeyConfigured,
    updatedAt: typeof document?.updatedAt === 'string' ? document.updatedAt : undefined,
  }
}

export function readMultimodalRelayConfig(overrides = {}) {
  const { configPath, secretPath } = relayPaths(overrides)
  return sanitizedState(readStoredDocument(configPath), existsSync(secretPath))
}

function validateDraft(input, apiKeyConfigured, requireComplete = false) {
  const draft = input && typeof input === 'object' ? input : {}
  const enabled = draft.enabled === true
  const apiKey = text(draft.apiKey)
  const normalizedUrl = normalizeBaseUrl(draft.baseUrl)
  const normalizedModel = normalizeModel(draft.model)
  if (normalizedUrl.error)
    return { ok: false, error: normalizedUrl.error }
  if (normalizedModel.error)
    return { ok: false, error: normalizedModel.error }
  if (apiKey.length > MAX_KEY_LENGTH)
    return { ok: false, error: 'API Key 长度无效。' }
  const hasAnyConfiguration = enabled || requireComplete || normalizedUrl.value || normalizedModel.value || apiKey
  if (hasAnyConfiguration && !normalizedUrl.value)
    return { ok: false, error: '请填写中转站 Base URL。' }
  if (hasAnyConfiguration && !normalizedModel.value)
    return { ok: false, error: '请填写视觉模型 ID。' }
  if ((enabled || requireComplete) && !apiKey && !apiKeyConfigured)
    return { ok: false, error: '启用或测试中转站前，请填写 API Key。' }
  return {
    ok: true,
    value: {
      enabled,
      baseUrl: normalizedUrl.value,
      model: normalizedModel.value,
      apiKey,
    },
  }
}

export function saveMultimodalRelayConfig(input, overrides = {}) {
  const { configPath, secretPath } = relayPaths(overrides)
  const previousSecret = existsSync(secretPath) ? readFileSync(secretPath, 'utf8') : undefined
  const validation = validateDraft(input, previousSecret !== undefined)
  if (!validation.ok)
    return validation
  const next = validation.value
  const document = {
    version: 1,
    enabled: next.enabled,
    baseUrl: next.baseUrl,
    model: next.model,
    updatedAt: new Date().toISOString(),
  }
  let secretChanged = false
  try {
    if (next.apiKey) {
      atomicWrite(secretPath, `${next.apiKey}\n`, 0o600)
      secretChanged = true
    }
    atomicWrite(configPath, `${JSON.stringify(document, null, 2)}\n`, 0o600)
    return { ok: true, config: sanitizedState(document, existsSync(secretPath)) }
  }
  catch {
    if (secretChanged) {
      try {
        if (previousSecret === undefined)
          rmSync(secretPath, { force: true })
        else
          atomicWrite(secretPath, previousSecret, 0o600)
      }
      catch {
        // The original error remains the only renderer-facing detail.
      }
    }
    return { ok: false, error: '无法保存多模态配置，请检查本地配置目录权限。' }
  }
}

function providerMessage(payload) {
  const raw = payload?.error?.message ?? payload?.message
  return typeof raw === 'string'
    ? raw.replace(/[\u0000-\u001F\u007F]+/gu, ' ').trim().slice(0, 280)
    : ''
}

function failureForStatus(status, payload) {
  if (status === 401 || status === 403)
    return '鉴权失败，请检查 API Key 和中转站权限。'
  if (status === 404)
    return '没有找到 Chat Completions 接口或模型，请检查 Base URL 和模型 ID。'
  if (status === 429)
    return '中转站请求过于频繁或额度不足，请稍后重试。'
  const detail = providerMessage(payload)
  return detail ? `中转站拒绝了视觉测试：${detail}` : `中转站返回 HTTP ${status}。`
}

async function responsePayload(response) {
  try {
    return await response.json()
  }
  catch {
    return undefined
  }
}

export async function testMultimodalRelayConfig(input, overrides = {}) {
  const { secretPath } = relayPaths(overrides)
  const savedSecret = existsSync(secretPath) ? text(readFileSync(secretPath, 'utf8')) : ''
  const validation = validateDraft(input, Boolean(savedSecret), true)
  if (!validation.ok)
    return validation
  const draft = validation.value
  const apiKey = draft.apiKey || savedSecret
  const fetchImpl = overrides.fetchImpl ?? globalThis.fetch
  const timeoutMs = Number.isFinite(overrides.timeoutMs) ? Math.max(250, overrides.timeoutMs) : 12_000
  if (typeof fetchImpl !== 'function')
    return { ok: false, error: '当前桌面运行时不支持连接测试。' }
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetchImpl(`${draft.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: draft.model,
        messages: [{
          role: 'user',
          content: [
            { type: 'text', text: 'Reply with OK if image input is accepted.' },
            { type: 'image_url', image_url: { url: TEST_IMAGE } },
          ],
        }],
        max_tokens: 16,
        stream: false,
      }),
      signal: controller.signal,
    })
    const payload = await responsePayload(response)
    if (!response.ok)
      return { ok: false, error: failureForStatus(response.status, payload) }
    const content = payload?.choices?.[0]?.message?.content
    if (typeof content !== 'string' || !content.trim())
      return { ok: false, error: '中转站接受了请求，但没有返回可读取的文本结果。' }
    return { ok: true, message: `视觉输入测试通过（${draft.model}）。` }
  }
  catch (error) {
    if (error?.name === 'AbortError')
      return { ok: false, error: '连接测试超时，请检查中转站地址或网络。' }
    return { ok: false, error: '无法连接中转站，请检查地址、网络和证书。' }
  }
  finally {
    clearTimeout(timeout)
  }
}
