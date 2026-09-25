@echo off
setlocal

REM Wrapper for IntelliJ External Tools. Calls the PowerShell script with a single file argument.
set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%smartcodereviewer.ps1"

REM Pass through the selected file path as-is.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" -FilePath "%~1"
exit /b %ERRORLEVEL%

