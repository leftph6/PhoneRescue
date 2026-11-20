param([switch]$SkipAndroid, [switch]$Test, [switch]$Package)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $repoRoot
try {
    dotnet publish PhoneRescue.csproj -c Release -r win-x64 --self-contained true -o release/staging-windows
    if ($LASTEXITCODE -ne 0) { throw 'Windows build failed' }
    if (-not $SkipAndroid) {
        python android/build.py
        if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    }
    if ($Test) {
        python tests/integration.py
        if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
    }
    if ($Package) {
        if (Get-Process PhoneRescue -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq (Join-Path $repoRoot 'release/windows/PhoneRescue.exe') }) {
            throw 'Stop the release/windows bridge before replacing its files, or package from a clean checkout.'
        }
        New-Item -ItemType Directory -Force release/windows | Out-Null
        Get-ChildItem -LiteralPath release/staging-windows -Force | Copy-Item -Destination release/windows -Recurse -Force
        python package.py
        if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
    }
} finally { Pop-Location }
