@echo off
rem Flowme - build tu CMD / PowerShell.
rem Trong CMD, lenh "bash" la WSL (may nay khong co ban Linux) => goi thang Git Bash de chay build.sh.
rem Dung: build.cmd                -> unit test + APK release (dist\Flowme-<phien ban>.apk)
rem       build.cmd assembleDebug  -> chay task Gradle tuy chon
setlocal
set "GITBASH="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined GITBASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "GITBASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not defined GITBASH (
  echo [Flowme] Khong tim thay Git Bash. Hay cai Git for Windows: https://git-scm.com/download/win
  exit /b 1
)
cd /d "%~dp0"
"%GITBASH%" build.sh %*
exit /b %ERRORLEVEL%
