param(
    [switch]$UpdateRemoteStaging,
    [switch]$SkipRemoteStagingPrompt
)

$ErrorActionPreference = 'Stop'
try {
    chcp 65001 > $null
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
}
catch {}

# Lightweight developer launcher:
# 1. Build Java without tests.
# 2. Build AIRI without tests.
# 3. Ask whether to replace the remote staging image/container.
# 4. Start the local Java/AIRI stack without health checks or smoke tests.

$meguriRoot = 'D:\program\meguri-pet'
$airiRoot = 'D:\program\airi-meguri'
$coreDir = Join-Path $meguriRoot 'java\meguri-core'
$jar = Join-Path $coreDir 'target\meguri-core-0.1.0-SNAPSHOT.jar'
$logRoot = 'D:\environment\logs\meguri'
$stateRoot = 'D:\environment\state\meguri-airi'
$java = 'D:\environment\jdk\temurin-21\jdk-21.0.11+10\bin\java.exe'
$javaHome = Split-Path -Parent (Split-Path -Parent $java)
$maven = 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd'
$pnpm = 'D:\environment\nodejs\global\pnpm.cmd'
$node = 'D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe'
$everything = 'D:\Program Files\Everything\es.exe'
$docker = 'C:\Users\IzumiShizuki\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
$dockerHost = 'tcp://111.228.35.186:2376'
$dockerCertPath = 'C:\Users\IzumiShizuki\.docker\cert_02'
$activeName = 'meguri-staging-core-1'
$remoteImage = 'meguri-core-java:manual-latest'
$remoteBuildContext = Join-Path $meguriRoot 'tmp\remote-java-build-manual'
$remoteEnvFile = Join-Path $meguriRoot 'tmp\meguri-remote-manual.env'
$javaBuildLog = Join-Path $logRoot 'meguri-java-build.log'
$airiBuildLog = Join-Path $logRoot 'airi-build.log'
$remoteBuildLog = Join-Path $logRoot 'meguri-remote-image-build.log'
$localLlmEnvFile = Join-Path $meguriRoot 'ops\env\deepseek.local.env'

function Assert-File {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required file not found: $Path"
    }
}

function Import-MeguriLocalEnvironment {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Host "No local LLM environment file at $Path; Java Core will use the current process environment." -ForegroundColor Yellow
        return
    }
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = ([string]$rawLine).Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Invalid local LLM environment line in $Path" }
        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if ($name -notmatch '^MEGURI_[A-Z0-9_]+$') {
            throw "Unsupported local environment key in ${Path}: $name"
        }
        Set-Item -Path "Env:$name" -Value $value
    }
    Write-Host "Loaded local Meguri LLM configuration from $Path (secret values are not printed)." -ForegroundColor Green
}

function Test-ListeningPort {
    param([Parameter(Mandatory = $true)][int]$Port)

    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Invoke-RemoteDocker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & $docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Remote Docker command failed: docker $($Arguments -join ' ')"
    }
}

function Invoke-LoggedNative {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogPath
    )

    # Windows PowerShell 5 turns native stderr into ErrorRecords when
    # ErrorActionPreference=Stop. Build tools legitimately write warnings to
    # stderr, so capture both streams and use only the native exit code.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $FilePath @Arguments *> $LogPath
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Test-RemoteContainerExists {
    param([Parameter(Mandatory = $true)][string]$Name)

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $docker inspect $Name 1>$null 2>$null
        return ($LASTEXITCODE -eq 0)
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Merge-EnvironmentOverrides {
    param(
        [Parameter(Mandatory = $true)][object[]]$Environment,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Overrides
    )

    $merged = [Collections.Generic.List[string]]::new()
    foreach ($entry in @($Environment)) {
        $line = [string]$entry
        $separator = $line.IndexOf('=')
        $name = if ($separator -gt 0) { $line.Substring(0, $separator) } else { $line }
        if (-not $Overrides.Contains($name)) {
            $merged.Add($line)
        }
    }
    foreach ($override in $Overrides.GetEnumerator()) {
        $merged.Add("$($override.Key)=$($override.Value)")
    }
    return $merged.ToArray()
}

function Reset-RemoteBuildContext {
    $fullContext = [IO.Path]::GetFullPath($remoteBuildContext)
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $meguriRoot 'tmp')) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullContext.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to reset a build context outside $allowedRoot"
    }

    if (Test-Path -LiteralPath $fullContext) {
        Remove-Item -LiteralPath $fullContext -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Join-Path $fullContext 'target') -Force | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $fullContext 'target\meguri-core-0.1.0-SNAPSHOT.jar') -Force
    Copy-Item -LiteralPath (Join-Path $meguriRoot 'configs') -Destination (Join-Path $fullContext 'configs') -Recurse -Force
}

function Update-RemoteStaging {
    Write-Host ''
    Write-Host 'Updating remote staging directly (no candidate and no smoke)...' -ForegroundColor Cyan

    $env:DOCKER_HOST = $dockerHost
    $env:DOCKER_TLS_VERIFY = '1'
    $env:DOCKER_CERT_PATH = $dockerCertPath
    $env:DOCKER_BUILDKIT = '1'

    $inspectJson = & $docker inspect $activeName
    if ($LASTEXITCODE -ne 0) {
        throw "Remote active container not found: $activeName"
    }
    $active = ($inspectJson -join "`n") | ConvertFrom-Json | Select-Object -First 1

    Reset-RemoteBuildContext
    # Do not inherit the currently running container's legacy v4-pro/auto
    # settings. Those settings make the first user-visible token wait behind
    # hidden reasoning and make the optional Planner reuse the slow outer
    # route. The Planner intentionally omits its own key file so the Java
    # factory falls back to MEGURI_LLM_API_KEY_FILE without copying a secret.
    $desiredRemoteEnvironment = [ordered]@{
        MEGURI_EXECUTION_MODE_ENABLED = 'true'
        MEGURI_FAST_PATH_ENABLED = 'true'
        # No production ReAct planner is configured on staging yet. Keep this
        # independently disabled while THINK/FAST are exercised.
        MEGURI_LIMITED_REACT_ENABLED = 'false'
        MEGURI_LLM_MODEL = 'deepseek-v4-flash'
        MEGURI_LLM_THINKING = 'disabled'
        MEGURI_AGENT_PLANNER_MODEL = 'deepseek-v4-flash'
        MEGURI_AGENT_PLANNER_THINKING = 'disabled'
        MEGURI_AGENT_PLANNER_MAX_TOKENS = '128'
        MEGURI_AGENT_PLANNER_MAX_CONCURRENCY = '1'
        MEGURI_AGENT_PLANNER_QUEUE_TIMEOUT_MS = '250'
        MEGURI_AGENT_PLANNER_PROVIDER_TIMEOUT_MS = '4000'
        MEGURI_AGENT_PLANNER_DEADLINE_MS = '5000'
    }
    $remoteEnvironment = Merge-EnvironmentOverrides `
        -Environment @($active.Config.Env) `
        -Overrides $desiredRemoteEnvironment
    [IO.File]::WriteAllLines(
        $remoteEnvFile,
        $remoteEnvironment,
        [Text.UTF8Encoding]::new($false)
    )

    $mountArgs = @()
    foreach ($mount in @($active.Mounts)) {
        $spec = "type=$($mount.Type),source=$($mount.Source),target=$($mount.Destination)"
        if ($mount.RW -eq $false) {
            $spec += ',readonly'
        }
        $mountArgs += @('--mount', $spec)
    }

    $networks = @($active.NetworkSettings.Networks.PSObject.Properties.Name)
    $edgeNetwork = $networks | Where-Object { $_ -like '*edge*' } | Select-Object -First 1
    $otherNetworks = @($networks | Where-Object { $_ -ne $edgeNetwork })
    if (-not $edgeNetwork) {
        throw 'Remote staging edge network was not found.'
    }

    Write-Host "Remote image build output: $remoteBuildLog"
    $remoteBuildExit = Invoke-LoggedNative `
        -FilePath $docker `
        -Arguments @(
        'build',
        '--pull=false',
        '--tag', $remoteImage,
        '--file', (Join-Path $coreDir 'Dockerfile'),
        $remoteBuildContext
    ) `
        -LogPath $remoteBuildLog
    if ($remoteBuildExit -ne 0) {
        Write-Host 'Remote image build failed. Last log lines:' -ForegroundColor Red
        Get-Content -LiteralPath $remoteBuildLog -Tail 60
        throw 'Remote Docker image build failed.'
    }

    $replaceName = "$activeName-replacing"
    if (Test-RemoteContainerExists $replaceName) {
        throw "Temporary replacement container already exists: $replaceName"
    }

    $oldRenamed = $false
    $newCreated = $false
    try {
        Invoke-RemoteDocker @('stop', $activeName)
        Invoke-RemoteDocker @('rename', $activeName, $replaceName)
        $oldRenamed = $true

        $jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLower()
        $labels = @(
            '--label', 'io.meguri.environment=staging',
            '--label', 'io.meguri.release-id=java-manual-latest',
            '--label', 'io.meguri.role=java-core',
            '--label', 'com.meguri.deployment.scope=manual',
            '--label', "com.meguri.deployment.jar-sha256=$jarHash",
            '--label', "com.meguri.deployment.image=$remoteImage"
        )
        $createArgs = @(
            'create',
            '--name', $activeName,
            '--env-file', $remoteEnvFile,
            '--init',
            '--restart', 'unless-stopped',
            '--publish', '127.0.0.1:18080:18080'
        ) + $labels + $mountArgs + @('--network', $edgeNetwork, $remoteImage)

        Invoke-RemoteDocker $createArgs
        $newCreated = $true
        foreach ($network in $otherNetworks) {
            Invoke-RemoteDocker @('network', 'connect', $network, $activeName)
        }
        Invoke-RemoteDocker @('start', $activeName)

        # The temporary old container is removed after the replacement starts,
        # so each run does not leave another backup/candidate container behind.
        Invoke-RemoteDocker @('rm', $replaceName)
        $oldRenamed = $false
        Write-Host "Remote staging started: $remoteImage" -ForegroundColor Green
    }
    catch {
        Write-Host 'Remote replacement failed; restoring the original container...' -ForegroundColor Yellow
        if ($newCreated -and (Test-RemoteContainerExists $activeName)) {
            try { Invoke-RemoteDocker @('stop', $activeName) } catch {}
            try { Invoke-RemoteDocker @('rm', $activeName) } catch {}
        }
        if ($oldRenamed -and (Test-RemoteContainerExists $replaceName)) {
            Invoke-RemoteDocker @('rename', $replaceName, $activeName)
            Invoke-RemoteDocker @('start', $activeName)
        }
        throw
    }
    finally {
        if (Test-Path -LiteralPath $remoteEnvFile) {
            Remove-Item -LiteralPath $remoteEnvFile -Force
        }
        $fullContext = [IO.Path]::GetFullPath($remoteBuildContext)
        $allowedRoot = [IO.Path]::GetFullPath((Join-Path $meguriRoot 'tmp')) + [IO.Path]::DirectorySeparatorChar
        if (
            $fullContext.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $fullContext)
        ) {
            Remove-Item -LiteralPath $fullContext -Recurse -Force
        }
    }
}

function Stop-LocalJava {
    $listener = Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        if ($owner -and $owner.ProcessName -eq 'java') {
            Write-Host "Stopping the old Java Core: PID $($owner.Id)"
            Stop-Process -Id $owner.Id -Force
            Start-Sleep -Seconds 2
        }
        else {
            throw "Port 18080 is occupied by a non-Java process (PID $($listener.OwningProcess))."
        }
    }
}

function Start-LocalJava {
    Stop-LocalJava
    $env:MEGURI_EVERYTHING_ENABLED = 'true'
    $env:MEGURI_EVERYTHING_ES_PATH = $everything
    $process = Start-Process -PassThru -WindowStyle Hidden `
        -WorkingDirectory $coreDir `
        -FilePath $java `
        -ArgumentList '-XX:MaxRAMPercentage=75', '-jar', $jar `
        -RedirectStandardOutput (Join-Path $logRoot 'meguri-core.log') `
        -RedirectStandardError (Join-Path $logRoot 'meguri-core.err.log')
    Write-Host "Java Core started: PID $($process.Id)" -ForegroundColor Green
}

function Start-LocalAiriStack {
    $gatewayListener = Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($gatewayListener) {
        $gatewayOwner = Get-Process -Id $gatewayListener.OwningProcess -ErrorAction SilentlyContinue
        if ($gatewayOwner -and $gatewayOwner.ProcessName -eq 'node') {
            Stop-Process -Id $gatewayOwner.Id -Force
            Start-Sleep -Seconds 1
        }
        else {
            throw "Port 5173 is occupied by a non-Node process (PID $($gatewayListener.OwningProcess))."
        }
    }
    Start-Process -WindowStyle Hidden `
        -WorkingDirectory $meguriRoot `
        -FilePath $node `
        -ArgumentList 'apps\desktop-airi\src\web-server.mjs' `
        -RedirectStandardOutput (Join-Path $logRoot 'airi-support-gateway.log') `
        -RedirectStandardError (Join-Path $logRoot 'airi-support-gateway.err.log') | Out-Null
    Write-Host 'AIRI support gateway restarted.'

    if (-not (Test-ListeningPort 9880)) {
        & (Join-Path $meguriRoot 'ops\scripts\start_local_tts.ps1') | Out-Null
        Write-Host 'Local TTS started.'
    }

    $renderers = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'electron.exe' -and
        $_.CommandLine -like "*--app-path=*$airiRoot\apps\stage-tamagotchi*"
    })
    foreach ($mainId in @($renderers.ParentProcessId | Sort-Object -Unique)) {
        if (Get-Process -Id $mainId -ErrorAction SilentlyContinue) {
            Stop-Process -Id $mainId -Force
        }
    }
    if ($renderers.Count -gt 0) {
        Start-Sleep -Seconds 2
    }

    $airiApp = Join-Path $airiRoot 'apps\stage-tamagotchi'
    $preferredElectron = Join-Path $airiApp 'node_modules\electron\dist\electron.exe'
    $fallbackElectron = Join-Path $meguriRoot 'apps\desktop-airi\node_modules\electron\dist\electron.exe'
    $electron = if (Test-Path -LiteralPath $preferredElectron) {
        $preferredElectron
    }
    elseif (Test-Path -LiteralPath $fallbackElectron) {
        $fallbackElectron
    }
    else {
        throw 'No Electron runtime is available for AIRI.'
    }

    $env:APP_USER_DATA_PATH = $stateRoot
    $process = Start-Process -PassThru -WindowStyle Hidden `
        -WorkingDirectory $airiApp `
        -FilePath $electron `
        -ArgumentList '.' `
        -RedirectStandardOutput (Join-Path $logRoot 'airi-tamagotchi-runtime.log') `
        -RedirectStandardError (Join-Path $logRoot 'airi-tamagotchi-runtime.err.log')
    Write-Host "AIRI Electron started: PID $($process.Id)" -ForegroundColor Green
}

Assert-File $java
Assert-File $maven
Assert-File $pnpm
Assert-File $node
Assert-File (Join-Path $coreDir 'pom.xml')
Assert-File (Join-Path $airiRoot 'apps\stage-tamagotchi\package.json')
New-Item -ItemType Directory -Force -Path $logRoot, $stateRoot | Out-Null
# Maven needs the same Java 21 runtime as the launched Core. Do this locally so
# a caller's shell default JDK cannot make the Java build target the wrong release.
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Import-MeguriLocalEnvironment -Path $localLlmEnvFile

Write-Host '=== 1/4 Build Java (tests skipped) ===' -ForegroundColor Cyan
Write-Host "Build output: $javaBuildLog"
# Spring Boot's repackage step replaces the target JAR. Windows keeps that file
# locked while the old Core is running, so stop it before Maven starts.
Stop-LocalJava
Push-Location $meguriRoot
try {
    $javaBuildExit = Invoke-LoggedNative `
        -FilePath $maven `
        -Arguments @('-q', '-f', 'java\meguri-core\pom.xml', 'package', '-Dmaven.test.skip=true') `
        -LogPath $javaBuildLog
    if ($javaBuildExit -ne 0) {
        Write-Host 'Java build failed. Last log lines:' -ForegroundColor Red
        Get-Content -LiteralPath $javaBuildLog -Tail 60
        throw 'Java build failed.'
    }
    Write-Host 'Java build completed.' -ForegroundColor Green
}
finally {
    Pop-Location
}

Write-Host '=== 2/4 Build AIRI (no tests) ===' -ForegroundColor Cyan
Write-Host "Build output: $airiBuildLog"
Push-Location $airiRoot
try {
    $airiBuildExit = Invoke-LoggedNative `
        -FilePath $pnpm `
        -Arguments @('--filter', '@proj-airi/stage-tamagotchi', 'build') `
        -LogPath $airiBuildLog
    if ($airiBuildExit -ne 0) {
        Write-Host 'AIRI build failed. Last log lines:' -ForegroundColor Red
        Get-Content -LiteralPath $airiBuildLog -Tail 60
        throw 'AIRI build failed.'
    }
    Write-Host 'AIRI build completed.' -ForegroundColor Green
}
finally {
    Pop-Location
}

Write-Host '=== 3/4 Remote image ===' -ForegroundColor Cyan
$answer = if ($UpdateRemoteStaging) { 'Y' } elseif ($SkipRemoteStagingPrompt) { 'N' } else {
    Read-Host 'Update the remote staging image and restart its container? (Y/N)'
}
if ($answer -match '^[Yy]') {
    Assert-File $docker
    Update-RemoteStaging
}
else {
    Write-Host 'Remote image update skipped.' -ForegroundColor Yellow
}

Write-Host '=== 4/4 Start the local stack (no health checks) ===' -ForegroundColor Cyan
Start-LocalJava
Start-LocalAiriStack

Write-Host ''
Write-Host 'Build and start commands completed. No tests, health checks, or smoke tests were run.' -ForegroundColor Green
