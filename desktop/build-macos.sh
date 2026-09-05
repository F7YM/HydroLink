#!/usr/bin/env bash
# Build the HydroStation desktop app (macOS) — run on a macOS machine.
set -e
cd "$(dirname "$0")"
go build -tags "desktop,production" -o bin/HydroStation .
echo "bin/HydroStation"
