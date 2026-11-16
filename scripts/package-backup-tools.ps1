param([Parameter(Mandatory)][string]$Version)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Specify a three-part release version.' }
$repo = Split-Path -Parent $PSScriptRoot
$tools = & "$PSScriptRoot/build-backup-tools.ps1"
$stage = Join-Path $repo "build/backup-package/$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item -LiteralPath $tools.Jar -Destination "$stage/Conversation-backup-tools.jar"
Copy-Item -LiteralPath "$repo/.tools/connection-tests/json-20240303.jar" -Destination $stage
foreach ($script in @('build-backup-tools.ps1','migrate-git-backup.ps1','restore-backup.ps1')) { Copy-Item -LiteralPath "$PSScriptRoot/$script" -Destination $stage }
Copy-Item -LiteralPath "$repo/docs/git-backup-migration.md" -Destination "$stage/README.md"
$output = Join-Path $repo "dist/Conversation-backup-tools-$Version.zip"
if (Test-Path -LiteralPath $output) { throw 'The package exists; use a new version or explicitly remove your previous local package.' }
[IO.Compression.ZipFile]::CreateFromDirectory($stage,$output)
Get-FileHash -LiteralPath $output -Algorithm SHA256
