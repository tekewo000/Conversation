param([Parameter(Mandatory)][string]$Serial)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$tools = Get-Content "$repo/.tools/android-toolchain.json" -Raw | ConvertFrom-Json
. "$PSScriptRoot/android-signing.ps1"
$build = Join-Path $repo "build/release-startup-probe/$([guid]::NewGuid())"
New-Item -ItemType Directory -Force "$build/classes", "$build/dex" | Out-Null
& "$($tools.JavaHome)/bin/javac.exe" '-J-Duser.language=en' '-Xlint:-options' -encoding UTF-8 --release 8 -classpath $tools.PlatformJar -d "$build/classes" "$repo/android/device-tests/jp/tekewo/conversation/ReleaseStartupProbe.java"
if ($LASTEXITCODE -ne 0) { throw 'Release probe compilation failed.' }
& "$($tools.JavaHome)/bin/java.exe" -classpath "$($tools.BuildTools)/lib/d8.jar" com.android.tools.r8.D8 --min-api 29 --lib $tools.PlatformJar --output "$build/dex" "$build/classes/jp/tekewo/conversation/ReleaseStartupProbe.class"
if ($LASTEXITCODE -ne 0) { throw 'Release probe DEX compilation failed.' }
$manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.tekewo.conversation.startupprobe" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36" />
    <instrumentation android:name="jp.tekewo.conversation.ReleaseStartupProbe" android:targetPackage="jp.tekewo.conversation" android:functionalTest="true" />
    <application android:label="Conversation Release startup probe" android:debuggable="false" android:allowBackup="false" />
</manifest>
'@
Set-Content "$build/AndroidManifest.xml" $manifest -Encoding utf8
& "$($tools.BuildTools)/aapt2.exe" link -o "$build/unsigned.apk" -I $tools.PlatformJar --manifest "$build/AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 36
if ($LASTEXITCODE -ne 0) { throw 'Release probe manifest packaging failed.' }
& "$($tools.JavaHome)/bin/jar.exe" uf "$build/unsigned.apk" -C "$build/dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Release probe DEX packaging failed.' }
& "$($tools.BuildTools)/zipalign.exe" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Release probe alignment failed.' }
$signing = Get-ConversationSigning -Configuration Release -Tools $tools -Repo $repo
$installed = $false
try {
    & "$($tools.JavaHome)/bin/java.exe" -jar "$($tools.BuildTools)/lib/apksigner.jar" sign --ks $signing.Key --ks-key-alias $signing.Alias --ks-pass "env:$($signing.PasswordEnv)" --key-pass "env:$($signing.PasswordEnv)" --out "$build/probe.apk" "$build/aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Release probe signing failed.' }
    & $tools.Adb -s $Serial install -r -t "$build/probe.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Release probe installation failed.' }
    $installed = $true
    $output = @(& $tools.Adb -s $Serial shell am instrument -w jp.tekewo.conversation.startupprobe/jp.tekewo.conversation.ReleaseStartupProbe)
    $output | Write-Output
    if ($LASTEXITCODE -ne 0 -or -not ($output -match 'RELEASE_STARTUP_PASS')) { throw 'Installed Release startup probe failed.' }
} finally {
    if ($installed) { & $tools.Adb -s $Serial uninstall jp.tekewo.conversation.startupprobe }
    Clear-ConversationSigning -Signing $signing
}
