# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [string]$JdkPath,
    [switch]$SkipNative,
    [switch]$SkipTests,
    [string]$RuntimeDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $projectRoot
$bridgeRoot = Join-Path $projectRoot 'bridge'
if ($RuntimeDirectory) { $RuntimeDirectory = (Resolve-Path -LiteralPath $RuntimeDirectory).Path }

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
$javac = Join-Path $JdkPath 'bin/javac.exe'
$java = Join-Path $JdkPath 'bin/java.exe'
$savedPreference = $ErrorActionPreference
try {
    # Java 8 prints its version on stderr; PowerShell 5.1 treats redirected stderr as errors.
    $ErrorActionPreference = 'Continue'
    $version = & $javac -version 2>&1 | Out-String
    $versionExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = $savedPreference }
if ($versionExitCode -ne 0) { throw 'Unable to execute the Java compiler.' }
if ($version -notmatch 'javac 1\.8\.') {
    throw 'Use a Java 8 JDK to check both source syntax and the Java 8 standard library.'
}

$buildRoot = Join-Path $bridgeRoot 'build'
$classes = Join-Path $buildRoot 'classes'
$headers = Join-Path $buildRoot 'headers'
$testClasses = Join-Path $buildRoot 'test-classes'
$nativeOutput = Join-Path $buildRoot 'native/win64'
$libs = Join-Path $buildRoot 'libs'
New-Item -ItemType Directory -Force -Path $classes,$headers,$testClasses,$nativeOutput,$libs | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $bridgeRoot 'src/main/java') -Recurse -Filter '*.java' |
    ForEach-Object FullName)
& $javac -source 8 -target 8 -encoding UTF-8 -h $headers -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Bridge Java compilation failed.' }

$metadata = Join-Path $classes 'META-INF'
New-Item -ItemType Directory -Force -Path $metadata | Out-Null
Copy-Item -LiteralPath (Join-Path $bridgeRoot 'LICENSE') -Destination (Join-Path $metadata 'LICENSE-RTX-Remix')
Copy-Item -LiteralPath (Join-Path $bridgeRoot 'NOTICE') -Destination (Join-Path $metadata 'NOTICE-RTX-Remix')
Copy-Item -LiteralPath (Join-Path $bridgeRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $metadata 'THIRD_PARTY_NOTICES-RTX-Remix.md')
$jarPath = Join-Path $libs 'rtxremix-java8-0.16.0.jar'
& (Join-Path $JdkPath 'bin/jar.exe') cf $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Bridge JAR packaging failed.' }

if (-not $SkipNative) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    $visualStudio = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $visualStudio) { throw 'Visual Studio with x64 C++ tools is required.' }
    $previousJdk = $env:BRIDGE_JAVA_HOME
    $previousRoot = $env:BRIDGE_ROOT
    $previousVs = $env:BRIDGE_VISUAL_STUDIO
    try {
        $env:BRIDGE_JAVA_HOME = $JdkPath
        $env:BRIDGE_ROOT = $bridgeRoot
        $env:BRIDGE_VISUAL_STUDIO = $visualStudio
        & $env:ComSpec /d /c (Join-Path $bridgeRoot 'native/build-native.cmd')
        if ($LASTEXITCODE -ne 0) { throw 'Bridge native compilation or ABI validation failed.' }
    } finally {
        $env:BRIDGE_JAVA_HOME = $previousJdk
        $env:BRIDGE_ROOT = $previousRoot
        $env:BRIDGE_VISUAL_STUDIO = $previousVs
    }
}

if (-not $SkipTests) {
    $testSources = @(Get-ChildItem -LiteralPath (Join-Path $bridgeRoot 'src/test/java') -Recurse -Filter '*.java' |
        ForEach-Object FullName)
    & $javac -source 8 -target 8 -encoding UTF-8 -cp $classes -d $testClasses @testSources
    if ($LASTEXITCODE -ne 0) { throw 'Bridge test compilation failed.' }
    & $java -ea -cp "$classes;$testClasses" com.nvidia.rtxremix.BridgeContractTest $bridgeRoot
    if ($LASTEXITCODE -ne 0) { throw 'Bridge contract tests failed.' }
    if (-not $SkipNative) {
        $smokeArgs = @((Join-Path $nativeOutput 'remixbridge.dll'))
        if ($RuntimeDirectory) { $smokeArgs += (Join-Path $RuntimeDirectory 'd3d9.dll') }
        Push-Location $buildRoot
        try {
            & $java -Xcheck:jni -cp "$classes;$testClasses" com.nvidia.rtxremix.NativeBridgeSmokeTest @smokeArgs
            if ($LASTEXITCODE -ne 0) { throw 'Bridge native smoke test failed.' }
        } finally { Pop-Location }

    }
}
Write-Output "Built $jarPath"
if (-not $SkipNative) { Write-Output "Built $(Join-Path $nativeOutput 'remixbridge.dll')" }
