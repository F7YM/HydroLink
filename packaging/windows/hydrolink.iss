; HydroLink —— Windows 安装程序（Inno Setup 6）
;
; 用法（在 Windows 上）：
;   iscc /DMyAppVersion=1.0.0 /DMySourceDir=..\..\bin packaging\windows\hydrolink.iss
; 或者直接双击 packaging\windows\build-installer.bat（会自动取版本号）。
;
; 设计取舍（都在下面有注释）：
;   · 默认**按用户安装**（PrivilegesRequired=lowest）：不需要管理员，装到
;     %LocalAppData%\Programs\HydroLink —— 与"托盘常驻的个人工具"定位一致，
;     也避免 UAC 打扰。要装到 Program Files 时把 PrivilegesRequired 改成 admin
;     （用 /DMyPrivileges=admin 覆盖即可）。
;   · 检测 WebView2 运行时：Win11 自带，Win10 可能没有；缺失时提示并可打开
;     官方 Evergreen 引导安装页（不静默下载执行，避免"安装器偷偷联网装东西"）。
;   · 卸载默认保留用户数据（%AppData%\hydrolink 里的配对密钥、插件、配置），
;     并在卸载前询问是否一并删除。
;   · 安装/卸载前结束正在运行的实例（否则文件占用会导致卸载失败）。

#ifndef MyPrivileges
  #define MyPrivileges "lowest"
#endif

; 所有路径都从**本 .iss 文件所在目录**推导（SourcePath 由 Inno 预定义），
; 这样与当前工作目录无关，也不会被调用方传错相对路径 ——
; 之前写成 "..\..\bin" 导致找不到 desktop\bin\HydroLink-amd64.exe。
#define MyRepoRoot  SourcePath + "\..\.."
#define MyDesktopDir MyRepoRoot + "\desktop"
#define MyBinDir    MyDesktopDir + "\bin"

; 二进制可能是交叉编译的 HydroLink-amd64.exe，也可能是 build-windows.bat 出的
; HydroLink.exe；找不到就在编译期报错，并给出该跑哪条命令。
#if FileExists(MyBinDir + "\HydroLink-amd64.exe")
  #define MyPayload MyBinDir + "\HydroLink-amd64.exe"
#elif FileExists(MyBinDir + "\HydroLink.exe")
  #define MyPayload MyBinDir + "\HydroLink.exe"
#else
  #error 找不到 desktop\bin\HydroLink-amd64.exe（或 HydroLink.exe）。先构建 Windows 二进制：Windows 上跑 desktop\build-windows.bat，或在 Linux 上跑 bash packaging/build-windows.sh
#endif

#if !FileExists(MyRepoRoot + "\LICENSE")
  #error 找不到仓库根目录的 LICENSE，请确认 hydrolink.iss 仍在 packaging\windows\ 下
#endif

; 版本号：命令行 /DMyAppVersion=… 优先（CI 可覆盖），否则直接读 desktop\VERSION。
; 曾经用 "0.0.0" 当兜底，结果直接跑 ISCC 时会静默产出 HydroLink-0.0.0-setup.exe。
#ifndef MyAppVersion
  #define VersionFile MyRepoRoot + "\desktop\VERSION"
  #if !FileExists(VersionFile)
    #error 找不到 desktop\VERSION（版本号唯一来源）
  #endif
  #define MyAppVersion Trim(FileRead(FileOpen(VersionFile)))
#endif

#define MyAppName "HydroLink"
#define MyAppExeName "HydroLink.exe"
#define MyAppPublisher "float11"
#define MyAppId "com.hydro.hydrolink"

[Setup]
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
VersionInfoVersion={#MyAppVersion}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired={#MyPrivileges}
OutputDir=..\..\dist
OutputBaseFilename=HydroLink-{#MyAppVersion}-setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\icon.ico
UninstallDisplayName={#MyAppName}
; 安装器自身的图标（用仓库里的 icon.ico）
SetupIconFile={#MyRepoRoot}\desktop\icon.ico
LicenseFile={#MyRepoRoot}\LICENSE
; 有代码签名证书时取消注释并填证书（否则 SmartScreen 会提示"未知发布者"）：
; SignTool=signtool sign /fd sha256 /tr http://timestamp.digicert.com /td sha256 /f "C:\path\to\cert.pfx" /p "password" $f
; SignedUninstaller=yes

[Languages]
; 英文是 Inno Setup 自带的；中文语言包（ChineseSimplified.isl）需要额外下载：
;   https://jrsoftware.org/files/istrans/  ->  放到 Inno Setup 的 Languages\ 目录
; 存在就自动启用，不存在就只用英文（避免因为缺个翻译文件编译不过）。
Name: "english"; MessagesFile: "compiler:Default.isl"
#if FileExists(AddBackslash(CompilerPath) + "Languages\ChineseSimplified.isl")
Name: "chinese"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
#endif

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "autostart"; Description: "开机自动启动"; GroupDescription: "启动选项："; Flags: unchecked

[Files]
Source: "{#MyPayload}"; DestDir: "{app}"; DestName: "{#MyAppExeName}"; Flags: ignoreversion
Source: "{#MyRepoRoot}\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#MyRepoRoot}\desktop\icon.ico"; DestDir: "{app}"; DestName: "icon.ico"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\icon.ico"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Registry]
; 开机自启（可选任务）：HKCU Run 项，卸载时自动清掉
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; \
  ValueName: "{#MyAppName}"; ValueData: """{app}\{#MyAppExeName}"""; \
  Flags: uninsdeletevalue; Tasks: autostart

[Run]
; 安装完成后可立即启动（不勾选也会由用户自己点开始菜单）
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; \
  Flags: nowait postinstall skipifsilent

[UninstallRun]
; 卸载前先结束正在运行的实例（否则 exe 被占用，卸载会失败）
Filename: "{cmd}"; Parameters: "/c taskkill /IM {#MyAppExeName} /F"; \
  Flags: runhidden; RunOnceId: "KillHydroLink"

[Code]
const
  WebView2Client = 'SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';
  WebView2Client64 = 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';
  WebView2URL = 'https://go.microsoft.com/fwlink/p/?LinkId=2124703';

function WebView2Installed(): Boolean;
var
  v: String;
begin
  Result :=
    RegQueryStringValue(HKLM, WebView2Client, 'pv', v) or
    RegQueryStringValue(HKLM, WebView2Client64, 'pv', v) or
    RegQueryStringValue(HKCU, WebView2Client, 'pv', v);
end;

function InitializeSetup(): Boolean;
var
  ErrCode: Integer;
begin
  Result := True;
  if not WebView2Installed() then
  begin
    if MsgBox('未检测到 Microsoft Edge WebView2 运行时。' + #13#10 +
              'HydroLink 的界面需要它（Windows 11 自带；Windows 10 可能需要安装）。' + #13#10#13#10 +
              '现在打开官方下载页吗？安装完可重新运行本安装程序。',
              mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
      ShellExec('open', WebView2URL, '', '', SW_SHOWNORMAL, ewNoWait, ErrCode);
    // 不阻断安装：核心服务端仍可使用（-no-gui），界面缺 WebView2 时才需要
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{userappdata}\hydrolink');
    if DirExists(DataDir) then
    begin
      // 默认按钮是"否"：静默卸载（/SUPPRESSMSGBOXES）时 Inno 采用默认按钮，
      // 这样绝不会不打招呼就删掉配对密钥/插件/设置。
      if MsgBox('是否同时删除用户数据？' + #13#10 + DataDir + #13#10#13#10 +
                '里面包含配对密钥、已安装插件与设置。选择"否"将保留（重新安装后继续可用）。',
                mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(DataDir, True, True, True);
    end;
  end;
end;
