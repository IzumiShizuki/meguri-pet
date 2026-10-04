@echo off
setlocal
set "LOG_ROOT=D:\program\meguri-pet\log"
if not exist "%LOG_ROOT%" mkdir "%LOG_ROOT%" >nul 2>&1
reg query "HKCU\Software\Meguri\Desktop" /v RunAsAdministrator 2>nul | findstr /R /C:"REG_DWORD[ ]*0x1$" >nul
if not errorlevel 1 (
  schtasks.exe /run /tn "Meguri Desktop" >> "%LOG_ROOT%\desktop-launcher.log" 2>&1
  if not errorlevel 1 exit /b 0
  echo Could not run the elevated Meguri Desktop task; trying the normal launcher. >> "%LOG_ROOT%\desktop-launcher.log"
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:\program\meguri-pet\ops\scripts\start-meguri-desktop.ps1" >> "%LOG_ROOT%\desktop-launcher.log" 2>&1
exit /b %ERRORLEVEL%
