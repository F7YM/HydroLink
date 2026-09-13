@echo off
rem Build the HydroLink desktop app (Windows) — run on a Windows machine.
cd /d %~dp0
rem -H windowsgui：GUI 子系统，双击不会多一个黑窗口（日志见 %AppData%\hydrolink\hydrolink.log）
rem 需要控制台排障时改成：go build -tags "desktop,production" -o bin\HydroLink-console.exe .
go build -tags "desktop,production" -ldflags "-s -w -H windowsgui" -o bin\HydroLink.exe .
echo bin\HydroLink.exe
