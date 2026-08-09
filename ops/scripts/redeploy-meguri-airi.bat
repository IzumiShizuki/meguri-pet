@echo off
setlocal
chcp 65001 >nul
title Meguri + AIRI Build and Start

rem Build Java and AIRI, optionally replace the remote staging image,
rem then start the local Java/AIRI stack. No tests or health/smoke checks.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0redeploy-meguri-airi.ps1"
set "exit_code=%ERRORLEVEL%"

echo.
if "%exit_code%"=="0" (
  echo Build/start command completed.
) else (
  echo Build/start failed with exit code %exit_code%.
)
pause
exit /b %exit_code%
