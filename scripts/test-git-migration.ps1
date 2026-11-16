$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$tools = & "$PSScriptRoot/build-backup-tools.ps1"
& $tools.Javac '-J-Duser.language=en' -encoding UTF-8 --release 17 -classpath "$($tools.Classpath);$repo/build/connection-tests" -d $tools.Classes "$repo/tools/git-migration/GitMigrationTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Migration test compilation failed.' }
& $tools.Java '-Dfile.encoding=UTF-8' "-Dpackaged.classpath=$($tools.Jar);$repo/.tools/connection-tests/json-20240303.jar" -ea -classpath "$($tools.Classpath);$repo/build/connection-tests" jp.tekewo.conversation.GitMigrationTest
if ($LASTEXITCODE -ne 0) { throw 'Git migration tests failed.' }
