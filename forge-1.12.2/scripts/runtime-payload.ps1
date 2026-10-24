# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

function Get-ForgeRuntimeFiles {
    param([Parameter(Mandatory = $true)][string]$RuntimeDirectory)

    $sourceRoot = (Resolve-Path -LiteralPath $RuntimeDirectory -ErrorAction Stop).Path.TrimEnd('\', '/')
    if (-not [IO.Directory]::Exists($sourceRoot)) { throw 'The runtime source must be a directory.' }
    $rules = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot '../config/runtime-payload-excludes.txt') |
        Where-Object { $_.Trim() -and -not $_.TrimStart().StartsWith('#') } | ForEach-Object { [regex]::new($_) })
    $files = [Collections.Generic.SortedDictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($sourceRoot)
    while ($pending.Count) {
        foreach ($entry in Get-ChildItem -LiteralPath $pending.Pop() -Force -ErrorAction Stop) {
            $relative = $entry.FullName.Substring($sourceRoot.Length + 1).Replace('\', '/')
            $matchPath = if ($entry.PSIsContainer) { $relative + '/' } else { $relative }
            $excluded = $false
            foreach ($rule in $rules) { if ($rule.IsMatch($matchPath)) { $excluded = $true; break } }
            if ($excluded) { continue }
            # The source root may be Packman's read-only junction; never traverse nested links.
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked runtime member is unsupported: $relative" }
            if ($entry.PSIsContainer) { $pending.Push($entry.FullName); continue }
            if ($files.Count -ge 8190 -or $entry.Length -gt 2GB) { throw 'The runtime inventory exceeds the extraction limits.' }
            if ($files.ContainsKey($relative)) { throw "Duplicate runtime member: $relative" }
            $files.Add($relative, $entry.FullName)
        }
    }
    if (-not $files.ContainsKey('d3d9.dll')) { throw 'The runtime source is missing d3d9.dll.' }
    return ,$files
}

function Assert-ForgeOwnedPath {
    param([Parameter(Mandatory = $true)][string]$GameDirectory,
          [Parameter(Mandatory = $true)][string]$Path)

    $game = [IO.Path]::GetFullPath($GameDirectory).TrimEnd('\', '/')
    $target = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if (-not $target.StartsWith($game + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'A native installation target must stay inside the selected game directory.'
    }
    # Check the complete ancestry as well as descendants: GetFullPath does not resolve junctions.
    $walk = $target
    while ($walk) {
        if (Test-Path -LiteralPath $walk) {
            $entry = Get-Item -LiteralPath $walk -Force -ErrorAction Stop
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Installation target contains a link: $walk" }
        }
        $parent = [IO.Directory]::GetParent($walk)
        $walk = if ($parent) { $parent.FullName } else { $null }
    }
    return $target
}

function Assert-ForgeUnlinkedTree {
    param([string]$GameDirectory, [string]$Path)
    $checked = Assert-ForgeOwnedPath -GameDirectory $GameDirectory -Path $Path
    if (-not (Test-Path -LiteralPath $checked)) { return }
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($checked)
    while ($pending.Count) {
        foreach ($entry in Get-ChildItem -LiteralPath $pending.Pop() -Force -ErrorAction Stop) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Installation tree contains a link: $($entry.FullName)" }
            if ($entry.PSIsContainer) { $pending.Push($entry.FullName) }
        }
    }
}

function Remove-ForgeStagingTree {
    param([string]$GameDirectory, [string]$Path)
    $checked = Assert-ForgeOwnedPath -GameDirectory $GameDirectory -Path $Path
    $name = [IO.Path]::GetFileName($checked)
    if ([IO.Directory]::GetParent($checked).FullName -ne [IO.Path]::GetFullPath($GameDirectory).TrimEnd('\', '/') -or
        $name -notmatch '^\.rlcraft-remix-(stage|previous)-[0-9a-f]{32}$') { throw 'Refusing to remove an unowned staging directory.' }
    Assert-ForgeUnlinkedTree -GameDirectory $GameDirectory -Path $checked
    if (Test-Path -LiteralPath $checked) { Remove-Item -LiteralPath $checked -Recurse -Force -ErrorAction Stop }
}

function Test-ForgeCommandDirectory {
    param([string]$CommandLine, [string]$Directory)
    if (-not $CommandLine -or -not $Directory) { return $false }
    $path = [IO.Path]::GetFullPath($Directory).Replace('/', '\').TrimEnd('\')
    $line = $CommandLine.Replace('/', '\')
    $offset = $line.IndexOf($path, [StringComparison]::OrdinalIgnoreCase)
    while ($offset -ge 0) {
        $end = $offset + $path.Length
        if ($end -eq $line.Length -or $line[$end] -in @('\', '"', "'", ' ', "`t")) { return $true }
        $offset = $line.IndexOf($path, $end, [StringComparison]::OrdinalIgnoreCase)
    }
    return $false
}

function Get-ForgePrismInstance {
    param([string]$GameDirectory)
    $game = [IO.Path]::GetFullPath($GameDirectory).TrimEnd('\', '/')
    if ([IO.Path]::GetFileName($game) -notin @('minecraft', '.minecraft')) { return $null }
    $parent = [IO.Directory]::GetParent($game).FullName
    if (Test-Path -LiteralPath (Join-Path $parent 'instance.cfg') -PathType Leaf) { return $parent }
    return $null
}

function Test-ForgeClientCommand {
    param([string]$CommandLine, [string]$GameDirectory, [string]$PrismInstance)
    # Prism sends game arguments over stdin; the JVM command still names the exact
    # instance's natives directory. Do not widen this match to the launcher/workspace:
    # unrelated instances and dedicated server processes may share those directories.
    return (Test-ForgeCommandDirectory $CommandLine $GameDirectory) -or
        ($PrismInstance -and (Test-ForgeCommandDirectory $CommandLine $PrismInstance))
}

function Assert-ForgeInstanceIdle {
    param([string]$GameDirectory)
    $instance = Get-ForgePrismInstance $GameDirectory
    # Account arguments remain in memory; never log or include a command line in an exception.
    foreach ($process in Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -Property CommandLine -ErrorAction Stop) {
        if (-not $process.CommandLine) { throw 'Cannot verify that an active Java process is unrelated to this instance.' }
        if (Test-ForgeClientCommand $process.CommandLine $GameDirectory $instance) {
            throw 'Close the game using the selected instance before installing the prototype.'
        }
    }
}

function Install-ForgeRuntime {
    param([Parameter(Mandatory = $true)][string]$GameDirectory,
          [Parameter(Mandatory = $true)][string]$RuntimeDirectory,
          [Parameter(Mandatory = $true)][string]$Bridge,
          [Parameter(Mandatory = $true)][scriptblock]$AssertIdle)

    $game = (Resolve-Path -LiteralPath $GameDirectory -ErrorAction Stop).Path.TrimEnd('\', '/')
    $source = (Resolve-Path -LiteralPath $RuntimeDirectory -ErrorAction Stop).Path.TrimEnd('\', '/')
    $bridgeSource = (Resolve-Path -LiteralPath $Bridge -ErrorAction Stop).Path
    if (-not [IO.File]::Exists($bridgeSource)) { throw 'The local JNI bridge must be a file.' }
    $target = Assert-ForgeOwnedPath -GameDirectory $game -Path (Join-Path $game 'remix-sdk')
    if ($source -eq $target -or $source.StartsWith($target + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        $game.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $game -eq $source) {
        throw 'The source runtime must be independent of the destination game/runtime.'
    }
    Assert-ForgeUnlinkedTree -GameDirectory $game -Path $target
    & $AssertIdle
    $files = Get-ForgeRuntimeFiles -RuntimeDirectory $source
    $files.Add('remixbridge.dll', $bridgeSource)
    $token = [Guid]::NewGuid().ToString('N')
    $stage = Assert-ForgeOwnedPath -GameDirectory $game -Path (Join-Path $game ".rlcraft-remix-stage-$token")
    $previous = Assert-ForgeOwnedPath -GameDirectory $game -Path (Join-Path $game ".rlcraft-remix-previous-$token")
    $backedUp = $false
    $published = $false
    try {
        [void][IO.Directory]::CreateDirectory($stage)
        foreach ($entry in $files.GetEnumerator()) {
            $destination = Assert-ForgeOwnedPath -GameDirectory $game -Path (Join-Path $stage $entry.Key)
            [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
            $hash = (Get-FileHash -LiteralPath $entry.Value -Algorithm SHA256).Hash
            Copy-Item -LiteralPath $entry.Value -Destination $destination -ErrorAction Stop
            if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $hash) { throw "Staged runtime checksum mismatch: $($entry.Key)" }
        }
        & $AssertIdle
        Assert-ForgeUnlinkedTree -GameDirectory $game -Path $target
        [void](Assert-ForgeOwnedPath -GameDirectory $game -Path $previous)
        if (Test-Path -LiteralPath $target) {
            Move-Item -LiteralPath $target -Destination $previous -ErrorAction Stop
            $backedUp = $true
        }
        try {
            & $AssertIdle
            [void](Assert-ForgeOwnedPath -GameDirectory $game -Path $stage)
            [void](Assert-ForgeOwnedPath -GameDirectory $game -Path $target)
            Move-Item -LiteralPath $stage -Destination $target -ErrorAction Stop
            $published = $true
        } catch {
            if ($backedUp -and -not (Test-Path -LiteralPath $target)) {
                [void](Assert-ForgeOwnedPath -GameDirectory $game -Path $previous)
                [void](Assert-ForgeOwnedPath -GameDirectory $game -Path $target)
                Move-Item -LiteralPath $previous -Destination $target -ErrorAction Stop
                $backedUp = $false
            }
            throw
        }
    } finally {
        Remove-ForgeStagingTree -GameDirectory $game -Path $stage
        # Preserve the only old copy if publishing and rollback both failed.
        if ($published -and $backedUp) { Remove-ForgeStagingTree -GameDirectory $game -Path $previous }
    }
}
