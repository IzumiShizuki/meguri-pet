#requires -Version 5
$ErrorActionPreference = 'Stop'
try { chcp 65001 > $null; [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

# ============ Meguri 悬浮窗桌宠（Electron overlay）启动器 ============
# 文件拖放 / Ctrl+V 粘贴文件只在这个 Electron 窗口里可用（普通浏览器拿不到
# 文件真实路径，属于浏览器安全限制，不是故障）。Ctrl+Shift+M 显示/隐藏。
# ====================================================================

$overlayMain = 'D:\program\meguri-pet\apps\desktop-airi\src\electron-main.mjs'

function Test-ListeningPort([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

if (-not (Test-ListeningPort 5173)) {
    Write-Host "[提示] 5173 网关还没启动。请先运行 start-meguri-airi.ps1（或 node apps\desktop-airi\src\web-server.mjs）。" -ForegroundColor Yellow
    Read-Host "按回车关闭"
    exit 1
}

# 依次探测可用的 Electron 运行时（与 start-meguri-airi.ps1 的策略一致，外加 pnpm 仓库）
$candidates = @(
    'D:\program\airi-meguri\apps\stage-tamagotchi\node_modules\electron\dist\electron.exe',
    'D:\program\meguri-pet\apps\desktop-airi\node_modules\electron\dist\electron.exe'
)
$candidates += (Get-ChildItem 'D:\program\airi-meguri\node_modules\.pnpm\electron@*\node_modules\electron\dist\electron.exe' -ErrorAction SilentlyContinue | ForEach-Object FullName)
$electron = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1

if (-not $electron) {
    Write-Host "[错误] 没找到可用的 electron.exe。可在 D:\program\meguri-pet\apps\desktop-airi 里执行 pnpm install 安装。" -ForegroundColor Red
    Read-Host "按回车关闭"
    exit 1
}

Write-Host "使用 Electron：$electron" -ForegroundColor Cyan
Write-Host "悬浮窗启动后：拖文件/Ctrl+V 粘贴文件即可附加；Ctrl+Shift+M 显示或隐藏。" -ForegroundColor Green
Start-Process -FilePath $electron -ArgumentList "`"$overlayMain`"" | Out-Null
Start-Sleep -Seconds 2
