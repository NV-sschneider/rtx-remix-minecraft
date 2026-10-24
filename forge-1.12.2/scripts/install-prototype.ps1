# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [string]$GameDirectory,
    [string]$RuntimeDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-payload.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $projectRoot
if (-not $GameDirectory) {
    $GameDirectory = Join-Path $repositoryRoot '.tools/PrismLauncher/instances/forge112-remix/.minecraft'
}
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $repositoryRoot 'remix-runtime/win64' }
$gameDirectory = (Resolve-Path -LiteralPath $GameDirectory).Path
$runtimeSource = (Resolve-Path -LiteralPath $RuntimeDirectory).Path
$bridge = Join-Path $projectRoot 'bridge/build/native/win64/remixbridge.dll'
$jar = Join-Path $projectRoot 'build/libs/rlcraft-remix-forge-0.1.0-prototype.jar'
foreach ($required in @($gameDirectory, $bridge, $jar, (Join-Path $runtimeSource 'd3d9.dll'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing prerequisite: $required" }
}
$mods = Join-Path $gameDirectory 'mods'
$configTarget = Join-Path $gameDirectory 'rtx.conf'
$jarTarget = Join-Path $mods (Split-Path $jar -Leaf)
if (-not (Test-Path -LiteralPath $mods -PathType Container) -and
    -not (Test-Path -LiteralPath (Join-Path $gameDirectory 'options.txt') -PathType Leaf)) {
    throw 'Select an existing Minecraft game directory with mods or options.txt.'
}
foreach ($target in @($mods, $configTarget, $jarTarget)) {
    [void](Assert-ForgeOwnedPath -GameDirectory $gameDirectory -Path $target)
}
$idle = { Assert-ForgeInstanceIdle -GameDirectory $gameDirectory }
& $idle
Install-ForgeRuntime -GameDirectory $gameDirectory -RuntimeDirectory $runtimeSource -Bridge $bridge -AssertIdle $idle
& $idle
[void][IO.Directory]::CreateDirectory($mods)
Copy-Item -LiteralPath $jar -Destination $jarTarget -Force
if (-not (Test-Path -LiteralPath $configTarget)) {
    Copy-Item -LiteralPath (Join-Path $projectRoot 'config/rtx.conf') -Destination $configTarget
}
Write-Output "Installed prototype in $gameDirectory"
Write-Output 'Launch the Prism instance using this game directory, create a disposable Creative world, and run /mrtx on.'
