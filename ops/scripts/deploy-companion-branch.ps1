param([Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{40}$')][string]$ExpectedCommit)
$ErrorActionPreference = 'Stop'
$deploymentRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ($deploymentRoot -ne 'D:\program\meguri-pet') { throw 'Unexpected deployment workspace.' }
$auditRoot = Join-Path $deploymentRoot '.codex-runtime\active-companion-20261025'
$statusPath = Join-Path $auditRoot 'deployment-status.json'
$git = 'D:\environment\git\PortableGit\cmd\git.exe'
$phase = 'preflight'
function Save-DeploymentStatus([string]$Name, [hashtable]$Extra = @{}) {
    $script:phase = $Name
    $value = @{ phase=$Name; commit=$ExpectedCommit; at=[DateTimeOffset]::UtcNow.ToString('o') }
    foreach($entry in $Extra.GetEnumerator()) { $value[$entry.Key] = $entry.Value }
    [IO.File]::WriteAllText($statusPath, ($value | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
}
function Invoke-LocalHealth([string]$Path, [hashtable]$Headers = @{}) {
    $probeHeaders = @{}
    foreach($entry in $Headers.GetEnumerator()) { if($entry.Key -ne 'User-Agent'){$probeHeaders[$entry.Key]=$entry.Value} }
    if($Headers.ContainsKey('User-Agent')) { return Invoke-RestMethod -Uri $Path -Headers $probeHeaders -UserAgent $Headers['User-Agent'] -TimeoutSec 4 }
    return Invoke-RestMethod -Uri $Path -Headers $probeHeaders -TimeoutSec 4
}
try {
    if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'The existing desktop services require an administrator deployment process.'
    }
    $head = (& $git -C $deploymentRoot rev-parse HEAD).Trim()
    $branch = (& $git -C $deploymentRoot branch --show-current).Trim()
    if ($head -ne $ExpectedCommit -or $branch -ne 'codex/companion-memory-corrections') { throw 'The requested merged branch is not checked out.' }
    & $git -C $deploymentRoot merge-base --is-ancestor master HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Master is not merged into the selected branch.' }
    $manifest = Get-Content -LiteralPath (Join-Path $auditRoot 'release-manifest.json') -Raw | ConvertFrom-Json
    if ($manifest.commit -ne $ExpectedCommit -or @($manifest.artifacts).Count -lt 5) { throw 'The release manifest does not describe this commit.' }
    foreach($artifact in @($manifest.artifacts)) {
        $artifactPath = [IO.Path]::GetFullPath((Join-Path $deploymentRoot $artifact.path))
        if (-not $artifactPath.StartsWith($deploymentRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Artifact escaped the workspace.' }
        if ((Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $artifact.sha256) { throw 'Artifact hash changed after verification.' }
    }
    $statePath = Join-Path $env:APPDATA 'Electron\desktop-state.json'
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'The existing desktop profile is unavailable.' }
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if (-not $state.desktopPreferences.privacy.remoteChatAllowed -or $state.desktopPreferences.privacy.acceptedPolicyVersion -ne '2026-10-05') {
        throw 'Existing AI consent is required; deployment will not override a withdrawn choice.'
    }
    Save-DeploymentStatus 'verified'
    $targets = @()
    $desktopProcesses = @(Get-CimInstance Win32_Process -Filter "Name='electron.exe'" | Where-Object {
        $_.ExecutablePath -like "$deploymentRoot\*" -and $_.CommandLine -match 'src[\\/]main[\\/]index\.mjs' -and $_.CommandLine -notmatch '--type='
    })
    foreach($process in $desktopProcesses) { $targets += @{id=$process.ProcessId; created=$process.CreationDate; kind='desktop'} }
    foreach($port in @(18080,15173,8000)) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $listener) { continue }
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if ($port -eq 18080) {
            $health = Invoke-LocalHealth 'http://127.0.0.1:18080/health'
            if ($process.Name -ne 'java.exe' -or $health.service -ne 'meguri-core') { throw 'Port 18080 is not the expected Meguri Core.' }
        }
        elseif ($port -eq 15173) {
            if ($process.Name -ne 'node.exe' -or $process.CommandLine -notmatch 'desktop-airi[\\/]src[\\/]web-server\.mjs') { throw 'Port 15173 is not the expected gateway.' }
        }
        else {
            if ($process.Name -ne 'python.exe' -or $process.ExecutablePath -notlike 'D:\environment\anaconda3\*') { throw 'Port 8000 is not the expected Python runtime.' }
            $probeToken = (Get-Content -LiteralPath 'D:\environment\secrets\meguri\internal-bridge-token.txt' -Raw).Trim()
            try {
                $body = @{tenant_id='meguri-local';user_id='local-airi-user';client_id='desktop_pet';session_id='deployment-preflight'} | ConvertTo-Json -Compress
                $probe = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/internal/memory/desktop/status' -Method Post -TimeoutSec 5 -Headers @{'X-Meguri-Internal-Token'=$probeToken} -ContentType 'application/json' -Body $body
                if (-not $probe.available -or $probe.provider -ne 'native_pgvector') { throw 'The existing authoritative memory bridge did not verify.' }
            } finally { $probeToken = $null }
        }
        $targets += @{id=$process.ProcessId; created=$process.CreationDate; kind=[string]$port}
    }
    Copy-Item -LiteralPath $statePath -Destination (Join-Path $auditRoot 'rollback\desktop-state-before-deploy.json') -Force
    Save-DeploymentStatus 'stopping_verified_services' @{ targets=@($targets | ForEach-Object { @{pid=$_.id;kind=$_.kind} }) }
    foreach($target in $targets) {
        $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($target.id)"
        if ($current -and $current.CreationDate -eq $target.created) { Stop-Process -Id $target.id -Force }
    }
    for($attempt=0;$attempt -lt 30;$attempt++) {
        $occupied = @(Get-NetTCPConnection -State Listen -LocalPort 18080,15173,8000 -ErrorAction SilentlyContinue)
        if (-not $occupied.Count) { break }
        Start-Sleep -Milliseconds 200
    }
    if ($occupied.Count) { throw 'Verified service ports did not stop.' }
    $state.desktopPreferences.companion | Add-Member -NotePropertyName screenObservationEnabled -NotePropertyValue $true -Force
    $temporaryState = $statePath + '.deployment.tmp'
    [IO.File]::WriteAllText($temporaryState, ($state | ConvertTo-Json -Depth 30), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporaryState -Destination $statePath -Force
    Save-DeploymentStatus 'starting_memory_bridge'
    & (Join-Path $PSScriptRoot 'start-meguri-memory-bridge.ps1') -TenantId 'meguri-local' > (Join-Path $auditRoot 'memory-start.log') 2>&1
    if (-not $?) { throw 'The authoritative memory launcher failed.' }
    Save-DeploymentStatus 'starting_core'
    $coreStart = @{
        PassThru=$true;WindowStyle='Hidden';WorkingDirectory=$deploymentRoot;FilePath='powershell.exe'
        ArgumentList=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'start-meguri-core.ps1'),'-NonInteractive')
        RedirectStandardOutput=(Join-Path $auditRoot 'core-start.log');RedirectStandardError=(Join-Path $auditRoot 'core-start.err.log')
    }
    $coreLauncher = Start-Process @coreStart
    $coreReady = $false
    for($attempt=0;$attempt -lt 120;$attempt++) {
        try { $health = Invoke-LocalHealth 'http://127.0.0.1:18080/health'; if ($health.status -eq 'ok' -and $health.mode -eq 'configured-provider') { $coreReady=$true;break } } catch {}
        if ($coreLauncher.HasExited) { throw 'The Core launcher exited before readiness.' }
        Start-Sleep -Milliseconds 500
    }
    if (-not $coreReady) { throw 'The updated Core did not become ready.' }
    Save-DeploymentStatus 'starting_gateway'
    & (Join-Path $PSScriptRoot 'start-meguri-desktop.ps1') -GatewayOnly > (Join-Path $auditRoot 'gateway-start.log') 2>&1
    $headers = @{'User-Agent'='MeguriDeployment Electron/43.1.1';'X-Meguri-Desktop-Client'='meguri';'X-Meguri-Desktop-Session'='companion-deployment-status'}
    $memory = Invoke-LocalHealth 'http://127.0.0.1:15173/core/v1/memory/desktop/status' $headers
    if (-not $memory.available -or 'memory_corrections' -notin $memory.capabilities) { throw 'The deployed memory bridge/Core/gateway capabilities did not agree.' }
    Save-DeploymentStatus 'starting_desktop'
    $env:MEGURI_RELEASE_COMMIT = $ExpectedCommit
    & (Join-Path $PSScriptRoot 'start-meguri-desktop.ps1') -OpenSettings > (Join-Path $auditRoot 'desktop-start.log') 2>&1
    $receipt = $null
    for($attempt=0;$attempt -lt 80;$attempt++) {
        $lines = @(Get-Content -LiteralPath (Join-Path $deploymentRoot 'log\desktop-electron.log') -ErrorAction SilentlyContinue)
        foreach($line in $lines) {
            if ($line.StartsWith('[Meguri companion] ready ')) {
                try { $value=$line.Substring(25)|ConvertFrom-Json; if($value.release -eq $ExpectedCommit){$receipt=$value} } catch {}
            }
        }
        if ($receipt) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $receipt) { throw 'The new desktop renderer did not produce its startup receipt.' }
    $ports = @(Get-NetTCPConnection -State Listen -LocalPort 18080,15173,8000,9880 | ForEach-Object { @{port=$_.LocalPort;pid=$_.OwningProcess} })
    Save-DeploymentStatus 'ready' @{ports=$ports;native=$receipt;memory_capabilities=@($memory.capabilities);screen_observation_enabled=$true}
}
catch {
    Save-DeploymentStatus 'failed' @{failed_phase=$phase;reason=$_.Exception.Message}
    exit 1
}
