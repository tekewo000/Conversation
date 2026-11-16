$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath "$PSScriptRoot/Conversation-backup-tools.jar") {
    $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) { "$env:JAVA_HOME/bin/java.exe" } else { (Get-Command java -ErrorAction Stop).Source }
    $version = (& $java -version 2>&1 | Out-String)
    if ($version -notmatch 'version "(\d+)' -or [int]$Matches[1] -lt 17) { throw 'Install Java 17 or later, or set JAVA_HOME to it.' }
    return @{ Java = $java; Classpath = "$PSScriptRoot/Conversation-backup-tools.jar;$PSScriptRoot/json-20240303.jar" }
}
$repo = Split-Path -Parent $PSScriptRoot
$toolchain = Get-Content -LiteralPath "$repo/.tools/android-toolchain.json" -Raw | ConvertFrom-Json
$json = Join-Path $repo '.tools/connection-tests/json-20240303.jar'
if (-not (Test-Path -LiteralPath $json) -or (Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash.ToLowerInvariant() -ne '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed') { throw 'Run scripts/test-connection.ps1 to provision the verified JSON library.' }
$classes = Join-Path $repo "build/backup-tools/run-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& "$($toolchain.JavaHome)/bin/javac.exe" '-J-Duser.language=en' -encoding UTF-8 --release 17 -sourcepath "$repo/android/src" -classpath $json -d $classes "$repo/tools/git-migration/GitMigration.java" "$repo/android/src/jp/tekewo/conversation/BackupRestore.java"
if ($LASTEXITCODE -ne 0) { throw 'Backup tool compilation failed.' }
New-Item -ItemType Directory -Force -Path "$repo/dist" | Out-Null
& "$($toolchain.JavaHome)/bin/jar.exe" --create --file "$repo/dist/Conversation-backup-tools.jar" --main-class jp.tekewo.conversation.GitMigration -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Backup tool JAR packaging failed.' }
return @{ Java = "$($toolchain.JavaHome)/bin/java.exe"; Javac = "$($toolchain.JavaHome)/bin/javac.exe"; Classpath = "$classes;$json"; Classes = $classes; Jar = "$repo/dist/Conversation-backup-tools.jar" }
