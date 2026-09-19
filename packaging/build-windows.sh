#!/usr/bin/env bash
# 在 Linux 上交叉编译 Windows 二进制（供 Inno Setup 打包使用）。
# Inno Setup 只能在 Windows 上跑：这里只交叉编译二进制，安装包在 Windows（虚拟机）里
# 用 packaging/windows/build-installer.bat 生成。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/desktop/VERSION")"
cd "$ROOT/desktop"
./build-windows-cross.sh amd64
echo "版本 $VERSION 的二进制已就绪：desktop/bin/HydroLink-amd64.exe"
