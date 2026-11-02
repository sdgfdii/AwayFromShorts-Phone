@echo off
chcp 65001 >nul
if "%~1"=="" (
 powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0wizard.ps1"
) else (
 powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
 pause
)
