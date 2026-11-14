$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$configPath = Join-Path $repo '.tools/android-toolchain.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'Run scripts/setup-android.ps1 first.' }
$toolchain = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$jsonJar = Join-Path $repo '.tools/connection-tests/json-20240303.jar'
$expected = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if (-not (Test-Path -LiteralPath $jsonJar)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $jsonJar) | Out-Null
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $jsonJar
}
if ((Get-FileHash -LiteralPath $jsonJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Test-only JSON jar checksum mismatch.' }
$classes = Join-Path $repo 'build/connection-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sourceRoot = Join-Path $repo 'android/src/jp/tekewo/conversation'
$tests = @(Get-ChildItem -LiteralPath 'android/tests' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$sources = @("$sourceRoot/OAuthValidation.java", "$sourceRoot/SseReader.java", "$sourceRoot/ChatRequest.java") + $tests
if (Test-Path -LiteralPath "$sourceRoot/ChatSession.java") { $sources += "$sourceRoot/ChatSession.java" }
if (Test-Path -LiteralPath "$sourceRoot/BackupArchive.java") { $sources += "$sourceRoot/BackupArchive.java" }
$sources += @('PersonDocument', 'PersonRegistry', 'GitArchiveFiles', 'GitHubBackup', 'ReplyText', 'MemoryWorker', 'HttpRequestPool') | ForEach-Object { "$sourceRoot/$_.java" }
& "$($toolchain.JavaHome)/bin/javac.exe" '-J-Duser.language=en' -encoding UTF-8 --release 8 -Xlint:-options -classpath $jsonJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Connection test compilation failed.' }
& "$($toolchain.JavaHome)/bin/java.exe" '-Dfile.encoding=UTF-8' -ea -classpath "$classes;$jsonJar" jp.tekewo.conversation.ConnectionTest
if ($LASTEXITCODE -ne 0) { throw 'Connection tests failed.' }
if (Test-Path -LiteralPath "$sourceRoot/ChatSession.java") {
    & "$($toolchain.JavaHome)/bin/java.exe" '-Dfile.encoding=UTF-8' -ea -classpath "$classes;$jsonJar" jp.tekewo.conversation.ChatSessionTest
    if ($LASTEXITCODE -ne 0) { throw 'Chat session tests failed.' }
}
if (Test-Path -LiteralPath "$sourceRoot/BackupArchive.java") {
    & "$($toolchain.JavaHome)/bin/java.exe" '-Dfile.encoding=UTF-8' -ea -classpath "$classes;$jsonJar" jp.tekewo.conversation.BackupArchiveTest
    if ($LASTEXITCODE -ne 0) { throw 'Backup archive tests failed.' }
}
foreach ($test in @('PersonDocumentTest', 'PersonRegistryTest', 'GitArchiveFilesTest', 'GitHubBackupTest', 'MemoryWorkerTest', 'HttpRequestPoolTest', 'ReplyTextTest')) {
    & "$($toolchain.JavaHome)/bin/java.exe" '-Dfile.encoding=UTF-8' -ea -classpath "$classes;$jsonJar" "jp.tekewo.conversation.$test"
    if ($LASTEXITCODE -ne 0) { throw "$test failed." }
}
