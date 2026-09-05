@echo off
rem Build the HydroLink desktop app (Windows) — run on a Windows machine.
cd /d %~dp0
go build -tags "desktop,production" -o bin\HydroLink.exe .
echo bin\HydroLink.exe
