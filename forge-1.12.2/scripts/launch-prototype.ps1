# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [switch]$SmokeTest,
    [switch]$ExitAfterSmoke,
    [string]$LauncherDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $projectRoot
if (-not $LauncherDirectory) { $LauncherDirectory = Join-Path $repositoryRoot '.tools/PrismLauncher' }
$launcherRoot = (Resolve-Path -LiteralPath $LauncherDirectory).Path
$launcher = Join-Path $launcherRoot 'prismlauncher.exe'
$config = Join-Path $launcherRoot 'instances/forge112-remix/instance.cfg'
if (-not (Test-Path -LiteralPath $launcher) -or -not (Test-Path -LiteralPath $config)) {
    throw 'The project-local Prism Launcher and Forge instance must be installed first.'
}
if ($ExitAfterSmoke -and -not $SmokeTest) { throw '-ExitAfterSmoke requires -SmokeTest.' }
# Let this portable launcher's settings cache close before changing its instance file.
$localJava = Join-Path $repositoryRoot '.tools/jdk8'
if (Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($localJava, [StringComparison]::OrdinalIgnoreCase) }) {
    throw 'Close the running Forge game before launching or changing smoke-test settings.'
}
foreach ($process in @(Get-Process prismlauncher -ErrorAction SilentlyContinue)) {
    if ($process.Path -ne $launcher) { continue }
    # Prism may keep several completed-game console windows open. Close each normally.
    for ($attempt = 0; $attempt -lt 8 -and -not $process.HasExited; $attempt++) {
        $process.Refresh()
        if (-not $process.CloseMainWindow()) { break }
        [void]$process.WaitForExit(750)
    }
    if (-not $process.HasExited) {
        throw 'Close this project''s Prism Launcher windows, then run the launch script again.'
    }
}
# Only the dedicated prototype instance is changed. Account storage is never read.
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
$jvmArgs = ($jvmArgs -replace '(?:^|\s)-Drlcraftremix\.smoke(?:\.exit)?=\S+', '').Trim()
if ($SmokeTest) { $jvmArgs = "$jvmArgs -Drlcraftremix.smoke=true".Trim() }
if ($ExitAfterSmoke) { $jvmArgs += ' -Drlcraftremix.smoke.exit=true' }
$general = @($general | Where-Object { $_ -notmatch '^(JvmArgs|OverrideJavaArgs)=' })
$outputLines = @($lines[0..$generalStart]) + $general + @('OverrideJavaArgs=true', "JvmArgs=$jvmArgs")
if ($generalEnd -lt $lines.Length) { $outputLines += $lines[$generalEnd..($lines.Length - 1)] }
[IO.File]::WriteAllLines($config, $outputLines, (New-Object Text.UTF8Encoding($false)))
# Prism uses the user's signed-in Minecraft account and performs normal ownership checks.
# This is the user's interactive game launcher; its window must be visible for Remix.
Start-Process -FilePath $launcher -ArgumentList @('--launch', 'forge112-remix') -WorkingDirectory $launcherRoot -WindowStyle Normal
Write-Output 'Requested authenticated launch of Forge 1.12.2 - Remix Port.'
