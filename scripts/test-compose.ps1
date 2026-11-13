param([string]$Serial, [switch]$CompileOnly)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$tools = Get-Content -LiteralPath "$repo/.tools/android-toolchain.json" -Raw | ConvertFrom-Json
if (-not $CompileOnly -and $Serial -notmatch '^emulator-\d+$') {
    throw 'Use -CompileOnly, or specify a disposable Android emulator with -Serial emulator-<port>.'
}
$stage = Get-ChildItem -Path "$repo/build/android-*/run-*" -Directory |
    Where-Object { (Test-Path -LiteralPath "$($_.FullName)/AndroidManifest.xml") -and
        (Test-Path -LiteralPath "$($_.FullName)/package/lib/arm64-v8a/libconversation.so") } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $stage) { throw 'Run scripts/build-android.ps1 first to build the native library.' }
$build = Join-Path $repo 'build/compose-tests'
[xml]$manifest = Get-Content -LiteralPath "$repo/android/AndroidManifest.xml" -Raw
$namespace = 'http://schemas.android.com/apk/res/android'
$version = $manifest.manifest.GetAttribute('versionName', $namespace) -replace '-.*$', ''
$versionCode = $manifest.manifest.GetAttribute('versionCode', $namespace)
$arguments = @('-p', "$repo/android", '--console=plain', "-PconversationBuildDir=$build",
    "-PconversationManifest=$($stage.FullName)/AndroidManifest.xml",
    "-PconversationJniDir=$($stage.FullName)/package/lib", "-PconversationVersionCode=$versionCode",
    "-PconversationVersionName=$version")
$env:JAVA_HOME = $tools.JavaHome
$env:ANDROID_HOME = $tools.Sdk
& $tools.Gradle @arguments assembleDebug assembleDebugAndroidTest
if ($LASTEXITCODE -ne 0) { throw 'Compose instrumentation compilation failed.' }
if ($CompileOnly) { Write-Host 'PASS: Compose app and instrumentation APKs compiled; tests were not executed.'; return }

function Get-ApkCertificate([string]$Apk) {
    $verification = & "$($tools.BuildTools)/apksigner.bat" verify --print-certs $Apk
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify an APK certificate.' }
    $certificate = @($verification | Select-String '^Signer #1 certificate SHA-256 digest: ([0-9a-f]+)$')
    if ($certificate.Count -ne 1) { throw 'Expected one APK signing certificate.' }
    return $certificate[0].Matches[0].Groups[1].Value
}

$emulator = (& $tools.Adb -s $Serial shell getprop ro.kernel.qemu | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $emulator -ne '1') { throw 'Compose tests require a running disposable Android emulator.' }
$packages = @(& $tools.Adb -s $Serial shell pm list packages jp.tekewo.conversation)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the emulator installation.' }
if ($packages -contains 'package:jp.tekewo.conversation') {
    $installed = @(& $tools.Adb -s $Serial shell pm path jp.tekewo.conversation)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the installed app path.' }
    $base = @($installed | Where-Object { $_ -match '^package:(/[^\r\n]+/base\.apk)$' })
    if ($base.Count -ne 1) { throw 'Could not identify the installed app; nothing was uninstalled.' }
    $remote = $base[0].Substring('package:'.Length).Trim()
    & $tools.Adb -s $Serial pull $remote "$build/installed.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the installed app certificate.' }
    if ((Get-ApkCertificate "$build/installed.apk") -ne (Get-ApkCertificate "$build/outputs/apk/debug/Conversation-debug.apk")) {
        throw 'The emulator app uses a different signing key (including Release); tests refused to replace or uninstall it. Use another disposable emulator.'
    }
}
$previousSerial = $env:ANDROID_SERIAL
try {
    $env:ANDROID_SERIAL = $Serial
    & $tools.Gradle @arguments connectedDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Compose instrumentation tests failed.' }
} finally {
    $env:ANDROID_SERIAL = $previousSerial
}
