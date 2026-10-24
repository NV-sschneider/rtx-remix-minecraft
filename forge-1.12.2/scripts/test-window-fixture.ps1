# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'window-fixture.ps1')
function Assert-Fixture([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$run = '20261024-074207-7b237d22'
$next = '20261024-080000-deadbeef'
$state = New-RemixWindowLogState
Add-RemixWindowLogLine $state "[Client] RLC_SMOKE event=START id=$run exit=true"
Add-RemixWindowLogLine $state '[Client] RTX Remix Forge: native renderer initialized (API 0.16.0, HWND 4919ca).'
Assert-Fixture ($state.Window -eq 0x4919ca) 'Initial native HWND was not parsed as hexadecimal.'
Add-RemixWindowLogLine $state '[Client] RTX Remix Forge: following recreated window 4a19ca'
Assert-Fixture ($state.Window -eq 0x4a19ca) 'Recreated HWND did not replace the original.'
Add-RemixWindowLogLine $state "[Client] RLC_SMOKE event=WINDOW_PHASE id=$run windowPhase=MINIMIZE_REQUEST menu=0"
$initial = $state.PSObject.Copy()
Assert-Fixture (-not (Test-RemixFreshWindowPhase $state $initial)) 'A stale phase was accepted without a newly written handshake.'
Add-RemixWindowLogLine $state '[Client] unrelated log write'
Assert-Fixture (-not (Test-RemixFreshWindowPhase $state $initial)) 'An unrelated log write refreshed a stale handshake.'
Add-RemixWindowLogLine $state "[Client] RLC_SMOKE event=WINDOW_PROGRESS id=$run windowPhase=MINIMIZE_REQUEST menu=0"
Assert-Fixture (Test-RemixFreshWindowPhase $state $initial) 'Fresh progress was not accepted.'
Add-RemixWindowLogLine $state "[Client] RLC_SMOKE event=WINDOW_PHASE id=$next windowPhase=RESTORE_REQUEST menu=0"
Assert-Fixture ($state.Phase -eq 'MINIMIZE_REQUEST') 'A different run changed the bound phase.'
Add-RemixWindowLogLine $state "[Client] RLC_SMOKE event=WINDOW_TIMEOUT id=$run windowPhase=MINIMIZE_REQUEST menu=0"
Assert-Fixture (-not (Test-RemixFreshWindowPhase $state $initial)) 'A timed out fixture can still minimize a window.'
Add-RemixWindowLogLine $state "[Client] RLC_SMOKE event=START id=$next exit=true"
Assert-Fixture ($state.Window -eq 0 -and $null -eq $state.Phase -and -not $state.Ended) 'A new run retained the previous HWND/phase.'

Assert-Fixture (Test-RemixWindowIdentity 0x970fae 17540 'LWJGL' 0x970fae 17540) 'Exact game window was rejected.'
Assert-Fixture (-not (Test-RemixWindowIdentity 0x5b19ca 17540 'RemixGuiInputSink' 0x5b19ca 17540)) 'Native GUI input sink was accepted as Minecraft.'
Assert-Fixture (-not (Test-RemixWindowIdentity 0x970fae 900 'LWJGL' 0x970fae 17540)) 'Reused HWND in a different process was accepted.'
Assert-Fixture (-not (Test-RemixWindowIdentity 0x970fae 17540 'LWJGL' 0x970faf 17540)) 'A different game HWND was accepted.'
Assert-Fixture (-not (Test-RemixWindowIdentity 0 17540 'LWJGL' 0 17540)) 'Destroyed HWND was accepted.'

$instance = 'C:\fixture\instances\forge112-remix'
Assert-Fixture (Test-RemixInstanceCommand 'javaw -Drlcraftremix.smoke.windowMinimize=true -Djava.library.path=C:/fixture/instances/forge112-remix/natives' $instance) 'Owned instance and explicit fixture flag were rejected.'
Assert-Fixture (-not (Test-RemixInstanceCommand 'javaw -Drlcraftremix.smoke.windowMinimize=true -Djava.library.path=C:/fixture/instances/forge112-remix-other/natives' $instance)) 'A neighboring instance was accepted.'
Assert-Fixture (-not (Test-RemixInstanceCommand 'javaw -Djava.library.path=C:/fixture/instances/forge112-remix/natives' $instance)) 'A normal game without the minimize fixture was accepted.'
Assert-Fixture (-not (Test-RemixInstanceCommand 'javaw -Drlcraftremix.smoke.windowMinimize=trueish -Djava.library.path=C:/fixture/instances/forge112-remix/natives' $instance)) 'A partial fixture flag was accepted.'
Assert-Fixture (-not (Test-RemixInstanceCommand $null $instance)) 'Missing command line was accepted.'

foreach ($script in @('check-window-minimize.ps1', 'window-fixture.ps1', 'test-window-fixture.ps1')) {
    $tokens = $null; $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $script), [ref]$tokens, [ref]$errors)
    Assert-Fixture ($errors.Count -eq 0) "PowerShell syntax error in $script."
}
Write-Output 'Window fixture parser/ownership tests passed; three PowerShell files parsed. No Win32 calls or game control performed.'
