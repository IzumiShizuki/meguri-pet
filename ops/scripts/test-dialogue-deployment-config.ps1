$ErrorActionPreference = 'Stop'
$deploymentScript = Join-Path $PSScriptRoot 'redeploy-meguri-airi.ps1'
$tokens = $null
$parseErrors = $null
$syntax = [System.Management.Automation.Language.Parser]::ParseFile($deploymentScript, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Deployment script syntax is invalid.' }
# Read the actual literal overrides without executing builds, Docker commands,
# loading private env files or restarting services.
$assignment = $syntax.Find({
    param($node)
    $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and
        $node.Left.VariablePath.UserPath -eq 'desiredRemoteEnvironment'
}, $true)
if (-not $assignment) { throw 'Remote capability overrides were not found.' }
$configuration = $assignment.Right.Expression.Child.SafeGetValue()
if ($configuration['MEGURI_WEB_SEARCH_ENABLED'] -ne 'true') { throw 'Remote deployment must enable the requested web lookup capability.' }
if ($configuration['MEGURI_WEB_SEARCH_BASE_URL'] -ne 'https://cn.bing.com/search') { throw 'Remote lookup must use the validated provider endpoint.' }
if ($configuration['MEGURI_INPUT_CHECK_ENABLED'] -ne 'true' -or -not $configuration['MEGURI_INPUT_CHECK_MODEL']) {
    throw 'Remote deployment must activate the separate initial-input checking route.'
}
if ([int]$configuration['MEGURI_INPUT_CHECK_TIMEOUT_MS'] -lt 1500 -or [int]$configuration['MEGURI_INPUT_CHECK_TIMEOUT_MS'] -gt 5000) {
    throw 'Input-check deployment timeout must fit the measured route and its bounded runtime configuration.'
}
if ($configuration.Keys | Where-Object { $_ -match 'API_KEY$|PASSWORD$|TOKEN$' }) {
    throw 'Remote overrides must not contain inline credentials.'
}
Write-Output 'PASS: actual remote deployment overrides enable web/input routes with bounded timeouts and no inline credentials.'
