$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$work = Join-Path $repo '.tools/rag'
$include = Join-Path $work 'usearch/include/usearch'
New-Item -ItemType Directory -Force -Path $include | Out-Null
$headers = @{
    'index.hpp' = '42141603ddd0a48d286849aa2d763acefa9c315f9ae983beebe0f8df73a8c3ee'
    'index_plugins.hpp' = 'a480e64ca5216b46127b34f26c5d9c985f5651a05c2ec7bba680e2536963c456'
    'index_dense.hpp' = 'd57d57eb192656d85e8e2b5857ce4710414e6affa77968c1822edf604a2f6904'
}
foreach ($name in $headers.Keys) {
    $file = Join-Path $include $name
    if (-not (Test-Path -LiteralPath $file)) {
        Invoke-WebRequest "https://raw.githubusercontent.com/unum-cloud/USearch/v2.26.2/include/usearch/$name" -OutFile $file
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $headers[$name]) { throw "USearch checksum mismatch: $name" }
}
$archive = Join-Path $work 'sentencepiece-v0.2.1.zip'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://github.com/google/sentencepiece/archive/refs/tags/v0.2.1.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'c26e1d516a0d52ee1f66bd8a64831b2f1ba74f183fa0010b4a728aedc1dfa854') { throw 'SentencePiece checksum mismatch.' }
$source = Join-Path $work 'sentencepiece-0.2.1'
if (-not (Test-Path -LiteralPath "$source/CMakeLists.txt")) { Expand-Archive -LiteralPath $archive -DestinationPath $work }
Write-Host 'Pinned USearch 2.26.2 and SentencePiece 0.2.1 sources verified.'
