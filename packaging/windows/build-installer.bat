@echo off
rem 在 Windows 上构建 HydroLink 安装程序（需要先装 Inno Setup 6：https://jrsoftware.org/isdl.php）
rem
rem   1) 先在仓库根目录构建 Windows 二进制：
rem        desktop\build-windows.bat              -> desktop\bin\HydroLink.exe
rem        或（Linux 交叉编译）bash packaging/build-windows.sh
rem   2) 再运行本脚本，产物：dist\HydroLink-<版本>-setup.exe
setlocal enabledelayedexpansion
cd /d "%~dp0\..\.."

set VERSION=
for /f "usebackq delims=" %%v in ("desktop\VERSION") do set VERSION=%%v
if "%VERSION%"=="" set VERSION=0.0.0

set ISCC="%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not exist %ISCC% set ISCC="%ProgramFiles%\Inno Setup 6\ISCC.exe"
if not exist %ISCC% (
  echo [错误] 找不到 ISCC.exe，请安装 Inno Setup 6。
  exit /b 1
)

set SRC=desktop\bin
if not exist "%SRC%\HydroLink-amd64.exe" (
  if exist "%SRC%\HydroLink.exe" (
    copy /y "%SRC%\HydroLink.exe" "%SRC%\HydroLink-amd64.exe" >nul
  ) else (
    echo [错误] 缺少 %SRC%\HydroLink-amd64.exe，请先构建 Windows 二进制。
    exit /b 1
  )
)

echo == 版本 %VERSION% ==
%ISCC% /DMyAppVersion=%VERSION% /DMySourceDir=%SRC% packaging\windows\hydrolink.iss
if errorlevel 1 exit /b 1
echo 产物：dist\HydroLink-%VERSION%-setup.exe
