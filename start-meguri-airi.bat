@echo off
setlocal EnableExtensions
chcp 65001 >nul
title Meguri AIRI Local Debug Launcher

set "ROOT=D:\program\meguri-pet"
set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "CORE_URL=http://127.0.0.1:18080"
set "TOKEN_FILE=D:\environment\secrets\meguri\desktop-core-token.txt"

if not exist "%ROOT%\ops\scripts\start-meguri-core.ps1" (
  echo [ERROR] Missing Java Core launcher.
  pause
  exit /b 1
)
if not exist "%ROOT%\ops\scripts\start-meguri-airi.ps1" (
  echo [ERROR] Missing AIRI launcher.
  pause
  exit /b 1
)

rem Route the AIRI support gateway to the local Java Core for debugging.
set "MEGURI_CORE_URL=%CORE_URL%"
set "MEGURI_LOCAL_CORE_URL=%CORE_URL%"
set "MEGURI_CORE_AUTH_TOKEN_FILE=%TOKEN_FILE%"

call :isListening 18080
if errorlevel 1 (
  echo [INFO] Starting local Java Core on port 18080...
  start "Meguri Java Core" "%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\ops\scripts\start-meguri-core.ps1"
) else (
  echo [INFO] Java Core is already listening on port 18080.
)

echo [INFO] Waiting for Java Core...
call :waitForPort 18080 120
if errorlevel 1 (
  echo [ERROR] Java Core did not start within 120 seconds.
  echo         Check the separate Java Core window for the Maven error.
  pause
  exit /b 1
)

if not exist "%TOKEN_FILE%" (
  echo [WARN] Core token file not found: %TOKEN_FILE%
  echo        The local gateway may reject normal chat requests.
)

echo [INFO] Starting AIRI gateway, local TTS, and AIRI desktop...
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\ops\scripts\start-meguri-airi.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
  echo [OK] Meguri AIRI started. Gateway: %CORE_URL% via 127.0.0.1:5173
) else (
  echo [ERROR] AIRI startup failed with exit code %EXIT_CODE%.
)
pause
exit /b %EXIT_CODE%

:isListening
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "if (Get-NetTCPConnection -State Listen -LocalPort %1 -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
exit /b %ERRORLEVEL%

:waitForPort
set "WAIT_SECONDS=%2"
:waitForPortLoop
call :isListening %1
if not errorlevel 1 exit /b 0
if %WAIT_SECONDS% LEQ 0 exit /b 1
timeout /t 2 /nobreak >nul
set /a WAIT_SECONDS-=2
goto :waitForPortLoop
