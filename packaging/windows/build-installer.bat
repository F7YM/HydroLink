@echo off
rem ===================================================================
rem  Build the HydroLink Windows installer with Inno Setup 6.
rem
rem  NOTE: keep this file ASCII-only. cmd.exe reads .bat files using the
rem  system ANSI codepage (GBK on a Chinese Windows), so UTF-8 Chinese
rem  text in here gets mangled and breaks the whole script.
rem  Chinese notes live in packaging/README.md instead.
rem
rem  Prerequisite: desktop\bin\HydroLink-amd64.exe
rem    built by  desktop\build-windows.bat  (on Windows)
rem    or       bash packaging/build-windows.sh  (cross-compile on Linux)
rem  Output: dist\HydroLink-<version>-setup.exe
rem ===================================================================
setlocal
cd /d "%~dp0..\.."

set "VERSION="
for /f "usebackq delims=" %%v in ("desktop\VERSION") do set "VERSION=%%v"
if not defined VERSION set "VERSION=0.0.0"

set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" set "ISCC=%ProgramFiles%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" (
  echo [ERROR] ISCC.exe not found. Install Inno Setup 6 first:
  echo         https://jrsoftware.org/isdl.php
  exit /b 1
)

if exist "desktop\bin\HydroLink-amd64.exe" goto build
if exist "desktop\bin\HydroLink.exe" goto build
echo [ERROR] Missing desktop\bin\HydroLink-amd64.exe
echo         Build the Windows binary first:
echo           desktop\build-windows.bat
echo           bash packaging/build-windows.sh
exit /b 1

:build
echo == HydroLink installer, version %VERSION% ==
"%ISCC%" /DMyAppVersion=%VERSION% "%CD%\packaging\windows\hydrolink.iss"
if errorlevel 1 (
  echo [ERROR] ISCC failed.
  exit /b 1
)
echo.
echo Output: dist\HydroLink-%VERSION%-setup.exe
exit /b 0
