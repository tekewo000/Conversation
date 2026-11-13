$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$clang = (Get-Command clang++ -ErrorAction Stop).Source
$build = Join-Path $repo 'build/native-check'
New-Item -ItemType Directory -Force -Path $build | Out-Null
$test = Join-Path $build 'ui-test.exe'
& $clang -std=c++17 -DIMGUI_USE_WCHAR32 -Ivendor/imgui native/tests/ui_test.cpp vendor/imgui/imgui.cpp vendor/imgui/imgui_draw.cpp vendor/imgui/imgui_tables.cpp vendor/imgui/imgui_widgets.cpp -o $test
if ($LASTEXITCODE -ne 0) { throw 'Native CPU UI test compilation failed.' }
& $test
if ($LASTEXITCODE -ne 0) { throw 'Native CPU UI tests failed.' }
