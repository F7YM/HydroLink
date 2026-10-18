#!/usr/bin/env bash
# 构建基于 Compose 运行时的 Windows 安装包准备目录 (packaging/build/compose-windows)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/packaging/build/compose-windows"
WINDOWS_DIR="$ROOT/packaging/windows"

mkdir -p "$STAGE"

echo "== 1. 确保 Windows 图标与版本资源 (.syso) 就绪 =="
if [ ! -f "$WINDOWS_DIR/rsrc_windows_amd64.syso" ]; then
    cp "$ROOT/desktop/rsrc_windows_amd64.syso" "$WINDOWS_DIR/rsrc_windows_amd64.syso"
fi

echo "== 2. 编译 Windows 专属启动引导器 (包含嵌入图标与 Java 环境智能探测) =="
cd "$WINDOWS_DIR"
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w -H=windowsgui" -o "$STAGE/HydroLink.exe" compose-launcher.go

echo "== 3. 校验并同步 Compose 应用 Jar 库 =="
APP_SOURCE="$ROOT/desktop-compose/build/compose/binaries/main/app/HydroLink/lib/app"
if [ -d "$APP_SOURCE" ]; then
    mkdir -p "$STAGE/lib"
    rsync -a --delete --exclude="*.so" --exclude="*.so.*" "$APP_SOURCE" "$STAGE/lib/" 2>/dev/null || cp -a "$APP_SOURCE" "$STAGE/lib/"
fi

echo "== 4. 拷贝许可证与清理不需要的文件 (严格不打包 README) =="
cp "$ROOT/LICENSE" "$STAGE/LICENSE"
rm -f "$STAGE/README.md" "$STAGE"/*.md

echo "== 完成！compose-windows 目录已准备就绪 =="
echo "主程序：$STAGE/HydroLink.exe ($(du -h "$STAGE/HydroLink.exe" | cut -f1))"
echo "使用 Inno Setup 运行 packaging/windows/hydrolink-compose.iss 即可生成安装包。"
