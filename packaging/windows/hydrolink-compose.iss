[Setup]
AppId={{com.hydro.link.compose}}
AppName=HydroLink
AppVersion=1.2.3
AppVerName=HydroLink 1.2.3
AppPublisher=float11
DefaultDirName={autopf}\HydroLink
DefaultGroupName=HydroLink
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\dist
OutputBaseFilename=HydroLink-1.2.3-setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\HydroLink.exe
UninstallDisplayName=HydroLink
SetupIconFile=..\..\desktop\icon.ico

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "autostart"; Description: "开机自动启动（开机后在托盘常驻）"; GroupDescription: "启动选项："; Flags: unchecked

[Files]
Source: "..\build\compose-windows\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\HydroLink"; Filename: "{app}\HydroLink.exe"
Name: "{autodesktop}\HydroLink"; Filename: "{app}\HydroLink.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "HydroLink"; ValueData: """{app}\HydroLink.exe"""; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\HydroLink.exe"; Description: "{cm:LaunchProgram,HydroLink}"; Flags: nowait postinstall skipifsilent
