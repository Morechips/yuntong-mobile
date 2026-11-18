@echo off
setlocal
cd /d "%~dp0"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0field-test\Start.ps1"
if errorlevel 1 (
  echo See field-test\README.md for recovery steps.
  pause
)
