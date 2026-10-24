# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-payload.ps1')
$repository = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scratch = [IO.Path]::GetFullPath((Join-Path $repository ('work/runtime-payload-tests-' + [Guid]::NewGuid().ToString('N'))))
$link = $null
$checks = 0

function Write-FixtureFile([string]$Path, [string]$Text) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path, $Text)
}
function Assert-Fixture([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-FixtureFailure([scriptblock]$Action, [string]$Message) {
    $failed = $false
    try { & $Action } catch { $failed = $true }
    Assert-Fixture $failed $Message
}
try {
    $prismInstance = Join-Path $scratch 'prism/instances/fixture'
    $prismGame = Join-Path $prismInstance '.minecraft'
    [void][IO.Directory]::CreateDirectory($prismGame)
    Assert-Fixture ($null -eq (Get-ForgePrismInstance $prismGame)) 'An arbitrary parent directory became a Prism instance.'
    Write-FixtureFile (Join-Path $prismInstance 'instance.cfg') '[General]'
    Assert-Fixture ((Get-ForgePrismInstance $prismGame) -eq $prismInstance) 'A verified Prism instance was not discovered.'
    $prismCommand = 'javaw -Djava.library.path="' + (Join-Path $prismInstance 'natives').Replace('\', '/') + '" org.prismlauncher.EntryPoint'
    Assert-Fixture (-not (Test-ForgeCommandDirectory $prismCommand $prismGame)) 'The fixture accidentally contains the game-directory path.'
    Assert-Fixture (Test-ForgeClientCommand $prismCommand $prismGame $prismInstance) 'A live Prism client with stdin game arguments was missed.'
    Assert-Fixture (Test-ForgeClientCommand ('java --gameDir "' + $prismGame + '"') $prismGame $null) 'A directly launched game-directory match was missed.'
    Assert-Fixture (-not (Test-ForgeClientCommand $prismCommand.Replace('/fixture/', '/fixture-other/') $prismGame $prismInstance)) 'A neighboring Prism instance was mistaken for this client.'
    $serverCommand = 'java -jar "' + (Join-Path $scratch 'work/multiplayer-server/forge-1.12.2-14.23.5.2860.jar') + '" nogui'
    Assert-Fixture (-not (Test-ForgeClientCommand $serverCommand $prismGame $prismInstance)) 'An independent Forge server in this workspace was mistaken for this client.'
    Assert-Fixture (-not (Test-ForgeClientCommand $null $prismGame $prismInstance)) 'A missing command line produced a path match.'
    $checks++

    $source = Join-Path $scratch 'sdk'
    $game = Join-Path $scratch 'game'
    $bridge = Join-Path $scratch 'local/remixbridge.dll'
    [void][IO.Directory]::CreateDirectory($game)
    foreach ($name in @('d3d9.dll', 'usd/plugin.json', 'folder.pdb/keep.dll',
            '.packman.sha1', 'usd/.hidden/secret.dll', 'usd/.metadata', 'usd/TEST.PDB',
            'NvRemixBridge.exe', 'usd/NVREMIXBRIDGE.EXE', 'remixbridge.dll', 'usd/remixbridge.dll',
            'python313.dll', 'libboost_python.dll', 'usd/PYTHON3.13/module.dll', 'usd/__pycache__/file',
            'usd/binding.PYD', 'usd/module.pyc', 'usd/module.py', 'usd/module.pyo')) {
        Write-FixtureFile (Join-Path $source $name) "source:$name"
    }
    Write-FixtureFile $bridge 'local bridge'
    $files = Get-ForgeRuntimeFiles -RuntimeDirectory $source
    Assert-Fixture ($files.Count -eq 3 -and $files.ContainsKey('d3d9.dll') -and
        $files.ContainsKey('usd/plugin.json') -and $files.ContainsKey('folder.pdb/keep.dll')) 'SDK exclusions differ from the supported payload.'
    $checks++

    Write-FixtureFile (Join-Path $game 'remix-sdk/stale.dll') 'previous runtime'
    Write-FixtureFile (Join-Path $game 'rtx.conf') 'authored configuration'
    Write-FixtureFile (Join-Path $game 'rtx-remix/mods/mine.usda') 'authored scene'
    $sourceHash = (Get-FileHash -LiteralPath (Join-Path $source 'd3d9.dll')).Hash
    Install-ForgeRuntime -GameDirectory $game -RuntimeDirectory $source -Bridge $bridge -AssertIdle { }
    Assert-Fixture (-not (Test-Path -LiteralPath (Join-Path $game 'remix-sdk/stale.dll'))) 'Old SDK files survived replacement.'
    Assert-Fixture ([IO.File]::ReadAllText((Join-Path $game 'remix-sdk/remixbridge.dll')) -eq 'local bridge') 'SDK bridge overrode the local build.'
    Assert-Fixture ([IO.File]::ReadAllText((Join-Path $game 'rtx.conf')) -eq 'authored configuration') 'Configuration was replaced.'
    Assert-Fixture ([IO.File]::ReadAllText((Join-Path $game 'rtx-remix/mods/mine.usda')) -eq 'authored scene') 'Authored scene was replaced.'
    Assert-Fixture ((Get-FileHash -LiteralPath (Join-Path $source 'd3d9.dll')).Hash -eq $sourceHash) 'SDK source was modified.'
    Assert-Fixture (@(Get-ChildItem -LiteralPath $game -Force | Where-Object Name -Like '.rlcraft-remix-*').Count -eq 0) 'Successful staging retained temporary directories.'
    $checks++

    Write-FixtureFile (Join-Path $game 'remix-sdk/rollback.txt') 'keep old runtime'
    $script:idleChecks = 0
    Assert-FixtureFailure {
        Install-ForgeRuntime -GameDirectory $game -RuntimeDirectory $source -Bridge $bridge -AssertIdle {
            $script:idleChecks++
            if ($script:idleChecks -eq 3) { throw 'Injected publication failure after backup.' }
        }
    } 'Publication failure did not propagate.'
    Assert-Fixture ([IO.File]::ReadAllText((Join-Path $game 'remix-sdk/rollback.txt')) -eq 'keep old runtime') 'Publication failure lost the previous runtime.'
    Assert-Fixture (@(Get-ChildItem -LiteralPath $game -Force | Where-Object Name -Like '.rlcraft-remix-*').Count -eq 0) 'Rollback left a staging directory.'
    $checks++

    Assert-FixtureFailure { Assert-ForgeOwnedPath -GameDirectory $game -Path (Join-Path $game '../outside') } 'Traversal escaped the game directory.'
    Assert-FixtureFailure { Remove-ForgeStagingTree -GameDirectory $game -Path (Join-Path $game 'remix-sdk') } 'Cleanup accepted the live SDK.'
    Assert-FixtureFailure { Install-ForgeRuntime -GameDirectory $game -RuntimeDirectory (Join-Path $game 'remix-sdk') -Bridge $bridge -AssertIdle { } } 'Installer accepted its destination as source.'
    $checks++

    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $link = Join-Path $game 'linked'
        [void](New-Item -ItemType Junction -Path $link -Target $source)
        Assert-FixtureFailure { Assert-ForgeOwnedPath -GameDirectory $game -Path (Join-Path $link 'd3d9.dll') } 'Destination junction was accepted.'
        Assert-FixtureFailure { Assert-ForgeUnlinkedTree -GameDirectory $scratch -Path $game } 'Recursive cleanup accepted a nested junction.'
        [IO.Directory]::Delete($link)
        $link = $null
        $checks++
    }
    Write-Output "Runtime inventory/staging checks passed: $checks"
} finally {
    if ($link -and [IO.Directory]::Exists($link)) { [IO.Directory]::Delete($link) }
    $checked = Assert-ForgeOwnedPath -GameDirectory $repository -Path $scratch
    $expectedParent = [IO.Path]::GetFullPath((Join-Path $repository 'work'))
    if ([IO.Directory]::GetParent($checked).FullName -ne $expectedParent -or
        [IO.Path]::GetFileName($checked) -notmatch '^runtime-payload-tests-[0-9a-f]{32}$') { throw 'Unexpected fixture cleanup directory.' }
    Assert-ForgeUnlinkedTree -GameDirectory $repository -Path $checked
    if (Test-Path -LiteralPath $checked) { Remove-Item -LiteralPath $checked -Recurse -Force }
}
