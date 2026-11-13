param([string]$Serial, [string]$CleanupProbeId)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$tools = Get-Content -LiteralPath (Join-Path $repo '.tools/android-toolchain.json') -Raw | ConvertFrom-Json
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$probeId = [guid]::NewGuid().ToString()
if ($CleanupProbeId) {
    if ($CleanupProbeId -notmatch '^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$') { throw 'Invalid cleanup probe ID.' }
    $probeId = $CleanupProbeId
}
$build = Join-Path $repo ('build/person-device-probe/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force -Path "$build/classes", "$build/dex" | Out-Null
& "$($tools.JavaHome)/bin/javac.exe" '-J-Duser.language=en' '-Xlint:-options' -encoding UTF-8 --release 8 -classpath $tools.PlatformJar -d "$build/classes" `
    (Join-Path $repo 'android/src/jp/tekewo/conversation/GitHubTokenStore.java') `
    (Join-Path $repo 'android/src/jp/tekewo/conversation/BackupArchive.java') `
    (Join-Path $repo 'android/device-tests/jp/tekewo/conversation/PersonDeviceProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Person device probe compilation failed.' }
# Only the harness enters the dex; production classes come from the installed APK.
& "$($tools.JavaHome)/bin/java.exe" -classpath "$($tools.BuildTools)/lib/d8.jar" com.android.tools.r8.D8 --min-api 29 --lib $tools.PlatformJar --output "$build/dex" "$build/classes/jp/tekewo/conversation/PersonDeviceProbe.class"
if ($LASTEXITCODE -ne 0) { throw 'Person device probe dex failed.' }
$manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.tekewo.conversation.probe" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36" />
    <instrumentation android:name="jp.tekewo.conversation.PersonDeviceProbe" android:targetPackage="jp.tekewo.conversation" android:functionalTest="true" />
    <application android:label="Conversation synthetic device probe" android:debuggable="true" android:allowBackup="false" />
</manifest>
'@
Set-Content -LiteralPath "$build/AndroidManifest.xml" -Value $manifest -Encoding utf8
& "$($tools.BuildTools)/aapt2.exe" link -o "$build/unsigned.apk" -I $tools.PlatformJar --manifest "$build/AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 36
if ($LASTEXITCODE -ne 0) { throw 'Person device probe manifest packaging failed.' }
& "$($tools.JavaHome)/bin/jar.exe" uf "$build/unsigned.apk" -C "$build/dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Person device probe dex packaging failed.' }
& "$($tools.BuildTools)/zipalign.exe" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Person device probe alignment failed.' }
$key = Join-Path $repo '.tools/conversation-debug.jks'
& "$($tools.JavaHome)/bin/java.exe" -jar "$($tools.BuildTools)/lib/apksigner.jar" sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$build/probe.apk" "$build/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Person device probe signing failed.' }
$installed = $false
try {
    & $tools.Adb @deviceArgs install -r -t "$build/probe.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Person device probe install failed.' }
    $installed = $true
    $instrumentArgs = @('-e', 'probe_id', $probeId)
    if ($CleanupProbeId) { $instrumentArgs += @('-e', 'cleanup_only', 'true') }
    $output = @(& $tools.Adb @deviceArgs shell am instrument -w @instrumentArgs jp.tekewo.conversation.probe/jp.tekewo.conversation.PersonDeviceProbe)
    $output | Write-Output
    if ($LASTEXITCODE -ne 0 -or -not ($output -match 'PERSON_DEVICE_PROBE_PASS')) { throw 'Person device probe failed; see instrumentation output.' }
} finally {
    if ($installed -and -not $CleanupProbeId -and -not ($output -match 'PERSON_DEVICE_PROBE_PASS')) {
        Write-Host "Cleaning only synthetic probe $probeId after diagnostic failure."
        & $tools.Adb @deviceArgs shell am instrument -w -e probe_id $probeId -e cleanup_only true jp.tekewo.conversation.probe/jp.tekewo.conversation.PersonDeviceProbe
    }
    if ($installed) { & $tools.Adb @deviceArgs uninstall jp.tekewo.conversation.probe }
}
