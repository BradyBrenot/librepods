#requires -Version 5.1
<#
.SYNOPSIS
  Builds the standalone UHID head-tracker helper (and the optional 5.1 audio probe)
  into build/uhid.jar.

.DESCRIPTION
  javac (JDK 11 bytecode) -> d8 -> classes.dex -> jar with classes.dex at the root,
  which is what `app_process -Djava.class.path=...` / `CLASSPATH=...` expects.

  SpatialTrackProbe is only built when an SDK android.jar is available, because it uses
  android.media.AudioTrack. UhidHeadTracker itself needs no Android SDK to compile.

.PARAMETER BuildDir
  Output directory. Defaults to <script dir>/build.
#>
param(
    [string]$BuildDir = (Join-Path $PSScriptRoot "build"),
    [string]$D8 = "",
    [string]$AndroidJar = "",
    [int]$MinApi = 33,
    [switch]$NoProbe
)

$ErrorActionPreference = "Stop"
$source = Join-Path $PSScriptRoot "UhidHeadTracker.java"
$probeSource = Join-Path $PSScriptRoot "SpatialTrackProbe.java"

# Locate a JDK for javac/jar. javac is usually on PATH through the Oracle javapath shim,
# but jar often is not, so resolve JAVA_HOME (or a typical install dir) when possible.
function Find-JdkHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
        return $env:JAVA_HOME
    }
    $roots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Android\Android Studio\jbr"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        if (Test-Path (Join-Path $root "bin\javac.exe")) { return $root }
        foreach ($dir in (Get-ChildItem $root -Directory -ErrorAction SilentlyContinue)) {
            if (Test-Path (Join-Path $dir.FullName "bin\javac.exe")) { return $dir.FullName }
        }
    }
    return $null
}

function Find-D8 {
    $roots = @()
    if ($env:ANDROID_HOME) { $roots += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $roots += $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA "Android\Sdk") }
    if ($env:USERPROFILE) { $roots += (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk") }
    foreach ($root in $roots) {
        if (-not $root -or -not (Test-Path $root)) { continue }
        $candidates = Get-ChildItem (Join-Path $root "build-tools\*\d8.bat") -ErrorAction SilentlyContinue
        if ($candidates) {
            $sorted = $candidates | Sort-Object {
                try { [version]$_.Directory.Name } catch { [version]"0.0" }
            } -Descending
            return $sorted[0].FullName
        }
    }
    $cmd = Get-Command d8 -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Find-AndroidJar {
    $roots = @()
    if ($env:ANDROID_HOME) { $roots += (Join-Path $env:ANDROID_HOME "platforms") }
    if ($env:ANDROID_SDK_ROOT) { $roots += (Join-Path $env:ANDROID_SDK_ROOT "platforms") }
    if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platforms") }
    if ($env:USERPROFILE) { $roots += (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platforms") }
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $jars = Get-ChildItem (Join-Path $root "android-*\android.jar") -ErrorAction SilentlyContinue |
            Sort-Object {
                try { [version]($_.Directory.Name -replace '^android-', '') } catch { [version]"0.0" }
            } -Descending
        if ($jars) { return $jars[0].FullName }
    }
    return $null
}

if (-not $D8) { $D8 = Find-D8 }
if (-not $D8 -or -not (Test-Path $D8)) {
    throw "d8 not found; pass -D8 <path> or set ANDROID_HOME/ANDROID_SDK_ROOT"
}

$jdkHome = Find-JdkHome
$javacExe = if ($jdkHome) { Join-Path $jdkHome "bin\javac.exe" } else { "javac" }
$jarExe = if ($jdkHome -and (Test-Path (Join-Path $jdkHome "bin\jar.exe"))) {
    Join-Path $jdkHome "bin\jar.exe"
} else {
    $null
}
if (-not $AndroidJar) { $AndroidJar = Find-AndroidJar }

$dexDir = Join-Path $BuildDir "dex"
$jarPath = Join-Path $BuildDir "uhid.jar"

if (-not (Test-Path $source)) { throw "source not found: $source" }
if (-not (Test-Path $D8)) { throw "d8 not found: $D8 (pass -D8 <path>)" }

if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null

$sources = @($source)
$classFiles = @()
$useProbe = (-not $NoProbe) -and (Test-Path $probeSource) -and $AndroidJar -and (Test-Path $AndroidJar)
if ($useProbe) {
    $sources += $probeSource
    Write-Host "== probe enabled (android.jar: $AndroidJar)"
} else {
    Write-Host "== probe disabled (no android.jar or -NoProbe); building helper only"
}

Write-Host "== javac ($javacExe)"
$javacArgs = @("--release", "11", "-Xlint:all", "-d", $BuildDir)
if ($useProbe) { $javacArgs += @("-classpath", $AndroidJar) }
$javacArgs += $sources
& $javacExe @javacArgs
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

$classFiles += (Join-Path $BuildDir "com\librepods\uhid\UhidHeadTracker.class")
if ($useProbe) { $classFiles += (Join-Path $BuildDir "com\librepods\uhid\SpatialTrackProbe.class") }
foreach ($c in $classFiles) {
    if (-not (Test-Path $c)) { throw "expected class file missing: $c" }
}

Write-Host "== d8"
& $D8 --min-api $MinApi --output $dexDir @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed with exit code $LASTEXITCODE" }

$dexFile = Join-Path $dexDir "classes.dex"
if (-not (Test-Path $dexFile)) { throw "d8 produced no classes.dex in $dexDir" }

Write-Host "== jar"
if (Test-Path $jarPath) { Remove-Item -Force $jarPath }
if ($jarExe) {
    & $jarExe --create --file $jarPath -C $dexDir classes.dex
    if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
} else {
    # No jar.exe on this machine: a plain zip with classes.dex at the root is valid for ART.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($dexDir, $jarPath)
}

$jar = Get-Item $jarPath
Write-Host ("built {0} ({1} bytes)" -f $jar.FullName, $jar.Length)
