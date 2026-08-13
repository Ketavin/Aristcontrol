param(
    [Parameter(Mandatory = $true)]
    [string]$JavaExe,

    [Parameter(Mandatory = $true)]
    [string]$ClassPath
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class AhaKeyForegroundProbe {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, IntPtr pid);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);

    public static void ForceForIsolatedTest(IntPtr target) {
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

$expected = @(
    [string][char]0x7532,
    [string][char]0xFF0C,
    [string][char]0x4E59,
    [string][char]0x3002,
    'ChatGPT',
    [string][char]0xFF0C,
    'Codex',
    [string][char]0x3002,
    'AhaKey 507C ',
    [string][char]0xD83D,
    [string][char]0xDE00
) -join ''

function Quote-ProcessArgument([string]$value) {
    return '"' + $value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Invoke-ControlProbe([string]$kind) {
    $title = "AhaKey WinForms $kind isolated probe 507C"
    $form = New-Object System.Windows.Forms.Form
    $form.Text = $title
    $form.Width = 720
    $form.Height = 260
    $form.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen
    $form.TopMost = $true

    if ($kind -eq 'TextBox') {
        $editor = New-Object System.Windows.Forms.TextBox
        $editor.Multiline = $true
    } else {
        $editor = New-Object System.Windows.Forms.RichTextBox
    }
    $editor.Dock = [System.Windows.Forms.DockStyle]::Fill
    $editor.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 14)
    $form.Controls.Add($editor)

    $state = [pscustomobject]@{
        Process = $null
        Stdout = ''
        Stderr = ''
        Actual = ''
    }
    $timer = New-Object System.Windows.Forms.Timer
    $timer.Interval = 100

    $form.Add_Shown({
        [AhaKeyForegroundProbe]::ForceForIsolatedTest($form.Handle)
        $form.Activate()
        [void]$editor.Focus()

        $arguments = @(
            '-cp', (Quote-ProcessArgument $ClassPath),
            'com.example.ahakey.service.KeyboardInjectorManualProbe',
            '600',
            (Quote-ProcessArgument ("--title=" + $title))
        ) -join ' '
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $JavaExe
        $startInfo.Arguments = $arguments
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $state.Process = [System.Diagnostics.Process]::Start($startInfo)
        $timer.Start()
    })

    $timer.Add_Tick({
        if ($null -ne $state.Process -and $state.Process.HasExited) {
            $timer.Stop()
            $state.Stdout = $state.Process.StandardOutput.ReadToEnd()
            $state.Stderr = $state.Process.StandardError.ReadToEnd()
            $state.Actual = $editor.Text
            $form.Close()
        }
    })

    [System.Windows.Forms.Application]::Run($form)
    $timer.Dispose()
    $form.Dispose()

    [pscustomobject]@{
        Control = $kind
        Passed = ($state.Process.ExitCode -eq 0 -and $state.Actual -ceq $expected)
        ExitCode = $state.Process.ExitCode
        Expected = $expected
        Actual = $state.Actual
        ProbeOutput = $state.Stdout.Trim()
        ProbeError = $state.Stderr.Trim()
    }
}

$results = @(
    Invoke-ControlProbe 'TextBox'
    Invoke-ControlProbe 'RichTextBox'
)
$results | ConvertTo-Json -Depth 4
if (@($results | Where-Object { -not $_.Passed }).Count -gt 0) {
    exit 1
}
