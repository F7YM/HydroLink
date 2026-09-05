#!/usr/bin/env bash
# Build the HydroLink desktop app (Linux).
set -e
cd "$(dirname "$0")"
go build -tags "desktop,production,webkit2_41" -o bin/HydroLink .
echo "bin/HydroLink"
