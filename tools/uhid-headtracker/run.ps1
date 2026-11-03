#requires -Version 5.1
<#
.SYNOPSIS
  Pushes and runs the standalone UHID head-tracker helper on the Pixel via app_process.

.DESCRIPTION
  Runs as the shell UID (2000) - the same identity Shizuku uses - so /dev/uhid is writable
  without root. The helper registers a Bluetooth-bus HID head tracker that carries the
  headset MAC in its persistent unique ID, answers GET/SET feature reports, and streams
  synthetic pose at 50 Hz.

  By default the helper runs in the foreground so Ctrl-C / `kill` ends it (the kernel
  destroys the UHID device when the fd closes). Use -Detach to leave it running in the
  background with a device-side log, and -Cleanup to stop it and remove the artifacts.

.PARAMETER Probe
  Also start SpatialTrackProbe (silent 5.1 AudioTrack). AOSP only activates the head
  tracker while an immersive/spatialized track is playing, so this is required to see
  SpatializerPoseController pick up the sensor. Requires -Detach.

.EXAMPLE
  .\run.ps1 -Mac AA:BB:CC:DD:EE:FF -Build -Seconds 30

.EXAMPLE
  .\run.ps1 -Mac AA:BB:CC:DD:EE:FF -Detach -Probe -Seconds 60
  # ... run adb dumpsys checks ...
  .\run.ps1 -Cleanup
#>
param(
    [string]$Mac = "",
    [int]$Seconds = 0,
    [string]$Serial = "",
    [string]$Adb = "",
    [switch]$NoReportId,
    [switch]$Detach,
    [switch]$Build,
    [switch]$Probe,
    [int]$ProbeSeconds = 0,
    [switch]$Cleanup,
    [string]$DeviceDir = "/data/local/tmp"
)

$ErrorActionPreference = "Stop"
$jar = Join-Path $PSScriptRoot "build\uhid.jar"
$deviceJar = "$DeviceDir/uhid.jar"
$deviceLog = "$DeviceDir/uhid-headtracker.log"
$deviceProbeLog = "$DeviceDir/uhid-probe.log"
$mainClass = "com.librepods.uhid.UhidHeadTracker"
$probeClass = "com.librepods.uhid.SpatialTrackProbe"

function Resolve-Adb {
    if ($Adb -and (Test-Path $Adb)) { return $Adb }
    if ($env:ADB -and (Test-Path $env:ADB)) { return $env:ADB }
    if ($env:LOCALAPPDATA) {
        $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (Test-Path $sdkAdb) { return $sdkAdb }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return "adb"
}

$adbExe = Resolve-Adb
$adbPrefix = @()
if ($Serial) { $adbPrefix += @("-s", $Serial) }
if (-not $Mac -and -not $Cleanup) { throw "-Mac <BT-MAC> is required (for example -Mac AA:BB:CC:DD:EE:FF)" }
if ($Probe -and -not $Detach) { throw "-Probe requires -Detach (the probe runs alongside the detached helper)" }

function Invoke-Adb {
    param([string[]]$Arguments)
    # Native stderr (e.g. "adb push" progress) shows up as ErrorRecords under PowerShell 5.1;
    # keep those non-terminating and judge success by the exit code instead.
    $ErrorActionPreference = "Continue"
    $out = & $adbExe @adbPrefix @Arguments 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        throw "adb $($Arguments -join ' ') failed ($code): $out"
    }
    return $out
}

function Stop-Helper {
    $ps = & $adbExe @adbPrefix shell "ps -A -o PID,ARGS" 2>&1
    $killed = 0
    foreach ($line in $ps) {
        if ($line -match 'app_process\s+.*com\.librepods\.uhid\.(UhidHeadTracker|SpatialTrackProbe)') {
            $pidText = ($line.Trim() -split '\s+')[0]
            & $adbExe @adbPrefix shell "kill -TERM $pidText" 2>&1 | Out-Null
            Write-Host "stopped pid $pidText"
            $killed++
        }
    }
    if ($killed -eq 0) { Write-Host "no helper process was running" }
    & $adbExe @adbPrefix shell "rm -f $deviceJar $deviceLog $deviceProbeLog" 2>&1 | Out-Null
    Write-Host "removed $deviceJar and test logs on device"
}

if ($Cleanup) {
    Stop-Helper
    return
}

if ($Build -or -not (Test-Path $jar)) {
    if (-not $Build -and -not (Test-Path $jar)) {
        Write-Host "build/uhid.jar missing, building first"
    }
    & (Join-Path $PSScriptRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}

if (-not (Test-Path $jar)) { throw "jar not found: $jar" }

$helperArgs = @($Mac)
if ($Seconds -gt 0) { $helperArgs += @("--seconds", "$Seconds") }
if ($NoReportId) { $helperArgs += "--no-report-id" }
$helperCmd = "CLASSPATH=$deviceJar app_process /system/bin $mainClass " + ($helperArgs -join " ")

if ($ProbeSeconds -le 0) { $ProbeSeconds = if ($Seconds -gt 0) { $Seconds } else { 60 } }
$probeCmd = "CLASSPATH=$deviceJar app_process /system/bin $probeClass --seconds $ProbeSeconds --mask 5.1"

# A leftover helper with the same UUID can make SpatializerHelper pick the old sensor.
Stop-Helper
Start-Sleep -Milliseconds 500
$sensorDump = & $adbExe @adbPrefix shell "dumpsys sensorservice" 2>&1
if ($sensorDump -match 'LibrePods Head Tracker') {
    throw "a LibrePods head tracker sensor is still present; wait for it to disappear and retry"
}

Push-Location $PSScriptRoot
try {
    Invoke-Adb @("push", $jar, $deviceJar) | Out-Null
} finally {
    Pop-Location
}
Write-Host "pushed $jar -> $deviceJar"
Write-Host "helper: $mainClass <MAC> (MAC redacted in this echo)"

if ($Detach) {
    Invoke-Adb @("shell", "rm -f $deviceLog") | Out-Null
    Invoke-Adb @("shell", "nohup sh -c '$helperCmd' > $deviceLog 2>&1 &") | Out-Null
    Write-Host "helper detached, device log: $deviceLog"
    if ($Probe) {
        Invoke-Adb @("shell", "rm -f $deviceProbeLog") | Out-Null
        Invoke-Adb @("shell", "nohup sh -c '$probeCmd' > $deviceProbeLog 2>&1 &") | Out-Null
        Write-Host "5.1 spatial-track probe detached, device log: $deviceProbeLog"
        Write-Host "AOSP only activates the head tracker while an immersive track is active."
    }
    $serialArg = if ($Serial) { "-s $Serial " } else { "" }
    Write-Host "watch:   adb $serialArg shell tail -f $deviceLog"
    Write-Host "stop:    .\run.ps1 -Cleanup"
    return
}

Write-Host "running in foreground (Ctrl-C to stop)"
& $adbExe @adbPrefix shell $helperCmd
if ($LASTEXITCODE -ne 0) {
    throw "helper exited with code $LASTEXITCODE"
}
