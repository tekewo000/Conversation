param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$tools = & "$PSScriptRoot/setup-android.ps1"
& "$PSScriptRoot/setup-sqlite.ps1"

function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed (exit $LASTEXITCODE)." }
}

$build = Join-Path $repo 'build/android'
$dist = Join-Path $repo 'dist'
foreach ($directory in @($build, $dist, "$build/core", "$build/java", "$build/dex", "$build/package/lib/arm64-v8a")) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
if (-not $SkipTests) {
    Run $tools.Odin @('test', 'core', '-all-packages', '-define:ODIN_TEST_THREADS=1', '-out:build/core-tests.exe',
        "-extra-linker-flags:/LIBPATH:$repo/.tools/sqlite/windows")
}

$llvmBin = Join-Path $tools.Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$object = Join-Path $build 'core/conversation_core.o'
$archive = Join-Path $build 'core/libconversation_core.a'
Run $tools.Odin @('build', 'core', '-target:linux_arm64', '-subtarget:android', '-minimum-os-version:29',
    '-build-mode:obj', '-no-entry-point', '-use-single-module', "-out:$object")
Run "$llvmBin/llvm-ar.exe" @('rcs', $archive, $object)

$cmake = $tools.CMake
$ninja = Join-Path (Split-Path -Parent $cmake) 'ninja.exe'
Run $cmake @('-S', 'native', '-B', "$build/native", '-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$($tools.Ndk)/build/cmake/android.toolchain.cmake", '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Debug',
    "-DCONVERSATION_CORE_LIBRARY=$archive")
Run $cmake @('--build', "$build/native", '--parallel', '4')
Copy-Item -LiteralPath "$build/native/libconversation.so" -Destination "$build/package/lib/arm64-v8a/libconversation.so" -Force

$javaSources = @(Get-ChildItem -LiteralPath 'android/src' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
Run "$($tools.JavaHome)/bin/javac.exe" (@('-J-Duser.language=en', '-encoding', 'UTF-8', '--release', '8',
    '-classpath', $tools.PlatformJar, '-d', "$build/java") + $javaSources)
$classes = @(Get-ChildItem -LiteralPath "$build/java" -Filter '*.class' -Recurse | ForEach-Object { $_.FullName })
Run "$($tools.BuildTools)/d8.bat" (@('--min-api', '29', '--lib', $tools.PlatformJar, '--output', "$build/dex") + $classes)
Run "$($tools.BuildTools)/aapt2.exe" @('link', '-o', "$build/unpacked.apk", '-I', $tools.PlatformJar,
    '--manifest', 'android/AndroidManifest.xml', '-A', 'android/assets', '--min-sdk-version', '29', '--target-sdk-version', '36')
Copy-Item -LiteralPath "$build/dex/classes.dex" -Destination "$build/package/classes.dex" -Force
Run "$($tools.JavaHome)/bin/jar.exe" @('uf', "$build/unpacked.apk", '-C', "$build/package", 'classes.dex', '-C', "$build/package", 'lib')
Run "$($tools.BuildTools)/zipalign.exe" @('-f', '-P', '16', '4', "$build/unpacked.apk", "$build/aligned.apk")

# This public-password key signs development builds only; never use it for release distribution.
$key = Join-Path $repo '.tools/conversation-debug.jks'
if (-not (Test-Path -LiteralPath $key)) {
    Run "$($tools.JavaHome)/bin/keytool.exe" @('-genkeypair', '-keystore', $key, '-storepass', 'android',
        '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-dname', 'CN=Android Debug,O=Conversation,C=JP', '-noprompt')
}
$apk = Join-Path $dist 'Conversation-0.2.0-arm64-debug.apk'
Run "$($tools.BuildTools)/apksigner.bat" @('sign', '--ks', $key, '--ks-key-alias', 'androiddebugkey',
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $apk, "$build/aligned.apk")
Run "$($tools.BuildTools)/apksigner.bat" @('verify', '--verbose', $apk)
Run "$($tools.BuildTools)/zipalign.exe" @('-c', '-P', '16', '4', $apk)
Get-FileHash -LiteralPath $apk -Algorithm SHA256
Write-Host "APK: $apk"
