# 在仓库根目录使用本地 dart_defines.local.json 运行 Flutter（该文件已在 .gitignore 中）。
# 用法：.\tool\flutter_run.ps1
#       .\tool\flutter_run.ps1 -d windows
#       .\tool\flutter_run.ps1 -d <deviceId> --profile
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $FlutterArgs
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Defines = Join-Path $Root 'dart_defines.local.json'

if (-not (Test-Path $Defines)) {
    Write-Host "缺少 $Defines" -ForegroundColor Red
    Write-Host "请复制:  copy dart_defines.local.example.json dart_defines.local.json" -ForegroundColor Yellow
    Write-Host "再编辑其中的 CLOUD_API_BASE 与 API_TOKEN（与后端 .env 一致）。" -ForegroundColor Yellow
    exit 1
}

Push-Location $Root
try {
    flutter run --dart-define-from-file="$Defines" @FlutterArgs
} finally {
    Pop-Location
}
