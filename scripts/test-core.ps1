$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
& "$PSScriptRoot/setup-sqlite.ps1"
$odin = Join-Path $repo '.tools/odin/dist/odin.exe'
if (-not (Test-Path -LiteralPath $odin)) { $odin = (Get-Command odin -ErrorAction Stop).Source }
$build = Join-Path $repo 'build/core-check'
New-Item -ItemType Directory -Force -Path $build | Out-Null
& $odin test core -vet -strict-style -define:ODIN_TEST_THREADS=1 "-out:$build/core-tests.exe"
if ($LASTEXITCODE -ne 0) { throw 'Odin core tests failed.' }
& $odin build core -build-mode:obj -no-entry-point -use-single-module "-out:$build/core_native.obj"
if ($LASTEXITCODE -ne 0) { throw 'Odin C ABI object build failed.' }
& clang -finput-charset=UTF-8 core/c_abi_test.c "$build/core_native.obj" "$repo/.tools/sqlite/windows/sqlite3.lib" -o "$build/c-abi-test.exe" -lkernel32 -lbcrypt -ladvapi32
if ($LASTEXITCODE -ne 0) { throw 'C ABI harness link failed.' }
$storage = Join-Path $build ([guid]::NewGuid().ToString())
& "$build/c-abi-test.exe" $storage write
if ($LASTEXITCODE -ne 0) { throw 'C ABI write process failed.' }
& "$build/c-abi-test.exe" $storage read
if ($LASTEXITCODE -ne 0) { throw 'C ABI read process failed.' }
