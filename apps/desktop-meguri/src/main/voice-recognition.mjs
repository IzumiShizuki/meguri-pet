import { spawn } from 'node:child_process'
import { isAbsolute, resolve } from 'node:path'

const MAX_OUTPUT_BYTES = 8_192
const MAX_TRANSCRIPT_LENGTH = 1_000
const DEFAULT_TIMEOUT_MS = 25_000

function resultError(error, code = 'recognition_failed') {
  return { ok: false, error, code }
}

function parseRecognitionOutput(stdout) {
  if (!stdout.trim())
    return resultError('语音识别没有返回结果。', 'empty_output')
  try {
    const payload = JSON.parse(stdout)
    if (!payload || typeof payload !== 'object')
      return resultError('语音识别返回了无效数据。', 'invalid_output')
    if (payload.ok !== true)
      return resultError(typeof payload.error === 'string' && payload.error.trim() ? payload.error.trim() : '语音识别失败。', typeof payload.code === 'string' ? payload.code : 'recognition_failed')
    const text = typeof payload.text === 'string' ? payload.text.trim() : ''
    if (!text)
      return resultError('没有识别到语音，请靠近麦克风后重试。', 'no_speech')
    return { ok: true, text: text.slice(0, MAX_TRANSCRIPT_LENGTH) }
  }
  catch {
    return resultError('语音识别返回了无效数据。', 'invalid_output')
  }
}

/**
 * Launches the fixed local Windows speech helper for one zh-CN utterance.
 * The helper accepts no user-provided arguments, writes only one UTF-8 JSON
 * result, and never writes microphone audio to disk or sends it to a network.
 */
export function createWindowsVoiceRecognizer({
  powershellPath,
  scriptPath,
  spawnImpl = spawn,
  timeoutMs = DEFAULT_TIMEOUT_MS,
} = {}) {
  let active

  function isRunning() {
    return Boolean(active)
  }

  function cancel() {
    if (!active)
      return { ok: false, error: '当前没有正在进行的语音识别。', code: 'not_running' }
    active.cancel()
    return { ok: true }
  }

  function start() {
    if (active)
      return Promise.resolve(resultError('语音识别正在进行中。', 'already_running'))
    if (process.platform !== 'win32')
      return Promise.resolve(resultError('语音输入仅支持 Windows。', 'unsupported_platform'))
    if (typeof powershellPath !== 'string' || !isAbsolute(powershellPath))
      return Promise.resolve(resultError('找不到 Windows PowerShell，无法启动语音识别。', 'powershell_unavailable'))
    if (typeof scriptPath !== 'string' || !isAbsolute(scriptPath))
      return Promise.resolve(resultError('语音识别辅助脚本路径无效。', 'script_unavailable'))

    const timeout = Number.isFinite(timeoutMs) ? Math.max(1_000, timeoutMs) : DEFAULT_TIMEOUT_MS
    const fixedScript = resolve(scriptPath)
    return new Promise(resolveResult => {
      let child
      let stdout = ''
      let outputBytes = 0
      let settled = false
      let cancelled = false
      let timer
      const session = {
        cancel: () => {
          if (settled)
            return
          cancelled = true
          try {
            child?.kill()
          }
          catch {
            // The process may already have exited; the completion handler owns the result.
          }
          finish(resultError('语音识别已取消。', 'cancelled'))
        },
      }
      const finish = result => {
        if (settled)
          return
        settled = true
        clearTimeout(timer)
        if (active === session)
          active = undefined
        resolveResult(result)
      }

      try {
        child = spawnImpl(powershellPath, [
          '-NoLogo',
          '-NoProfile',
          '-NonInteractive',
          '-ExecutionPolicy',
          'Bypass',
          '-File',
          fixedScript,
        ], {
          shell: false,
          windowsHide: true,
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      }
      catch {
        finish(resultError('无法启动 Windows 语音识别。请确认 PowerShell 和中文语音识别组件可用。', 'launch_failed'))
        return
      }

      active = session
      child.stdout?.setEncoding('utf8')
      child.stdout?.on('data', chunk => {
        if (settled)
          return
        const value = String(chunk)
        outputBytes += Buffer.byteLength(value, 'utf8')
        if (outputBytes > MAX_OUTPUT_BYTES) {
          try {
            child.kill()
          }
          catch {
            // finish below reports the bounded-output failure.
          }
          finish(resultError('语音识别返回数据过长。', 'output_limit'))
          return
        }
        stdout += value
      })
      child.once('error', () => finish(resultError('无法启动 Windows 语音识别。请确认 PowerShell 和中文语音识别组件可用。', 'launch_failed')))
      child.once('close', code => {
        if (settled)
          return
        if (cancelled)
          return finish(resultError('语音识别已取消。', 'cancelled'))
        if (code !== 0)
          return finish(resultError('Windows 语音识别无法使用。请检查麦克风权限和中文语音识别组件。', 'recognizer_failed'))
        finish(parseRecognitionOutput(stdout))
      })
      timer = setTimeout(() => {
        try {
          child.kill()
        }
        catch {
          // finish reports the timeout even when the child has already exited.
        }
        finish(resultError('语音识别超时。请说完后重试。', 'timeout'))
      }, timeout)
    })
  }

  return { start, cancel, isRunning }
}

export const voiceRecognitionInternals = Object.freeze({
  MAX_OUTPUT_BYTES,
  MAX_TRANSCRIPT_LENGTH,
  parseRecognitionOutput,
})
