# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [string]$GameDirectory,
    [string]$RuntimeDirectory
)
$ErrorActionPreference = 'Stop'
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
$runtimeTarget = Join-Path $gameDirectory 'remix-sdk'
New-Item -ItemType Directory -Force -Path $mods,$runtimeTarget | Out-Null
# The Packman source is read-only. Copy to the game before layering the local bridge.
Get-ChildItem -LiteralPath $runtimeSource -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $runtimeTarget -Recurse -Force
}
Copy-Item -LiteralPath $bridge -Destination (Join-Path $runtimeTarget 'remixbridge.dll') -Force
Copy-Item -LiteralPath $jar -Destination (Join-Path $mods (Split-Path $jar -Leaf)) -Force
$configTarget = Join-Path $gameDirectory 'rtx.conf'
if (-not (Test-Path -LiteralPath $configTarget)) {
    Copy-Item -LiteralPath (Join-Path $projectRoot 'config/rtx.conf') -Destination $configTarget
}
Write-Output "Installed prototype in $gameDirectory"
Write-Output 'Launch the Prism instance using this game directory, create a disposable Creative world, and run /mrtx on.'
