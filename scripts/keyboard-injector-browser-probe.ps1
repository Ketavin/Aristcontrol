param(
    [Parameter(Mandatory = $true)]
    [string]$JavaExe,

    [Parameter(Mandatory = $true)]
    [string]$ClassPath,

    [Parameter(Mandatory = $true)]
    [string]$ChromeExe
)

$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;

public static class AhaKeyBrowserProbeNative {
    public delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr parameter);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, IntPtr pid);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);

    public static string Title(IntPtr hwnd) {
        var text = new StringBuilder(512);
        GetWindowText(hwnd, text, text.Capacity);
        return text.ToString();
    }

    public static IntPtr FindExact(string title) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((hwnd, parameter) => {
            if (Title(hwnd) == title) { found = hwnd; return false; }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    public static void ForceForeground(IntPtr target) {
        IntPtr previous = GetForegroundWindow();
        uint previousThread = previous == IntPtr.Zero ? 0 : GetWindowThreadProcessId(previous, IntPtr.Zero);
        uint currentThread = GetCurrentThreadId();
        bool attached = previousThread != 0 && previousThread != currentThread
            && AttachThreadInput(currentThread, previousThread, true);
        try {
            ShowWindow(target, 9);
            BringWindowToTop(target);
            SetForegroundWindow(target);
        } finally {
            if (attached) AttachThreadInput(currentThread, previousThread, false);
        }
    }
}
'@

function Quote-ProcessArgument([string]$value) {
    return '"' + $value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Invoke-BrowserProbe([string]$mode) {
    $initialTitle = 'AhaKey Browser Input Probe 507C'
    $html = (Resolve-Path (Join-Path $PSScriptRoot 'keyboard-injector-browser-probe.html')).Path
    $uri = ([System.Uri]$html).AbsoluteUri + '?mode=' + $mode
    [void](Start-Process -FilePath $ChromeExe -ArgumentList ("--app=" + $uri) -PassThru)

    $window = [IntPtr]::Zero
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    while ($window -eq [IntPtr]::Zero -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 100
        $window = [AhaKeyBrowserProbeNative]::FindExact($initialTitle)
    }
    if ($window -eq [IntPtr]::Zero) {
        throw "Chrome probe window did not appear for $mode"
    }
    [AhaKeyBrowserProbeNative]::ForceForeground($window)
    Start-Sleep -Milliseconds 500

    $arguments = @(
        '-cp', (Quote-ProcessArgument $ClassPath),
        'com.example.ahakey.service.KeyboardInjectorManualProbe',
        '600',
        (Quote-ProcessArgument ("--title=" + $initialTitle))
    ) -join ' '
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $JavaExe
    $startInfo.Arguments = $arguments
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $probe = [System.Diagnostics.Process]::Start($startInfo)
    $probe.WaitForExit()
    $stdout = $probe.StandardOutput.ReadToEnd()
    $stderr = $probe.StandardError.ReadToEnd()
    Start-Sleep -Milliseconds 300
    $finalTitle = [AhaKeyBrowserProbeNative]::Title($window)
    [void][AhaKeyBrowserProbeNative]::PostMessage($window, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)

    [pscustomobject]@{
        Mode = $mode
        Passed = ($probe.ExitCode -eq 0 -and $finalTitle.StartsWith('PASS '))
        ExitCode = $probe.ExitCode
        FinalTitle = $finalTitle
        ProbeOutput = $stdout.Trim()
        ProbeError = $stderr.Trim()
    }
}

$results = @(
    Invoke-BrowserProbe 'textarea'
    Invoke-BrowserProbe 'editable'
)
$results | ConvertTo-Json -Depth 4
if (@($results | Where-Object { -not $_.Passed }).Count -gt 0) {
    exit 1
}
