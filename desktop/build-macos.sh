#!/usr/bin/env bash
# Build the HydroLink desktop app (macOS) — run on a macOS machine.
set -e
cd "$(dirname "$0")"
go build -tags "desktop,production" -o bin/HydroLink .
echo "bin/HydroLink"
