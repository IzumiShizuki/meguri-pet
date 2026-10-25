param([int]$ExcludeProcessId = 0, [switch]$Probe, [switch]$Fixture)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$bitmap = $null
$memory = $null
$stream = $null
$software = $null
try {
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
    $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType=WindowsRuntime]
    $null = [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Foundation, ContentType=WindowsRuntime]
    $null = [Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime]
    $null = [Windows.Storage.Streams.DataWriter, Windows.Storage.Streams, ContentType=WindowsRuntime]
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    if ($null -eq $engine) { throw 'ocr_unavailable' }
    if ($Probe) { @{ok=$true; provider='windows-ocr'} | ConvertTo-Json -Compress; exit 0 }
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 } | Select-Object -First 1
    function Await-Result($operation, [Type]$resultType) {
        $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
        if (-not $task.Wait(5000)) { throw 'ocr_timeout' }
        return $task.Result
    }
    $appName = 'fixture'
    $windowHandle = [IntPtr]::Zero
    if ($Fixture) {
        $bitmap = New-Object Drawing.Bitmap 1000, 220
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear([Drawing.Color]::White)
            $font = New-Object Drawing.Font 'Arial', 32
            try { $graphics.DrawString('MEGURI ACTIVITY TEST 2026', $font, [Drawing.Brushes]::Black, 24, 60) }
            finally { $font.Dispose() }
        } finally { $graphics.Dispose() }
    }
    else {
        Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class MeguriForegroundOcr {
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr handle, out Rect rect);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr handle, StringBuilder text, int count);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
  [StructLayout(LayoutKind.Sequential)] struct LastInput { public uint size, time; }
  [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LastInput input);
  public static bool RecentInput() { var input = new LastInput { size = 8 }; return GetLastInputInfo(ref input) && unchecked((uint)Environment.TickCount - input.time) < 600000; }
}
'@
        $windowHandle = [MeguriForegroundOcr]::GetForegroundWindow()
        [uint32]$foregroundProcessId = 0
        $null = [MeguriForegroundOcr]::GetWindowThreadProcessId($windowHandle, [ref]$foregroundProcessId)
        $title = New-Object Text.StringBuilder 512
        $null = [MeguriForegroundOcr]::GetWindowText($windowHandle, $title, 512)
        $appName = (Get-Process -Id $foregroundProcessId).ProcessName
        if ($foregroundProcessId -eq $ExcludeProcessId -or $windowHandle -eq [IntPtr]::Zero -or
            $appName -match '(?i)^(LockApp|LogonUI|CredentialUIBroker|KeePass|KeePassXC|Bitwarden|1Password)$' -or
            $title.ToString() -match '(?i)(password|credential|private key|incognito|inprivate|\u5bc6\u7801|\u5bc6\u94a5|\u9a8c\u8bc1\u7801|\u65e0\u75d5|\u9690\u79c1\u6d4f\u89c8)') {
            @{ok=$false; reason='excluded'} | ConvertTo-Json -Compress; exit 0
        }
        $rect = New-Object MeguriForegroundOcr+Rect
        if (-not [MeguriForegroundOcr]::GetWindowRect($windowHandle, [ref]$rect)) { throw 'window_unavailable' }
        $left = [Math]::Max($rect.Left, [MeguriForegroundOcr]::GetSystemMetrics(76))
        $top = [Math]::Max($rect.Top, [MeguriForegroundOcr]::GetSystemMetrics(77))
        $right = [Math]::Min($rect.Right, [MeguriForegroundOcr]::GetSystemMetrics(76) + [MeguriForegroundOcr]::GetSystemMetrics(78))
        $bottom = [Math]::Min($rect.Bottom, [MeguriForegroundOcr]::GetSystemMetrics(77) + [MeguriForegroundOcr]::GetSystemMetrics(79))
        $width = [Math]::Min(4096, $right - $left)
        $height = [Math]::Min(2160, $bottom - $top)
        if ($width -lt 32 -or $height -lt 32) { throw 'window_unavailable' }
        $bitmap = New-Object Drawing.Bitmap $width, $height
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            if (-not [MeguriForegroundOcr]::RecentInput()) { throw 'inactive' }
            $graphics.CopyFromScreen($left, $top, 0, 0, $bitmap.Size)
        }
        finally { $graphics.Dispose() }
        if ([MeguriForegroundOcr]::GetForegroundWindow() -ne $windowHandle) { throw 'window_changed' }
    }
    if ($bitmap.Width -gt 1920 -or $bitmap.Height -gt 1080) {
        $ratio = [Math]::Min(1920.0 / $bitmap.Width, 1080.0 / $bitmap.Height)
        $small = New-Object Drawing.Bitmap ([int]($bitmap.Width * $ratio)), ([int]($bitmap.Height * $ratio))
        $graphics = [Drawing.Graphics]::FromImage($small)
        try { $graphics.DrawImage($bitmap, 0, 0, $small.Width, $small.Height) } finally { $graphics.Dispose() }
        $bitmap.Dispose(); $bitmap = $small
    }
    $memory = New-Object IO.MemoryStream
    $bitmap.Save($memory, [Drawing.Imaging.ImageFormat]::Png)
    $stream = [Windows.Storage.Streams.InMemoryRandomAccessStream]::new()
    $writer = [Windows.Storage.Streams.DataWriter]::new($stream)
    try {
        $writer.WriteBytes($memory.ToArray())
        $null = Await-Result ($writer.StoreAsync()) ([uint32])
        $null = $writer.DetachStream()
    } finally { $writer.Dispose() }
    $stream.Seek(0)
    $decoder = Await-Result ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $software = Await-Result ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $recognized = Await-Result ($engine.RecognizeAsync($software)) ([Windows.Media.Ocr.OcrResult])
    if (-not $Fixture -and ([MeguriForegroundOcr]::GetForegroundWindow() -ne $windowHandle -or -not [MeguriForegroundOcr]::RecentInput())) { throw 'window_changed' }
    $text = [regex]::Replace($recognized.Text, '\s+', ' ').Trim()
    if ($text.Length -gt 1000) { $text = $text.Substring(0, 1000) }
    @{ok=$true; text=$text; app=$appName} | ConvertTo-Json -Compress
}
catch { @{ok=$false; reason='unavailable'} | ConvertTo-Json -Compress }
finally {
    if ($software) { $software.Dispose() }
    if ($stream) { $stream.Dispose() }
    if ($memory) { $memory.Dispose() }
    if ($bitmap) { $bitmap.Dispose() }
}
