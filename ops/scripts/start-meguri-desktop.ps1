param(
    [switch]$GatewayOnly,
    [switch]$OpenSettings
)

$ErrorActionPreference = 'Stop'

$meguriRoot = 'D:\program\meguri-pet'
$desktopRoot = Join-Path $meguriRoot 'apps\desktop-meguri'
$electron = Join-Path $desktopRoot 'node_modules\electron\dist\electron.exe'
$node = 'D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe'
$logRoot = Join-Path $meguriRoot 'log'
trap {
    $message = $_.Exception.Message
    try {
        New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
        Add-Content -LiteralPath (Join-Path $logRoot 'desktop-launcher.log') `
            -Value "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] ERROR: $message" -Encoding UTF8
    }
    catch {
        # Keep the bootstrap failure visible on stderr if its log directory is unavailable.
    }
    [Console]::Error.WriteLine($message)
    exit 1
}
$tokenFile = 'D:\environment\secrets\meguri\desktop-core-token.txt'
$gatewayPort = 15173

$configuredScopes = @($env:MEGURI_CAPABILITY_SCOPES -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$env:MEGURI_CAPABILITY_SCOPES = (@($configuredScopes + 'skill:read' + 'skill:manage' + 'shizuki:control') | Select-Object -Unique) -join ','
$env:MEGURI_SHIZUKI_ENABLED = 'true'

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$env:MEGURI_TTS_LOG_DIR = $logRoot

function Test-ListeningPort([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Test-MeguriGateway([int]$Port) {
    try {
        $assetProbe = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 `
            -Uri "http://127.0.0.1:$Port/assets/sprites/meguri/ce04001l.png"
        return $assetProbe.StatusCode -eq 200 -and $assetProbe.Headers['Content-Type'] -eq 'image/png'
    }
    catch {
        return $false
    }
}

if (-not (Test-ListeningPort 18080)) {
    Start-Process -WindowStyle Hidden -WorkingDirectory $meguriRoot `
        -FilePath 'powershell.exe' `
        -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $meguriRoot 'ops\scripts\start-meguri-core.ps1') `
        -RedirectStandardOutput (Join-Path $logRoot 'desktop-core.log') `
        -RedirectStandardError (Join-Path $logRoot 'desktop-core.err.log') | Out-Null
}

if (-not (Test-ListeningPort $gatewayPort)) {
    $gatewayEnvironment = @{
        MEGURI_CORE_URL = 'http://127.0.0.1:18080'
        MEGURI_LOCAL_CORE_URL = 'http://127.0.0.1:18080'
        MEGURI_CORE_AUTH_TOKEN_FILE = $tokenFile
        MEGURI_DESKTOP_PORT = [string]$gatewayPort
        MEGURI_GATEWAY_ORIGIN = "http://127.0.0.1:$gatewayPort"
    }
    foreach ($entry in $gatewayEnvironment.GetEnumerator()) {
        Set-Item -Path "Env:$($entry.Key)" -Value $entry.Value
    }
    Start-Process -WindowStyle Hidden -WorkingDirectory $meguriRoot `
        -FilePath $node `
        -ArgumentList 'apps\desktop-airi\src\web-server.mjs' `
        -RedirectStandardOutput (Join-Path $logRoot 'desktop-gateway.log') `
        -RedirectStandardError (Join-Path $logRoot 'desktop-gateway.err.log') | Out-Null

    for ($attempt = 0; $attempt -lt 20 -and -not (Test-MeguriGateway $gatewayPort); $attempt++) {
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-MeguriGateway $gatewayPort)) {
        throw "The Meguri desktop gateway did not become ready on port $gatewayPort."
    }
}
elseif (-not (Test-MeguriGateway $gatewayPort)) {
    throw "Port $gatewayPort is occupied by a service that is not the Meguri desktop gateway."
}

$env:MEGURI_GATEWAY_ORIGIN = "http://127.0.0.1:$gatewayPort"
$env:MEGURI_OPEN_SETTINGS_ON_START = if ($OpenSettings) { '1' } else { '0' }

if ($GatewayOnly) {
    return
}

if (-not (Test-ListeningPort 9880)) {
    & (Join-Path $meguriRoot 'ops\scripts\start_local_tts.ps1') | Out-Null
}

if (-not (Test-Path -LiteralPath $electron -PathType Leaf)) {
    throw "Meguri Electron executable was not found: $electron"
}

$electronStdout = Join-Path $logRoot 'desktop-electron.log'
$electronStderr = Join-Path $logRoot 'desktop-electron.err.log'
$desktopProcess = Start-Process -PassThru -WorkingDirectory $desktopRoot `
    -FilePath $electron `
    -ArgumentList 'src/main/index.mjs' `
    -RedirectStandardOutput $electronStdout `
    -RedirectStandardError $electronStderr
Write-Output "Started Meguri Desktop (PID $($desktopProcess.Id)). Desktop errors: $electronStderr"
