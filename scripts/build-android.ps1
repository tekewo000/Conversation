param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Release',
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$tools = & "$PSScriptRoot/setup-android.ps1"
& "$PSScriptRoot/setup-sqlite.ps1"

function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed (exit $LASTEXITCODE)." }
}

function Test-NativePageAlignment([string]$Library) {
    $headers = @(Run "$llvmBin/llvm-readelf.exe" @('--program-headers', '--wide', $Library))
    $loads = @($headers | Where-Object { $_ -match '^\s*LOAD\s' })
    if ($loads.Count -eq 0) { throw "Native library has no ELF load segments: $Library" }
    foreach ($segment in $loads) {
        if ($segment -notmatch '\s0x([0-9a-fA-F]+)\s*$' -or [Convert]::ToInt64($Matches[1], 16) -lt 16384) {
            throw "Native library does not support 16 KiB page alignment: $Library"
        }
    }
}

$flavor = $Configuration.ToLowerInvariant()
# Each run starts empty so removed Java/Kotlin classes cannot survive a rebuild.
$build = Join-Path $repo "build/android-$flavor/run-$([guid]::NewGuid().ToString('N'))"
$dist = Join-Path $repo 'dist'
foreach ($directory in @($build, $dist, "$build/core", "$build/symbols", "$build/package/lib/arm64-v8a")) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
Write-Host "Build: $build"
if (-not $SkipTests) {
    Run $tools.Odin @('test', 'core', '-all-packages', '-define:ODIN_TEST_THREADS=1', "-out:$build/core-tests.exe",
        "-extra-linker-flags:/LIBPATH:$repo/.tools/sqlite/windows")
}

$androidNamespace = 'http://schemas.android.com/apk/res/android'
[xml]$manifest = Get-Content -LiteralPath (Join-Path $repo 'android/AndroidManifest.xml') -Raw
$sourceVersion = $manifest.manifest.GetAttribute('versionName', $androidNamespace)
$version = $sourceVersion -replace '-.*$', ''
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Manifest versionName must start with a three-part version.' }
$package = $manifest.manifest.GetAttribute('package')
$versionCode = $manifest.manifest.GetAttribute('versionCode', $androidNamespace)
if ([string]::IsNullOrWhiteSpace($package) -or $versionCode -notmatch '^\d+$') { throw 'Manifest package or versionCode is invalid.' }
$debuggable = if ($Configuration -eq 'Debug') { 'true' } else { 'false' }
[void]$manifest.manifest.application.SetAttribute('debuggable', $androidNamespace, $debuggable)
if ($Configuration -eq 'Release') { [void]$manifest.manifest.SetAttribute('versionName', $androidNamespace, $version) }
$generatedManifest = Join-Path $build 'AndroidManifest.xml'
# AGP owns the namespace and build-type flags; keep the source manifest for the version/install scripts.
[void]$manifest.manifest.RemoveAttribute('package')
[void]$manifest.manifest.application.RemoveAttribute('debuggable', $androidNamespace)
[void]$manifest.manifest.application.RemoveAttribute('extractNativeLibs', $androidNamespace)
$manifest.Save($generatedManifest)
$expectedVersion = $manifest.manifest.GetAttribute('versionName', $androidNamespace)

$llvmBin = Join-Path $tools.Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$object = Join-Path $build 'core/conversation_core.o'
$archive = Join-Path $build 'core/libconversation_core.a'
$odinArguments = @('build', 'core', '-target:linux_arm64', '-subtarget:android', '-minimum-os-version:29',
    '-build-mode:obj', '-no-entry-point', '-use-single-module', "-out:$object")
if ($Configuration -eq 'Release') { $odinArguments += '-o:speed' }
Run $tools.Odin $odinArguments
Run "$llvmBin/llvm-ar.exe" @('rcs', $archive, $object)

$cmake = $tools.CMake
$ninja = Join-Path (Split-Path -Parent $cmake) 'ninja.exe'
Run $cmake @('-S', 'native', '-B', "$build/native", '-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$($tools.Ndk)/build/cmake/android.toolchain.cmake", '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_static', "-DCMAKE_BUILD_TYPE=$Configuration",
    "-DCONVERSATION_CORE_LIBRARY=$archive")
Run $cmake @('--build', "$build/native", '--parallel', '4')
$library = Join-Path $build 'package/lib/arm64-v8a/libconversation.so'
Copy-Item -LiteralPath "$build/native/libconversation.so" -Destination "$build/symbols/libconversation.so" -Force
Copy-Item -LiteralPath "$build/native/libconversation.so" -Destination $library -Force
if ($Configuration -eq 'Release') { Run "$llvmBin/llvm-strip.exe" @('--strip-debug', $library) }
Test-NativePageAlignment $library

Run $tools.Gradle @('-p', "$repo/android", '--console=plain', "assemble$Configuration",
    "-PconversationBuildDir=$build/gradle", "-PconversationManifest=$generatedManifest",
    "-PconversationJniDir=$build/package/lib", "-PconversationVersionCode=$versionCode",
    "-PconversationVersionName=$expectedVersion")
$gradleApkName = if ($Configuration -eq 'Release') { 'Conversation-release-unsigned.apk' } else { 'Conversation-debug.apk' }
$gradleApk = Join-Path $build "gradle/outputs/apk/$flavor/$gradleApkName"
if (-not (Test-Path -LiteralPath $gradleApk)) { throw "Gradle did not produce the APK: $gradleApk" }
Run "$($tools.BuildTools)/zipalign.exe" @('-f', '-P', '16', '4', $gradleApk, "$build/aligned.apk")

. "$PSScriptRoot/android-signing.ps1"
$signing = $null
$signedApk = Join-Path $build 'signed.apk'
try {
    $signing = Get-ConversationSigning -Configuration $Configuration -Tools $tools -Repo $repo
    $signArguments = @('sign', '--ks', $signing.Key, '--ks-key-alias', $signing.Alias,
        '--ks-pass', "env:$($signing.PasswordEnv)", '--key-pass', "env:$($signing.PasswordEnv)")
    if ($Configuration -eq 'Release') {
        if (-not $signing.Lineage) { throw 'Release signing requires the existing app signing lineage.' }
        $signArguments += @('--lineage', $signing.Lineage, '--rotation-min-sdk-version', '28',
            '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'true',
            '--debuggable-apk-permitted', 'false')
    }
    Run "$($tools.BuildTools)/apksigner.bat" ($signArguments + @('--out', $signedApk, "$build/aligned.apk"))
} finally {
    if ($null -ne $signing) { Clear-ConversationSigning -Signing $signing }
}
Run "$($tools.BuildTools)/apksigner.bat" @('verify', '--verbose', '--print-certs', $signedApk)
Run "$($tools.BuildTools)/zipalign.exe" @('-c', '-P', '16', '4', $signedApk)
$apkArchive = [IO.Compression.ZipFile]::OpenRead($signedApk)
try {
    $nativeEntries = @($apkArchive.Entries | Where-Object { $_.FullName -match '^lib/.*\.so$' })
    if ($nativeEntries.Count -eq 0) { throw 'APK has no native libraries.' }
    New-Item -ItemType Directory -Force -Path "$build/elf-check" | Out-Null
    foreach ($entry in $nativeEntries) {
        if ($entry.FullName -notmatch '^lib/arm64-v8a/[a-zA-Z0-9._-]+\.so$') { throw "Unexpected native library path: $($entry.FullName)" }
        $packagedLibrary = Join-Path "$build/elf-check" $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $packagedLibrary, $true)
        Test-NativePageAlignment $packagedLibrary
    }
} finally {
    $apkArchive.Dispose()
}
$badging = (Run "$($tools.BuildTools)/aapt2.exe" @('dump', 'badging', $signedApk)) -join "`n"
if ($badging -notmatch [regex]::Escape("name='$package'") -or
    $badging -notmatch [regex]::Escape("versionCode='$versionCode'") -or
    $badging -notmatch [regex]::Escape("versionName='$expectedVersion'")) { throw 'APK package or version does not match the generated manifest.' }
$apkDebuggable = $badging -match '(?m)^application-debuggable\s*$'
if ($apkDebuggable -ne ($Configuration -eq 'Debug')) { throw 'APK debuggable flag does not match the selected configuration.' }
$apk = Join-Path $dist "Conversation-$version-arm64-$flavor.apk"
Copy-Item -LiteralPath $signedApk -Destination $apk -Force
Get-FileHash -LiteralPath $apk -Algorithm SHA256
Write-Host "APK: $apk"
Write-Host "Unstripped symbols: $build/symbols/libconversation.so"
