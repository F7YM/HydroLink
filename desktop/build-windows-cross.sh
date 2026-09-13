#!/usr/bin/env bash
# Cross-compile the HydroLink desktop app for Windows from Linux.
#
# Wails v2's Windows frontend is cgo-free, so the exe cross-compiles cleanly.
# The app icon + version resource are embedded via rsrc_windows_amd64.syso
# (see desktop/icon.ico).
#
# Output: bin/HydroLink.exe  (console subsystem — server logs stay visible)
#
# Usage:
#   ./build-windows-cross.sh            # amd64
#   ./build-windows-cross.sh arm64      # arm64 (icon resource not embedded)
set -e
cd "$(dirname "$0")"

ARCH="${1:-amd64}"
CONSOLE=""
if [ "$ARCH" = "--console" ]; then
  echo "usage: $0 [amd64|arm64] [--console]"
  exit 1
fi
case "$ARCH" in
  amd64|arm64) ;;
  *) echo "unsupported arch: $ARCH (use amd64|arm64)"; exit 1 ;;
esac
case "${2:-}" in
  --console) CONSOLE="console" ;;
  "") ;;
  *) echo "unknown option: $2 (use --console)"; exit 1 ;;
esac

mkdir -p bin

# 默认构建 GUI 子系统（-H windowsgui）：双击不会多出一个黑窗口。
# 日志写到 <%AppData%/hydrolink/hydrolink.log>，状态页有'查看'按钮。
# 需要看控制台输出时用 --console（子系统保持 console）。
LDFLAGS="-s -w"
OUT="bin/HydroLink-$ARCH.exe"
if [ "$CONSOLE" = "" ]; then
  LDFLAGS="$LDFLAGS -H windowsgui"
else
  OUT="bin/HydroLink-$ARCH-console.exe"
fi

CGO_ENABLED=0 \
GOOS=windows \
GOARCH="$ARCH" \
go build -tags "desktop,production" -trimpath -ldflags "$LDFLAGS" \
  -o "$OUT" .

echo "$OUT"
