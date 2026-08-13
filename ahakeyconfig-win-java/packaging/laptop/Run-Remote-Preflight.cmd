@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Remote-Preflight.ps1"
echo.
echo Press any key to close.
pause >nul
