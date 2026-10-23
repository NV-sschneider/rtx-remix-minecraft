# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [string[]]$Tasks = @('build'),
    [string]$JdkPath
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $projectRoot
if (-not $JdkPath) {
    $localJdks = Join-Path $repositoryRoot '.tools/jdk8'
    if (Test-Path -LiteralPath $localJdks) {
        $JdkPath = (Get-ChildItem -LiteralPath $localJdks -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } |
            Select-Object -First 1).FullName
    }
}
if (-not $JdkPath -or -not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin/javac.exe'))) {
    throw 'A Java 8 JDK is required. Supply -JdkPath or install it in the repository .tools/jdk8 directory.'
}
$JdkPath = (Resolve-Path -LiteralPath $JdkPath).Path
$previousJdk = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JdkPath
    $env:GRADLE_USER_HOME = Join-Path $repositoryRoot '.gradle-home'
    & .\gradlew.bat @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally {
    $env:JAVA_HOME = $previousJdk
    $env:GRADLE_USER_HOME = $previousGradleHome
    Pop-Location
}

