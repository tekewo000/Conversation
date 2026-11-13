$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/android-signing.ps1"
$tools = Get-Content -LiteralPath (Join-Path $repo '.tools/android-toolchain.json') -Raw | ConvertFrom-Json
foreach ($configuration in @('Debug', 'Release')) {
    $first = $null; $second = $null
    try {
        $first = Get-ConversationSigning -Configuration $configuration -Tools $tools -Repo $repo
        if (-not [Environment]::GetEnvironmentVariable($first.PasswordEnv, 'Process')) { throw 'Signing environment was not initialized.' }
        $digest = (Get-FileHash -LiteralPath $first.Key -Algorithm SHA256).Hash
        $second = Get-ConversationSigning -Configuration $configuration -Tools $tools -Repo $repo
        if ($first.Key -ne $second.Key -or $digest -ne (Get-FileHash -LiteralPath $second.Key -Algorithm SHA256).Hash) {
            throw 'Repeated setup changed the signing key.'
        }
        if ($configuration -eq 'Release' -and -not (Test-Path -LiteralPath $second.Lineage)) { throw 'The development-to-release lineage is missing.' }
    } finally {
        Clear-ConversationSigning -Signing $first
        Clear-ConversationSigning -Signing $second
    }
    foreach ($signing in @($first, $second)) {
        if ([Environment]::GetEnvironmentVariable($signing.PasswordEnv, 'Process')) { throw 'Signing password environment was not cleared.' }
    }
}
Write-Host 'PASS: stable signing keys, encrypted-password reload, rotation lineage and environment cleanup.'
