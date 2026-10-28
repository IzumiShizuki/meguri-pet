$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-postgres-config.ps1')
$variables = @('MEGURI_RUNTIME_DATABASE_URL_FILE','MEGURI_DATABASE_URL_FILE','MEGURI_TURN_JOURNAL_MODE',
    'MEGURI_POSTGRES_URL','MEGURI_POSTGRES_USER','MEGURI_POSTGRES_PASSWORD','SPRING_PROFILES_ACTIVE',
    'MEGURI_BACKGROUND_WORKERS_MEMORY_STORE_MODE')
$before = @{}
foreach ($name in $variables) { $before[$name] = [Environment]::GetEnvironmentVariable($name) }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('meguri-runtime-config-' + [Guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $null) }
    $missing = Join-Path $testRoot 'missing.txt'
    if ((Set-MeguriTurnRuntimeEnvironment -DatabaseSecretFile $missing).State -ne 'in-memory') { throw 'offline selection failed' }
    $env:MEGURI_TURN_JOURNAL_MODE = 'typo'
    try { Set-MeguriTurnRuntimeEnvironment -DatabaseSecretFile $missing | Out-Null; throw 'expected rejection mode' }
    catch { if ($_.Exception.Message -ne 'Turn runtime mode is invalid.') { throw } }
    $env:MEGURI_TURN_JOURNAL_MODE = 'postgres'
    try { Set-MeguriTurnRuntimeEnvironment -DatabaseSecretFile $missing | Out-Null; throw 'expected rejection missing' }
    catch { if ($_.Exception.Message -ne 'Dedicated Turn runtime PostgreSQL configuration is missing or invalid.') { throw } }
    $secret = Join-Path $testRoot 'runtime.txt'
    [IO.File]::WriteAllText($secret, 'postgresql://fixture:fixture%40password@127.0.0.1:15432/runtime_fixture?sslmode=disable')
    $env:SPRING_PROFILES_ACTIVE = 'local-mock'
    $status = Set-MeguriTurnRuntimeEnvironment -DatabaseSecretFile $secret
    if ($status.State -ne 'postgres' -or $env:SPRING_PROFILES_ACTIVE -ne 'postgres' -or
        $env:MEGURI_POSTGRES_PASSWORD -ne 'fixture@password' -or
        $env:MEGURI_POSTGRES_URL -ne 'jdbc:postgresql://127.0.0.1:15432/runtime_fixture?sslmode=disable') { throw 'durable selection failed' }
    $memory = Join-Path $testRoot 'memory-database-url.txt'
    [IO.File]::WriteAllText($memory, 'postgresql://fixture:fixture@127.0.0.1/runtime_fixture')
    try { Set-MeguriTurnRuntimeEnvironment -DatabaseSecretFile $memory | Out-Null; throw 'expected rejection memory' }
    catch { if ($_.Exception.Message -ne 'Dedicated Turn runtime PostgreSQL configuration is missing or invalid.') { throw } }
    $env:MEGURI_TURN_JOURNAL_MODE = 'in-memory'
    if ((Set-MeguriTurnRuntimeEnvironment -DatabaseSecretFile $secret).State -ne 'in-memory' -or
        $env:SPRING_PROFILES_ACTIVE -ne 'local-mock' -or $env:MEGURI_POSTGRES_PASSWORD -or
        $env:MEGURI_BACKGROUND_WORKERS_MEMORY_STORE_MODE -ne 'in-memory') { throw 'explicit rollback failed' }
    Write-Output 'PASS: offline selection, invalid mode, explicit fail-closed, dedicated secret, profile wiring and explicit rollback.'
} finally {
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $before[$name]) }
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $expected = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($expected, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved).StartsWith('meguri-runtime-config-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
