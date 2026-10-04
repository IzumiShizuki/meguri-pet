$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$recognizer = $null
try {
  Add-Type -AssemblyName System.Speech
  $culture = [System.Globalization.CultureInfo]::GetCultureInfo('zh-CN')
  $recognizer = New-Object System.Speech.Recognition.SpeechRecognitionEngine($culture)
  $grammar = New-Object System.Speech.Recognition.DictationGrammar
  $recognizer.LoadGrammar($grammar)
  $recognizer.SetInputToDefaultAudioDevice()
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
  [Console]::Out.WriteLine((@{ ok = $false; code = 'recognizer_unavailable'; error = 'Windows 中文语音识别或麦克风不可用。请检查麦克风权限和语音组件。' } | ConvertTo-Json -Compress))
}
finally {
  if ($null -ne $recognizer) { $recognizer.Dispose() }
}
