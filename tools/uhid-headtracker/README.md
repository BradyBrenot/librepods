# UHID head-tracker helper (test tool)

Standalone, app-free Android UHID head tracker for the head-tracked spatial audio spike
(`docs/plans/head-tracked-spatial-audio.md`). It runs as the **shell UID 2000** through
`app_process` (the identity Shizuku UserServices use), opens `/dev/uhid`, registers a
Bluetooth-bus HID device that implements the AOSP head-tracker HID protocol, and automates
the GET/SET feature-report replies that the AOSP `hid` command requires the caller to drive
interactively.

It does **not** touch the AirPods and is not part of the app. It proves the OS-side path:

```
UHID device -> hidraw -> dynamic-sensor HAL -> TYPE_HEAD_TRACKER
            -> SpatializerPoseController (SensorPoseProvider) -> spatializer pose input
```

This proves the pose-input path, not audible HRTF/effect output.

Nothing under `android/`, Gradle, or `docs/` is modified.

## Files

| File | Purpose |
| --- | --- |
| `UhidHeadTracker.java` | The helper: CREATE2, GET/SET_REPORT replies, 50 Hz input report 1, teardown. |
| `SpatialTrackProbe.java` | Optional test harness: silent 5.1 `AudioTrack` (needed to make the framework activate the sensor, see below). |
| `build.ps1` | `javac` -> `d8` -> `classes.dex` -> `build/uhid.jar`. Builds the probe only when an SDK `android.jar` is found. |
| `run.ps1` | Push + `app_process` (foreground or detached), optional probe, cleanup. |

## Device facts (target)

- Pixel 9 Pro (`caiman`), serial `<serial>`, Android 17 / SDK 37, user build, `ro.debuggable=0`, no root.
- `adb shell id` -> `uid=2000(shell)`, groups include `3011(uhid)`, context `u:r:shell:s0`.
- `/dev/uhid` is `crw-rw---- uhid uhid 10,239`; shell can open it read/write.
- AirPods 4 ANC `AA:BB:CC:DD:EE:FF` connected over A2DP with Spatial Audio + Head tracking
  enabled (`HT: true HTenabled: true`).
- Kernel `uhid_event` is 4376 bytes (`u32 type` + 4372-byte union); the helper writes each
  event as one 4376-byte `write(2)` (the kernel can zero-extend short writes).

## Build

```powershell
# from tools/uhid-headtracker
.\build.ps1
# or: javac --release 11 -d build UhidHeadTracker.java
#     d8 --min-api 33 --output build/dex build/com/librepods/uhid/UhidHeadTracker.class
#     jar --create --file build/uhid.jar -C build/dex classes.dex
```

Requirements: JDK 11+ (`javac`, `jar`) and `d8` from
`%ANDROID_HOME%\build-tools\<version>\d8.bat` or `PATH` (override with `-D8`).
The probe additionally needs an SDK `android.jar`; `build.ps1` auto-detects
`%ANDROID_HOME%\platforms\android-*\android.jar`, or pass `-AndroidJar <path>`, or `-NoProbe`
to build the helper only. No NDK is used.

## Run

```powershell
# foreground, runs until Ctrl-C / kill (fd close destroys the device)
.\run.ps1 -Mac AA:BB:CC:DD:EE:FF
# 30 second self-terminating test (sends UHID_DESTROY and exits 0)
.\run.ps1 -Mac AA:BB:CC:DD:EE:FF -Seconds 30

# leave it running next to the 5.1 probe, then stop and clean up
# (-Probe requires -Detach)
.\run.ps1 -Mac AA:BB:CC:DD:EE:FF -Detach -Probe -Seconds 60
.\run.ps1 -Cleanup
```

Equivalent raw commands on the device:

```sh
adb -s <serial> push build/uhid.jar /data/local/tmp/uhid.jar
adb -s <serial> shell "CLASSPATH=/data/local/tmp/uhid.jar app_process /system/bin \
  com.librepods.uhid.UhidHeadTracker AA:BB:CC:DD:EE:FF --seconds 30"
# or, equivalent on this build:
adb -s <serial> shell "app_process -Djava.class.path=/data/local/tmp/uhid.jar /system/bin \
  com.librepods.uhid.UhidHeadTracker AA:BB:CC:DD:EE:FF --seconds 30"
```

Helper options: `<BT-MAC>` (required; becomes the persistent UUID),
`--seconds N`, `--no-report-id`, `--rate HZ` (default 50).

The 5.1 probe, which the acceptance checks need:

```sh
adb -s <serial> shell "CLASSPATH=/data/local/tmp/uhid.jar app_process /system/bin \
  com.librepods.uhid.SpatialTrackProbe --seconds 60 --mask 5.1"
```

## Why the 5.1 probe is required

`Spatializer::checkSensorsState_l()` only hands the head sensor to
`SpatializerPoseController` when **all** of these hold (AOSP `Spatializer.cpp`):

```cpp
if (shouldUseHeadTracking_l() && mLevel != Spatialization::Level::NONE
        && mDesiredHeadTrackingMode != HeadTrackingMode::STATIC
        && mHeadSensor != SpatializerPoseController::INVALID_SENSOR) {
    mPoseController->setHeadSensor(mHeadSensor);
```

and `shouldUseHeadTracking_l()` is `containsImmersiveChannelMask(mActiveTracksMasks)`, i.e. it
needs an **active immersive (multichannel) playback track**. Enabling Spatial Audio in
Bluetooth settings already gives `mLevel = MULTICHANNEL` and the per-device head-tracker
sensor handle, but with no 5.1 track playing the sensor is never activated:
`SpatializerPoseController` stays `HeadSensor: INVALID`, `Sensors total number 0`, and the HAL
never calls `enable()` (so no `UHID_SET_REPORT` ever appears).

`SpatialTrackProbe` writes silence to a 5.1 PCM `AudioTrack`; the policy then reports
`FormatInfo{isSpatialized=true, channelMask=0x3f}`, and the pose controller activates the
sensor within ~1 s.

## Verified on device (2026-11-03)

Run: `.\run.ps1 -Mac AA:BB:CC:DD:EE:FF -Detach -Probe -Seconds 40`, checked while it was still running.

1. `adb shell dumpsys sensorservice`

```
0x00000017) LibrePods Head Tracker    | Bluetooth <vendor>:<product> | ver: 16777216 | type: android.sensor.head_tracker(37) | perm: n/a | flags: 0x00000020
...
LibrePods Head Tracker (handle=0x00000017, connections=1)
```

The sensor is in **Active sensors** with `connections=1`, requested by
`pid=1609 uid=1041` (audioserver), `samplingPeriod=10000us ... result=OK`.

2. `adb shell dumpsys media.audio_policy`

```
  HeadSensorHandle: 0x00000017
   HeadSensor: 0x00000017 (active world-to-head : head-relative velocity) ...
   ActualMode: WORLD_RELATIVE
    Sensors total number 2:
    [Handle: 0x00000017, Format kRotationVectorsAndDiscontinuityCount Period (set 10000 max 38.0814 min 5.0591) ms, DiscontinuityCount: 0]
```

`HeadSensor` is no longer `INVALID` and `ActualMode` is `WORLD_RELATIVE` (was `STATIC`).
"Sensors total number" is 2, not 1, because the pose controller also subscribes the screen
sensor (`Handle: 0x0101000a, Format kQuaternion`); the head sensor entry is the
`kRotationVectorsAndDiscontinuityCount` one shown above. The pose controller logged
`calculated 43..51 samples in 1.0000 second(s)` while the helper sends 50 Hz, and the
synthetic +/-0.05 rad pitch step showed up as `pitch min -2.87 / max 2.87` degrees during the
first ~6 s of activation. After the 6 s auto-recenter window the pose returns to ~0 and the
same motion is reported as head-relative velocity (`d_pitch` about +/-27 deg/s), because
+/-2.87 deg is below the processor's 0.183 rad (10.5 deg) recenter threshold.

3. `adb shell dumpsys audio`

```
type: 8 ... addr: XX:XX:XX:XX:XX:XX ... enabled: true HT: true HTenabled: true
	mActualHeadTrackingMode:HEAD_TRACKING_MODE_RELATIVE_WORLD
	headtracker available:true
```

4. `adb shell logcat -d | grep 'avc: denied' | grep -v adbd` -> no output. The only
   `avc: denied` strings in the buffer are the adbd command echoes of the grep itself, so
   there is no denial for `uhid_device` / `hidraw_device`.

5. Cleanup: `.\run.ps1 -Cleanup` stops the helper and removes `/data/local/tmp/uhid.jar` and
   both logs. Verified afterwards: no `app_process` helper, `dumpsys sensorservice` has no
   `LibrePods` entry, `headtracker available:false`, `mActualHeadTrackingMode:DISABLED`
   (back to the pre-test state), and `/dev/uhid` is untouched.

## UHID / feature-report conventions (as measured)

- `CREATE2`: name `LibrePods Head Tracker`, uniq = MAC string, `bus=0x05` (Bluetooth),
  vendor `0x004C`, product/version/country 0, the 171-byte AOSP descriptor. The kernel emits
  `UHID_START` immediately and `UHID_OPEN` when the HAL opens hidraw (both seen at <10 ms).
- **GET_REPORT reply data includes the report ID as the first byte** (mirrors the AOSP `hid`
  command): `rnum=2` -> `02` + 23 ASCII + 16-byte UUID (40 bytes), `rnum=1` -> `01` + state
  byte (2 bytes). This variant was accepted by the HAL; the `--no-report-id` alternative
  exists in case a different HAL wants the bare payload (not needed here).
- **SET_REPORT data also includes the report ID** on this kernel: the HAL enables with
  `rnum=1, size=2, data=0103`. The helper accepts both `01 xx` and bare `xx`, stores the
  state byte, and always replies `err=0`. The HAL performs 3 GET/SET pairs on enable.
- `rtype=0` is `UHID_FEATURE_REPORT` (not an anomaly); select report 1/2 with `rnum`. Do not
  gate the state machine on the numeric `rtype` value.
- Feature report 1 values observed: `0x03` (All Events + Full Power, interval code 0) while
  head tracking is active, `0x01`/`0x00` when the spatial track stops and the HAL powers the
  sensor down. The helper keeps streaming at 50 Hz regardless (test-tool behaviour; the host
  ignores reports while powered off). A production helper should honour the host gate.
- Input report 1: `01` + 3 x i16 rotation vector (`i16 = round(rad * 32767/pi)`) + 3 x i16
  angular velocity (`i16 = round(rad/s * 32767/32)`) + u8 discontinuity = 14 bytes, sent every
  20 ms (50 Hz) while `UHID_START` + `UHID_OPEN` are set. The synthetic pose alternates
  +/-0.05 rad around X every 500 ms; angular velocity and discontinuity are 0.
- The HAL requested a 10 ms period; this test tool sends 20 ms (it does not honor the host
  interval) and still meets the AOSP >= 50 Hz rule. The descriptor's interval range is
  10..100 ms; the `min`/`max` values in the dump are delivery/jitter bounds, not the
  descriptor range. The pose controller measured 43-51 delivered samples/s.

## Implementation notes / pitfalls

- **Do not use `FileChannel.read()` for the blocking event read.** `FileChannelImpl.read()`
  holds the channel's position lock while it is blocked in `read(2)`, so every
  `FileChannel.write()` on the same fd (input reports, `DESTROY`) blocks behind it. With that
  layout the helper enumerated the device, answered the initial GET_REPORTs, and then silently
  sent nothing at all: no input reports, no `DESTROY`, and `SIGTERM` could not even tear the
  JVM down. The fix is a `RandomAccessFile` (single shared fd, no position lock) for
  `read`/`write`; `FileChannel` (from `raf.getChannel()`) is used only for the 4376-byte event
  writes, and events are built/parsed with little-endian `ByteBuffer`s.
- Every event is written with one `write(2)` of exactly 4376 zero-padded bytes; a short write
  is treated as fatal. The kernel can zero-extend short user writes, but exact-size writes are
  the safest. Reads loop until 4376 bytes are collected.
- `--seconds` teardown runs on its own thread with a 3 s join and then `System.exit(0)`, so a
  wedged kernel write cannot keep the process alive.
- `UHID_DESTROY` wakes the blocked reader (`EVENT CLOSE`) and the process exits normally; the
  device and sensor disappear immediately.

## Limitations

- **`Ctrl-C`/`SIGTERM` does not run the JVM shutdown hook** under `app_process` on this build:
  `kill -TERM` printed `Terminated` and no `TX DESTROY` line appeared. Cleanup is still safe
  because closing the fd (process exit) makes the kernel destroy the HID device; verified the
  sensor disappears and `headtracker available` returns to `false`. Only the `--seconds` path
  sends an explicit `UHID_DESTROY`.
- AOSP only activates the head tracker while an immersive track is playing. Expect
  `HeadSensor: INVALID` with no 5.1/Atmos playback even when everything else is correct; this
  is framework policy, not a helper failure. The stereo-content caveat from the plan still
  applies.
- The pose is synthetic. It proves the transport, activation, rate and teardown; it does not
  validate AirPods 4 axis mapping, latency, or real spatial rendering.
- This is **not a Shizuku test**. It runs through `adb shell` as UID 2000 (`shell`), which is
  the same identity a Shizuku UserService uses, but the Shizuku Binder transport and its
  lifecycle still need a separate integration test.
- `SpatialTrackProbe` relies on `AudioTrack`'s default spatialization behaviour
  (`SPATIALIZATION_BEHAVIOR_AUTO`); the hidden `setSpatializationBehavior(int)` setter is
  blocked by hidden-API filtering under `app_process` (`NoSuchMethodException`), which is fine
  because AUTO is the default and the policy confirms `isSpatialized=true`.
- shell-UID access to `/dev/uhid` is OEM/build dependent. It works on this Pixel 9 Pro; a
  production Shizuku UserService still has to re-verify `u:r:shell:s0`, the `AID_UHID` group
  and `allow shell uhid_device:chr_file rw_file_perms` on each target.
- No app integration, no Gradle changes, no binding to the AirPods `0x17` stream: that is the
  next step (`HeadTrackingParser`/`PoseConverter` -> this UHID state machine).



