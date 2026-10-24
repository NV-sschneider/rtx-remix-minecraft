# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [switch]$SmokeTest,
    [switch]$EntitySmokeTest,
    [switch]$SkySmokeTest,
    [switch]$MaterialSmokeTest,
    [switch]$ObjectSmokeTest,
    [switch]$EffectSmokeTest,
    [switch]$CombatSmokeTest,
    [switch]$CloudSmokeTest,
    [switch]$PbrSmokeTest,
    [switch]$TerrainSmokeTest,
    [switch]$TerrainNaturalSmokeTest,
    [ValidateRange(4, 32)]
    [int]$TerrainSmokeRadius = 4,
    [switch]$ExitAfterSmoke,
    [string]$LauncherDirectory,
    [string]$InstanceName = 'forge112-remix'
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($InstanceName) -or
    $InstanceName -in @('.', '..') -or
    $InstanceName.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0 -or
    $InstanceName -ne $InstanceName.TrimEnd(' ', '.')) {
    throw '-InstanceName must be an instance folder name, without paths, invalid filename characters, or trailing spaces/dots.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $projectRoot
if (-not $LauncherDirectory) { $LauncherDirectory = Join-Path $repositoryRoot '.tools/PrismLauncher' }
$launcherRoot = (Resolve-Path -LiteralPath $LauncherDirectory).Path
$launcher = Join-Path $launcherRoot 'prismlauncher.exe'
$instanceDirectory = Join-Path (Join-Path $launcherRoot 'instances') $InstanceName
$config = Join-Path $instanceDirectory 'instance.cfg'
if (-not (Test-Path -LiteralPath $launcher) -or -not (Test-Path -LiteralPath $config)) {
    throw "Prism Launcher and the '$InstanceName' instance must be installed first."
}
if ($ExitAfterSmoke -and -not $SmokeTest) { throw '-ExitAfterSmoke requires -SmokeTest.' }
if ($EntitySmokeTest -and -not $SmokeTest) { throw '-EntitySmokeTest requires -SmokeTest.' }
if ($SkySmokeTest -and -not $SmokeTest) { throw '-SkySmokeTest requires -SmokeTest.' }
if ($MaterialSmokeTest -and -not $SmokeTest) { throw '-MaterialSmokeTest requires -SmokeTest.' }
if ($ObjectSmokeTest -and -not $SmokeTest) { throw '-ObjectSmokeTest requires -SmokeTest.' }
if ($EffectSmokeTest -and -not $SmokeTest) { throw '-EffectSmokeTest requires -SmokeTest.' }
if ($CombatSmokeTest -and -not $SmokeTest) { throw '-CombatSmokeTest requires -SmokeTest.' }
if ($CloudSmokeTest -and -not $SmokeTest) { throw '-CloudSmokeTest requires -SmokeTest.' }
if ($PbrSmokeTest -and -not $SmokeTest) { throw '-PbrSmokeTest requires -SmokeTest.' }
if ($TerrainSmokeTest -and -not $SmokeTest) { throw '-TerrainSmokeTest requires -SmokeTest.' }
if ($TerrainNaturalSmokeTest -and -not $TerrainSmokeTest) { throw '-TerrainNaturalSmokeTest requires -TerrainSmokeTest.' }
if ($PSBoundParameters.ContainsKey('TerrainSmokeRadius') -and -not $TerrainSmokeTest) {
    throw '-TerrainSmokeRadius requires -TerrainSmokeTest.'
}

function Assert-InstanceIdle {
    param([string]$InstanceDirectory, [int[]]$LauncherProcessIds)

    $instancePath = [IO.Path]::GetFullPath($InstanceDirectory).Replace('/', '\').TrimEnd('\') + '\'
    $parents = @{}
    foreach ($entry in @(Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId)) {
        $parents[[int]$entry.ProcessId] = [int]$entry.ParentProcessId
    }
    # Read Java command lines only in memory; never emit or save account arguments.
    $javaProcesses = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -Property ProcessId,CommandLine)
    foreach ($javaProcess in $javaProcesses) {
        if ($javaProcess.CommandLine -and
            $javaProcess.CommandLine.Replace('/', '\').IndexOf($instancePath, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'Close the game using this instance before changing its settings or launching it again.'
        }
        $ancestorId = [int]$javaProcess.ProcessId
        $visited = New-Object 'System.Collections.Generic.HashSet[int]'
        while ($ancestorId -gt 0 -and $visited.Add($ancestorId)) {
            if ($LauncherProcessIds -contains $ancestorId) {
                throw 'Close every game running through this Prism Launcher before changing settings or closing its windows.'
            }
            if (-not $parents.ContainsKey($ancestorId)) { break }
            $ancestorId = $parents[$ancestorId]
        }
    }
}

$launcherProcesses = @(Get-Process prismlauncher -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $launcher })
$launcherProcessIds = @($launcherProcesses | ForEach-Object Id)
Assert-InstanceIdle -InstanceDirectory $instanceDirectory -LauncherProcessIds $launcherProcessIds
# Let this portable launcher's settings cache close before changing its instance file.
foreach ($process in $launcherProcesses) {
    # Prism may keep several completed-game console windows open. Close each normally.
    for ($attempt = 0; $attempt -lt 8 -and -not $process.HasExited; $attempt++) {
        $process.Refresh()
        Assert-InstanceIdle -InstanceDirectory $instanceDirectory -LauncherProcessIds $launcherProcessIds
        if (-not $process.CloseMainWindow()) { break }
        [void]$process.WaitForExit(750)
    }
    if (-not $process.HasExited) {
        throw 'Close this project''s Prism Launcher windows, then run the launch script again.'
    }
}
Assert-InstanceIdle -InstanceDirectory $instanceDirectory -LauncherProcessIds $launcherProcessIds
# Only the selected instance is changed. Account storage is never read.
$lines = @(Get-Content -LiteralPath $config)
$generalStart = [Array]::IndexOf($lines, '[General]')
if ($generalStart -lt 0) { throw 'Missing General section in Prism instance configuration.' }
$generalEnd = $lines.Length
for ($i = $generalStart + 1; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -match '^\[') { $generalEnd = $i; break }
}
$general = @($lines[($generalStart + 1)..($generalEnd - 1)])
$argsLine = $general | Where-Object { $_ -match '^JvmArgs=' } | Select-Object -First 1
$jvmArgs = if ($argsLine) { $argsLine.Substring(8).Trim('"') } else { '' }
$jvmArgs = ($jvmArgs -replace '(?:^|\s)-Drlcraftremix\.smoke(?:\.(?:exit|entities|sky|materials|objects|effects|combat|clouds|pbr|terrain|terrainNatural|terrainRadius))?=\S+', '').Trim()
if ($SmokeTest) { $jvmArgs = "$jvmArgs -Drlcraftremix.smoke=true".Trim() }
if ($ExitAfterSmoke) { $jvmArgs += ' -Drlcraftremix.smoke.exit=true' }
if ($EntitySmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.entities=true' }
if ($SkySmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.sky=true' }
if ($MaterialSmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.materials=true' }
if ($ObjectSmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.objects=true' }
if ($EffectSmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.effects=true' }
if ($CombatSmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.combat=true' }
if ($CloudSmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.clouds=true' }
if ($PbrSmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.pbr=true' }
if ($TerrainSmokeTest) { $jvmArgs += " -Drlcraftremix.smoke.terrain=true -Drlcraftremix.smoke.terrainRadius=$TerrainSmokeRadius" }
if ($TerrainNaturalSmokeTest) { $jvmArgs += ' -Drlcraftremix.smoke.terrainNatural=true' }
$general = @($general | Where-Object { $_ -notmatch '^(JvmArgs|OverrideJavaArgs)=' })
$outputLines = @($lines[0..$generalStart]) + $general + @('OverrideJavaArgs=true', "JvmArgs=$jvmArgs")
if ($generalEnd -lt $lines.Length) { $outputLines += $lines[$generalEnd..($lines.Length - 1)] }
[IO.File]::WriteAllLines($config, $outputLines, (New-Object Text.UTF8Encoding($false)))
# Prism uses the user's signed-in Minecraft account and performs normal ownership checks.
# This is the user's interactive game launcher; its window must be visible for Remix.
# Start-Process joins argument arrays without quoting; preserve spaces in the folder name.
$launchArguments = '--launch "{0}"' -f $InstanceName
Start-Process -FilePath $launcher -ArgumentList $launchArguments -WorkingDirectory $launcherRoot -WindowStyle Normal
Write-Output "Requested authenticated launch of Prism instance '$InstanceName'."
