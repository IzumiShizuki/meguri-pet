import { spawn } from 'node:child_process'
import { isAbsolute, resolve } from 'node:path'

const MAX_OUTPUT_BYTES = 8_192
const MAX_TRANSCRIPT_LENGTH = 1_000
const DEFAULT_TIMEOUT_MS = 25_000
const HELPER_STAGE_MARKERS = Object.freeze({
  'MEGURI_VOICE_STAGE=launcher': 'launcher',
  'MEGURI_VOICE_STAGE=bootstrap': 'bootstrap',
  'MEGURI_VOICE_STAGE=engine': 'engine',
  'MEGURI_VOICE_STAGE=audio_input': 'audio_input',
  'MEGURI_VOICE_STAGE=recognition': 'recognition',
})
const HELPER_STAGE_MARKER_LENGTH = Math.max(...Object.keys(HELPER_STAGE_MARKERS).map(marker => marker.length))
const HELPER_FAILURE_SOURCES = new Set(['launcher', 'helper'])
const HELPER_FAILURE_DIAGNOSTICS = new Set([
  'script_missing',
  'script_parse_failure',
  'script_parameter_failure',
  'script_access_denied',
  'script_runtime_failure',
  'helper_argument_failure',
  'helper_method_failure',
  'helper_io_failure',
  'speech_component_failure',
  'helper_bootstrap_failure',
])
const FAILURE_PRESENTATION = Object.freeze({
  helper_startup_failed: {
    stage: 'helper_process',
    error: 'Windows 语音识别辅助程序启动失败。请重启 Meguri 后重试。',
  },
  audio_input_unavailable: {
    stage: 'audio_input',
    error: '无法打开 Windows 默认麦克风。请确认默认输入设备正常，并允许桌面应用访问麦克风。',
  },
  engine_unavailable: {
    stage: 'engine',
    error: 'Windows 中文语音识别组件无法启动。请确认已安装中文语音功能并重试。',
  },
  recognition_failed: {
    stage: 'recognition',
    error: 'Windows 中文语音识别过程中遇到问题。请重试；如果持续发生，请检查中文语音组件。',
  },
  recognizer_unavailable: {
    stage: 'recognition',
    error: 'Windows 中文语音识别或麦克风不可用。请检查麦克风权限和语音组件。',
  },
  no_speech: {
    stage: 'recognition',
    error: '没有识别到语音，请靠近麦克风后重试。',
  },
})

function resultError(error, code = 'recognition_failed', stage) {
  return { ok: false, error, code, ...(stage ? { stage } : {}) }
}

function failureResult(code) {
  const presentation = FAILURE_PRESENTATION[code]
  if (presentation)
    return resultError(presentation.error, code, presentation.stage)
  if (code === 'invalid_output')
    return resultError('语音识别辅助程序返回了无效数据，请重启 Meguri 后重试。', code, 'helper_process')
  return resultError('Windows 本机语音识别失败，请重试。', code, 'recognition')
}

function helperExitResult(exitCode, stage) {
  const exitDetail = Number.isInteger(exitCode) ? `（退出代码 ${exitCode}）` : ''
  const failureCode = {
    launcher: 'helper_startup_failed',
    bootstrap: 'helper_startup_failed',
    engine: 'engine_unavailable',
    audio_input: 'audio_input_unavailable',
    recognition: 'recognition_failed',
  }[stage]
  if (failureCode) {
    const failure = failureResult(failureCode)
    return resultError(`${failure.error}${exitDetail}`, failure.code, failure.stage)
  }
  return resultError(`本机语音识别辅助程序异常退出${exitDetail}。请重启 Meguri 后重试。`, 'recognizer_failed', 'helper_process')
}

function consumeHelperStageMarker(chunk, state) {
  const text = state.markerCarry + String(chunk)
  for (const [marker, stage] of Object.entries(HELPER_STAGE_MARKERS)) {
    if (text.lastIndexOf(marker) >= 0)
      state.stage = stage
  }

  let markerCarry = ''
  const maxSuffixLength = Math.min(HELPER_STAGE_MARKER_LENGTH - 1, text.length)
  for (let length = 1; length <= maxSuffixLength; length++) {
    const suffix = text.slice(-length)
    if (Object.keys(HELPER_STAGE_MARKERS).some(marker => marker.startsWith(suffix)))
      markerCarry = suffix
  }
  state.markerCarry = markerCarry
}

function parseRecognitionOutput(stdout) {
  if (!stdout.trim())
    return resultError('语音识别辅助程序没有返回结果，请重启 Meguri 后重试。', 'empty_output', 'helper_process')
  try {
    const payload = JSON.parse(stdout)
    if (!payload || typeof payload !== 'object')
      return failureResult('invalid_output')
    if (payload.ok !== true) {
      const code = typeof payload.code === 'string' && /^[a-z][a-z0-9_]{0,47}$/u.test(payload.code)
        ? payload.code
        : 'recognition_failed'
      const failure = failureResult(code)
      if (code === 'helper_startup_failed') {
        if (HELPER_FAILURE_SOURCES.has(payload.source))
          failure.failureSource = payload.source
        if (HELPER_FAILURE_DIAGNOSTICS.has(payload.diagnostic))
          failure.failureDiagnostic = payload.diagnostic
        if (Number.isSafeInteger(payload.diagnosticLine) && payload.diagnosticLine > 0 && payload.diagnosticLine <= 2_048)
          failure.failureLine = payload.diagnosticLine
      }
      return failure
    }
    const text = typeof payload.text === 'string' ? payload.text.trim() : ''
    if (!text)
      return failureResult('no_speech')
    return { ok: true, text: text.slice(0, MAX_TRANSCRIPT_LENGTH) }
  }
  catch {
    return failureResult('invalid_output')
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
  diagnosticOnly = false,
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
      const stderrState = { markerCarry: '', stage: undefined }
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
        // Launch through a small PowerShell boundary so parse/dispatch failures
        // in the .ps1 file become a fixed JSON result instead of exit code 1.
        const helperCommand = [
          "$ErrorActionPreference = 'Stop'",
          "[Console]::Error.WriteLine('MEGURI_VOICE_STAGE=launcher')",
          'try {',
          "  if ($env:MEGURI_VOICE_DIAGNOSTIC_ONLY -eq '1') { & $env:MEGURI_VOICE_SCRIPT -DiagnosticOnly }",
          '  else { & $env:MEGURI_VOICE_SCRIPT }',
          '}',
          'catch {',
          '  $diagnostic = switch ($_.Exception.GetType().Name) {',
          "    'CommandNotFoundException' { 'script_missing' }",
          "    'FileNotFoundException' { 'script_missing' }",
          "    'ParseException' { 'script_parse_failure' }",
          "    'ParameterBindingException' { 'script_parameter_failure' }",
          "    'PSSecurityException' { 'script_access_denied' }",
          "    'UnauthorizedAccessException' { 'script_access_denied' }",
          "    default { 'script_runtime_failure' }",
          '  }',
          '  $parseLine = 0',
          "  if ($_.Exception.GetType().Name -eq 'ParseException' -and $_.Exception.Errors.Count -gt 0) { $parseLine = $_.Exception.Errors[0].Extent.StartLineNumber }",
          "  [Console]::Out.WriteLine((@{ ok = $false; code = 'helper_startup_failed'; source = 'launcher'; diagnostic = $diagnostic; diagnosticLine = $parseLine } | ConvertTo-Json -Compress))",
          '}',
        ].join('\n')
        child = spawnImpl(powershellPath, [
          '-NoLogo',
          '-NoProfile',
          '-NonInteractive',
          '-ExecutionPolicy',
          'Bypass',
          '-Command',
          helperCommand,
        ], {
          shell: false,
          windowsHide: true,
          stdio: ['ignore', 'pipe', 'pipe'],
          env: {
            ...process.env,
            MEGURI_VOICE_SCRIPT: fixedScript,
            MEGURI_VOICE_DIAGNOSTIC_ONLY: diagnosticOnly ? '1' : '0',
          },
        })
      }
      catch {
        finish(resultError('无法启动 Windows 语音识别。请确认 PowerShell 和中文语音识别组件可用。', 'launch_failed'))
        return
      }

      active = session
      child.stdout?.setEncoding('utf8')
      child.stderr?.setEncoding('utf8')
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
      child.stderr?.on('data', chunk => consumeHelperStageMarker(chunk, stderrState))
      child.once('error', () => finish(resultError('无法启动 Windows 语音识别。请确认 PowerShell 和中文语音识别组件可用。', 'launch_failed')))
      child.once('close', code => {
        if (settled)
          return
        if (cancelled)
          return finish(resultError('语音识别已取消。', 'cancelled'))
        const result = parseRecognitionOutput(stdout)
        if (code !== 0 && ['empty_output', 'invalid_output'].includes(result.code))
          return finish(helperExitResult(code, stderrState.stage))
        finish(result)
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
