#!/usr/bin/env bash
# Build the HydroStation desktop app (Linux).
set -e
cd "$(dirname "$0")"
go build -tags "desktop,production,webkit2_41" -o bin/HydroStation .
echo "bin/HydroStation"
