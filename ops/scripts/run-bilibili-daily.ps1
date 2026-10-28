[CmdletBinding()]
param(
    [string]$BhfRoot = 'D:\program\bilibili-history-fetcher',
    [string]$BhfPython = '',
    [string]$MeguriRoot = 'D:\program\meguri-pet',
    [string]$ReportPython = 'D:\environment\anaconda3\envs\py314\python.exe',
    [string]$CoreUrl = 'https://bot.shizuki.online/meguri-core',
    [string]$CoreTokenFile = 'D:\environment\secrets\meguri\desktop-core-token.txt',
    [string]$ContentLlmBaseUrl = '',
    [string]$ContentLlmModel = '',
    [string]$ContentLlmKeyFile = '',
    [ValidateRange(1, 24)][int]$ContentMaxVideos = 12,
    [string]$TargetDate = '',
    [switch]$MetadataOnly,
    [switch]$AllowLate,
    [switch]$SkipRemotePublish,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedTimeZone = 'China Standard Time'
if ([TimeZoneInfo]::Local.Id -ne $expectedTimeZone) {
    throw "Bilibili daily task expects Windows timezone '$expectedTimeZone'."
}

$now = Get-Date
$runDate = $now.Date
if ([string]::IsNullOrWhiteSpace($TargetDate)) {
    $TargetDate = $runDate.AddDays(-1).ToString('yyyy-MM-dd')
} else {
    $parsedTarget = [datetime]::MinValue
    if (-not [datetime]::TryParseExact(
        $TargetDate,
        'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::None,
        [ref]$parsedTarget
    )) {
        throw "TargetDate must use yyyy-MM-dd."
    }
    $TargetDate = $parsedTarget.ToString('yyyy-MM-dd')
}

$hardDeadline = $runDate.AddHours(3)
if ($AllowLate) {
    $hardDeadline = $now.AddMinutes(30)
} elseif ($now -ge $hardDeadline -and -not $DryRun) {
    throw 'The 03:00 hard deadline has passed. Use -AllowLate only for an explicit manual run.'
}
$syncDeadline = $hardDeadline.AddMinutes(-10)
$serviceDeadline = $hardDeadline.AddMinutes(-7)
$reportDeadline = $hardDeadline.AddMinutes(-2)

if ([string]::IsNullOrWhiteSpace($BhfPython)) {
    $candidates = @(
        'D:\environment\anaconda3\envs\bilibili-history\python.exe',
        'D:\environment\venvs\bilibili-history-fetcher\Scripts\python.exe',
        (Join-Path $BhfRoot '.venv\Scripts\python.exe')
    )
    $BhfPython = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}

$syncScript = Join-Path $BhfRoot 'scripts\meguri_daily_sync.py'
$bootstrapScript = Join-Path $BhfRoot 'scripts\bootstrap_private_config.py'
$serverScript = Join-Path $BhfRoot 'main.py'
$reportScript = Join-Path $MeguriRoot 'tools\generate_bilibili_browser_report.py'
$publisherScript = Join-Path $MeguriRoot 'tools\publish_daily_report.py'
$contentScript = Join-Path $MeguriRoot 'tools\enrich_bilibili_report.py'
if (-not $ContentLlmBaseUrl) {
    $ContentLlmBaseUrl = if ($env:MEGURI_LLM_BASE_URL) { $env:MEGURI_LLM_BASE_URL } else { 'https://api.deepseek.com' }
}
if (-not $ContentLlmModel) {
    $ContentLlmModel = if ($env:MEGURI_LLM_MODEL) { $env:MEGURI_LLM_MODEL } else { 'deepseek-v4-flash' }
}
if (-not $ContentLlmKeyFile) {
    # Reuse external key files; never copy keys into arguments, logs or reports.
    # The established default key belongs only to the established DeepSeek endpoint.
    if ($env:MEGURI_LLM_API_KEY_FILE) {
        $ContentLlmKeyFile = $env:MEGURI_LLM_API_KEY_FILE
    } elseif ($ContentLlmBaseUrl.TrimEnd('/') -in @('https://api.deepseek.com', 'https://api.deepseek.com/v1')) {
        foreach ($candidateKeyFile in @('D:\environment\secrets\meguri-deepseek-api-key.txt', 'D:\environment\secrets\meguri\deepseek-api-key.txt')) {
            if (Test-Path -LiteralPath $candidateKeyFile -PathType Leaf) {
                $ContentLlmKeyFile = $candidateKeyFile
                break
            }
        }
    }
}
$privateRoot = Join-Path $HOME '.meguri\bilibili'
$stateFile = Join-Path $privateRoot 'state\daily-sync-latest.json'
$taskStateFile = Join-Path $privateRoot 'state\meguri-daily-latest.json'
$tokenFile = Join-Path $privateRoot 'mcp-token.txt'
$logDirectory = Join-Path $privateRoot 'logs\meguri-daily'
$mcpUrl = 'http://127.0.0.1:8899/mcp/'
$healthUrl = 'http://127.0.0.1:8899/health'

$preview = [ordered]@{
    target_date = $TargetDate
    timezone = 'Asia/Shanghai'
    random_window = '02:20-02:40'
    hard_deadline = $hardDeadline.ToString('o')
    bhf_root = $BhfRoot
    bhf_python = $BhfPython
    report_python = $ReportPython
    mcp_url = $mcpUrl
    token_file = $tokenFile
    sync_state_file = $stateFile
    task_state_file = $taskStateFile
    sync_cutoff = $syncDeadline.ToString('o')
    service_cutoff = $serviceDeadline.ToString('o')
    report_cutoff = $reportDeadline.ToString('o')
    core_url = $CoreUrl
    core_token_file = $CoreTokenFile
    skip_remote_publish = [bool]$SkipRemotePublish
    content_enabled = -not [bool]$MetadataOnly
    content_max_videos = $ContentMaxVideos
    content_llm_model = $ContentLlmModel
}
if ($DryRun) {
    $preview | ConvertTo-Json -Depth 3
    return
}

foreach ($required in @($BhfPython, $ReportPython, $syncScript, $bootstrapScript, $serverScript, $reportScript, $publisherScript)) {
    if ([string]::IsNullOrWhiteSpace($required) -or -not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required Bilibili daily file is missing: $required"
    }
}

$mutex = [Threading.Mutex]::new($false, 'Local\MeguriBilibiliDaily')
if (-not $mutex.WaitOne(0)) {
    $mutex.Dispose()
    return
}

function Invoke-BoundedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][datetime]$StopAt,
        [Parameter(Mandatory = $true)][string]$LogPrefix
    )

    $stdout = Join-Path $logDirectory ($LogPrefix + '.stdout.log')
    $stderr = Join-Path $logDirectory ($LogPrefix + '.stderr.log')
    $quotedArguments = foreach ($argument in $ArgumentList) {
        if ($argument.Contains('"')) {
            throw 'Process arguments may not contain quote characters.'
        }
        if ($argument -match '\s') { '"' + $argument + '"' } else { $argument }
    }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = $quotedArguments -join ' '
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $startInfo.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Failed to start bounded process: $FilePath"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $timedOut = $false
    while (-not $process.WaitForExit(500)) {
        if ((Get-Date) -ge $StopAt) {
            $process.Kill()
            $null = $process.WaitForExit()
            $timedOut = $true
            break
        }
    }
    $null = $process.WaitForExit()
    $stdoutText = $stdoutTask.GetAwaiter().GetResult()
    $stderrText = $stderrTask.GetAwaiter().GetResult()
    [IO.File]::WriteAllText($stdout, $stdoutText, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($stderr, $stderrText, [Text.UTF8Encoding]::new($false))
    $exitCode = if ($timedOut) { 124 } else { [int]$process.ExitCode }
    $process.Dispose()
    return $exitCode
}

function Test-BhfHealth {
    try {
        $response = Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 2
        return $response.status -eq 'running'
    } catch {
        return $false
    }
}

function Write-ForcedSyncState {
    param([string]$Status, [string]$Message)
    $directory = Split-Path $stateFile
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $value = [ordered]@{
        schema_version = 1
        task = 'bilibili_watch_history_sync'
        target_date = $TargetDate
        status = $Status
        finished_at = (Get-Date).ToString('o')
        message = $Message
    } | ConvertTo-Json -Depth 3
    [IO.File]::WriteAllText($stateFile, $value + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

$startedServer = $null
$bootstrapExitCode = 1
$syncExitCode = 1
$reportExitCode = 2
$reportDataSource = 'unavailable'
$reportSyncStatus = 'unavailable'
$reportStatus = 'unavailable'
$publishExitCode = 2
$publishStatus = 'not_attempted'
$contentStatus = if ($MetadataOnly) { 'disabled' } else { 'not_attempted' }
$pipelineError = $null
try {
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

    $bootstrapExitCode = Invoke-BoundedProcess `
        -FilePath $BhfPython `
        -ArgumentList @($bootstrapScript) `
        -WorkingDirectory $BhfRoot `
        -StopAt $syncDeadline `
        -LogPrefix 'bootstrap'
    if ($bootstrapExitCode -ne 0) {
        Write-ForcedSyncState -Status 'error' -Message "Private BHF bootstrap failed with exit code $bootstrapExitCode."
    } else {
        $syncExitCode = Invoke-BoundedProcess `
            -FilePath $BhfPython `
            -ArgumentList @($syncScript, '--target-date', $TargetDate) `
            -WorkingDirectory $BhfRoot `
            -StopAt $syncDeadline `
            -LogPrefix ('sync-' + $TargetDate)
        if ($syncExitCode -eq 124) {
            Write-ForcedSyncState -Status 'error' -Message 'Watch-history synchronization reached the 02:50 safety cutoff.'
        }
    }

    if (-not (Test-BhfHealth)) {
        $serverStdout = Join-Path $logDirectory 'server.stdout.log'
        $serverStderr = Join-Path $logDirectory 'server.stderr.log'
        Remove-Item -LiteralPath $serverStdout, $serverStderr -Force -ErrorAction SilentlyContinue
        $startedServer = Start-Process `
            -FilePath $BhfPython `
            -ArgumentList @($serverScript) `
            -WorkingDirectory $BhfRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput $serverStdout `
            -RedirectStandardError $serverStderr `
            -PassThru
        while (-not (Test-BhfHealth) -and (Get-Date) -lt $serviceDeadline) {
            if ($startedServer.HasExited) { break }
            Start-Sleep -Milliseconds 500
        }
    }

    $reportArguments = @(
        $reportScript,
        '--project-root', $MeguriRoot,
        '--date', $TargetDate,
        '--timezone', 'Asia/Shanghai',
        '--mcp-url', $mcpUrl,
        '--mcp-token-file', $tokenFile,
        '--mcp-timeout-seconds', '2',
        '--sync-status-file', $stateFile
    )
    $reportExitCode = Invoke-BoundedProcess `
        -FilePath $ReportPython `
        -ArgumentList $reportArguments `
        -WorkingDirectory $MeguriRoot `
        -StopAt $reportDeadline `
        -LogPrefix ('report-' + $TargetDate)
    if ($reportExitCode -eq 0 -or $reportExitCode -eq 2) {
        $reportStdout = Join-Path $logDirectory ('report-' + $TargetDate + '.stdout.log')
        try {
            $reportResult = Get-Content -Raw -Encoding UTF8 -LiteralPath $reportStdout | ConvertFrom-Json
            $reportDataSource = [string]$reportResult.data_source
            $reportSyncStatus = [string]$reportResult.sync_status
            $reportStatus = [string]$reportResult.status
            if ($reportExitCode -eq 0 -and $reportStatus -in @('ready', 'empty')) {
                if (-not $MetadataOnly -and (Test-Path -LiteralPath $contentScript -PathType Leaf)) {
                    try {
                        $contentReportJson = Join-Path $logDirectory ('content-report-' + $TargetDate + '.json')
                        $contentArguments = @(
                            $contentScript, '--project-root', $MeguriRoot,
                            '--report-json', $reportStdout, '--output-json', $contentReportJson,
                            '--max-videos', [string]$ContentMaxVideos,
                            '--budget-seconds', '100',
                            '--llm-base-url', $ContentLlmBaseUrl, '--llm-model', $ContentLlmModel
                        )
                        if ($ContentLlmKeyFile) { $contentArguments += @('--llm-key-file', $ContentLlmKeyFile) }
                        $contentStopAt = (Get-Date).AddSeconds(110)
                        if ($contentStopAt -gt $reportDeadline) { $contentStopAt = $reportDeadline }
                        if ((Get-Date).AddSeconds(5) -lt $contentStopAt) {
                            $contentExitCode = Invoke-BoundedProcess `
                                -FilePath $ReportPython -ArgumentList $contentArguments `
                                -WorkingDirectory $MeguriRoot -StopAt $contentStopAt `
                                -LogPrefix ('content-' + $TargetDate)
                            if ($contentExitCode -eq 0) {
                                $contentResult = Get-Content -Raw -Encoding UTF8 -LiteralPath $contentReportJson | ConvertFrom-Json
                                $contentStatus = [string]$contentResult.content_analysis.status
                                $reportStdout = $contentReportJson
                            } else { $contentStatus = 'failed_metadata_retained' }
                        } else { $contentStatus = 'skipped_deadline' }
                    } catch { $contentStatus = 'failed_metadata_retained' }
                } elseif (-not $MetadataOnly) { $contentStatus = 'script_missing' }
                $reportPath = Join-Path $MeguriRoot ('reports\daily\bilibili-' + $TargetDate + '.md')
                $localNotice = Join-Path $MeguriRoot 'reports\daily\latest.json'
                $publishArguments = @(
                    $publisherScript,
                    '--kind', 'bilibili',
                    '--report-json', $reportStdout,
                    '--markdown', $reportPath,
                    '--local-notice', $localNotice,
                    '--core-url', $CoreUrl,
                    '--timeout-seconds', '10'
                )
                if ($SkipRemotePublish) {
                    $publishArguments += '--skip-upload'
                } else {
                    $publishArguments += @('--token-file', $CoreTokenFile)
                }
                $publishExitCode = Invoke-BoundedProcess `
                    -FilePath $ReportPython `
                    -ArgumentList $publishArguments `
                    -WorkingDirectory $MeguriRoot `
                    -StopAt $hardDeadline `
                    -LogPrefix ('publish-' + $TargetDate)
                $publishStatus = if ($publishExitCode -eq 0) { 'published' } else { 'degraded' }
            }
        } catch {
            $reportExitCode = 2
        }
    }
} catch {
    $pipelineError = $_.Exception
} finally {
    if ($null -ne $startedServer -and -not $startedServer.HasExited) {
        Stop-Process -Id $startedServer.Id -Force -ErrorAction SilentlyContinue
        $null = $startedServer.WaitForExit()
    }
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}

$finalStatus = 'success'
$finalExitCode = 0
if ($null -ne $pipelineError) {
    $finalStatus = 'error'
    $finalExitCode = 1
} elseif ($reportExitCode -ne 0) {
    $finalStatus = 'error'
    $finalExitCode = $reportExitCode
} elseif ($syncExitCode -eq 75) {
    $finalStatus = 'risk_stop'
    $finalExitCode = 75
} elseif ($syncExitCode -ne 0) {
    $finalStatus = 'error'
    $finalExitCode = $syncExitCode
} elseif ($reportDataSource -ne 'account_mcp') {
    $finalStatus = 'degraded'
    $finalExitCode = 3
} elseif ($publishExitCode -ne 0) {
    $finalStatus = 'degraded'
    $finalExitCode = 3
}

$reportPath = Join-Path $MeguriRoot ('reports\daily\bilibili-' + $TargetDate + '.md')
$taskState = [ordered]@{
    schema_version = 1
    task = 'meguri_bilibili_daily'
    target_date = $TargetDate
    status = $finalStatus
    finished_at = (Get-Date).ToString('o')
    bootstrap_exit_code = $bootstrapExitCode
    sync_exit_code = $syncExitCode
    report_exit_code = $reportExitCode
    report_data_source = $reportDataSource
    report_sync_status = $reportSyncStatus
    report_status = $reportStatus
    publish_status = $publishStatus
    content_status = $contentStatus
    publish_exit_code = $publishExitCode
    report_path = $reportPath
    report_exists = Test-Path -LiteralPath $reportPath -PathType Leaf
    pipeline_error_type = if ($null -eq $pipelineError) { $null } else { $pipelineError.GetType().Name }
    exit_code = [int]$finalExitCode
}
New-Item -ItemType Directory -Path (Split-Path $taskStateFile) -Force | Out-Null
[IO.File]::WriteAllText(
    $taskStateFile,
    ($taskState | ConvertTo-Json -Depth 4) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)
exit ([int]$finalExitCode)
