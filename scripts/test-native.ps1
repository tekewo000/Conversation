$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$clang = (Get-Command clang++ -ErrorAction Stop).Source
$build = Join-Path $repo 'build/native-check'
New-Item -ItemType Directory -Force -Path $build | Out-Null
$test = Join-Path $build 'bridge-test.exe'
& $clang -std=c++17 native/tests/bridge_test.cpp -o $test
if ($LASTEXITCODE -ne 0) { throw 'Native bridge test compilation failed.' }
& $test
if ($LASTEXITCODE -ne 0) { throw 'Native bridge tests failed.' }
& "$PSScriptRoot/setup-rag.ps1"
& $clang -std=c++17 -O2 -D_CRT_SECURE_NO_WARNINGS "-I$repo/.tools/rag/usearch/include" native/tests/semantic_test.cpp -o "$build/semantic-test.exe"
if ($LASTEXITCODE -ne 0) { throw 'USearch test compilation failed.' }
& "$build/semantic-test.exe"
if ($LASTEXITCODE -ne 0) { throw 'USearch tests failed.' }
