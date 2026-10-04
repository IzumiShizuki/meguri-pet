#requires -Version 5
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'multimodal-relay-config.ps1')

function Assert-Equal($Expected, $Actual, [string]$Label) {
    if ($Expected -ne $Actual) {
        throw "$Label expected '$Expected' but received '$Actual'."
    }
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("meguri-relay-contract-{0}" -f [Guid]::NewGuid().ToString('N'))
$resolvedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
if (-not $resolvedTestRoot.StartsWith($resolvedTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create relay contract fixtures outside the system temp directory.'
}

try {
    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    $configPath = Join-Path $testRoot 'relay.json'
    $secretPath = Join-Path $testRoot 'relay.key'

    Assert-Equal 'missing' (Set-MeguriMultimodalRelayEnvironment -ConfigPath $configPath -SecretPath $secretPath).State 'missing config'

    @{ version = 1; enabled = $false; baseUrl = ''; model = '' } |
        ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    Assert-Equal 'disabled' (Set-MeguriMultimodalRelayEnvironment -ConfigPath $configPath -SecretPath $secretPath).State 'disabled config'

    @{ version = 1; enabled = $true; baseUrl = 'https://relay.example/v1'; model = '' } |
        ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    Assert-Equal 'incomplete' (Set-MeguriMultimodalRelayEnvironment -ConfigPath $configPath -SecretPath $secretPath).State 'incomplete config'

    @{ version = 1; enabled = $true; baseUrl = 'https://relay.example/v1'; model = 'qwen3.7-flash' } |
        ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    Assert-Equal 'secret-missing' (Set-MeguriMultimodalRelayEnvironment -ConfigPath $configPath -SecretPath $secretPath).State 'missing secret'

    Set-Content -LiteralPath $secretPath -Value 'test-secret' -Encoding UTF8
    $enabled = Set-MeguriMultimodalRelayEnvironment -ConfigPath $configPath -SecretPath $secretPath
    Assert-Equal 'enabled' $enabled.State 'enabled config'
    Assert-Equal 'qwen3.7-flash' $env:MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL 'fallback model'
    Assert-Equal 'https://relay.example/v1' $env:MEGURI_LLM_MULTIMODAL_FALLBACK_BASE_URL 'fallback URL'
    Assert-Equal $secretPath $env:MEGURI_LLM_MULTIMODAL_FALLBACK_API_KEY_FILE 'fallback secret path'
    Assert-Equal 'disabled' $env:MEGURI_LLM_MULTIMODAL_FALLBACK_THINKING 'fallback thinking'

    Write-Output '{"ok":true,"states":["missing","disabled","incomplete","secret-missing","enabled"]}'
}
finally {
    Clear-MeguriMultimodalRelayEnvironment
    if (Test-Path -LiteralPath $resolvedTestRoot) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
