$ErrorActionPreference = 'Stop'

$meguriRoot = 'D:\program\meguri-pet'
$airiRoot = 'D:\program\airi-meguri'
$airiApp = Join-Path $airiRoot 'apps\stage-tamagotchi'
$airiMain = Join-Path $airiApp 'out\main\index.js'
$stateRoot = 'D:\environment\state\meguri-airi'
$logRoot = 'D:\environment\logs\meguri'
$node = 'D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe'
$preferredElectron = Join-Path $airiApp 'node_modules\electron\dist\electron.exe'
$fallbackElectron = Join-Path $meguriRoot 'apps\desktop-airi\node_modules\electron\dist\electron.exe'

New-Item -ItemType Directory -Force -Path $stateRoot, $logRoot | Out-Null

function Test-ListeningPort([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

if (-not (Test-ListeningPort 5173)) {
    Start-Process -WindowStyle Hidden -WorkingDirectory $meguriRoot `
        -FilePath $node `
        -ArgumentList 'apps\desktop-airi\src\web-server.mjs' `
        -RedirectStandardOutput (Join-Path $logRoot 'airi-support-gateway.log') `
        -RedirectStandardError (Join-Path $logRoot 'airi-support-gateway.err.log') | Out-Null
}

if (-not (Test-ListeningPort 9880)) {
    & (Join-Path $meguriRoot 'ops\scripts\start_local_tts.ps1') | Out-Null
}

$ttsDeadline = (Get-Date).AddMinutes(3)
do {
    try {
        $ttsHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:9880/health' -TimeoutSec 3
    }
    catch {
        $ttsHealth = $null
    }
    if ($ttsHealth.ready) {
        break
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $ttsDeadline)

if (-not $ttsHealth.ready) {
    throw 'Meguri GPT-SoVITS did not become ready on http://127.0.0.1:9880.'
}

$coreHeaders = @{
    Origin = 'null'
    'User-Agent' = 'Mozilla/5.0 Electron/43.1.1'
    'X-Meguri-Desktop-Client' = 'airi'
}
$coreHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/core/health' -Headers $coreHeaders -TimeoutSec 10
if ($coreHealth.status -ne 'ok' -or $coreHealth.runtime -ne 'java') {
    throw 'The AIRI support gateway could not reach the Meguri Java Core.'
}

if (-not (Test-Path -LiteralPath $airiMain)) {
    throw "AIRI is not built. Run 'pnpm --filter @proj-airi/stage-tamagotchi build' in $airiRoot."
}

$existingRenderer = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'electron.exe' -and
    $_.CommandLine -like '*--app-path=*D:\program\airi-meguri\apps\stage-tamagotchi*'
} | Select-Object -First 1
$existing = if ($existingRenderer) {
    Get-CimInstance Win32_Process -Filter "ProcessId = $($existingRenderer.ParentProcessId)"
}
else {
    $null
}

if (-not $existing) {
    $electron = if (Test-Path -LiteralPath $preferredElectron) {
        $preferredElectron
    }
    elseif (Test-Path -LiteralPath $fallbackElectron) {
        $fallbackElectron
    }
    else {
        throw 'No existing Electron runtime is available for AIRI.'
    }

    $env:APP_USER_DATA_PATH = $stateRoot
    $process = Start-Process -PassThru -WorkingDirectory $airiApp `
        -FilePath $electron -ArgumentList '.' `
        -RedirectStandardOutput (Join-Path $logRoot 'airi-tamagotchi-runtime.log') `
        -RedirectStandardError (Join-Path $logRoot 'airi-tamagotchi-runtime.err.log')
    $airiPid = $process.Id
}
else {
    $airiPid = $existing.ProcessId
}

[pscustomobject]@{
    airi_pid = $airiPid
    java_core_build = $coreHealth.build_id
    tts_model = $ttsHealth.model_version
    tts_ready = $ttsHealth.ready
}
