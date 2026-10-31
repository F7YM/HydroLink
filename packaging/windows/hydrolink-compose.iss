[Setup]
AppId={{com.hydro.link.compose}}
AppName=HydroLink
AppVersion=1.3.0
AppVerName=HydroLink 1.3.0
AppPublisher=float11
DefaultDirName={autopf}\HydroLink
DefaultGroupName=HydroLink
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\dist
OutputBaseFilename=HydroLink-1.3.0-setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\icon.ico
UninstallDisplayName=HydroLink
SetupIconFile=..\..\desktop\icon.ico

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "autostart"; Description: "开机自动启动"; GroupDescription: "启动选项："; Flags: unchecked

[Files]
Source: "..\build\compose-windows\*"; DestDir: "{app}"; Excludes: "README.md,*.md"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\desktop\icon.ico"; DestDir: "{app}"; DestName: "icon.ico"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\HydroLink"; Filename: "{app}\HydroLink.exe"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\HydroLink"; Filename: "{app}\HydroLink.exe"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "HydroLink"; ValueData: """{app}\HydroLink.exe"""; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\HydroLink.exe"; Description: "{cm:LaunchProgram,HydroLink}"; Flags: nowait postinstall skipifsilent
