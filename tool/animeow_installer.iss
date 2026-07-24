; =====================================================================
;  AniMeow Windows 桌面端安装包脚本 (Inno Setup 6)
;  由 build_windows.bat 调用，命令示例：
;    ISCC.exe /DMyAppVersion=1.3.7 tool\animeow_installer.iss
;  MyAppVersion 由 bat 解析 pubspec.yaml 后通过 /D 传入。
; =====================================================================
#define MyAppName          "AniMeow"
#define MyAppExeName       "anime_tracker.exe"
#define MyAppPublisher     "AniMeow"
#define MyAppURL          "https://github.com/xunlys7930/AniMeow"

[Setup]
; 固定 AppId，保证后续版本可覆盖升级；双花括号 {{ 表示字面 GUID
AppId={{ED24678D-27EF-40AC-B312-52083CF2AD7A}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
; 输出到 build\installer，文件名固定，由 bat 重命名为带版本号的中文名
OutputDir=..\build\installer
OutputBaseFilename=AniMeow_Setup
SetupIconFile=..\windows\runner\resources\app_icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
; 压缩与体积优化
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes
WizardStyle=modern
; 仅 64 位
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; 安装到 Program Files 需要管理员权限
PrivilegesRequired=admin
; 升级时自动卸载旧版本文件，保留用户数据
UsePreviousAppDir=yes
UsePreviousTasks=yes

[Languages]
; 使用内置英文 UI（Inno Setup 默认语言，无需额外 isl 文件）
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; 把 Flutter Windows 构建产物整体打入安装包
Source: "..\build\windows\x64\runner\Release\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent
