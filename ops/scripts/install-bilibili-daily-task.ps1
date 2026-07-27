[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$TaskName = 'Meguri-Bilibili-Daily',
    [string]$Runner = (Join-Path $PSScriptRoot 'run-bilibili-daily.ps1'),
    [string]$ExpectedWindowsTimeZone = 'China Standard Time',
    [switch]$Preview,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedRunner = (Resolve-Path -LiteralPath $Runner).Path
$localTimeZone = [TimeZoneInfo]::Local.Id
if ($localTimeZone -ne $ExpectedWindowsTimeZone) {
    throw "The task is defined in Windows local time. Expected '$ExpectedWindowsTimeZone', found '$localTimeZone'."
}

$powerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f $resolvedRunner
$action = New-ScheduledTaskAction -Execute $powerShell -Argument $arguments -WorkingDirectory (Split-Path $resolvedRunner)
$trigger = New-ScheduledTaskTrigger `
    -Daily `
    -At ([datetime]::Today.AddHours(2).AddMinutes(20)) `
    -RandomDelay (New-TimeSpan -Minutes 20)
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -WakeToRun `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 45) `
    -MultipleInstances IgnoreNew

$userId = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $userId -LogonType Interactive -RunLevel Limited
$definition = New-ScheduledTask -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
    -Description 'Sync only Bilibili watch history between 02:20 and 02:40, then write the Meguri metadata report before 03:00.'

$previewValue = [ordered]@{
    task_name = $TaskName
    user = $userId
    timezone = $localTimeZone
    start_at = '02:20'
    random_delay_minutes = 20
    hard_deadline = '03:00'
    runner = $resolvedRunner
    multiple_instances = 'IgnoreNew'
}

if ($Preview) {
    $previewValue | ConvertTo-Json -Depth 3
    return
}

$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -ne $existing -and -not $Force) {
    throw "Scheduled task '$TaskName' already exists. Re-run with -Force to replace it."
}

if ($PSCmdlet.ShouldProcess($TaskName, 'Register daily Bilibili watch-history task')) {
    Register-ScheduledTask -TaskName $TaskName -InputObject $definition -Force:$Force | Out-Null
    Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State, Author, Description
}
