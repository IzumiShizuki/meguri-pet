[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceCsv
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = (Resolve-Path -LiteralPath $SourceCsv).Path
$matches = @([regex]::Matches(
    [System.IO.File]::ReadAllText($source, [System.Text.Encoding]::UTF8),
    '\bsk-[A-Za-z0-9._-]+\b'
) | ForEach-Object Value | Select-Object -Unique)
if (@($matches).Count -ne 1) {
    throw "Expected exactly one DashScope API key in the CSV; found $(@($matches).Count)."
}

$secretDirectory = Join-Path $repoRoot 'ops\secrets'
$secretFile = Join-Path $secretDirectory 'dashscope-api-key.txt'
$envFile = Join-Path $repoRoot 'ops\env\dashscope.local.env'
New-Item -ItemType Directory -Force -Path $secretDirectory | Out-Null
[System.IO.File]::WriteAllText(
    $secretFile,
    ($matches[0] + [Environment]::NewLine),
    [System.Text.UTF8Encoding]::new($false)
)

# Restrict the local copy to this Windows account.  Failure is explicit rather
# than silently leaving a world-readable credential file behind.
$currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $secretFile /inheritance:r /grant:r "$currentUser`:(R,W)" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to set restrictive ACL on DashScope API key file.'
}

$content = @(
    "MEGURI_DASHSCOPE_API_KEY_FILE=$secretFile",
    'MEGURI_EMBEDDING_BACKEND=dashscope',
    'MEGURI_EMBEDDING_MODEL=text-embedding-v4',
    'MEGURI_EMBEDDING_DIMENSION=2048',
    'MEGURI_EMBEDDING_MODEL_REVISION=dashscope-text-embedding-v4-2048-r20260725',
    'MEGURI_EMBEDDING_TIMEOUT_SECONDS=20',
    'MEGURI_DASHSCOPE_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1',
    'MEGURI_RERANK_BACKEND=dashscope',
    'MEGURI_RERANK_MODEL=qwen3-rerank',
    'MEGURI_RERANK_TIMEOUT_SECONDS=12',
    'MEGURI_DASHSCOPE_RERANK_ENDPOINT=https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank',
    'MEGURI_RAG_BACKEND=dashscope',
    'MEGURI_RAG_VECTOR_ARTIFACT=dashscope_vectors.json',
    'MEGURI_RAG_CANDIDATE_LIMIT=20'
) -join [Environment]::NewLine
[System.IO.File]::WriteAllText($envFile, ($content + [Environment]::NewLine), [System.Text.UTF8Encoding]::new($false))

Write-Output ('Configured DashScope retrieval secrets at {0}; key value was not displayed.' -f $secretFile)
