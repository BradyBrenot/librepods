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

## Research notes (2026-11-03)

What the platform and published measurements say:

- AOSP `SpatializerPoseController` predicts **120 ms** into the future by default
  (`kPredictionDuration`) and reads `audio.spatializer.prediction_duration_ms`
  when set. The predictor can be `LAST`, `TWIST`, `LEAST_SQUARES` or `AUTO`
  (`audio.spatializer.pose_predictor_type`); recent AOSP defaults to
  `LEAST_SQUARES`. The source comment says the prediction duration should match
  the actual head-tracker-to-audio-output latency and that too much prediction
  overshoots during acceleration.
  - <https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audiopolicy/service/SpatializerPoseController.cpp>
  - <https://android.googlesource.com/platform/frameworks/av/+/8702c31c44>
- Android's spatial-audio guidance targets **< 150 ms round trip** from head
  movement to audio; for A2DP Classic it recommends a low-latency codec (Opus)
  and HAL latency control. AOSP's own default predictor is 120 ms, so most of
  that budget is already assigned to prediction.
  - <https://source.android.com/docs/core/audio/spatial>
- Published AirPods/A2DP measurements vary widely by host and codec:
  - Kinicho measured Apple Spatial Audio (AirPods Pro, iPhone) at **~204 ms**
    head-motion-to-audio round trip; the audio return leg alone was ~144 ms.
    <https://kinicho.medium.com/latency-in-spatial-audio-57236c15f243>
  - Stephen Coyle measured AirPods Pro 2 audio latency at **~126 ms** on iOS.
    <https://stephencoyle.net/airpods-pro-2>
  - SoundGuys/Android Authority measured AAC end-to-end at **~369 ms average**
    across four older Android phones; the best phone overall averaged ~244 ms
    across codecs. These are old (2019-2021) and device-dependent.
    <https://soundtest.net/guides/bluetooth-audio-latency-by-codec>
  - A 2026 earbud round-up measured head-tracking latency at ~120 ms median for
    AirPods Pro 3, ~150 ms for Pixel Buds Pro 2, and 180-280 ms for several
    competitors. <https://havitsmart.com/blogs/guide/best-earbuds-for-spatial-audio-2026>
- OpenTrack-style trackers use prediction/lead to compensate for output latency;
  smoothing adds latency, so prediction is preferred over filtering for
  motion-to-photon/audio response.
  <https://github.com/opentrack/opentrack/issues/1414>

Implication for this bridge: the platform already adds 120 ms of prediction. If
the Pixel 9 Pro + AAC output latency is ~200-250 ms, the remaining app-side
compensation is roughly **80-130 ms**. That is small enough to test with a
user-tunable pose lead, but large enough that over-compensation will be visible
as overshoot/oscillation during fast nods or turns. The property path is closed
on the user build, so the lead must be applied before the HID report.

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

If consecutive samples are more than 200 ms apart (bud removal, link pause),
`HeadTracking` increments the discontinuity counter, reports a zero twist for
that sample, and continues from the current sample as the new previous. A
quaternion sign flip at the neutral antipode is deliberately **not** treated as
a gap: `angularVelocity` already takes the shortest path, so the twist stays
continuous. Tests: `sampleGapRaisesDiscontinuityAndDropsTheTwist`,
`normalCadenceKeepsTwistAndDiscontinuity`, and `antipodeWrapKeepsTwistContinuous`.

### 5. Host-driven battery gating

The UserService knows when the host has reporting enabled (`SET_REPORT` report
1). Expose that state to the app (AIDL `isHostActive()` or a callback) and stop
the AirPods `0x17` stream after a period (e.g. 30 s) with no host activation.
Keep the UHID sensor registered so the OS can activate it again. This is the
main battery improvement, but it needs care: the sensor must be present before
immersive playback starts.

### 6. Pose lead - implemented

AOSP predicts 120 ms from the HID report. If the real output latency is higher,
pre-advance the reported pose by the remainder before sending it:

```text
P_lead = P * exp(omega * lead)
```

`P` is the neutral-relative quaternion; `omega` is the head-frame twist from
`HeadTracking`; the multiplication order matches AOSP's intrinsic prediction.
`HeadPosePredictor` performs the quaternion composition, and the existing
`HeadPoseAxes` mapping is applied afterwards. The angular velocity itself is not
changed, so the platform predictor still works from the advanced pose.

The lead is stored as `system_spatial_audio_lead_ms` (0..200 ms, default 0) and
is exposed as a slider in App Settings next to the system-spatial-audio toggle.
Tuning procedure: play a familiar 5.1/Atmos scene, start at 0, then try 30, 60,
90 and 120 ms; pick the lowest value that keeps the sound field anchored during
slow turns. Too high shows as overshoot when stopping a fast turn.

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

- The 50 Hz and 100 Hz experiments are closed: the AirPods cap at ~25 samples/s
  and the HAL at ~44 samples/s. Do not re-enable them without a new capture.
- Prediction tuning can overshoot during acceleration; only change it after a
  measured latency test. Shell cannot set the property on the user build.
- Host gating can make the first second of playback static if the stream is
  cold; pre-warm on A2DP playback if needed.
- The 200 ms gap threshold assumes the 25 Hz source; re-check it if a faster
  model is supported.
