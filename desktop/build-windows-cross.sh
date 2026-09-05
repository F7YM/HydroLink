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
case "$ARCH" in
  amd64|arm64) ;;
  *) echo "unsupported arch: $ARCH (use amd64|arm64)"; exit 1 ;;
esac

mkdir -p bin

CGO_ENABLED=0 \
GOOS=windows \
GOARCH="$ARCH" \
go build -tags "desktop,production" -trimpath -ldflags "-s -w" \
  -o "bin/HydroLink-$ARCH.exe" .

echo "bin/HydroLink-$ARCH.exe"
