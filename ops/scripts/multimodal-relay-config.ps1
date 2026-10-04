#requires -Version 5

function Clear-MeguriMultimodalRelayEnvironment {
    foreach ($name in @(
        'MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL',
        'MEGURI_LLM_MULTIMODAL_FALLBACK_BASE_URL',
        'MEGURI_LLM_MULTIMODAL_FALLBACK_API_KEY_FILE',
        'MEGURI_LLM_MULTIMODAL_FALLBACK_THINKING'
    )) {
        Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    }
}

function Set-MeguriMultimodalRelayEnvironment {
    [CmdletBinding()]
    param(
        [string]$ConfigPath = $(if ($env:MEGURI_MULTIMODAL_RELAY_CONFIG_FILE) {
            $env:MEGURI_MULTIMODAL_RELAY_CONFIG_FILE
        } else {
            'D:\environment\config\meguri\multimodal-relay.json'
        }),
        [string]$SecretPath = $(if ($env:MEGURI_MULTIMODAL_RELAY_SECRET_FILE) {
            $env:MEGURI_MULTIMODAL_RELAY_SECRET_FILE
        } else {
            'D:\environment\secrets\meguri\multimodal-relay-api-key.txt'
        })
    )

    Clear-MeguriMultimodalRelayEnvironment

    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        return [pscustomobject]@{ State = 'missing'; Detail = 'No multimodal relay configuration is saved.' }
    }

    try {
        $config = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
    }
    catch {
        return [pscustomobject]@{ State = 'invalid'; Detail = 'The multimodal relay configuration is not valid JSON.' }
    }

    if ($config.enabled -ne $true) {
        return [pscustomobject]@{ State = 'disabled'; Detail = 'The multimodal relay is disabled.' }
    }

    $baseUrl = [string]$config.baseUrl
    $model = [string]$config.model
    $baseUrl = $baseUrl.Trim().TrimEnd('/')
    $model = $model.Trim()
    if (-not $baseUrl -or -not $model -or $model -notmatch '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$') {
        return [pscustomobject]@{ State = 'incomplete'; Detail = 'The multimodal relay is missing a valid Base URL or model ID.' }
    }

    try {
        $uri = [Uri]$baseUrl
    }
    catch {
        return [pscustomobject]@{ State = 'invalid'; Detail = 'The multimodal relay Base URL is invalid.' }
    }
    $loopback = $uri.IsLoopback -or $uri.Host -in @('127.0.0.1', 'localhost', '::1')
    if ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $loopback)) {
        return [pscustomobject]@{ State = 'invalid'; Detail = 'Remote multimodal relay URLs must use HTTPS.' }
    }
    if ($uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        return [pscustomobject]@{ State = 'invalid'; Detail = 'The multimodal relay Base URL contains forbidden credentials or parameters.' }
    }
    if (-not (Test-Path -LiteralPath $SecretPath -PathType Leaf)) {
        return [pscustomobject]@{ State = 'secret-missing'; Detail = 'The multimodal relay is enabled but its API Key file is missing.' }
    }

    $env:MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL = $model
    $env:MEGURI_LLM_MULTIMODAL_FALLBACK_BASE_URL = $baseUrl
    $env:MEGURI_LLM_MULTIMODAL_FALLBACK_API_KEY_FILE = $SecretPath
    $env:MEGURI_LLM_MULTIMODAL_FALLBACK_THINKING = 'disabled'
    return [pscustomobject]@{ State = 'enabled'; Detail = "Multimodal relay enabled: $model"; Model = $model }
}
