# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# These parsers are independent of Win32 so the fixture's ownership rules are testable.
function New-RemixWindowLogState {
    [pscustomobject]@{ Run = $null; Phase = $null; Window = [long]0; Line = 0; PhaseLine = 0; Ended = $false }
}
function Add-RemixWindowLogLine($State, [string]$Line) {
    $State.Line++
    if ($Line -match 'RLC_SMOKE event=START id=(\d{8}-\d{6}-[0-9a-f]+)(?: |$)') {
        $State.Run = $Matches[1]; $State.Phase = $null; $State.Window = [long]0
        $State.PhaseLine = 0; $State.Ended = $false
    } elseif ($State.Run -and $Line -match 'RTX Remix Forge: (?:following recreated window ([0-9a-fA-F]+)(?: |$)|native renderer initialized \(API [^,]+, HWND ([0-9a-fA-F]+)\))') {
        $hex = $Matches[1] + $Matches[2]
        if ($hex.Length -gt 16) { throw 'Invalid native window handle in smoke log.' }
        $State.Window = [Convert]::ToInt64($hex, 16)
    } elseif ($Line -match 'RLC_SMOKE event=([^ ]+) id=([^ ]+)(?: |$)') {
        $event = $Matches[1]; $runId = $Matches[2]
        if ($runId -ne $State.Run) { return }
        if ($event -match '^WINDOW_(?:PHASE|PROGRESS|PASS)$' -and $Line -match '(?: |^)windowPhase=([A-Z_]+)(?: |$)') {
            $State.Phase = $Matches[1]; $State.PhaseLine = $State.Line
        }
        if ($event -in @('FAIL', 'EXIT', 'WORLD_UNLOAD', 'WINDOW_TIMEOUT')) { $State.Ended = $true }
    }
}
function Read-RemixWindowLogState([string]$Path) {
    $state = New-RemixWindowLogState
    if (-not (Test-Path -LiteralPath $Path)) { return $state }
    if ((Get-Item -LiteralPath $Path).Length -gt 64MB) { throw 'Smoke log exceeds the bounded window fixture input.' }
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    $reader = [IO.StreamReader]::new($stream)
    try { while ($null -ne ($line = $reader.ReadLine())) { Add-RemixWindowLogLine $state $line } }
    finally { $reader.Dispose() }
    return $state
}
function Test-RemixFreshWindowPhase($State, $Initial) {
    return $State.Run -and $State.PhaseLine -gt 0 -and -not $State.Ended -and
        ($State.Run -ne $Initial.Run -or $State.PhaseLine -gt $Initial.PhaseLine)
}
function Test-RemixInstanceCommand([string]$CommandLine, [string]$Instance) {
    if (-not $CommandLine) { return $false }
    $path = $Instance.Replace('/', '\').TrimEnd('\') + '\'
    return $CommandLine.Replace('/', '\').IndexOf($path, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $CommandLine -match '(?:^|\s)-Drlcraftremix\.smoke\.windowMinimize=true(?:\s|$)'
}
function Test-RemixWindowIdentity([long]$Window, [uint32]$Owner, [string]$Class, [long]$ExpectedWindow, [uint32]$ExpectedOwner) {
    # RemixGuiInputSink is a visible top-level window in this same Java process.
    # Process.MainWindowHandle can select it, so process identity alone is insufficient.
    return $Window -ne 0 -and $Window -eq $ExpectedWindow -and $Owner -eq $ExpectedOwner -and $Class -ceq 'LWJGL'
}
