[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$TaskName = 'Meguri-Bilibili-ReadOnly-MCP',
    [string]$BhfRoot = 'D:\program\bilibili-history-fetcher',
    [string]$BhfPython = 'D:\environment\venvs\bilibili-history-fetcher\Scripts\python.exe',
    [switch]$Preview,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedRoot = (Resolve-Path -LiteralPath $BhfRoot).Path
$resolvedPython = (Resolve-Path -LiteralPath $BhfPython).Path
$serverScript = (Resolve-Path -LiteralPath (Join-Path $resolvedRoot 'main.py')).Path
$userId = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

$action = New-ScheduledTaskAction `
    -Execute $resolvedPython `
    -Argument ('"{0}"' -f $serverScript) `
    -WorkingDirectory $resolvedRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $userId
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -Hidden `
    -MultipleInstances IgnoreNew `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1)
$principal = New-ScheduledTaskPrincipal -UserId $userId -LogonType Interactive -RunLevel Limited
$definition = New-ScheduledTask `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Description 'Run the loopback-only, read-only Bilibili watch-history MCP for Meguri while this user is signed in.'

$previewValue = [ordered]@{
    task_name = $TaskName
    user = $userId
    trigger = 'current-user logon'
    execute = $resolvedPython
    server = $serverScript
    bind = '127.0.0.1:8899'
    mode = 'read-only MCP'
    hidden = $true
    restart_count = 3
}
if ($Preview) {
    $previewValue | ConvertTo-Json -Depth 3
    return
}

$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -ne $existing -and -not $Force) {
    throw "Scheduled task '$TaskName' already exists. Re-run with -Force to replace it."
}

if ($PSCmdlet.ShouldProcess($TaskName, 'Register and start loopback read-only Bilibili MCP task')) {
    Register-ScheduledTask -TaskName $TaskName -InputObject $definition -Force:$Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName

    $deadline = (Get-Date).AddSeconds(15)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri 'http://127.0.0.1:8899/health' -TimeoutSec 2
            if ($response.status -eq 'running' -and $response.mode -eq 'watch_history_read_only_mcp') {
                $healthy = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $healthy) {
        throw 'The scheduled read-only MCP task was registered but did not become healthy on 127.0.0.1:8899.'
    }

    Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State, Author, Description
}
