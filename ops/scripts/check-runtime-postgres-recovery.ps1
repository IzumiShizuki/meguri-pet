param(
    [Parameter(Mandatory = $true)][string]$DatabaseSecretFile
)
$ErrorActionPreference = 'Stop'
if (-not [IO.Path]::IsPathRooted($DatabaseSecretFile) -or -not (Test-Path -LiteralPath $DatabaseSecretFile -PathType Leaf)) {
    throw 'An absolute, readable PostgreSQL test connection file is required.'
}
try {
    $testConnection = [Uri]([IO.File]::ReadAllText($DatabaseSecretFile).Trim().Replace('postgresql+asyncpg://', 'postgresql://'))
    if ($testConnection.Scheme -notin @('postgres', 'postgresql') -or -not $testConnection.Host -or
        -not $testConnection.UserInfo.Contains(':') -or $testConnection.AbsolutePath -notmatch '^/[A-Za-z0-9_-]+$') {
        throw 'invalid'
    }
} catch {
    throw 'PostgreSQL recovery test connection configuration is invalid.'
}
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$coreDirectory = Join-Path $repositoryRoot 'java/meguri-core'
$temporaryDirectory = Join-Path $repositoryRoot 'tmp'
New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null
$beforeDsnFile = $env:MEGURI_RECOVERY_TEST_DSN_FILE
try {
    . 'D:\environment\activate-dev-env.ps1' 21
    $env:MEGURI_RECOVERY_TEST_DSN_FILE = [IO.Path]::GetFullPath($DatabaseSecretFile)
    Push-Location $coreDirectory
    try {
        & 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd' -q '-Dtest=PostgresRestartRecoveryIntegrationTest,PostgresCoreProcessRestartIntegrationTest' "-DargLine=-Djdk.net.unixdomain.tmpdir=$temporaryDirectory" test
        if ($LASTEXITCODE -ne 0) { throw 'Isolated PostgreSQL recovery verification failed; inspect the test report.' }
    } finally { Pop-Location }
} finally {
    $env:MEGURI_RECOVERY_TEST_DSN_FILE = $beforeDsnFile
}
