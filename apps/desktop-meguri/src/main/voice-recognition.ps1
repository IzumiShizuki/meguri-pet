param(
  [switch]$DiagnosticOnly
)

$ErrorActionPreference = 'Stop'

$recognizer = $null
$stage = 'bootstrap'
try {
  [Console]::Error.WriteLine('MEGURI_VOICE_STAGE=bootstrap')
  [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
  [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

  $stage = 'engine'
  [Console]::Error.WriteLine('MEGURI_VOICE_STAGE=engine')
  Add-Type -AssemblyName System.Speech
  $culture = [System.Globalization.CultureInfo]::GetCultureInfo('zh-CN')
  $recognizer = New-Object System.Speech.Recognition.SpeechRecognitionEngine($culture)
  $grammar = New-Object System.Speech.Recognition.DictationGrammar
  $recognizer.LoadGrammar($grammar)

  if ($DiagnosticOnly) {
    [Console]::Out.WriteLine((@{ ok = $true; text = 'voice-helper-ready' } | ConvertTo-Json -Compress))
    return
  }

  $stage = 'audio_input'
  [Console]::Error.WriteLine('MEGURI_VOICE_STAGE=audio_input')
  $recognizer.SetInputToDefaultAudioDevice()

  $stage = 'recognition'
  [Console]::Error.WriteLine('MEGURI_VOICE_STAGE=recognition')
  $result = $recognizer.Recognize([TimeSpan]::FromSeconds(20))
  $text = if ($null -eq $result) { '' } else { [string]$result.Text }
  $text = $text.Trim()
  if ([string]::IsNullOrWhiteSpace($text)) {
    [Console]::Out.WriteLine((@{ ok = $false; code = 'no_speech'; error = '没有识别到语音，请靠近麦克风后重试。' } | ConvertTo-Json -Compress))
  }
  else {
    if ($text.Length -gt 1000) { $text = $text.Substring(0, 1000) }
    [Console]::Out.WriteLine((@{ ok = $true; text = $text } | ConvertTo-Json -Compress))
  }
}
catch {
  $failure = switch ($stage) {
    'bootstrap' { @{ ok = $false; code = 'helper_startup_failed'; stage = 'helper_process'; error = 'Windows 语音识别辅助程序启动失败。请重启 Meguri 后重试。' } }
    'audio_input' { @{ ok = $false; code = 'audio_input_unavailable'; stage = 'audio_input'; error = '无法打开 Windows 默认麦克风。请确认默认输入设备正常，并允许桌面应用访问麦克风。' } }
    'recognition' { @{ ok = $false; code = 'recognition_failed'; stage = 'recognition'; error = 'Windows 中文语音识别过程中遇到问题。请重试；如果持续发生，请检查中文语音组件。' } }
    default { @{ ok = $false; code = 'engine_unavailable'; stage = 'engine'; error = 'Windows 中文语音识别组件无法启动。请确认已安装中文语音功能并重试。' } }
  }
  $failure.source = 'helper'
  $failure.diagnostic = switch ($_.Exception.GetType().Name) {
    'ArgumentException' { 'helper_argument_failure' }
    'MethodInvocationException' { 'helper_method_failure' }
    'IOException' { 'helper_io_failure' }
    'TypeInitializationException' { 'speech_component_failure' }
    default { 'helper_bootstrap_failure' }
  }
  [Console]::Out.WriteLine(($failure | ConvertTo-Json -Compress))
}
finally {
  if ($null -ne $recognizer) {
    try { $recognizer.Dispose() }
    catch { }
  }
}
