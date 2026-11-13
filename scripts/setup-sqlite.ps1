$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$version = '3510200'
$archiveHash = '6E2A845A493026BDBAD0618B2B5A0CF48584FAAB47384480ED9F592D912F23EC'
$source = Join-Path $repo 'vendor/sqlite'
$work = Join-Path $repo '.tools/sqlite'
New-Item -ItemType Directory -Force -Path $source,$work | Out-Null
if (-not (Test-Path "$source/sqlite3.c")) {
    $archive = Join-Path $work "sqlite-amalgamation-$version.zip"
    Invoke-WebRequest "https://www.sqlite.org/2026/sqlite-amalgamation-$version.zip" -OutFile $archive
    if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne $archiveHash) { throw 'SQLite archive checksum mismatch.' }
    Expand-Archive $archive $work -Force
    Copy-Item "$work/sqlite-amalgamation-$version/sqlite3.c","$work/sqlite-amalgamation-$version/sqlite3.h" $source
}
# Source hash is also published in the upstream release page (SHA3-256).
$sha = & python -c 'import hashlib,pathlib,sys; print(hashlib.sha3_256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' "$source/sqlite3.c"
if ($LASTEXITCODE -ne 0 -or $sha -ne '733b3fcc6cccb1e334424b9b91a9d68b618385b76ebfcbb106690bd3a9e61367') { throw 'SQLite source SHA3 checksum mismatch.' }
$out = Join-Path $work 'windows'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$config = 'SQLITE_THREADSAFE=1 SQLITE_ENABLE_FTS5=1 SQLITE_OMIT_LOAD_EXTENSION=1'
if (-not (Test-Path "$out/sqlite3.lib") -or -not (Test-Path "$out/config.txt") -or (Get-Content "$out/config.txt" -Raw).Trim() -ne $config -or (Get-Item "$source/sqlite3.c").LastWriteTimeUtc -gt (Get-Item "$out/sqlite3.lib").LastWriteTimeUtc) {
    & clang -O2 -c "$source/sqlite3.c" -DSQLITE_THREADSAFE=1 -DSQLITE_ENABLE_FTS5=1 -DSQLITE_OMIT_LOAD_EXTENSION=1 -o "$out/sqlite3.obj"
    if ($LASTEXITCODE -ne 0) { throw 'SQLite Windows compilation failed.' }
    & llvm-lib "/OUT:$out/sqlite3.lib" "$out/sqlite3.obj"
    if ($LASTEXITCODE -ne 0) { throw 'SQLite Windows archive failed.' }
    Set-Content "$out/config.txt" $config -Encoding ascii
}
Write-Host 'Pinned SQLite 3.51.2 source verified; Windows static library ready.'
