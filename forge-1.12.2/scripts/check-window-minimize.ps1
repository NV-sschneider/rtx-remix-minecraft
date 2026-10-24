# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [string]$LauncherDirectory,
    [string]$InstanceName = 'forge112-remix',
    [ValidateRange(30, 1800)][int]$TimeoutSeconds = 300
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'window-fixture.ps1')
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $LauncherDirectory) { $LauncherDirectory = Join-Path $root '.tools/PrismLauncher' }
if ([string]::IsNullOrWhiteSpace($InstanceName) -or $InstanceName -in @('.', '..') -or
    $InstanceName.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0 -or $InstanceName -ne $InstanceName.TrimEnd(' ', '.')) {
    throw 'InstanceName must name one instance folder.'
}
$launcherRoot = (Resolve-Path -LiteralPath $LauncherDirectory).Path
$instance = (Resolve-Path -LiteralPath (Join-Path $launcherRoot "instances/$InstanceName")).Path
$game = if (Test-Path -LiteralPath (Join-Path $instance '.minecraft')) { Join-Path $instance '.minecraft' } else { Join-Path $instance 'minecraft' }
$log = Join-Path $game 'logs/latest.log'
$initial = Read-RemixWindowLogState $log
$started = [DateTime]::UtcNow
$deadline = $started.AddSeconds($TimeoutSeconds)
$state = $initial
$lastLength = -1L
$lastWrite = [DateTime]::MinValue
$window = [IntPtr]::Zero
$gameProcess = $null
$processStarted = $null
$run = $null
$minimized = $false
$restored = $false
$controlled = [Collections.Generic.HashSet[long]]::new()
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class RemixMinimizeFixture {
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr window, StringBuilder name, int capacity);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr window);
    [DllImport("user32.dll", SetLastError = true)] public static extern bool ShowWindowAsync(IntPtr window, int command);
}
'@
function Get-WindowIdentity([IntPtr]$Handle) {
    [uint32]$owner = 0
    $name = [Text.StringBuilder]::new(128)
    [void][RemixMinimizeFixture]::GetWindowThreadProcessId($Handle, [ref]$owner)
    [void][RemixMinimizeFixture]::GetClassName($Handle, $name, $name.Capacity)
    [pscustomobject]@{ Owner = $owner; Class = $name.ToString() }
}
function Test-OwnedWindow([IntPtr]$Handle) {
    if ($Handle -eq [IntPtr]::Zero -or $null -eq $gameProcess -or -not [RemixMinimizeFixture]::IsWindow($Handle)) { return $false }
    $gameProcess.Refresh()
    if ($gameProcess.HasExited -or $gameProcess.StartTime.ToUniversalTime() -ne $processStarted) { return $false }
    $identity = Get-WindowIdentity $Handle
    return Test-RemixWindowIdentity $Handle.ToInt64() $identity.Owner $identity.Class $Handle.ToInt64() $gameProcess.Id
}
function Set-WindowMinimized([IntPtr]$Handle, [bool]$Minimize) {
    if (-not (Test-OwnedWindow $Handle)) { throw 'The selected Minecraft window was destroyed or changed ownership.' }
    $command = if ($Minimize) { 6 } else { 9 }
    if (-not [RemixMinimizeFixture]::ShowWindowAsync($Handle, $command)) {
        throw "Windows rejected the owned window request (error $([Runtime.InteropServices.Marshal]::GetLastWin32Error()))."
    }
    $until = [DateTime]::UtcNow.AddSeconds(5)
    do {
        if (-not (Test-OwnedWindow $Handle)) { throw 'Minecraft recreated its HWND during the minimize/restore request.' }
        if ([RemixMinimizeFixture]::IsIconic($Handle) -eq $Minimize) { return }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $until)
    throw "Owned HWND $($Handle.ToInt64().ToString('x')) did not reach IsIconic=$Minimize within five seconds."
}
try {
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Test-Path -LiteralPath $log)) { Start-Sleep -Milliseconds 150; continue }
        $file = Get-Item -LiteralPath $log
        if ($file.LastWriteTimeUtc -lt $started) { Start-Sleep -Milliseconds 150; continue }
        if ($file.Length -ne $lastLength -or $file.LastWriteTimeUtc -ne $lastWrite) {
            $state = Read-RemixWindowLogState $log
            $lastLength = $file.Length; $lastWrite = $file.LastWriteTimeUtc
        }
        if ($run -and $state.Run -ne $run) { throw 'The smoke run changed during the window handshake.' }
        if ($restored -and $state.Phase -in @('RETURN', 'DONE')) {
            Write-Output "Minimize/resume handshake completed for $run."
            return
        }
        if ($run -and $state.Ended) { throw "Smoke run $run ended before the minimize/resume handshake completed." }
        if ($state.Phase -eq 'MINIMIZE_REQUEST' -and -not $minimized -and (Test-RemixFreshWindowPhase $state $initial)) {
            $candidate = [IntPtr]::new($state.Window)
            # Display mode changes destroy HWNDs. Wait for the renderer's next publication;
            # never substitute Process.MainWindowHandle or a foreground window.
            if ($candidate -eq [IntPtr]::Zero -or -not [RemixMinimizeFixture]::IsWindow($candidate)) {
                Start-Sleep -Milliseconds 150; continue
            }
            $identity = Get-WindowIdentity $candidate
            if ($identity.Class -cne 'LWJGL') { throw "Native log HWND has unexpected class '$($identity.Class)'." }
            # Command lines contain authentication arguments. Match only in memory and never print them.
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($identity.Owner)" -Property ProcessId,Name,CommandLine
            if ($null -eq $owner -or $owner.Name -notin @('java.exe', 'javaw.exe') -or
                    -not (Test-RemixInstanceCommand $owner.CommandLine $instance)) {
                throw 'Native log HWND does not belong to this instance with its minimize fixture enabled.'
            }
            $gameProcess = Get-Process -Id $identity.Owner
            $processStarted = $gameProcess.StartTime.ToUniversalTime()
            $runStarted = [DateTime]::ParseExact($state.Run.Substring(0, 15), 'yyyyMMdd-HHmmss',
                [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal).ToUniversalTime()
            if ($processStarted -gt $runStarted.AddSeconds(1)) { throw 'Native log belongs to an older Java process.' }
            $run = $state.Run; $window = $candidate
            if ([RemixMinimizeFixture]::IsIconic($window)) { throw 'Minecraft was already minimized before this helper acquired its window.' }
            [void]$controlled.Add($window.ToInt64())
            Set-WindowMinimized $window $true
            $minimized = $true
            Write-Output "Verified minimized run=$run pid=$($gameProcess.Id) hwnd=$($window.ToInt64().ToString('x')) class=LWJGL IsIconic=True."
        } elseif ($state.Phase -eq 'RESTORE_REQUEST' -and $minimized -and -not $restored) {
            $candidate = [IntPtr]::new($state.Window)
            if (-not (Test-OwnedWindow $candidate)) { Start-Sleep -Milliseconds 150; continue }
            if ($candidate -ne $window) {
                Write-Output "Owned Minecraft HWND recreated from $($window.ToInt64().ToString('x')) to $($candidate.ToInt64().ToString('x'))."
                $window = $candidate
                if ($controlled.Count -ge 8) { throw 'Too many HWND recreations during the bounded window fixture.' }
                [void]$controlled.Add($window.ToInt64())
            }
            Set-WindowMinimized $window $false
            $restored = $true
            Write-Output "Verified restored run=$run pid=$($gameProcess.Id) hwnd=$($window.ToInt64().ToString('x')) class=LWJGL IsIconic=False."
        }
        Start-Sleep -Milliseconds 150
    }
    throw "Timed out waiting for the owned window fixture (run=$run phase=$($state.Phase) minimized=$minimized restored=$restored)."
} finally {
    if ($controlled.Count -gt 0 -and $controlled.Count -lt 8) {
        try {
            $latest = Read-RemixWindowLogState $log
            $replacement = [IntPtr]::new($latest.Window)
            if ($latest.Run -eq $run -and (Test-OwnedWindow $replacement)) {
                [void]$controlled.Add($replacement.ToInt64())
            }
        } catch { Write-Warning 'Could not resolve the final owned Minecraft HWND; checking only previously acquired handles.' }
    }
    # Restore only windows acquired by this helper, with live process identity and class
    # checked again. A destroyed handle must never turn into a different process's target.
    foreach ($handle in $controlled) {
        $target = [IntPtr]::new($handle)
        if ((Test-OwnedWindow $target) -and [RemixMinimizeFixture]::IsIconic($target)) {
            try { Set-WindowMinimized $target $false }
            catch { Write-Warning "Could not restore the owned Minecraft HWND $($handle.ToString('x')): $($_.Exception.Message)" }
        }
    }
}
