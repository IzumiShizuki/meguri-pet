param(
    [string]$DatabaseSecretFile = $env:MEGURI_DATABASE_URL_FILE,
    [string]$BridgeTokenFile = $env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE,
    [string]$TenantId = $(if ($env:MEGURI_TENANT_ID) { $env:MEGURI_TENANT_ID } else { 'meguri-local' }),
    [switch]$CheckConfiguration,
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'
$meguriRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$meguriPython = 'D:\environment\anaconda3\envs\py314\python.exe'
. (Join-Path $PSScriptRoot 'memory-runtime-config.ps1')
Set-MeguriMemoryRuntimeEnvironment -DatabaseSecretFile $DatabaseSecretFile -BridgeTokenFile $BridgeTokenFile -TenantId $TenantId
if ($env:MEGURI_MEMORY_BRIDGE_ENABLED -ne 'true') {
    if ($CheckConfiguration) { Write-Output 'Authoritative memory bridge is disabled.'; return }
    throw 'Authoritative memory bridge is disabled or its external secrets are missing.'
}
$DatabaseSecretFile = $env:MEGURI_DATABASE_URL_FILE
$BridgeTokenFile = $env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE
$TenantId = $env:MEGURI_TENANT_ID
if ($env:MEGURI_MEMORY_PROVIDER -ne 'native_pgvector') { throw 'This launcher requires the authoritative native_pgvector provider.' }
if ($env:MEGURI_DESKTOP_TENANT_ID -ne $TenantId) { throw 'Desktop and database memory tenants must match.' }
if ($env:MEGURI_MEMORY_BRIDGE_URL -ne 'http://127.0.0.1:8000') { throw 'This local bridge launcher requires loopback port 8000.' }

foreach ($entry in @(@{ Name = 'MEGURI_DATABASE_URL_FILE'; Path = $DatabaseSecretFile }, @{ Name = 'MEGURI_INTERNAL_BRIDGE_TOKEN_FILE'; Path = $BridgeTokenFile })) {
    if ([string]::IsNullOrWhiteSpace($entry.Path) -or -not [IO.Path]::IsPathRooted($entry.Path) -or -not (Test-Path -LiteralPath $entry.Path -PathType Leaf)) {
        throw "$($entry.Name) must reference an existing absolute secret file. See docs/native-desktop-memory.md."
    }
    $resolvedSecret = (Resolve-Path -LiteralPath $entry.Path).Path
    if ($resolvedSecret.StartsWith($meguriRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$($entry.Name) must be outside the repository."
    }
}
if (-not (Test-Path -LiteralPath $meguriPython -PathType Leaf)) { throw 'The existing py314 Python runtime is unavailable.' }
if ([string]::IsNullOrWhiteSpace($TenantId)) { throw 'A memory tenant identity is required.' }

$env:MEGURI_DATABASE_URL_FILE = $DatabaseSecretFile
$env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE = $BridgeTokenFile
$env:MEGURI_TENANT_ID = $TenantId
$env:MEGURI_DESKTOP_TENANT_ID = $TenantId

if ($CheckConfiguration) {
    Write-Output 'Authoritative memory configuration paths are present. No service started and no database accessed.'
    return
}
& $meguriPython (Join-Path $PSScriptRoot 'check-memory-database.py')
if ($LASTEXITCODE -ne 0) { throw 'The authoritative database is not ready. Check its connection, migration revision, and pgvector extension.' }

function Get-MeguriMemoryBridgeStatus {
    $probeToken = (Get-Content -LiteralPath $BridgeTokenFile -Raw).Trim()
    try {
        return Invoke-RestMethod -Uri 'http://127.0.0.1:8000/internal/memory/desktop/status' -Method Post `
            -Headers @{ 'X-Meguri-Internal-Token' = $probeToken } -ContentType 'application/json' `
            -Body (@{ tenant_id = $TenantId; user_id = $env:MEGURI_DESKTOP_USER_ID; client_id = 'desktop_pet'; session_id = 'memory-launcher-check' } | ConvertTo-Json -Compress) -TimeoutSec 4
    }
    finally { $probeToken = $null }
}
if (Get-NetTCPConnection -State Listen -LocalPort 8000 -ErrorAction SilentlyContinue) {
    # Verify the existing service through its protected endpoint before reuse.
    try {
        $probe = Get-MeguriMemoryBridgeStatus
        if ($probe.available -and $probe.provider -eq 'native_pgvector') { Write-Output 'Existing authoritative memory bridge is ready.'; return }
    }
    catch { throw 'Port 8000 is occupied, but the authorized native memory bridge did not pass readiness.' }
    throw 'Port 8000 is occupied by an incompatible memory provider.'
}
if ($Foreground) {
    Push-Location $meguriRoot
    try { & $meguriPython -m uvicorn services.meguri_core.app:app --host 127.0.0.1 --port 8000 }
    finally { Pop-Location }
    return
}
$memoryLogRoot = Join-Path $meguriRoot 'log'
New-Item -ItemType Directory -Force -Path $memoryLogRoot | Out-Null
$memoryBridgeProcess = Start-Process -PassThru -WindowStyle Hidden -WorkingDirectory $meguriRoot -FilePath $meguriPython `
    -ArgumentList '-m', 'uvicorn', 'services.meguri_core.app:app', '--host', '127.0.0.1', '--port', '8000' `
    -RedirectStandardOutput (Join-Path $memoryLogRoot 'memory-bridge.log') `
    -RedirectStandardError (Join-Path $memoryLogRoot 'memory-bridge.err.log')
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if ($memoryBridgeProcess.HasExited) { throw 'The memory bridge exited during startup. Inspect log/memory-bridge.err.log.' }
    try {
        $probe = Get-MeguriMemoryBridgeStatus
        if ($probe.available -and $probe.provider -eq 'native_pgvector') {
            Write-Output 'Authoritative memory bridge is ready on loopback port 8000.'
            return
        }
    }
    catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $memoryBridgeProcess.HasExited) { Stop-Process -Id $memoryBridgeProcess.Id -Force }
throw 'The memory bridge did not become ready. Inspect log/memory-bridge.err.log.'
