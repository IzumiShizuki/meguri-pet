function Set-MeguriMemoryRuntimeEnvironment {
    param([string]$DatabaseSecretFile, [string]$BridgeTokenFile, [string]$TenantId)

    $secretRoot = 'D:\environment\secrets\meguri'
    if ($DatabaseSecretFile) { $env:MEGURI_DATABASE_URL_FILE = $DatabaseSecretFile }
    elseif (-not $env:MEGURI_DATABASE_URL_FILE) { $env:MEGURI_DATABASE_URL_FILE = Join-Path $secretRoot 'memory-database-url.txt' }
    if ($BridgeTokenFile) { $env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE = $BridgeTokenFile }
    elseif (-not $env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE) { $env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE = Join-Path $secretRoot 'internal-bridge-token.txt' }
    if ($TenantId) { $env:MEGURI_TENANT_ID = $TenantId }
    if (-not $env:MEGURI_TENANT_ID) { $env:MEGURI_TENANT_ID = 'meguri-local' }

    if ([string]::IsNullOrWhiteSpace($env:MEGURI_MEMORY_BRIDGE_ENABLED)) {
        $secretsPresent = (Test-Path -LiteralPath $env:MEGURI_DATABASE_URL_FILE -PathType Leaf) -and
            (Test-Path -LiteralPath $env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE -PathType Leaf)
        $env:MEGURI_MEMORY_BRIDGE_ENABLED = if ($secretsPresent) { 'true' } else { 'false' }
    }
    if ($env:MEGURI_MEMORY_BRIDGE_ENABLED -ne 'true') { return }

    $defaults = @{
        MEGURI_ENV = 'dev'
        MEGURI_MEMORY_PROVIDER = 'native_pgvector'
        MEGURI_MEMORY_BRIDGE_URL = 'http://127.0.0.1:8000'
        MEGURI_DATABASE_REVISION = '20260729_0007'
        MEGURI_EMBEDDING_MODEL_REVISION = 'dashscope-text-embedding-v4-2048-r20260725'
        MEGURI_EMBEDDING_BACKEND = 'disabled'
        MEGURI_RERANK_BACKEND = 'disabled'
        MEGURI_MUTATION_ALLOWED = 'true'
        MEGURI_DESKTOP_USER_ID = 'local-airi-user'
        MEGURI_FORMAL_MEMORY_ALLOWED_CLIENTS = 'desktop_pet'
    }
    foreach ($entry in $defaults.GetEnumerator()) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($entry.Key))) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value)
        }
    }
    if (-not $env:MEGURI_DESKTOP_TENANT_ID) { $env:MEGURI_DESKTOP_TENANT_ID = $env:MEGURI_TENANT_ID }
    if (-not $env:MEGURI_FORMAL_MEMORY_ALLOWED_USERS) { $env:MEGURI_FORMAL_MEMORY_ALLOWED_USERS = $env:MEGURI_DESKTOP_USER_ID }
}
