# Head-tracking latency and robustness improvements

Status: P0 implementation in progress (2026-11-03). Follows the verified
`head-tracked-spatial-audio.md` path.

## Context

The Pixel 9 Pro + AirPods 4 ANC + Shizuku UHID path is working with a 5.1
stream. Subjective result: tilt and nod are correct in direction and magnitude;
latency is noticeable. The remaining work is split into latency, robustness,
battery and device coverage.

Current pipeline:

```text
AirPods 0x17 (requested 40 ms, observed ~25 Hz)
  -> HeadTracking (neutral-relative pose + head-frame twist)
  -> Binder oneway to HeadTrackerUserService
  -> UHID input report every 20 ms (50 Hz)
  -> dynamic-sensor HAL -> SpatializerPoseController (120 ms prediction)
  -> A2DP/AAC output
```

## P0 - latency (tested 2026-11-03)

### 1. Request a 50 Hz AirPods stream - no effect

- Changed `HEAD_TRACKING_INTERVAL_MICROS` from `40_000` to `20_000`; the AAP
  request became `... 1A 05 01 20 4E 00 00`.
- Measured with a debug counter (`HeadTrackingRate`): the AirPods 4 ANC still
  produced **24-27 samples/s** (`packets/s` equal to `samples/s`), so the
  accessory caps the stream at 25 Hz regardless of the requested interval.
- Reverted to `40_000`. The 25 Hz source is a firmware limit, not a request bug.

### 2. UHID input reports at 100 Hz - no effect

- Changed the writer period from 20 ms to 10 ms (100 Hz); the host requests
  interval code 0 (10 ms).
- The standalone helper at `--rate 100` and the app writer both still produced
  `calculated 43-44 samples in 1.0000 second(s)` in
  `SpatializerPoseController`. The dynamic-sensor HAL caps delivery at ~50 Hz.
- Reverted to 20 ms (50 Hz, the AOSP minimum). 100 Hz only burned CPU.

### What the measurements mean

Sensor-side and UHID-side rates are already at the platform limit. The remaining
noticeable latency is dominated by the A2DP/AAC output path and the fixed 120 ms
AOSP prediction, not by 25 Hz sampling or 50 Hz UHID reports. The next latency
lever is prediction tuning (P1) and, if the OEM effect allows it, an output
latency setting.

### Verification performed

- `adb logcat -s HeadTrackingRate` (debug builds) logs samples/s and packets/s.
- `adb shell dumpsys media.audio_policy` with a 5.1 probe showed an active
  `HeadSensor`, `ActualMode: WORLD_RELATIVE`, and 43-44 samples/s at both 50 Hz
  and 100 Hz writer settings.
- No `HeadSensor: INVALID` or `avc: denied`; audible 5.1 integration was
  confirmed separately.

## P1 - robustness

### 3. Prediction duration

AOSP's `SpatializerPoseController` defaults to 120 ms prediction and reads
`audio.spatializer.prediction_duration_ms` if set. AAC/A2DP output latency may
be higher. Experiment with 160-220 ms via `adb shell setprop` (if shell is
allowed) and compare subjective latency. Do not ship a property change in the
app; document the recommended value per device.

**Tested 2026-11-03:** `adb shell setprop audio.spatializer.prediction_duration_ms 200`
is denied on the Pixel user build (`Failed to set property`). Tuning requires
root or an audio-policy service change; leave the default 120 ms for now.

### 4. Sensor-gap discontinuity - implemented

If consecutive samples are more than 200 ms apart (bud removal, link pause) or
the relative quaternion is nearly antipodal (a pose wrap), `HeadTracking`
increments the discontinuity counter, clears the previous pose, and reports a
zero twist for that sample. Test:
`sampleGapRaisesDiscontinuityAndDropsTheTwist`.

### 5. Host-driven battery gating

The UserService knows when the host has reporting enabled (`SET_REPORT` report
1). Expose that state to the app (AIDL `isHostActive()` or a callback) and stop
the AirPods `0x17` stream after a period (e.g. 30 s) with no host activation.
Keep the UHID sensor registered so the OS can activate it again. This is the
main battery improvement, but it needs care: the sensor must be present before
immersive playback starts.

## P2 - UX and coverage

- Add a small settings status row: Shizuku running, permission granted, UHID
  sensor active, current sample/report rate.
- Add a "Recenter" action (calls `HeadTracking.recenter()`), useful after the
  user changes posture.
- Per-model axis mapping or a gravity-based calibration for AirPods Pro/Max.
- Oppo Find X9 via Shizuku wireless debugging; Logitech G Cloud if `/dev/uhid`
  is accessible to shell.
- Measure A2DP output latency and compare prediction values; keep the 120 ms
  default if the OEM effect already compensates.

## Risks

- A 50 Hz AirPods request increases AAP traffic and battery use; measure both.
- A 100 Hz UHID writer increases Binder/CPU wakeups; the host interval is the
  contract, but a 20 ms fallback is acceptable if 10 ms is unstable.
- Prediction tuning can overshoot during acceleration; only change it after a
  measured latency test.
- Host gating can make the first second of playback static if the stream is
  cold; pre-warm on A2DP playback if needed.
