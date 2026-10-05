$ErrorActionPreference = 'Stop'
$secret = Read-Host 'Enter Gemini API Key' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr).Trim()
    if ([String]::IsNullOrWhiteSpace($key) -or $key -match '[\r\n]' -or $key.Length -gt 4096) { throw 'Enter a non-empty API key on one line.' }
    $dir = Join-Path $PSScriptRoot '.secrets'
    [IO.Directory]::CreateDirectory($dir) | Out-Null
    [IO.File]::WriteAllText((Join-Path $dir 'gemini-api-key'), $key, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Saved locally. Rebuild and reinstall the APK to apply.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    $key = $null
    $secret.Dispose()
}
