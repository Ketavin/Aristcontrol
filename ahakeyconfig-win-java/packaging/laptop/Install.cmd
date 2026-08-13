@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-Arist-AI-Control.ps1"
if errorlevel 1 (
  echo.
  echo Installation failed. Press any key to close.
  pause >nul
  exit /b 1
)
echo.
echo Installation completed. Press any key to close.
pause >nul
