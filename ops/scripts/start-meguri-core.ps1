#requires -Version 5
$ErrorActionPreference = 'Stop'
try { chcp 65001 > $null; [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

# ================= 按你的环境固定的路径（如有变动改这里）=================
$JavaHome = 'D:\environment\jdk\temurin-21\jdk-21.0.11+10'
$MavenBin = 'D:\environment\maven\runtime\apache-maven-3.9.16\bin'
$CoreDir  = 'D:\program\meguri-pet\java\meguri-core'
$EsPath   = 'D:\Program Files\Everything\es.exe'
$LlmKeyFile = 'D:\environment\secrets\meguri-deepseek-api-key.txt'
# =======================================================================

. (Join-Path $PSScriptRoot 'multimodal-relay-config.ps1')

function Main {
    # 关键：强制这个 core 跑在 Windows 上，并明确指向正确的 es.exe
    $env:MEGURI_EVERYTHING_ENABLED = 'true'
    $env:MEGURI_EVERYTHING_ES_PATH = $EsPath
    # Local development rollout for the bounded ReAct path. Application
    # defaults remain disabled; other deployment entry points must opt in.
    $env:MEGURI_EXECUTION_MODE_ENABLED = 'true'
    $env:MEGURI_LIMITED_REACT_ENABLED = 'true'
    $env:MEGURI_SHIZUKI_ENABLED = 'true'
    $configuredScopes = @($env:MEGURI_CAPABILITY_SCOPES -split ',' |
        ForEach-Object { $_.Trim() } | Where-Object { $_ })
    # This explicit local launcher is the administrative desktop boundary.
    # Hosted deployments must grant these scopes through server policy instead.
    $env:MEGURI_CAPABILITY_SCOPES = (@($configuredScopes + 'skill:read' + 'skill:manage' + 'shizuki:control') |
        Select-Object -Unique) -join ','
    # The key remains outside the repository and is read only by the trusted
    # local Core process. Renderer and gateway processes never receive it.
    if (Test-Path -LiteralPath $LlmKeyFile) {
        $env:MEGURI_LLM_PROVIDER = 'openai-compatible'
        $env:MEGURI_LLM_BASE_URL = 'https://api.deepseek.com'
        $env:MEGURI_LLM_MODEL = 'deepseek-v4-flash'
        $env:MEGURI_LLM_API_KEY_FILE = $LlmKeyFile
        $env:MEGURI_LLM_RESPONSE_FORMAT = 'json_object'
        $env:MEGURI_LLM_THINKING = 'disabled'
        # DeepSeek V4 is text-only. A separate OpenAI-compatible vision route
        # may be inherited explicitly before images or PDFs are submitted.
        $env:MEGURI_MULTIMODAL_MAX_ATTACHMENT_BYTES = '5242880'
        $env:MEGURI_MULTIMODAL_MAX_TOTAL_BYTES = '10485760'
    }
    $relayStatus = Set-MeguriMultimodalRelayEnvironment
    if ($relayStatus.State -eq 'enabled') {
        Write-Host "[视觉] $($relayStatus.Detail)" -ForegroundColor Green
    }
    elseif ($relayStatus.State -notin @('missing', 'disabled')) {
        Write-Host "[视觉] $($relayStatus.Detail)" -ForegroundColor Yellow
    }
    $env:JAVA_HOME = $JavaHome
    $env:Path      = "$JavaHome\bin;$MavenBin;$env:Path"

    # Keep the JDK's automatically bound Unix-domain socket path short on Windows.
    $socketTmpDir = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\tmp\jdk-unix-sockets'))
    New-Item -ItemType Directory -Force -Path $socketTmpDir | Out-Null
    $socketOption = "-Djdk.net.unixdomain.tmpdir=$socketTmpDir"
    $javaToolOptions = [regex]::Replace(
        [string]$env:JAVA_TOOL_OPTIONS,
        '(?<!\S)-Djdk\.net\.unixdomain\.tmpdir=(?:"[^"]*"|\S+)',
        ''
    ).Trim()
    $env:JAVA_TOOL_OPTIONS = (@($javaToolOptions, $socketOption) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join ' '

    Write-Host "=== Meguri Core 启动器 (端口 18080) ===" -ForegroundColor Cyan
    Write-Host "JAVA_HOME : $JavaHome"
    Write-Host "JDK socket tmp : $socketTmpDir"
    Write-Host "es.exe    : $EsPath"
    Write-Host ""

    # ---- 前置检查 ----
    if (-not (Test-Path "$JavaHome\bin\java.exe")) {
        Write-Host "[错误] 找不到 java：$JavaHome\bin\java.exe，请检查 JDK 路径。" -ForegroundColor Red; return
    }
    if (-not (Test-Path "$MavenBin\mvn.cmd")) {
        Write-Host "[错误] 找不到 maven：$MavenBin\mvn.cmd，请检查 Maven 路径。" -ForegroundColor Red; return
    }
    if (-not (Test-Path "$CoreDir\pom.xml")) {
        Write-Host "[错误] 找不到工程：$CoreDir" -ForegroundColor Red; return
    }
    if (-not (Test-Path $EsPath)) {
        Write-Host "[警告] 没找到 es.exe：$EsPath —— @文件搜索会不可用，但 core 仍会启动。" -ForegroundColor Yellow
    }

    # ---- 端口 18080 占用检查（避免和旧 / WSL / Docker 里的 core 冲突）----
    $listen = Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listen) {
        $op = Get-Process -Id $listen.OwningProcess -ErrorAction SilentlyContinue
        Write-Host "[提示] 端口 18080 已被占用：PID=$($listen.OwningProcess) 进程=$($op.ProcessName)" -ForegroundColor Yellow
        if ($op -and $op.ProcessName -eq 'java') {
            $ans = Read-Host "这看起来是旧的 core。结束它并重启？(Y/N)"
            if ($ans -match '^[Yy]') { Stop-Process -Id $op.Id -Force; Start-Sleep -Seconds 2 }
            else { Write-Host "已取消，未做改动。" -ForegroundColor Yellow; return }
        }
        else {
            Write-Host "这不是 Windows 原生的 java 进程——很可能你的 core 现在跑在 WSL/Docker 里。" -ForegroundColor Yellow
            Write-Host "这正是 @搜索 报错的根因：Linux 下用不了 Windows 的 es.exe。" -ForegroundColor Yellow
            Write-Host "请先去 WSL/Docker 那边停掉旧 core，再回来双击本脚本用 Windows 原生方式启动。" -ForegroundColor Yellow
            return
        }
    }

    # ---- 启动（从源码编译并运行，杜绝旧 jar 带来的问题）----
    Set-Location $CoreDir
    Write-Host ""
    Write-Host "正在用 mvn spring-boot:run 启动（首次会编译，请稍等十几秒）..." -ForegroundColor Green
    Write-Host "启动后请保持本窗口开着；要停止按 Ctrl+C 或直接关闭窗口。" -ForegroundColor Green
    Write-Host ""
    & "$MavenBin\mvn.cmd" -B spring-boot:run
}

try { Main }
catch { Write-Host "[异常] $($_.Exception.Message)" -ForegroundColor Red }
finally { Write-Host ""; Read-Host "按回车关闭本窗口" | Out-Null }
