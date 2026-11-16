param(
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$RecoveryKey,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$tools = & "$PSScriptRoot/build-backup-tools.ps1"
$arguments = @('verify', (Resolve-Path -LiteralPath $RecoveryKey).Path, (Resolve-Path -LiteralPath $Source).Path)
if ($OutputDirectory) { $arguments[0] = 'restore'; $arguments += [IO.Path]::GetFullPath($OutputDirectory) }
& $tools.Java '-Dfile.encoding=UTF-8' -classpath $tools.Classpath jp.tekewo.conversation.BackupRestore @arguments
if ($LASTEXITCODE -ne 0) { throw 'Encrypted backup verification/recovery failed.' }
