# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $projectRoot
$workDirectory = Join-Path $repositoryRoot 'work'
New-Item -ItemType Directory -Path $workDirectory -Force | Out-Null
[xml]$manifest = Get-Content -LiteralPath (Join-Path $repositoryRoot 'packman/remix-sdk.packman.xml')
$package = $manifest.project.dependency | Where-Object name -eq 'remix-sdk-win64'
$target = Join-Path $repositoryRoot 'remix-runtime/win64'
$document = New-Object System.Xml.XmlDocument
$root = $document.CreateElement('project')
$root.SetAttribute('toolsVersion', '7.6')
[void]$document.AppendChild($root)
$dependency = $document.ImportNode($package, $true)
$dependency.SetAttribute('linkPath', $target)
[void]$root.AppendChild($dependency)
$manifestPath = Join-Path $workDirectory 'remix-sdk-win64.xml'
$document.Save($manifestPath)
$previousPackagesRoot = $env:PM_PACKAGES_ROOT
try {
    $env:PM_PACKAGES_ROOT = Join-Path $repositoryRoot '.tools/packman-cache'
    & (Join-Path $repositoryRoot 'packman/packman.cmd') pull $manifestPath -p windows-x86_64
    if ($LASTEXITCODE -ne 0) { throw "Runtime download failed: $LASTEXITCODE" }
} finally {
    $env:PM_PACKAGES_ROOT = $previousPackagesRoot
}
Write-Output "Pinned runtime available: $target"
