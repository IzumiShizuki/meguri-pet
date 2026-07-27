@echo off
setlocal
rem Rebuild/redeploy Meguri Java staging and restart the local AIRI frontend.
rem The PowerShell script contains the guarded candidate, smoke, switch, and rollback flow.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0redeploy-meguri-airi.ps1"
set "exit_code=%ERRORLEVEL%"
if not "%exit_code%"=="0" (
  echo.
  echo Redeploy failed with exit code %exit_code%.
)
exit /b %exit_code%
