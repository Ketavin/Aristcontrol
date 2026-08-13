<#
Build the Windows app image and assemble the versioned laptop ZIP.
Requires the repository-local JDK and Maven to be present on PATH.
#>

$ErrorActionPreference = 'Stop'

$Version = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-version.txt') -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($Version)) {
    throw 'release-version.txt is empty'
}

$buildScript = Join-Path $PSScriptRoot 'build-exe.ps1'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $buildScript
if ($LASTEXITCODE -ne 0) {
    throw "Application image build failed with exit code $LASTEXITCODE"
}

$appImage = Join-Path $PSScriptRoot 'target\installer\AristAIControl'
$support = Join-Path $PSScriptRoot 'packaging\laptop'
$distribution = Join-Path $PSScriptRoot 'target\distribution'
$packageRoot = Join-Path $distribution 'Arist-AI-Control-Windows'
$zipPath = Join-Path $distribution "Arist-AI-Control-v$Version-Windows-x64.zip"
$hashPath = "$zipPath.sha256"

if (-not (Test-Path -LiteralPath (Join-Path $appImage 'AristAIControl.exe'))) {
    throw "App image is incomplete: $appImage"
}
if (Test-Path -LiteralPath $packageRoot) {
    Remove-Item -LiteralPath $packageRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $packageRoot | Out-Null
Copy-Item -LiteralPath $appImage -Destination $packageRoot -Recurse -Force
Get-ChildItem -LiteralPath $support -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $packageRoot -Force
}

foreach ($name in 'README.md','REMOTE-VOICE-TEST-GUIDE.md') {
    $path = Join-Path $packageRoot $name
    $content = (Get-Content -LiteralPath $path -Raw).Replace('{{VERSION}}', $Version)
    [IO.File]::WriteAllText($path, $content, [Text.UTF8Encoding]::new($false))
}

$release = @"
Arist AI Control $Version
Build date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
Platform: Windows x64

Includes Java 17, BLE bridge, cloud speech recognition, clipboard-first
text injection, remote preflight, RDP template, and four-stage test guide.
"@
[IO.File]::WriteAllText((Join-Path $packageRoot 'RELEASE.txt'), $release, [Text.UTF8Encoding]::new($false))

if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
if (Test-Path -LiteralPath $hashPath) { Remove-Item -LiteralPath $hashPath -Force }
Compress-Archive -LiteralPath $packageRoot -DestinationPath $zipPath -CompressionLevel Optimal
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash
[IO.File]::WriteAllText($hashPath, "$hash  $(Split-Path -Leaf $zipPath)`r`n", [Text.Encoding]::ASCII)

Write-Host "Package: $zipPath" -ForegroundColor Green
Write-Host "SHA256: $hash" -ForegroundColor Green
