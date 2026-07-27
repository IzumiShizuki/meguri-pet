param(
    [string]$GameDir = '',
    [string]$RepositoryRoot = 'D:\program\meguri-pet',
    [string]$ReleaseId = 'meguri_v2_02c3db0c507d7c2d-gal-v3',
    [string]$OutputRoot = 'D:\program\meguri-pet\output\astrbot-meguri-gal-assets'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Convert-TlgToJpeg {
    param(
        [string]$SourcePath,
        [string]$OutputPath,
        [long]$Quality = 88
    )

    $temporaryPng = [System.IO.Path]::ChangeExtension(
        [System.IO.Path]::GetTempFileName(),
        '.png'
    )
    try {
        $stream = [GameRes.BinaryStream]::FromFile($SourcePath)
        try {
            $tuple = [GameRes.ImageFormat]::FindFormat($stream)
            if ($null -eq $tuple.Item1) {
                throw "Unable to detect image format for '$SourcePath'."
            }
            $stream.Position = 0
            $image = $tuple.Item1.Read($stream, $tuple.Item2)
            $outputStream = [System.IO.File]::Create($temporaryPng)
            try {
                [GameRes.ImageFormat]::Png.Write($outputStream, $image)
            }
            finally {
                $outputStream.Dispose()
            }
        }
        finally {
            $stream.Dispose()
        }

        $bitmap = [System.Drawing.Bitmap]::new($temporaryPng)
        try {
            $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
                Where-Object { $_.MimeType -eq 'image/jpeg' } |
                Select-Object -First 1
            $parameters = [System.Drawing.Imaging.EncoderParameters]::new(1)
            try {
                $parameters.Param[0] = [System.Drawing.Imaging.EncoderParameter]::new(
                    [System.Drawing.Imaging.Encoder]::Quality,
                    $Quality
                )
                $bitmap.Save($OutputPath, $codec, $parameters)
            }
            finally {
                $parameters.Dispose()
            }
        }
        finally {
            $bitmap.Dispose()
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPng) {
            Remove-Item -LiteralPath $temporaryPng -Force
        }
    }
}

Add-Type -Path 'D:\Program Files\GARbro\GameRes.dll'
Add-Type -Path 'D:\Program Files\GARbro\ArcFormats.dll'
Add-Type -AssemblyName System.Drawing

$resolvedGameDir = $GameDir
if (-not $resolvedGameDir) {
    $candidate = Get-ChildItem -LiteralPath 'D:\G' -Directory | Where-Object {
        Test-Path -LiteralPath (Join-Path $_.FullName '_extract_moteyaba\full\jp_base\parts\bg')
    } | Select-Object -First 1
    if ($null -eq $candidate) {
        throw 'Could not locate the unpacked game background directory under D:\G.'
    }
    $resolvedGameDir = $candidate.FullName
}

$spriteSource = Join-Path $RepositoryRoot 'data\meguri\assets\sprites\meguri'
$backgroundSource = Join-Path $resolvedGameDir '_extract_moteyaba\full\jp_base\parts\bg'
$uiSource = Join-Path $resolvedGameDir '_extract_moteyaba\full\jp_base\parts\frame'
$releaseRoot = Join-Path $OutputRoot "meguri_assets\releases\$ReleaseId"
$spriteOutput = Join-Path $releaseRoot 'sprites'
$backgroundOutput = Join-Path $releaseRoot 'backgrounds'
$uiOutput = Join-Path $releaseRoot 'ui'
Ensure-Directory -Path $spriteOutput
Ensure-Directory -Path $backgroundOutput
Ensure-Directory -Path $uiOutput

$outfits = @('01', '02', '03', '04', '05', '06')
$spriteFiles = New-Object System.Collections.Generic.List[string]
foreach ($outfit in $outfits) {
    $pattern = "ce${outfit}???m.png"
    $files = Get-ChildItem -LiteralPath $spriteSource -File -Filter $pattern | Sort-Object Name
    if ($files.Count -ne 25) {
        throw "Expected 25 medium sprites for outfit $outfit, found $($files.Count)."
    }
    foreach ($file in $files) {
        $target = Join-Path $spriteOutput $file.Name.ToLowerInvariant()
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        $spriteFiles.Add($file.Name.ToLowerInvariant())
    }
}

$backgroundNames = @(
    'BG01a', 'BG01d',
    'BG06a', 'BG06c',
    'BG11a', 'BG11b',
    'BG15a', 'BG15b', 'BG15c',
    'BG16b', 'BG16d',
    'BG18a', 'BG18E',
    'BG21a', 'BG21b', 'BG21c',
    'BG22a', 'BG22b', 'BG22c'
)
foreach ($name in $backgroundNames) {
    $source = Join-Path $backgroundSource ($name + '.tlg')
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Missing background source: $source"
    }
    $target = Join-Path $backgroundOutput ($name.ToLowerInvariant() + '.jpg')
    Convert-TlgToJpeg -SourcePath $source -OutputPath $target
}

$uiNames = @('FRM_0101.png', 'FRM_0105.png')
foreach ($name in $uiNames) {
    $source = Join-Path $uiSource $name
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Missing game UI source: $source"
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $uiOutput $name) -Force
}

$backgroundGroups = [ordered]@{
    work_day = @('bg11a.jpg')
    work_evening = @('bg11b.jpg', 'bg16b.jpg')
    work_night = @('bg16d.jpg')
    private_day = @('bg18a.jpg', 'bg21a.jpg', 'bg22a.jpg', 'bg01a.jpg')
    private_evening = @('bg21b.jpg', 'bg22b.jpg', 'bg16b.jpg')
    private_night = @('bg21c.jpg', 'bg22c.jpg', 'bg16d.jpg')
    # Use a lit bedroom for sleep scenes to avoid excessively dark night imagery.
    sleep_night = @('bg01a.jpg')
    event_pool_day = @('bg15a.jpg')
    event_pool_evening = @('bg15b.jpg')
    event_pool_night = @('bg15c.jpg')
    event_shrine_day = @('bg06a.jpg')
    event_shrine_night = @('bg06c.jpg')
}

$resolverBackgroundGroups = [ordered]@{}
foreach ($entry in $backgroundGroups.GetEnumerator()) {
    $resolverBackgroundGroups[$entry.Key] = @(
        $entry.Value | ForEach-Object { "backgrounds/$_" }
    )
}
$resolverManifest = [ordered]@{
    version = 1
    release_id = $ReleaseId
    build_id = 'meguri_v2_02c3db0c507d7c2d'
    sprite_dirs = @('sprites')
    backgrounds = $resolverBackgroundGroups
    ui = [ordered]@{
        dialogue_frame = 'ui/FRM_0101.png'
        advance_indicator = 'ui/FRM_0105.png'
        source = 'moteyaba unpacked MessageFrame assets'
    }
}
$resolverManifestPath = Join-Path $releaseRoot 'meguri_asset_manifest.json'
$resolverManifest | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath $resolverManifestPath -Encoding UTF8

$hashRows = New-Object System.Collections.Generic.List[object]
Get-ChildItem -LiteralPath $releaseRoot -Recurse -File |
    Where-Object { $_.FullName -ne (Join-Path $releaseRoot 'manifest.json') } |
    Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($releaseRoot.Length).TrimStart('\').Replace('\', '/')
    $hashRows.Add([ordered]@{
        path = $relative
        bytes = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    })
}

$manifest = [ordered]@{
    schema_version = 1
    release_id = $ReleaseId
    build_id = 'meguri_v2_02c3db0c507d7c2d'
    generated_at = [DateTimeOffset]::Now.ToString('o')
    canonical_data_modified = $false
    sprite = [ordered]@{
        directory = 'sprites'
        size = 'm'
        enabled_outfits = $outfits
        disabled_outfits = @('07', '08')
        files = $spriteFiles
    }
    background = [ordered]@{
        directory = 'backgrounds'
        source = 'moteyaba unpacked ordinary background assets'
        groups = $backgroundGroups
    }
    ui = [ordered]@{
        directory = 'ui'
        source = 'moteyaba unpacked MessageFrame assets'
        dialogue_frame = 'FRM_0101.png'
        advance_indicator = 'FRM_0105.png'
    }
    files = $hashRows
}
$manifestPath = Join-Path $releaseRoot 'manifest.json'
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

[pscustomobject]@{
    ReleaseRoot = $releaseRoot
    SpriteCount = $spriteFiles.Count
    BackgroundCount = $backgroundNames.Count
    UiCount = $uiNames.Count
    Manifest = $manifestPath
} | Format-List
