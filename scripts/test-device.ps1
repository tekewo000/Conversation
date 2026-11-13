param([string]$Serial)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$tools = Get-Content -LiteralPath (Join-Path $repo '.tools/android-toolchain.json') -Raw | ConvertFrom-Json
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
& $tools.Adb @deviceArgs shell am start -W -n 'jp.tekewo.conversation/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
$process = & $tools.Adb @deviceArgs shell pidof jp.tekewo.conversation
if (-not $process) { throw 'App process is not running.' }
$logs = & $tools.Adb @deviceArgs logcat -d "--pid=$($process.Trim())" -s Conversation AndroidRuntime libc
$logs | Write-Output
if ($logs -match 'E Conversation:|FATAL EXCEPTION|Fatal signal') { throw 'App initialization or native runtime failed; see the log above.' }
Write-Host 'PASS: app running, no initialization or crash errors in its process log.'
