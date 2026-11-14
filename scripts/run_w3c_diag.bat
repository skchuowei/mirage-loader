@echo off
rem ASCII-only + CRLF ON PURPOSE. cmd.exe parses .bat byte-by-byte using the OEM
rem code page, CP936 on zh-CN Windows. UTF-8 Chinese bytes get mis-decoded and
rem can swallow neighbouring quotes or percent signs, or turn into command
rem separators and parentheses. Bare LF also breaks multi-line blocks and labels.
rem All Chinese UI and logic live in run_w3c_diag.ps1, saved as UTF-8 WITH BOM
rem so Windows PowerShell 5.1 reads it correctly. See entry C105 in the project
rem experience notes. Do NOT add BOM, non-ASCII bytes, LF-only endings, chcp,
rem or command metacharacters to this file.
setlocal EnableExtensions
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_w3c_diag.ps1" %*
set "RC=%ERRORLEVEL%"
echo.
echo Press any key to close this window...
pause >nul
exit /b %RC%
