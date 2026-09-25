[CmdletBinding()]
param(
    [ValidateRange(1, 10000)]
    [int]$Samples = 100,

    [ValidateRange(1, 1000)]
    [int]$Warmup = 15,

    [ValidateNotNullOrEmpty()]
    [string]$Label = "local-synthetic",

    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$moduleRoot = Join-Path $repositoryRoot "java\meguri-core"
$javaRoot = "D:\environment\jdk\temurin-21\jdk-21.0.11+10"
$maven = "D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd"
$git = "D:\environment\git\PortableGit\bin\git.exe"

foreach ($required in @($moduleRoot, $javaRoot, $maven, $git)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required local dependency is unavailable: $required"
    }
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $safeLabel = $Label -replace '[^A-Za-z0-9._-]', '-'
    $ReportPath = Join-Path $moduleRoot "target\ttft-$safeLabel.json"
} elseif (-not [System.IO.Path]::IsPathRooted($ReportPath)) {
    $ReportPath = Join-Path $repositoryRoot $ReportPath
}

$commit = (& $git -C $repositoryRoot rev-parse HEAD).Trim()
$dirty = [bool](& $git -C $repositoryRoot status --porcelain)
$env:JAVA_HOME = $javaRoot

$mavenArguments = @(
    '-Dtest=SyntheticTurnTtftBenchmark',
    "-Dmeguri.ttft.label=$Label",
    "-Dmeguri.ttft.gitCommit=$commit",
    "-Dmeguri.ttft.dirtyWorktree=$($dirty.ToString().ToLowerInvariant())",
    "-Dmeguri.ttft.samples=$Samples",
    "-Dmeguri.ttft.warmup=$Warmup",
    "-Dmeguri.ttft.report=$ReportPath",
    'test'
)

Push-Location $moduleRoot
try {
    & $maven $mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Synthetic TTFT benchmark failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host "Synthetic-only TTFT report: $ReportPath"
Write-Host "This result excludes real Provider, PostgreSQL, HTTP/SSE socket flush, proxy, and client render latency."
Get-Content -Raw -LiteralPath $ReportPath
