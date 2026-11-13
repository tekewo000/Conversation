param(
    [string]$Serial,
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Release'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $repo '.tools/android-toolchain.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'Run scripts/build-android.ps1 first.' }
$tools = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
[xml]$manifest = Get-Content -LiteralPath (Join-Path $repo 'android/AndroidManifest.xml') -Raw
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android') -replace '-.*$', ''
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Manifest versionName must start with a three-part version.' }
$flavor = $Configuration.ToLowerInvariant()
$apk = Join-Path $repo "dist/Conversation-$version-arm64-$flavor.apk"
if (-not (Test-Path -LiteralPath $apk)) { throw "APK missing: $apk. Run scripts/build-android.ps1 -Configuration $Configuration first." }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$installOutput = @(& $tools.Adb @deviceArgs install -r $apk 2>&1)
$installExit = $LASTEXITCODE
$installOutput | ForEach-Object { Write-Host $_ }
if ($installExit -ne 0) {
    if (($installOutput -join "`n") -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        throw 'Do not uninstall or clear app data. Rebuild/install -Configuration Release using the existing release signing key.'
    }
    throw 'Install failed. Enable USB debugging, unlock the phone and approve this computer.'
}
$package = $manifest.manifest.GetAttribute('package')
& $tools.Adb @deviceArgs shell am start -n "$package/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw 'The app could not be launched. Check adb logcat.' }
