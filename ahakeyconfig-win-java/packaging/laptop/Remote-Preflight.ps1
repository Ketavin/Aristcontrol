$ErrorActionPreference = 'Continue'

$results = New-Object System.Collections.Generic.List[string]
function Add-Result([string]$Status, [string]$Item, [string]$Detail) {
    $line = '[{0}] {1}: {2}' -f $Status, $Item, $Detail
    $results.Add($line)
    $color = switch ($Status) {
        'PASS' { 'Green' }
        'WARN' { 'Yellow' }
        default { 'Red' }
    }
    Write-Host $line -ForegroundColor $color
}

Write-Host 'Arist AI Control remote voice preflight' -ForegroundColor Cyan
Write-Host 'This check never reads or prints the API key.' -ForegroundColor DarkGray

$windows = [Environment]::OSVersion.Version
Add-Result ($(if ([Environment]::Is64BitOperatingSystem) { 'PASS' } else { 'FAIL' })) `
    'Windows' "$windows; 64-bit=$([Environment]::Is64BitOperatingSystem)"

$dotNetRelease = 0
foreach ($path in @(
    'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\NET Framework Setup\NDP\v4\Full'
)) {
    try {
        $value = (Get-ItemProperty -LiteralPath $path -Name Release -ErrorAction Stop).Release
        if ($value -gt $dotNetRelease) { $dotNetRelease = $value }
    } catch { }
}
Add-Result ($(if ($dotNetRelease -ge 461808) { 'PASS' } else { 'WARN' })) `
    '.NET Framework' "Release=$dotNetRelease; BLE bridge requires 4.7.2 or newer"

$installRoot = Join-Path $env:LOCALAPPDATA 'Programs\AristAIControl'
$appPath = Join-Path $installRoot 'AristAIControl.exe'
$bridgePath = Join-Path $installRoot 'BLE_tcp_driver.exe'
Add-Result ($(if (Test-Path -LiteralPath $appPath) { 'PASS' } else { 'FAIL' })) 'Arist AI Control' $appPath
Add-Result ($(if (Test-Path -LiteralPath $bridgePath) { 'PASS' } else { 'FAIL' })) 'BLE bridge' $bridgePath

$keyPath = Join-Path $env:LOCALAPPDATA 'AristAIControl\dashscope.key'
Add-Result ($(if (Test-Path -LiteralPath $keyPath) { 'PASS' } else { 'WARN' })) `
    'DashScope key' $(if (Test-Path -LiteralPath $keyPath) { 'Encrypted key file exists' } else { 'Not configured' })

$bluetooth = Get-Service -Name 'bthserv' -ErrorAction SilentlyContinue
Add-Result ($(if ($bluetooth -and $bluetooth.Status -eq 'Running') { 'PASS' } else { 'WARN' })) `
    'Bluetooth service' $(if ($bluetooth) { [string]$bluetooth.Status } else { 'Not found' })

$tailscale = Get-Service -Name 'Tailscale' -ErrorAction SilentlyContinue
Add-Result ($(if ($tailscale -and $tailscale.Status -eq 'Running') { 'PASS' } else { 'WARN' })) `
    'Tailscale service' $(if ($tailscale) { [string]$tailscale.Status } else { 'Not found; ignore if another remote tool is used' })

foreach ($port in 9000, 8765) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    Add-Result ($(if ($listener) { 'PASS' } else { 'WARN' })) `
        "Local port $port" $(if ($listener) { "Listening; PID=$($listener.OwningProcess)" } else { 'Not listening; start Arist AI Control first' })
}

$app = Get-Process -Name 'AristAIControl' -ErrorAction SilentlyContinue | Select-Object -First 1
$bridge = Get-Process -Name 'BLE_tcp_driver' -ErrorAction SilentlyContinue | Select-Object -First 1
Add-Result ($(if ($app) { 'PASS' } else { 'WARN' })) 'Arist AI Control process' $(if ($app) { "PID=$($app.Id)" } else { 'Not running' })
Add-Result ($(if ($bridge) { 'PASS' } else { 'WARN' })) 'BLE bridge process' $(if ($bridge) { "PID=$($bridge.Id)" } else { 'Not running' })

$remoteNames = @('mstsc', 'ToDesk', 'SunloginClient', 'AnyDesk')
$activeRemote = foreach ($name in $remoteNames) {
    Get-Process -Name $name -ErrorAction SilentlyContinue | Select-Object -ExpandProperty ProcessName
}
Add-Result ($(if ($activeRemote) { 'PASS' } else { 'WARN' })) `
    'Remote client' $(if ($activeRemote) { ($activeRemote -join ', ') } else { 'No common remote client detected' })

try {
    $audioEndpoints = @(Get-PnpDevice -Class AudioEndpoint -Status OK -ErrorAction Stop)
    Add-Result ($(if ($audioEndpoints.Count -gt 0) { 'PASS' } else { 'WARN' })) `
        'Audio endpoints' "$($audioEndpoints.Count) available; microphone permission still requires a manual check"
} catch {
    Add-Result 'WARN' 'Audio endpoints' 'Automatic query unavailable; check Windows microphone settings manually'
}

$results.Add('')
$results.Add('Manual checks still required:')
$results.Add('1. AhaKey 507C is connected to this laptop, not another computer.')
$results.Add('2. Desktop apps have microphone permission.')
$results.Add('3. The remote client has clipboard synchronization enabled.')
$results.Add('4. Arist AI Control and the remote client run at the same privilege level.')
$results.Add('5. Run the four-stage test in REMOTE-VOICE-TEST-GUIDE.md.')

$desktop = [Environment]::GetFolderPath('Desktop')
$output = Join-Path $desktop 'Arist-AI-Control-Remote-Preflight.txt'
$header = @(
    'Arist AI Control remote voice preflight',
    "Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
    ''
)
($header + $results) | Set-Content -LiteralPath $output -Encoding UTF8
Write-Host "`nReport saved to: $output" -ForegroundColor Cyan
