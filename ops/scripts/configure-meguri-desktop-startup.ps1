param(
    [Parameter(Mandatory = $true)]
    [Alias('OpenAtLogin')]
    [ValidateSet('true', 'false')]
    [string]$OpenAtLoginFlag,
    [Parameter(Mandatory = $true)]
    [Alias('RunAsAdministrator')]
    [ValidateSet('true', 'false')]
    [string]$RunAsAdministratorFlag,
    [switch]$CheckElevation,
    [string]$ExpectedUserSid
)

$OpenAtLogin = $OpenAtLoginFlag -eq 'true'
$RunAsAdministrator = $RunAsAdministratorFlag -eq 'true'

$ErrorActionPreference = 'Stop'
$meguriRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$logRoot = Join-Path $meguriRoot 'log'
$logFile = Join-Path $logRoot 'desktop-startup-settings.log'
$taskName = 'Meguri Desktop'
$registryPath = 'HKCU:\Software\Meguri\Desktop'
$launcherPath = Join-Path $meguriRoot 'ops\scripts\start-meguri-desktop.ps1'
$powerShellPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

function Write-StartupLog([string]$Message) {
    New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
    Add-Content -LiteralPath $logFile -Value "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Message" -Encoding UTF8
}

function Test-CurrentElevation {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-SameWindowsUser([string]$TaskUserId, $Identity) {
    try {
        $taskSid = if ($TaskUserId -match '^S-\d-\d+') {
            $TaskUserId
        }
        else {
            ([Security.Principal.NTAccount]::new($TaskUserId)).Translate([Security.Principal.SecurityIdentifier]).Value
        }
        return $taskSid -eq $Identity.User.Value
    }
    catch {
        $currentShortName = $Identity.Name.Substring($Identity.Name.LastIndexOf([char]92) + 1)
        return $TaskUserId -ieq $Identity.Name -or $TaskUserId -ieq $currentShortName
    }
}

function Set-StartupRegistryFlags {
    New-Item -Path $registryPath -Force | Out-Null
    New-ItemProperty -Path $registryPath -Name 'OpenAtLogin' -Value ([int]$OpenAtLogin) -PropertyType DWord -Force | Out-Null
    New-ItemProperty -Path $registryPath -Name 'RunAsAdministrator' -Value ([int]$RunAsAdministrator) -PropertyType DWord -Force | Out-Null
}

try {
    if ($CheckElevation) {
        if (Test-CurrentElevation) { 'true' } else { 'false' }
        exit 0
    }

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if ($ExpectedUserSid -and $identity.User.Value -ne $ExpectedUserSid) {
        throw 'UAC 使用了另一个 Windows 账户。请使用当前登录账户的管理员权限再试。'
    }

    $existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    $existingHighest = $existingTask -and $existingTask.Principal.RunLevel -eq 'Highest'
    $desiredRunLevel = if ($RunAsAdministrator) { 'Highest' } else { 'Limited' }
    $savedFlags = Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue
    $registryMatches = $savedFlags -and
        $savedFlags.OpenAtLogin -eq [int]$OpenAtLogin -and
        $savedFlags.RunAsAdministrator -eq [int]$RunAsAdministrator

    $taskMatches = $false
    if ($existingTask) {
        $logonTriggers = @($existingTask.Triggers | Where-Object {
            $_.CimClass.CimClassName -eq 'MSFT_TaskLogonTrigger'
        })
        $triggerMatches = if ($OpenAtLogin) { $logonTriggers.Count -gt 0 } else { $logonTriggers.Count -eq 0 }
        $actionMatches = $existingTask.Actions.Count -eq 1 -and
            $existingTask.Actions[0].Arguments -like "*`"$launcherPath`"*" -and
            $existingTask.Actions[0].WorkingDirectory -ieq $meguriRoot
        $taskMatches = (Test-SameWindowsUser ([string]$existingTask.Principal.UserId) $identity) -and
            $existingTask.Principal.RunLevel -eq $desiredRunLevel -and
            $triggerMatches -and
            $actionMatches
    }
    elseif (-not $OpenAtLogin -and -not $RunAsAdministrator) {
        $taskMatches = $true
    }

    if ($taskMatches) {
        if (-not $registryMatches) {
            Set-StartupRegistryFlags
            Write-StartupLog "Synchronized saved settings: openAtLogin=$OpenAtLogin, runAsAdministrator=$RunAsAdministrator."
        }
        Write-Output 'Windows 启动设置已应用。'
        exit 0
    }

    $requiresElevation = $RunAsAdministrator -or $existingHighest
    if ($requiresElevation -and -not (Test-CurrentElevation)) {
        $childArguments = @(
            '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass',
            '-File', ('"{0}"' -f $PSCommandPath),
            '-OpenAtLogin', $OpenAtLogin.ToString(),
            '-RunAsAdministrator', $RunAsAdministrator.ToString(),
            '-ExpectedUserSid', $identity.User.Value
        )
        Write-StartupLog "Requesting one-time UAC approval for openAtLogin=$OpenAtLogin, runAsAdministrator=$RunAsAdministrator."
        try {
            $elevatedProcess = Start-Process -FilePath $powerShellPath -ArgumentList $childArguments `
                -Verb RunAs -WindowStyle Hidden -Wait -PassThru
        }
        catch {
            throw "Windows 管理员授权未完成：$($_.Exception.Message)"
        }
        if ($elevatedProcess.ExitCode -ne 0) {
            throw "管理员权限任务配置失败（退出码 $($elevatedProcess.ExitCode)），请检查 $logFile。"
        }
        Write-Output 'Windows 启动设置已应用。'
        exit 0
    }

    if (-not $OpenAtLogin -and -not $RunAsAdministrator) {
        if ($existingTask) {
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        }
        Set-StartupRegistryFlags
        Write-StartupLog 'Removed the Meguri Desktop scheduled task; sign-in launch and administrator launch are disabled.'
        Write-Output 'Windows 启动设置已应用。'
        exit 0
    }

    $actionArguments = '-NoLogo -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}"' -f $launcherPath
    $action = New-ScheduledTaskAction -Execute $powerShellPath -Argument $actionArguments -WorkingDirectory $meguriRoot
    $principal = New-ScheduledTaskPrincipal -UserId $identity.Name -LogonType Interactive -RunLevel $desiredRunLevel
    $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $definitionArguments = @{
        Action = $action
        Principal = $principal
        Settings = $settings
        Description = 'Starts the Meguri desktop for the current Windows user.'
    }
    if ($OpenAtLogin) {
        $definitionArguments.Trigger = New-ScheduledTaskTrigger -AtLogOn -User $identity.Name
    }
    $definition = New-ScheduledTask @definitionArguments
    Register-ScheduledTask -TaskName $taskName -InputObject $definition -Force | Out-Null
    Set-StartupRegistryFlags
    Write-StartupLog "Applied settings: openAtLogin=$OpenAtLogin, runAsAdministrator=$RunAsAdministrator, user=$($identity.Name)."
    Write-Output 'Windows 启动设置已应用。'
}
catch {
    $message = $_.Exception.Message
    try {
        Write-StartupLog "ERROR: $message"
    }
    catch {
        # Preserve the original configuration error if the repository log is unavailable.
    }
    [Console]::Error.WriteLine($message)
    exit 1
}
