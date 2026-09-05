@echo off
rem Build the HydroStation desktop app (Windows) — run on a Windows machine.
cd /d %~dp0
go build -tags "desktop,production" -o bin\HydroStation.exe .
echo bin\HydroStation.exe
