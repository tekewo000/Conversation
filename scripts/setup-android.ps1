$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $repo '.tools'
$downloads = Join-Path $toolsRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloads | Out-Null

function Get-Archive([string]$Name, [string]$Url, [string]$Hash, [string]$Algorithm = 'SHA256') {
    $archive = Join-Path $downloads $Name
    if (-not (Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash -ne $Hash) {
        Write-Host "Downloading $Name (resuming cached downloads)..."
        & curl.exe --fail --location --silent --show-error --retry 3 --continue-at - --output $archive $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url (curl exit $LASTEXITCODE)." }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash -ne $Hash) {
        throw "Checksum failed: $archive. Remove that cached file and run setup again."
    }
    return $archive
}

function Test-Jdk([string]$Path) {
    if (-not $Path -or -not (Test-Path -LiteralPath "$Path/bin/javac.exe")) { return $false }
    $version = (& "$Path/bin/javac.exe" -version 2>&1 | Out-String).Trim()
    return $LASTEXITCODE -eq 0 -and $version -match '^javac (17|21)\.'
}

$javaHome = @($env:JAVA_HOME, 'C:/Program Files/Android/Android Studio/jbr', (Join-Path $toolsRoot 'java/jdk-21.0.10+7')) |
    Where-Object { Test-Jdk $_ } | Select-Object -First 1
if (-not $javaHome) {
    # Official Temurin release metadata: adoptium/temurin21-binaries, jdk-21.0.10+7.
    $zip = Get-Archive 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.10_7.zip' 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.10%2B7/OpenJDK21U-jdk_x64_windows_hotspot_21.0.10_7.zip' '08cae782814f027f8b159d6b68823f0f87422eb475c1a0ea1abc7a4357aaf11f'
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $toolsRoot 'java') -Force
    $javaHome = Join-Path $toolsRoot 'java/jdk-21.0.10+7'
}
$env:JAVA_HOME = $javaHome

$gradle = Join-Path $toolsRoot 'gradle/gradle-8.13/bin/gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) {
    $zip = Get-Archive 'gradle-8.13-bin.zip' 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $toolsRoot 'gradle') -Force
}

function Test-Sdk([string]$Path) {
    if (-not $Path) { return $false }
    foreach ($file in @('platforms/android-36/android.jar', 'build-tools/36.0.0/aapt2.exe', 'build-tools/36.0.0/d8.bat', 'build-tools/36.0.0/zipalign.exe', 'build-tools/36.0.0/apksigner.bat', 'platform-tools/adb.exe', 'cmake/3.22.1/bin/cmake.exe', 'cmake/3.22.1/bin/ninja.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Path $file))) { return $false }
    }
    return $true
}

$sdk = @($env:ODIN_ANDROID_SDK, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA/Android/Sdk", 'D:/Android/Sdk', 'D:/Android', (Join-Path $toolsRoot 'android/sdk')) |
    Where-Object { Test-Sdk $_ } | Select-Object -First 1
if (-not $sdk) {
    $sdk = Join-Path $toolsRoot 'android/sdk'
    $sdkManager = Join-Path $sdk 'cmdline-tools/19.0/bin/sdkmanager.bat'
    if (-not (Test-Path -LiteralPath $sdkManager)) {
        # Pinned Android command-line tools 19.0; hash from Google's repository2-3.xml.
        $zip = Get-Archive 'commandlinetools-win-13114758_latest.zip' 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' '54a582f3bf73e04253602f2d1c80bd5868aac115' 'SHA1'
        $unpack = Join-Path $toolsRoot 'android/commandlinetools-19'
        Expand-Archive -LiteralPath $zip -DestinationPath $unpack -Force
        New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
        Move-Item -LiteralPath (Join-Path $unpack 'cmdline-tools') -Destination (Join-Path $sdk 'cmdline-tools/19.0')
    }
    if (-not (Test-Path -LiteralPath "$sdk/licenses/android-sdk-license")) {
        Write-Host 'Review the Android SDK license terms below and answer sdkmanager yourself; setup never accepts licenses automatically.'
        & $sdkManager "--sdk_root=$sdk" --licenses | Out-Host
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath "$sdk/licenses/android-sdk-license")) {
            throw 'Android SDK license acceptance is required. Run setup interactively to review the terms.'
        }
    }
    & $sdkManager "--sdk_root=$sdk" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools' 'cmake;3.22.1' | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Android SDK installation failed (exit $LASTEXITCODE)." }
}

$ndk = Join-Path $toolsRoot 'android/android-ndk-r30'
if (-not (Test-Path -LiteralPath "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe")) {
    # Odin dev-2026-11a's Android bindings target NDK r30. Official Google repository checksum.
    $zip = Get-Archive 'android-ndk-r30-windows.zip' 'https://dl.google.com/android/repository/android-ndk-r30-windows.zip' '9bf167a1985fa7d4a036186b78f702eab9179408' 'SHA1'
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $toolsRoot 'android') -Force
}
$odin = Join-Path $toolsRoot 'odin/dist/odin.exe'
if (-not (Test-Path -LiteralPath $odin)) {
    $zip = Get-Archive 'odin-windows-amd64-dev-2026-11a.zip' 'https://github.com/odin-lang/Odin/releases/download/dev-2026-11a/odin-windows-amd64-dev-2026-11a.zip' '259effedf2997293c8fa4a5e497496f8ebce9f826652d9dd353a9dd2113c31fd'
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $toolsRoot 'odin') -Force
}

$env:ANDROID_HOME = $sdk
$env:ODIN_ANDROID_SDK = $sdk
$env:ODIN_ANDROID_NDK = $ndk
$buildTools = Join-Path $sdk 'build-tools/36.0.0'
$env:PATH = "$javaHome/bin;$buildTools;$sdk/platform-tools;$env:PATH"
$config = [pscustomobject]@{
    Odin = $odin
    JavaHome = $javaHome
    Sdk = $sdk
    Ndk = $ndk
    BuildTools = $buildTools
    PlatformJar = Join-Path $sdk 'platforms/android-36/android.jar'
    Adb = Join-Path $sdk 'platform-tools/adb.exe'
    CMake = Join-Path $sdk 'cmake/3.22.1/bin/cmake.exe'
    Gradle = $gradle
}

# These checks also catch interrupted extractions and incompatible existing installations.
if (-not (Test-Jdk $javaHome) -or -not (Test-Sdk $sdk)) { throw 'JDK or Android SDK verification failed.' }
if ((Get-Content -LiteralPath "$ndk/source.properties" -Raw) -notmatch 'Pkg.Revision\s*=\s*30\.0\.16248370') { throw 'Expected Android NDK r30 (30.0.16248370).' }
foreach ($check in @(@($odin, 'version'), @("$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe", '--version'), @("$buildTools/aapt2.exe", 'version'), @("$buildTools/d8.bat", '--version'), @("$buildTools/apksigner.bat", 'version'), @($config.Adb, 'version'))) {
    $program = $check[0]
    $argument = $check[1]
    & $program $argument | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Tool verification failed: $program (exit $LASTEXITCODE)." }
}
$config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $toolsRoot 'android-toolchain.json') -Encoding UTF8
return $config
