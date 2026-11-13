param([string]$Serial)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $repo '.tools/android-toolchain.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'Run scripts/build-android.ps1 first.' }
$tools = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$apk = Join-Path $repo 'dist/Conversation-0.1.0-arm64-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw "APK missing: $apk. Run scripts/build-android.ps1 first." }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
& $tools.Adb @deviceArgs install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed. Enable USB debugging, unlock the phone and approve this computer.' }
& $tools.Adb @deviceArgs shell am start -n 'jp.tekewo.conversation/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'The app could not be launched. Check adb logcat.' }
