#!/usr/bin/env bash
# 在 Linux 上交叉编译 Windows 二进制（供 Inno Setup 打包使用）。
# Inno Setup 本体只能在 Windows 上运行；也可以装 wine + ISCC 后在 Linux 出安装包
# （见 packaging/windows/README.md 的"在 Linux 上出安装包"一节）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/desktop/VERSION")"
cd "$ROOT/desktop"
./build-windows-cross.sh amd64
echo "版本 $VERSION 的二进制已就绪：desktop/bin/HydroLink-amd64.exe"
