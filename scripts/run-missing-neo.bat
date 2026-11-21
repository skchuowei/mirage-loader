@echo off
rem ASCII-only + CRLF ON PURPOSE, see C105. cmd parses .bat byte-by-byte as CP936.
rem All Chinese UI and logic live in run-missing-neo.ps1, saved UTF-8 WITH BOM plus CRLF.
rem Do NOT add BOM, non-ASCII bytes, LF-only endings, chcp, or command metacharacters.
setlocal EnableExtensions
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-missing-neo.ps1" %*
set "RC=%ERRORLEVEL%"
echo.
echo Press any key to close this window...
pause >nul
exit /b %RC%
