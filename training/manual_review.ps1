param(
    [string]$ReviewCsv = "D:\program\meguri-pet\reports\tts_quality_review.csv",
    [string]$Ffplay = "D:\environment\ffmpeg\bin\ffplay.exe"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $ReviewCsv)) { throw "Review CSV not found: $ReviewCsv" }
if (-not (Test-Path -LiteralPath $Ffplay)) { throw "ffplay not found: $Ffplay" }

$rows = Import-Csv -LiteralPath $ReviewCsv
$index = 0
foreach ($row in $rows) {
    $index++
    if ($row.manual_status -in @("pass", "reject")) { continue }
    Write-Host "[$index/$($rows.Count)] $($row.voice_id) | $($row.split) | $($row.relationship_stage) | $($row.voice_style)"
    Write-Host "Text: $($row.text_jp_single_line)"
    Write-Host "Audio: $($row.audio_path_absolute)"
    & $Ffplay -hide_banner -loglevel error -nodisp -autoexit -- $row.audio_path_absolute
    do {
        $status = (Read-Host "Enter pass, reject, or skip").Trim().ToLowerInvariant()
    } while ($status -notin @("pass", "reject", "skip"))
    if ($status -eq "skip") { continue }
    $row.manual_status = $status
    if ($status -eq "reject") {
        $row.manual_issue = (Read-Host "Issue: transcript_mismatch, bgm, se, noise, truncated, wrong_speaker, other").Trim()
    } else {
        $row.manual_issue = ""
    }
    $row.manual_notes = (Read-Host "Notes (optional)").Trim()
    $row.reviewer = $env:USERNAME
    $row.reviewed_at = (Get-Date).ToUniversalTime().ToString("o")
    $rows | Export-Csv -LiteralPath $ReviewCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Saved $($row.utterance_id)"
}

Write-Host "Review session complete. Run:"
Write-Host "D:\environment\anaconda3\envs\py314\python.exe -m training.tts_acoustic_gate --finalize-only"
