# 使用 dart_defines.local.json 打 Release 包（该文件已在 .gitignore 中，不会上传 GitHub）。
# 用法：.\tool\build_release.ps1
#       .\tool\build_release.ps1 -Target apk
#       .\tool\build_release.ps1 -Target windows
param(
    [ValidateSet('apk', 'windows', 'appbundle', 'all')]
    [string] $Target = 'all'
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Defines = Join-Path $Root 'dart_defines.local.json'

if (-not (Test-Path $Defines)) {
    Write-Host "缺少 $Defines" -ForegroundColor Red
    Write-Host "请复制 dart_defines.local.example.json 为 dart_defines.local.json 并填入真实值。" -ForegroundColor Yellow
    exit 1
}

Push-Location $Root
try {
    if ($Target -eq 'apk' -or $Target -eq 'all') {
        flutter build apk --release --dart-define-from-file="$Defines"
    }
    if ($Target -eq 'windows' -or $Target -eq 'all') {
        flutter build windows --release --dart-define-from-file="$Defines"
    }
    if ($Target -eq 'appbundle') {
        flutter build appbundle --release --dart-define-from-file="$Defines"
    }
} finally {
    Pop-Location
}
