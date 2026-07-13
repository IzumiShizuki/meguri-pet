param(
    [int]$StartAt = 1
)

$ErrorActionPreference = 'Stop'
$ffplay = 'D:\environment\ffmpeg\bin\ffplay.exe'
$review = 'D:\program\meguri-pet\reports\tts_finetune_ab_review.csv'
if (-not (Test-Path -LiteralPath $ffplay)) { throw "ffplay not found: $ffplay" }
if (-not (Test-Path -LiteralPath $review)) { throw "review CSV not found: $review" }
$rows = Import-Csv -LiteralPath $review | Where-Object { $_.language -eq 'ja' }

function Read-Choice([string]$Prompt, [string[]]$Allowed) {
    while ($true) {
        $value = (Read-Host $Prompt).Trim().ToUpperInvariant()
        if ($Allowed -contains $value) { return $value }
        Write-Host "Allowed: $($Allowed -join ', ')"
    }
}

function Read-Score([string]$Prompt) {
    while ($true) {
        $value = (Read-Host "$Prompt (1-5)").Trim()
        if ($value -in @('1','2','3','4','5')) { return $value }
        Write-Host 'Enter a score from 1 to 5.'
    }
}

function Play-Sample([string]$Label, [string]$Path) {
    Write-Host "Playing $Label"
    & $ffplay -nodisp -autoexit -loglevel error $Path
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ffplay returned $LASTEXITCODE; the review can continue."
    }
}

function Play-Pair($Row) {
    Play-Sample 'A' $Row.A_path
    Play-Sample 'B' $Row.B_path
}

for ($index = [Math]::Max(0, $StartAt - 1); $index -lt $rows.Count; $index++) {
    $row = $rows[$index]
    Write-Host "[$($index + 1)/$($rows.Count)] $($row.pair_id) $($row.language)"
    Write-Host $row.text
    Play-Pair $row
    while ($true) {
        $listenAction = Read-Choice 'Listen again: A=only A, B=only B, R=both, N=score, Q=quit' @('A','B','R','N','Q')
        switch ($listenAction) {
            'A' { Play-Sample 'A' $row.A_path }
            'B' { Play-Sample 'B' $row.B_path }
            'R' { Play-Pair $row }
            'N' { break }
            'Q' { Write-Host 'Review paused. Existing saved rows were preserved.'; exit 0 }
        }
        if ($listenAction -eq 'N') { break }
    }
    $row.preference_A_B_TIE = Read-Choice 'Preference' @('A','B','TIE')
    $row.A_pronunciation_1_5 = Read-Score 'A pronunciation'
    $row.B_pronunciation_1_5 = Read-Score 'B pronunciation'
    $row.A_voice_similarity_1_5 = Read-Score 'A voice similarity'
    $row.B_voice_similarity_1_5 = Read-Score 'B voice similarity'
    $row.A_naturalness_1_5 = Read-Score 'A naturalness'
    $row.B_naturalness_1_5 = Read-Score 'B naturalness'
    $row.A_severe_issue_Y_N = Read-Choice 'A severe issue?' @('Y','N')
    $row.B_severe_issue_Y_N = Read-Choice 'B severe issue?' @('Y','N')
    $row.notes = Read-Host 'Notes (optional)'
    $rows | Export-Csv -LiteralPath $review -NoTypeInformation -Encoding UTF8
    Write-Host 'Saved.'
}
Write-Host 'Blind review complete. Do not open the key; ask Codex to score and unblind it.'
