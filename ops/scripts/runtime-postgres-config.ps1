# Preserve UTF-8 BOM: the desktop launcher uses Windows PowerShell 5.1.
function Set-MeguriTurnRuntimeEnvironment {
    param([string]$DatabaseSecretFile)

    $configuredFile = if ($DatabaseSecretFile) { $DatabaseSecretFile }
        elseif ($env:MEGURI_RUNTIME_DATABASE_URL_FILE) { $env:MEGURI_RUNTIME_DATABASE_URL_FILE }
        else { 'D:\environment\secrets\meguri\turn-runtime-database-url.txt' }
    if ($env:MEGURI_TURN_JOURNAL_MODE -and $env:MEGURI_TURN_JOURNAL_MODE -notin @('postgres', 'in-memory')) {
        throw 'Turn runtime mode is invalid.'
    }
    if ($env:MEGURI_TURN_JOURNAL_MODE -eq 'in-memory') {
        foreach ($name in @('MEGURI_POSTGRES_URL', 'MEGURI_POSTGRES_USER', 'MEGURI_POSTGRES_PASSWORD')) {
            [Environment]::SetEnvironmentVariable($name, $null)
        }
        $profiles = @($env:SPRING_PROFILES_ACTIVE -split ',' | ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and $_ -notin @('postgres', 'local-mock') })
        $env:SPRING_PROFILES_ACTIVE = (@($profiles + 'local-mock') | Select-Object -Unique) -join ','
        $env:MEGURI_BACKGROUND_WORKERS_MEMORY_STORE_MODE = 'in-memory'
        return [pscustomobject]@{ State = 'in-memory'; Detail = '已明确选择内存 Turn 模式。' }
    }
    $requested = $env:MEGURI_TURN_JOURNAL_MODE -eq 'postgres'
    $available = Test-Path -LiteralPath $configuredFile -PathType Leaf
    if (-not $requested -and -not $available) {
        return [pscustomobject]@{ State = 'in-memory'; Detail = '未配置专用 Turn 数据库；当前会话状态不能通过 Core 重启恢复。' }
    }
    try {
        if (-not [IO.Path]::IsPathRooted($configuredFile) -or -not $available) { throw 'invalid' }
        $secretPath = [IO.Path]::GetFullPath($configuredFile)
        # Memory credentials are a separate authorization boundary, including the migration role.
        if ([IO.Path]::GetFileName($secretPath) -in @('memory-database-url.txt', 'memory-migration-database-url.txt')) { throw 'invalid' }
        if ($env:MEGURI_DATABASE_URL_FILE -and $secretPath -eq [IO.Path]::GetFullPath($env:MEGURI_DATABASE_URL_FILE)) { throw 'invalid' }
        $connectionText = [IO.File]::ReadAllText($secretPath).Trim()
        $uri = [Uri]$connectionText
        if ($uri.Scheme -notin @('postgres', 'postgresql') -or -not $uri.Host -or
            $uri.AbsolutePath -notmatch '^/[A-Za-z0-9_-]+$' -or -not $uri.UserInfo.Contains(':')) { throw 'invalid' }
        # Select the same bounded Split overload on .NET Framework and modern .NET.
        $parts = $uri.UserInfo.Split([char[]]@(':'), 2, [StringSplitOptions]::None)
        $username = [Uri]::UnescapeDataString($parts[0])
        $password = [Uri]::UnescapeDataString($parts[1])
        if (-not $username -or -not $password) { throw 'invalid' }
        $port = if ($uri.Port -gt 0) { $uri.Port } else { 5432 }
        $hostPart = if ($uri.Host.Contains(':')) { "[$($uri.Host)]" } else { $uri.Host }
        # Restrict JDBC options; identity and password must remain separate from the URL.
        $query = $uri.Query.TrimStart('?')
        if ($query -and $query -notmatch '^(sslmode=(disable|allow|prefer|require|verify-ca|verify-full))$') { throw 'invalid' }
        $env:MEGURI_POSTGRES_URL = "jdbc:postgresql://${hostPart}:$port$($uri.AbsolutePath)$($uri.Query)"
        $env:MEGURI_POSTGRES_USER = $username
        $env:MEGURI_POSTGRES_PASSWORD = $password
        $env:MEGURI_RUNTIME_DATABASE_URL_FILE = $secretPath
        $env:MEGURI_TURN_JOURNAL_MODE = 'postgres'
        # Explicit profile selection prevents local-mock from excluding the datasource.
        $profiles = @($env:SPRING_PROFILES_ACTIVE -split ',' | ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and $_ -notin @('local-mock', 'postgres') })
        $env:SPRING_PROFILES_ACTIVE = (@($profiles + 'postgres') | Select-Object -Unique) -join ','
        $env:MEGURI_BACKGROUND_WORKERS_MEMORY_STORE_MODE = 'postgres'
        return [pscustomobject]@{ State = 'postgres'; Detail = '已选择 PostgreSQL Turn、事件、会话和任务存储；数据库不可用时启动将失败。' }
    } catch {
        # URI and driver errors may contain credentials. Keep the diagnostic content-free.
        throw 'Dedicated Turn runtime PostgreSQL configuration is missing or invalid.'
    }
}
