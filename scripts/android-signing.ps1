function Clear-ConversationSigning {
    param($Signing)
    if ($Signing -and $Signing.PasswordEnv) {
        [Environment]::SetEnvironmentVariable($Signing.PasswordEnv, $null, 'Process')
    }
}

function Get-ConversationSigning {
    param(
        [ValidateSet('Debug', 'Release')][string]$Configuration,
        $Tools,
        [string]$Repo
    )
    $passwordEnv = 'CONVERSATION_SIGNING_' + [guid]::NewGuid().ToString('N')
    $signing = [pscustomobject]@{ Key = ''; Alias = ''; PasswordEnv = $passwordEnv; Lineage = $null }
    try {
        $debugKey = Join-Path $Repo '.tools/conversation-debug.jks'
        if ($Configuration -eq 'Debug') {
            $signing.Key = $debugKey; $signing.Alias = 'androiddebugkey'
            [Environment]::SetEnvironmentVariable($passwordEnv, 'android', 'Process')
            if (-not (Test-Path -LiteralPath $debugKey)) {
                & "$($Tools.JavaHome)/bin/keytool.exe" -genkeypair -keystore $debugKey -storepass:env $passwordEnv `
                    -keypass:env $passwordEnv -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
                    -dname 'CN=Android Debug,O=Conversation,C=JP' -noprompt | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Could not create development signing key.' }
            }
            return $signing
        }
        if (-not $IsWindows) { throw 'Release signing currently requires this Windows user account.' }
        $signingRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Conversation/signing'
        New-Item -ItemType Directory -Force -Path $signingRoot | Out-Null
        $signing.Key = Join-Path $signingRoot 'release.p12'
        $signing.Alias = 'conversation-release'
        $passwordFile = Join-Path $signingRoot 'password.dpapi'
        $lineage = Join-Path $signingRoot 'lineage.bin'
        $mutex = [Threading.Mutex]::new($false, 'Local\ConversationReleaseSigning')
        $locked = $false
        try {
            $locked = $mutex.WaitOne(30000)
            if (-not $locked) { throw 'Another release signing operation is running.' }
            $hasKey = Test-Path -LiteralPath $signing.Key
            $hasPassword = Test-Path -LiteralPath $passwordFile
            if ($hasKey -ne $hasPassword) { throw 'Release signing files are incomplete. Restore the existing key and password file; do not replace them.' }
            $entropy = [Text.Encoding]::UTF8.GetBytes('Conversation.Android.Signing.v1')
            if (-not $hasKey) {
                $random = [byte[]]::new(48)
                [Security.Cryptography.RandomNumberGenerator]::Fill($random)
                $password = [Convert]::ToBase64String($random)
                [Array]::Clear($random, 0, $random.Length)
                [Environment]::SetEnvironmentVariable($passwordEnv, $password, 'Process')
                $protected = [Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($password),
                    $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
                $pendingPassword = "$passwordFile.pending"
                $pendingKey = "$($signing.Key).pending"
                if ((Test-Path -LiteralPath $pendingPassword) -or (Test-Path -LiteralPath $pendingKey)) {
                    throw 'An unfinished signing-key setup exists. Recover those files before retrying.'
                }
                [IO.File]::WriteAllBytes($pendingPassword, $protected)
                & "$($Tools.JavaHome)/bin/keytool.exe" -genkeypair -storetype PKCS12 -keystore $pendingKey `
                    -storepass:env $passwordEnv -keypass:env $passwordEnv -alias $signing.Alias -keyalg RSA -keysize 3072 `
                    -validity 10000 -dname 'CN=Conversation,O=Conversation,C=JP' -noprompt | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Could not create release signing key; pending files were retained for recovery.' }
                [IO.File]::Move($pendingKey, $signing.Key)
                [IO.File]::Move($pendingPassword, $passwordFile)
            } else {
                $plain = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($passwordFile),
                    $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
                try { [Environment]::SetEnvironmentVariable($passwordEnv, [Text.Encoding]::UTF8.GetString($plain), 'Process') }
                finally { [Array]::Clear($plain, 0, $plain.Length) }
            }
            & "$($Tools.JavaHome)/bin/keytool.exe" -list -keystore $signing.Key -storepass:env $passwordEnv `
                -alias $signing.Alias | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'The existing release signing key could not be opened.' }
            if ((Test-Path -LiteralPath $debugKey) -and -not (Test-Path -LiteralPath $lineage)) {
                $pendingLineage = "$lineage.pending"
                & "$($Tools.JavaHome)/bin/java.exe" -jar "$($Tools.BuildTools)/lib/apksigner.jar" rotate --out $pendingLineage `
                    --old-signer --ks $debugKey --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android `
                    --set-installed-data true --set-shared-uid false --set-permission false --set-rollback false --set-auth false `
                    --new-signer --ks $signing.Key --ks-key-alias $signing.Alias --ks-pass "env:$passwordEnv" --key-pass "env:$passwordEnv"
                if ($LASTEXITCODE -ne 0) { throw 'Could not create the data-preserving signing lineage.' }
                [IO.File]::Move($pendingLineage, $lineage)
            }
            if (Test-Path -LiteralPath $lineage) { $signing.Lineage = $lineage }
        } finally {
            if ($locked) { $mutex.ReleaseMutex() }
            $mutex.Dispose()
        }
        return $signing
    } catch {
        Clear-ConversationSigning -Signing $signing
        throw
    }
}
