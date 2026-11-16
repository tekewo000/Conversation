$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $repo '.tools/sqlcipher/4.20.0'
$artifact = Join-Path $directory 'sqlcipher-android-4.20.0.aar'
$expected = '1e57857adfd29e2f5ddb4686e1e5ec6659920220d4bf182fdb5ef085b5577100'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
if (-not (Test-Path -LiteralPath $artifact)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/net/zetetic/sqlcipher-android/4.20.0/sqlcipher-android-4.20.0.aar' -OutFile "$artifact.pending"
    if ((Get-FileHash -LiteralPath "$artifact.pending" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'SQLCipher checksum mismatch.' }
    Move-Item -LiteralPath "$artifact.pending" -Destination $artifact
}
if ((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'SQLCipher checksum mismatch.' }
$archive = [IO.Compression.ZipFile]::OpenRead($artifact)
try {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('jni/arm64-v8a/libsqlcipher.so'), "$directory/libsqlcipher.so", $true)
} finally { $archive.Dispose() }
Write-Host 'Pinned SQLCipher 4.20.0 verified.'
