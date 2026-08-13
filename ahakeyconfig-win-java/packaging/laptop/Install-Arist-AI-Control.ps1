$ErrorActionPreference = 'Stop'

function New-Shortcut {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$TargetPath,
        [string]$Arguments = ''
    )

    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($Path)
    $shortcut.TargetPath = $TargetPath
    $shortcut.Arguments = $Arguments
    $shortcut.WorkingDirectory = Split-Path -Parent $TargetPath
    $shortcut.IconLocation = "$TargetPath,0"
    $shortcut.Save()
}

function Save-DpapiApiKey {
    param([Parameter(Mandatory = $true)][string]$Path)

    $secure = Read-Host 'Enter the Bailian DashScope API Key (input is hidden)' -AsSecureString
    if ($secure.Length -lt 4) {
        Write-Warning 'The API Key is empty. Run Install.cmd again later to configure it.'
        return
    }

    Add-Type -AssemblyName System.Security
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $clearBytes = $null
    $encrypted = $null
    try {
        $clearText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        $clearBytes = [Text.Encoding]::UTF8.GetBytes($clearText.Trim())
        $encrypted = [Security.Cryptography.ProtectedData]::Protect(
            $clearBytes,
            $null,
            [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        $directory = Split-Path -Parent $Path
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
        [IO.File]::WriteAllText($Path, [Convert]::ToBase64String($encrypted), [Text.Encoding]::ASCII)
    }
    finally {
        if ($clearBytes) { [Array]::Clear($clearBytes, 0, $clearBytes.Length) }
        if ($encrypted) { [Array]::Clear($encrypted, 0, $encrypted.Length) }
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

if (-not $env:LOCALAPPDATA) {
    throw 'LOCALAPPDATA is unavailable. Sign in as a standard Windows user and retry.'
}

$source = Join-Path $PSScriptRoot 'AristAIControl'
$target = Join-Path $env:LOCALAPPDATA 'Programs\AristAIControl'
$legacyTarget = Join-Path $env:LOCALAPPDATA 'Programs\AhaKeyStudio-Qwen'
$appExePath = Join-Path $target 'AristAIControl.exe'
$keyPath = Join-Path $env:LOCALAPPDATA 'AristAIControl\dashscope.key'
$legacyKeyPath = Join-Path $env:LOCALAPPDATA 'AhaKeyVoiceBridge\dashscope.key'
$legacyDataRoot = Join-Path $env:LOCALAPPDATA 'AhaKeyStudio-Qwen'
$productDataRoot = Join-Path $env:LOCALAPPDATA 'AristAIControl'

if (-not (Test-Path -LiteralPath (Join-Path $source 'AristAIControl.exe'))) {
    throw "The package is incomplete: $source\AristAIControl.exe was not found."
}
if (-not (Test-Path -LiteralPath (Join-Path $source 'BLE_tcp_driver.exe'))) {
    throw "The package is incomplete: $source\BLE_tcp_driver.exe was not found."
}

Get-Process -Name 'AristAIControl','AhaKeyStudio','BLE_tcp_driver' -ErrorAction SilentlyContinue |
    Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500

$backup = $null
if (Test-Path -LiteralPath $target) {
    $backup = "$target.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item -LiteralPath $target -Destination $backup
}

$legacyBackup = $null
if (Test-Path -LiteralPath $legacyTarget) {
    $legacyBackup = "$legacyTarget.migrated-backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item -LiteralPath $legacyTarget -Destination $legacyBackup
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
Copy-Item -LiteralPath $source -Destination $target -Recurse -Force

# The BLE bridge keeps the paired AhaKey identity beside its executable.  Keep
# that user-specific file across upgrades so the freshly installed bridge can
# reconnect without asking the user to pair the same keyboard again.
foreach ($previousInstall in @($backup, $legacyBackup)) {
    if (-not $previousInstall) { continue }
    $oldBleConfig = Join-Path $previousInstall 'config_server.json'
    if (Test-Path -LiteralPath $oldBleConfig -PathType Leaf) {
        Copy-Item -LiteralPath $oldBleConfig -Destination (Join-Path $target 'config_server.json') -Force
        break
    }
}

# Resolve the copied launcher again instead of relying only on the path that was
# calculated before the upgrade. This also turns a partial copy into an explicit
# installation error rather than passing an empty value to Start-Process.
$appExePath = Join-Path $target 'AristAIControl.exe'
if (-not (Test-Path -LiteralPath $appExePath -PathType Leaf)) {
    throw "The application copy is incomplete: $appExePath was not found. The old-version backup was retained."
}
$appExePath = (Resolve-Path -LiteralPath $appExePath).Path

$desktopDirectory = [Environment]::GetFolderPath('Desktop')
$startMenuDirectory = [Environment]::GetFolderPath('Programs')
$startupDirectory = [Environment]::GetFolderPath('Startup')
foreach ($legacyShortcut in @(
    (Join-Path $desktopDirectory 'AhaKey Studio Qwen.lnk'),
    (Join-Path $startMenuDirectory 'AhaKey Studio Qwen.lnk'),
    (Join-Path $startupDirectory 'AhaKey Studio Qwen.lnk')
)) {
    if (Test-Path -LiteralPath $legacyShortcut -PathType Leaf) {
        Remove-Item -LiteralPath $legacyShortcut -Force
    }
}

$desktopShortcut = Join-Path $desktopDirectory 'Arist AI Control.lnk'
$startMenuShortcut = Join-Path $startMenuDirectory 'Arist AI Control.lnk'
$startupShortcut = Join-Path $startupDirectory 'Arist AI Control.lnk'
New-Shortcut -Path $desktopShortcut -TargetPath $appExePath
New-Shortcut -Path $startMenuShortcut -TargetPath $appExePath
New-Shortcut -Path $startupShortcut -TargetPath $appExePath -Arguments '--startup'

New-Item -ItemType Directory -Force -Path $productDataRoot | Out-Null
if (-not (Test-Path -LiteralPath $keyPath -PathType Leaf) -and
    (Test-Path -LiteralPath $legacyKeyPath -PathType Leaf)) {
    Copy-Item -LiteralPath $legacyKeyPath -Destination $keyPath
}
foreach ($name in 'terminology.txt','corrections.tsv') {
    $legacyDataFile = Join-Path $legacyDataRoot $name
    $productDataFile = Join-Path $productDataRoot $name
    if (-not (Test-Path -LiteralPath $productDataFile -PathType Leaf) -and
        (Test-Path -LiteralPath $legacyDataFile -PathType Leaf)) {
        Copy-Item -LiteralPath $legacyDataFile -Destination $productDataFile
    }
}

if (Test-Path -LiteralPath $keyPath) {
    Write-Host 'The existing Bailian API Key for this Windows user was retained.' -ForegroundColor Green
}
else {
    $answer = Read-Host 'Configure the Bailian DashScope API Key now? [Y/n]'
    if ([string]::IsNullOrWhiteSpace($answer) -or $answer -match '^[Yy]') {
        Save-DpapiApiKey -Path $keyPath
    }
    else {
        Write-Warning 'No API Key was configured. The app can start, but cloud speech recognition is unavailable.'
    }
}

Write-Host "Installation complete: $target" -ForegroundColor Green
if ($backup) {
    Write-Host "Old-version backup: $backup" -ForegroundColor Yellow
}
if ($legacyBackup) {
    Write-Host "Legacy application backup: $legacyBackup" -ForegroundColor Yellow
}
if (-not (Test-Path -LiteralPath $appExePath -PathType Leaf)) {
    throw "Installation completed, but the launcher is missing: $appExePath"
}
Start-Process -FilePath $appExePath -WorkingDirectory $target
