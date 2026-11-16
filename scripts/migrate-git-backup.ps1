param(
    [ValidateSet('Migrate','Verify','Restore')][string]$Mode = 'Migrate',
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$RecoveryKey,
    [string]$Destination,
    [string]$WorkDirectory,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$tools = & "$PSScriptRoot/build-backup-tools.ps1"
$arguments = @($Mode.ToLowerInvariant(), '--source', $Source, '--key', (Resolve-Path -LiteralPath $RecoveryKey).Path)
if ($Mode -eq 'Migrate') {
    if (-not $Destination -or -not $WorkDirectory) { throw 'Specify Destination and a dedicated WorkDirectory.' }
    $arguments += @('--destination', $Destination, '--work', [IO.Path]::GetFullPath($WorkDirectory))
} elseif ($Mode -eq 'Restore') {
    if (-not $OutputDirectory) { throw 'Specify a new OutputDirectory.' }
    $arguments += @('--output', [IO.Path]::GetFullPath($OutputDirectory))
}
& $tools.Java '-Dfile.encoding=UTF-8' -classpath $tools.Classpath jp.tekewo.conversation.GitMigration @arguments
if ($LASTEXITCODE -ne 0) { throw 'Git migration/recovery did not complete. Originals were retained; inspect the error and retry with the same inputs.' }
