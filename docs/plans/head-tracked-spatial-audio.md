# Head-tracked spatial audio for AirPods 4 (ANC)

Status: investigation / high-level design. No production code has been written.
Repository state: `main` at `89356c0` (LibrePods upstream: `librepods-org/librepods`).

## 1. Executive summary

LibrePods already receives the AirPods head-tracking stream. What is missing is a way to
hand that pose to the operating system in the form the OS spatializer understands.

For Android the situation is:

- AirPods 4 with ANC (models `A3055`, `A3056`, `A3057`) have hardware head tracking. Apple
  specifies "Personalized Spatial Audio with dynamic head tracking", and LibrePods marks this
  model as supporting `HEAD_GESTURES`.
- AAP opcode `0x17` carries a motion stream. Recent AirPods firmware sends a 58-byte
  `SensorDataWX` sample containing a Q15 quaternion, rotation rate, user acceleration,
  gravity and a nanosecond timestamp. This is enough to build a full 3D pose, not just the
  two-angle pitch/yaw that the current Android code derives.
- Android's system spatializer (the effect behind Dolby/immersive head tracking) reads head
  pose only from a `Sensor.TYPE_HEAD_TRACKER` dynamic sensor. A normal app cannot register
  such a sensor or push pose into it.
- A privileged shell-UID bridge can create a virtual HID head tracker through `/dev/uhid`.
  That can be full root, or Shizuku in ADB/shell mode if the process can open `/dev/uhid`
  (AOSP's shell domain is allowed to do this). The kernel exposes it as `hidraw`; the Android
  dynamic-sensor HAL turns it into `TYPE_HEAD_TRACKER`; `SpatializerHelper` associates it with
  the current A2DP device by Bluetooth MAC; the system spatializer then consumes the pose for
  content that the OEM effect spatializes.

One important content caveat: AOSP's spatial-audio guidelines say stereo content must not be
rendered through the spatializer effect. Head-tracked spatialization normally applies to
multichannel/Atmos content; ordinary stereo music is bypassed unless the OEM ships a stereo
upmix/spatialization toggle. A perfect pose feed does not by itself make stereo music rotate.
This must be tested on the target phone and stated in the UX.

So the work is split into two very different problems:

1. **Get a correct 3D pose out of the AirPods** (parsing and coordinate conversion). This is
   feasible and mostly a matter of fixing the current parser and validating the AirPods 4
   axes.
2. **Deliver that pose to the OS** (system integration). On Android this requires privileged
   access to `/dev/uhid` (root, or Shizuku/shell mode), an OEM dynamic-sensor HAL, and an OEM
   spatializer effect that actually supports head tracking. On Linux there is no OS-level
   head-tracker API at all; the realistic route is a user-space PipeWire spatializer, not an
   OS feature.

The recommended first step is a short device spike on one rooted or Shizuku-enabled Android
phone, not a full
implementation: prove that a dummy UHID head tracker becomes `TYPE_HEAD_TRACKER` and makes
`Spatializer.isHeadTrackerAvailable()` true, then prove that AirPods 4 ANC produces a valid
Q15 quaternion at >= 50 Hz. If either gate fails, the system-wide route is not viable on that
device.

## 2. What exists today

### 2.1 Android application

Relevant code:

- `android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt`
  - opcode `HEADTRACKING = 0x17` (`Opcodes.HEADTRACKING`)
  - start/stop packet builders around lines 661-782
  - incoming `0x17` dispatch around line 486
- `android/app/src/main/java/me/kavishdevar/librepods/data/Packets.kt`
  - `isHeadTrackingData()` around lines 248-265
- `android/app/src/main/java/me/kavishdevar/librepods/utils/HeadOrientation.kt`
  - `HeadTracking.processPacket()` and the pitch/yaw math around lines 44-93
- `android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt`
  - `onHeadTrackingReceived()` around line 1076
  - `processHeadTrackingData()` around line 2198
  - `startHeadTracking()` / `stopHeadTracking()` around lines 3143-3176
- `android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt`
  - `AirPods4ANC` and its `Capability.HEAD_GESTURES` around lines 96-106
- `android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/HeadTrackingScreen.kt`
  - live visualizer and gesture test UI

Current limitations:

- The parser assumes fixed absolute offsets (43/45/47 and 51/53) and a legacy packet shape.
  The packet is a protobuf message whose sequence-number varint changes length; absolute
  offsets move. A worked example from real captures: the attitude words are at packet
  offsets 43/45/47 only in the 81-byte (two-byte sequence varint) framing; in the 80-byte
  framing they are at 42/44/46. The current visualizer therefore reads shifted words
  whenever the sequence-varint length changes (runs of packets, not literally every other
  packet).
- At 50-100 Hz a packet can contain up to five samples. `isHeadTrackingData()` checks a
  10-byte prefix, `data[10] == 0x44/0x45` (the low byte of the protobuf payload length),
  `data[11] == 0`, and `data.size > 60`; it neither iterates samples nor accepts all
  framings.
- Only two orientation values are used to produce pitch/yaw. Roll, quaternion, timestamp,
  rotation rate and gravity are thrown away.
- The offsets 51/53 used by the gesture detector were labelled "horizontal/vertical
  acceleration" in the older packet layout. Later measurements show those words behave as
  the second and third rotation-rate values, not linear acceleration, in the 81-byte
  framing; in the 80-byte framing those absolute offsets straddle the rotation-y/z words.
- The start/stop packets are static byte arrays. They work for gestures, but robust repeated
  start/stop should use an incrementing request sequence number and the correct stream
  activation order (14 then 16).
- Nothing is exported to the OS. `isHeadTrackingActive` only drives the in-app visualizer and
  gesture detector.

### 2.2 Linux

- `main` contains the older Qt/C++ Linux app; it does not implement head tracking.
- `origin/linux/rust` is a separate Rust rewrite branch. It contains only an unused
  `HEADTRACKING = 0x17` constant (`linux-rust/src/bluetooth/aacp.rs`) and a root-level
  `head-tracking/` Python experiment; it does not contain the Rust scaffold.
- PR #617 is an open PR against `linux/rust`, not merged into that branch or `main`. It adds
  the Rust head-tracking scaffold, gesture detector, and AirPods Max gating. PR #617
  explicitly gates head tracking off for AirPods Max because the Max exposes motion through a
  ProtectedAccess HID-over-AAP relay; the Pro-style `0x17` stream is used for models that
  support it.

### 2.3 AirPods 4 ANC evidence

- Apple tech specs list "Personalized Spatial Audio with dynamic head tracking" for AirPods 4
  and AirPods 4 with ANC; the ANC model uses the H2 chip.
- LibrePods `AirPods4ANC` capability set includes `HEAD_GESTURES`, so the app already assumes
  the motion stream exists.
- The `mutix` project lists "AirPods Pro, Pro 2, Pro 3, AirPods 3 or 4, AirPods Max" as
  head-tracking-capable, but only AirPods Pro 3 were tested.
- `FIocker/aap-head-tracker` lists AirPods 3/4 among experimental candidates for its
  service-14/service-16 decoder, with only AirPods Max 2 confirmed.
- There is no known AirPods 4 ANC capture in this repository that proves which service ID
  and sample layout its firmware uses.

Conclusion: AirPods 4 ANC almost certainly uses the same family of `0x17` motion stream, but
this must be captured and verified on the actual device before the bridge is designed around
it.

## 3. Protocol facts needed by the bridge

### 3.1 Transport and request

- AAP runs over Bluetooth Classic L2CAP PSM `0x1001`.
- The L2CAP channel must be at least `BT_SECURITY_MEDIUM` (encrypted). With the default
  security level the AirPods accept the connection but do not answer the handshake.
- Opcode `0x17` requests head tracking. After the 12-byte AAP header the payload is a small
  protobuf message:

```text
04 00 04 00 17 00 00 00 10 00 LL 00
    08 <seq varint>
    42 0B
        08 <stream>
        10 02
        1A 05 01 <interval_us u32 LE>
```

- `stream 0x0E` (14) is an activation/status stream; `stream 0x10` (16) carries motion
  samples. Stream 16 only starts after stream 14 has been requested at least once. A client
  can request 14, then 16, then stop 14; 16 continues.
- An interval of 0 stops that stream.
- The interval is honoured: 40 000 us ~= 25 Hz, 20 000 us = 50 Hz, 10 000 us ~= 100 Hz.
  Bluetooth delivery saturates at roughly 90-100 samples/s even below 10 ms.
- Sequence numbers are request identifiers. Increment them for every request.

### 3.2 Sample layout

Each motion sample is a 58-byte blob carried inside the protobuf `cmd` message (field 7):
the `cmd` message has field 1 = service (16 for device motion) and field 3 = the 58-byte
blob. To support batched packets, iterate **every** top-level field 7, then every nested
field 3, and return every valid sample in order. Do not parse from the end of the packet:
that would silently discard all but the last sample in a batch.

| Offset in 58-byte blob | Type | Content |
| --- | --- | --- |
| 0 | u8 | `0x01` for a sensor sample |
| 1 | u64 LE | timestamp, nanoseconds |
| 20 | 3 x i16 LE | attitude: Q15 quaternion x, y, z |
| 26 | 3 x i16 LE | rotation rate, raw signed 16-bit |
| 38 | 3 x i16 LE | user acceleration, gravity removed |
| 44 | 3 x i16 LE | gravity, about 1024 per g |
| 54 | u32 LE | counter, in 5 ms ticks |

Bytes 9-19, 32-37 and 50-53 vary little between samples; they are reserved/unused here.
The layout above was measured on AirPods Pro 3; AirPods 4 and other models must be
confirmed against a capture.

The first samples after a start carry a zero attitude and must be skipped. At 100 Hz one
packet can contain several samples; `mutix` documents up to five per packet (its tests use
batches of four).

The attitude is a Q15 quaternion with `w` reconstructed positive:

```text
qx = o1 / 32768.0
qy = o2 / 32768.0
qz = o3 / 32768.0
qw = sqrt(max(0, 1 - qx*qx - qy*qy - qz*qz))
q  = normalize(qx, qy, qz, qw)
```

`FIocker/aap-head-tracker` validated this decoding on AirPods Max 2 and checks that rotating
the gravity vector by the quaternion keeps world gravity nearly constant. The same decoder
accepts service 14/16 packets; AirPods 4 is untested. Note that AirPods Max 2 on firmware
8E258 advertises motion as a dynamically discovered service ID 6 (`devmotion6`), not 14/16.
The parser must not assume 16 forever; log the advertised RTBuddy service IDs and support
service discovery or a per-model fallback.

### 3.3 Ownership and stream lifecycle

- LibrePods' `startHeadTracking()` currently forces a takeover (`OWNS_CONNECTION`) when
  ownership is false. This affects multipoint/other devices. It is **not proven** to be
  required for the motion stream: `mutix` streams at ~85-100 Hz without sending the ownership
  control command. Verify on device; do not force takeover by default. Make it conditional on
  the audio-routing case that actually needs it.
- The motion stream is shared by every AAP connection. Stopping it from one client stops it
  for all.
- The stream pauses when a bud leaves the ear and resumes by itself; a robust implementation
  must detect stale data and re-request the stream.
- The app must not start the bridge when spatial audio/head tracking is disabled; continuous
  50-100 Hz streaming has a battery cost.

## 4. Android system integration

### 4.1 Contract

Android 13+ defines a head-tracker HID protocol
(`source.android.com/docs/core/interaction/sensors/head-tracker-hid-protocol`). The device is
an application collection on Sensors page `0x20`, usage Other:Custom `0xE1`, with:

- Feature report 2 (read-only):
  - Sensor Description `0x0308`: the 23-byte ASCII string `#AndroidHeadTracker#1.0`
  - Persistent Unique ID `0x0302`: 16 bytes, `00 00 00 00 00 00 00 00 42 54 <6-byte MAC>`
    (`BT` + the AirPods identity/BR-EDR address)
- Feature report 1 (read/write):
  - Reporting State: No Events / All Events
  - Power State: Full Power / Power Off
  - Report Interval: 0..63 -> 10..100 ms
- Input report 1:
  - Custom Value 1 `0x0544`: 3 x i16, rotation vector in radians, magnitude <= pi
  - Custom Value 2 `0x0545`: 3 x i16, angular velocity in rad/s, range +/-32
  - Custom Value 3 `0x0546`: u8 discontinuity counter

The host only expects input reports when Power State is Full Power, Reporting State is All
Events, and Report Interval is non-zero. AOSP requires at least 50 Hz reporting (20 ms
interval); 10-20 ms is recommended. The system's `SpatializerPoseController` treats samples
older than 120 ms as stale, and predicts forward by 120 ms by default.

`SpatializerHelper` picks the dynamic sensor whose UUID matches the current audio route.
`UuidUtils.uuidFromAudioDeviceAttributes()` builds the UUID from the A2DP device's identity
address, which is why the HID persistent UUID must contain the AirPods BR/EDR MAC.

The byte order is easy to get wrong. `UuidUtils` decodes the address in **display order**:
for `A4:C3:F0:12:34:56` the 16 UUID bytes are
`00 00 00 00 00 00 00 00 42 54 A4 C3 F0 12 34 56`, i.e.
`UUID(0, 0x4254A4C3F0123456)`. This is not the reversed/kernel order used in `sockaddr_l2`.
If the order is wrong, the sensor still enumerates and `dumpsys sensorservice` shows it, but
`isHeadTrackerAvailable()` stays false forever. Add a unit test for this exact mapping.

#### Content requirement

AOSP's implementation guidelines say stereo content must not be rendered through the
spatializer effect, even when spatial audio is enabled; an implementation that allows stereo
spatialization must present a custom UI. In practice, head-tracked spatialization works for
5.1/Atmos/multichannel content, and ordinary stereo music is bypassed unless the OEM ships a
stereo upmix/spatialization toggle. The plan must test both:
(a) a 5.1 movie and (b) stereo music. If the target OEM does not spatialize stereo, the
feature should say so in the UI rather than implying that all audio rotates.

There is a second activation gate. `Spatializer::shouldUseHeadTracking_l()` is
`containsImmersiveChannelMask(mActiveTracksMasks)`: Android only hands the head sensor to
`SpatializerPoseController` while an immersive/multichannel track is actively playing. With no
5.1/Atmos playback, the sensor can be present and associated (`HT: true`, `headtracker
available: true`) while `SpatializerPoseController` stays `HeadSensor: INVALID` and
`ActualMode: STATIC`. Stereo-only playback never exercises head tracking, even on a device
whose spatializer effect supports it.

### 4.2 Delivery mechanism: `/dev/uhid`

A privileged helper creates a virtual HID device through `/dev/uhid`. The helper can be a
root process or a Shizuku UserService running as shell (see the next subsection):

1. Open `/dev/uhid` (root or shell UID; see the Shizuku subsection).
2. Write one complete `UHID_CREATE2` event with the AOSP report descriptor, `BUS_BLUETOOTH`
   identity, and a name such as "LibrePods Head Tracker".
3. Read kernel events continuously. Reply to every `UHID_GET_REPORT` and
   `UHID_SET_REPORT` with the matching request ID. Persist the host's selected reporting
   state, power state and interval.
4. After `UHID_START`, write `UHID_INPUT2` reports only while the feature state enables
   reporting, at the requested interval.
5. On AirPods disconnect, audio-route change, service shutdown, or helper failure, write
   `UHID_DESTROY` and close the descriptor.

UHID events cannot be split across writes, and feature requests cannot be ignored: the
kernel blocks the HID driver while waiting for the reply. Open the UHID fd non-blocking and
make the app-to-helper pipe non-blocking with a drop-oldest policy: a stalled helper must
never stall the AAP reader or the audio path. `UHID_INPUT2.data[0]` is the report ID.

The UHID device appears as `/dev/hidraw*`. The dynamic-sensor HAL must be present and able to
open `hidraw_device` (SELinux). On many OEM builds it is; on others it is not. If a
`BUS_BLUETOOTH` UHID device is rejected, test `BUS_VIRTUAL`/`BUS_USB`; the UUID association is
what matters to `SpatializerHelper`, but the HAL must still enumerate the device. The HAL
enumerates `hidraw*` via inotify. `logcat -s DynamicSensorHal` shows the sensor being created
and its description/version parsed; `logcat -s AS.SpatializerHelper` and `dumpsys audio` show
whether the framework associated it with the current route and whether the per-device Head
tracking toggle is on. The HAL does not reject enumeration based on that toggle; the
framework does.

#### Host-driven gating

The helper sees the host's feature report 1. Its Reporting State (No Events / All Events) and
Power State (Full Power / Power Off) are the authoritative signal for whether the OS wants
pose. Use them to start/stop the AirPods `0x17` stream: when the host sets No Events or Power
Off, the app should stop streaming; when it sets All Events + Full Power + a non-zero
interval, the app should start streaming. This also satisfies the AOSP requirement that the
headset sends pose only while the user has Head tracking enabled, and it makes the battery
rule enforceable. The helper should forward the requested Report Interval to the app; request
the AirPods interval at or below the host interval (for example host 20 ms -> AirPods 20 ms;
host 10 ms -> AirPods 10 ms), and downsample in the helper if needed.

#### Root vs Shizuku (shell UID)

Full root is the simplest and most reliable route, but it is not the only one. AOSP gives the
`shell` domain access to `/dev/uhid`:

- `/dev/uhid` is `0660 uhid:uhid` in `system/core/rootdir/ueventd.rc`.
- `system/sepolicy/private/shell.te` contains
  `allow shell uhid_device:chr_file rw_file_perms;`.
- `packages/modules/adb/daemon/main.cpp` adds `AID_UHID` to the shell process's
  supplementary groups.
- AOSP's `hid` command (`frameworks/base/cmds/hid`) uses `/dev/uhid` from shell, supports
  `"bus": "bluetooth"`, input reports, feature reports, and GET/SET report replies.

Shizuku in ADB/wireless-debugging mode runs its server and UserServices as UID 2000
(`shell`), so a Shizuku UserService can host the UHID helper natively. This may avoid full
root. Caveats:

- Shizuku must be started via ADB/wireless debugging, not in root mode (root mode is root
  anyway). It must be restarted after reboot.
- The UserService must inherit the `uhid` group and run in `u:r:shell:s0`. This is expected
  from the Shizuku server started by `adbd`, but must be verified on the target OEM.
- The OEM may restrict shell access to `uhid_device`, remove `hid`, or ship a different
  `/dev/uhid` permission. Check `id`, `ls -l /dev/uhid`, `exec 3<>/dev/uhid` via `rish` or a
  Shizuku shell, and `logcat | grep avc`.
- The app cannot grant `MODIFY_DEFAULT_AUDIO_EFFECTS` through Shizuku (it is
  `signature|privileged`). The user must enable Spatial Audio / Head tracking in Bluetooth
  settings, or the app must be installed as the existing privileged system app.
- The dynamic-sensor HAL and OEM spatializer requirements are unchanged. Shizuku cannot add
  them.

A quick proof-of-concept can use the AOSP `hid` command through Shizuku/rish: register a
Bluetooth-bus HID device with the head-tracker descriptor, supply report 2 in
`feature_reports`, answer GET/SET requests, and send report 1. `hid` does **not** auto-reply
to `UHID_SET_REPORT`: the driver script must read the `eventId: 13` output and send
`{"id":<deviceId>,"command":"send_set_report_reply","success":true}` (and update its stored
report 1 with `set_get_report_response`). Drive `hid -` bidirectionally; a static input file
cannot answer
SET_REPORT, and the kernel/HAL will block waiting for the reply. If `hid` is absent, a custom
Shizuku UserService with native code is the robust route. If `/dev/uhid` cannot be opened by
shell on the target device, fall back to full root.

### 4.3 Pose conversion

The AirPods quaternion and gravity are in the AirPods sensor frame. Android's head frame is:

- X from left ear to right
- Y from back of head to nose
- Z from neck to top of head

The bridge needs a separately testable converter that:

- validates the sample discriminator and skips zero-attitude samples;
- normalizes the Q15 quaternion and picks a consistent sign. Accept a small tolerance
  (`x^2 + y^2 + z^2 <= 1.001` for validation; loosen only if captures show more noise) and
  clamp only the square-root argument; exact `<= 1` is too strict for Q15 rounding near a
  180-degree rotation;
- establishes the neutral reference from the first N still samples (for example 25 samples
  at 50 Hz = 0.5 s). The quaternion direction is not yet established: if `q` is
  head-to-world, the reference-to-head pose is `q_curr^-1 * q_ref`; if `q` is world-to-head,
  it is `q_curr * q_ref^-1`. Do not hard-code the multiplication order until a known
  yaw+pitch motion validates it. The FIocker decoder composes `neutral.Conjugate() * q`;
  that is one convention, not proof that it matches Android's reference-to-head field;
- canonicalizes the relative quaternion to `w >= 0` (negate all components if `w < 0`);
- converts it to Android's rotation-vector field with the stable form
  `n = sqrt(x^2 + y^2 + z^2); theta = 2 * atan2(n, w); r = v * (theta / n)`, returning a zero
  vector when `n < 1e-9`. This is `0` at identity, avoids the `0/0` in
  `(x,y,z)/sin(theta/2)`, and gives `theta in [0, pi]` after canonicalization. Encode each
  component as `i16 = round(r * 32767 / pi)`;
- finds the fixed axis permutation/sign that maps the AirPods world frame to Android's frame.
  Gravity can verify that the quaternion is a valid attitude, but it cannot detect an
  inverted yaw/pitch mapping; add a known combined yaw+pitch (and roll) motion test and check
  the direction the soundfield appears to move, not only that it "stays fixed";
- derives angular velocity. Prefer the raw rotation-rate triple if its scale/frame can be
  verified against finite differences of the quaternion; otherwise use finite differences
  `2 * vec(q_prev^-1 * q_curr) / dt`, with smoothing. Encode with
  `i16 = round(v * 32767 / 32)` and clamp to +/-32 rad/s;
- clamps rotation magnitude to pi and the angular velocity to the descriptor's range;
- increments the discontinuity counter whenever the neutral reference changes or the stream
  restarts.

Because Android's reference frame is arbitrary and auto-recenters after about 6 seconds of
stillness, absolute yaw alignment is not required. Drift is acceptable if small.

### 4.4 Application integration

Proposed components:

- `HeadTrackingParser` (Kotlin or native): protobuf-relative 0x17 parsing, batched samples,
  timestamps, validation, zero-attitude skip.
- `PoseConverter`: Q15 quaternion, neutral calibration, axis mapping, angular velocity,
  clamping, discontinuity.
- `UhidHeadTracker` helper: a small native executable launched with `su`, or hosted in a
  Shizuku UserService running as shell. The app sends fixed-size pose frames to it; the
  helper writes UHID input reports. The helper exits when the app/stream stops. For the
  root route the transport can be the helper's stdin; for the Shizuku UserService route it
  must be Binder/AIDL (or a local socket), not a child-process pipe. A Magisk-module daemon
  is a later hardening option if `su` lifetime proves unreliable.
  - UserService native-library caveat: a Shizuku UserService is `app_process` with the
    Shizuku manager APK on the classpath, so `System.loadLibrary` will not find the LibrePods
    APK's JNI libraries by default. Either extract the UHID helper `.so` to
    `/data/local/tmp` and `System.load` it by absolute path, or implement the UHID event
    loop in Java/`ByteBuffer` code. Verify this in the Shizuku spike before designing the
    transport.
  - Packaging caveat: with modern AGP the merged manifest normally sets
    `android:extractNativeLibs="false"`, so a `.so` in `nativeLibraryDir` may not exist as a
    file. The FOSS flavor must either set
    `packaging { jniLibs { useLegacyPackaging = true } }` /
    `android:extractNativeLibs="true"`, or ship the helper in the Magisk module and execute
    it from `/data/adb/...`. Verify on a built APK that `ApplicationInfo.nativeLibraryDir`
    actually contains the executable. Do not rely on copying it to app data and executing
    it; Android 10+ W^X rules may block that.
- `SystemSpatialAudioController`: preconditions, start/stop, route-change handling, status
  reporting, and user guidance.
- `HeadTrackingStreamArbiter`: reference-count the shared `0x17` stream so the system bridge,
  the in-app visualizer, and the gesture detector do not stop a stream still needed by
  another feature. The existing `isHeadTrackingActive` flag is not sufficient for this.
- The new parser should also feed the in-app visualizer and gesture detector. Retire the
  old 43/45/47 and 51/53 math rather than leaving two parsers that disagree once the
  sequence-varint length changes.

Start sequence (host-driven):

1. AirPods connected and routed over A2DP.
2. Start the privileged helper (root, or a Shizuku UserService) with the AirPods MAC. It
   creates the UHID device and waits for the host's feature report 1. The host discovers the
   sensor once the per-device Head tracking setting is enabled.
3. If ownership turns out to be required for the motion stream on the target device, take it
   here, before requesting streams. Do not force it by default; it is not proven necessary.
4. On the first feature report with Reporting State = All Events, Power State = Full Power
   and a non-zero interval, request stream 14, then stream 16 at that interval (or 20 ms,
   whichever is smaller).
5. Optionally stop stream 14 once stream 16 is flowing; stop it explicitly on teardown.
6. Wait for the first valid quaternion; calibrate neutral.
7. Check `Spatializer.isHeadTrackerAvailable()` and the `TYPE_HEAD_TRACKER` sensor; then feed
   pose frames at 50-100 Hz.

Stop sequence:

1. Stop feeding; send `UHID_DESTROY` (or close stdin and let the helper clean up).
2. Stop every stream this feature started (14 and 16), unless the arbiter says another
   feature still needs them.
3. Release ownership only if this feature took it and no other feature needs it.

Status/UX requirements:

- Show each failed precondition independently: no root, no `/dev/uhid`, no `CONFIG_UHID`,
  no dynamic-sensor HAL, no `TYPE_HEAD_TRACKER` sensor, no OEM spatializer, AirPods not
  routed over A2DP.
- Deep-link to the Bluetooth device settings to enable Spatial Audio / Head Tracking.
- Explain the stereo-content limitation from section 4.1 in the setting description.
- If the app is installed as the privileged system app (the existing `root-module-manual`),
  add `MODIFY_DEFAULT_AUDIO_EFFECTS` to `privapp-permissions-librepods.xml`. It is
  `signature|privileged`, so the existing priv-app install can hold it. The useful methods
  (`isAvailableForDevice()`, `setEnabled()`, head-tracker controls) are
  `@SystemApi(client = PRIVILEGED_APPS)`, so they are not in the public SDK; access them by
  reflection and add `android.media.Spatializer`/`AudioManager` to the existing hidden-API
  exemption. Platform signing is not required. Keep this optional and FOSS-only.
- Privacy: the persistent UUID publishes the AirPods BR/EDR MAC as `Sensor.getUuid()`, which
  any app can read through `getDynamicSensorList(TYPE_HEAD_TRACKER)` without permission. This
  is inherent to the AOSP scheme. The all-zero standalone UUID is an alternative, but it
  matches every route and bypasses per-device association. Do not log raw Bluetooth
  addresses in bug reports.
- Log the first sample, sample rate, quaternion norm, gravity magnitude, and sensor
  timestamps for diagnostics.

### 4.5 Preconditions checklist

All of these are required for the system route:

- Android 13 or newer (the app already has `minSdk 33`).
- Root, or Shizuku in ADB/shell mode, that can open `/dev/uhid` and keep a helper process
  alive. AOSP's `shell` domain has `uhid_device` access and adbd adds `AID_UHID`, but OEM
  policy may deny it; test before committing to the Shizuku route.
- `CONFIG_UHID=y` (the `/dev/uhid` node exists) and `CONFIG_HIDRAW=y`.
- `sensors.dynamic_sensor_hal.so` enabled in the OEM sensor HAL configuration, with
  `hidraw_device` SELinux access.
- An OEM spatializer effect that advertises head-tracking support for A2DP.
- A valid AirPods identity/BR-EDR MAC and an active A2DP route.
- AirPods firmware that streams service 16 (or a discoverable equivalent) with the 58-byte
  sample.

If any item is missing, creating a UHID device will not add head-tracking support to the
OEM audio effect.

### 4.6 Why not Xposed or an app-level renderer?

- An Xposed/LSPosed hook could inject pose into `SpatializerPoseController` or
  `SensorPoseProvider`, but it requires root anyway, is tied to framework internals, and is
  more fragile than the documented UHID/sensor path.
- An app-owned HRTF renderer (Oboe/AAudio + Resonance Audio or libspatialaudio) can only
  spatialize audio the app itself plays. It cannot rotate system-wide or other apps' audio.
  LibrePods' README already excludes app-owned stereo spatialization from scope. It is a
  useful no-root fallback only if the user accepts app-scoped audio.

## 5. Linux and Windows

### 5.1 Linux

Linux has no OS-level head-tracker API. There is no equivalent of Android's
`TYPE_HEAD_TRACKER`/`Spatializer` contract that LibrePods can feed.

The realistic Linux route is user-space:

- PipeWire 1.6 has a built-in `sofa` spatializer filter and can create a virtual sink for
  system-wide audio.
- A daemon can read AirPods opcode `0x17`, compute pose, and either:
  - publish pose over OpenTrack UDP (six doubles) for apps/games that support OpenTrack, or
  - drive a PipeWire spatializer/filter that rotates the virtual speaker layout or an
    ambisonic scene.
- This is not an OS feature; it depends on PipeWire configuration, the HRTF/SOFA dataset, and
  per-desktop integration. It is feasible but a separate project with its own latency and
  routing work.

If the target is Linux and the user only needs OpenTrack-style tracking, this is a small
addition. If the target is "system-wide spatial audio like Android", it is a larger
user-space DSP project, not a protocol change.

### 5.2 Windows

Windows also has no public API for third-party head pose into the system spatial audio
pipeline. Windows Sonic/Dolby Atmos for Headphones are not open to arbitrary head trackers.
AirPods AAP also runs over Bluetooth Classic L2CAP, which normal Windows user-mode code
cannot open; the existing Windows experiments use the third-party MagicAAP kernel driver and
send pose to OpenTrack. LibrePods does not target Windows in `main`. This is out of scope.

## 6. Validation plan

### 6.1 AirPods 4 ANC protocol validation (device required)

- Capture raw AAP traffic from AirPods 4 ANC at 25, 50 and 100 Hz. Use the app's existing
  packet log (`logPacket`/packet-logs flow in `AirPodsService.kt`), a rooted capture on the
  phone, or a Linux host with `mutix`/`btmon`. Note that the current `logPacket` call is
  guarded by `if (!isHeadTrackingData(data))`, so single-sample `0x17` packets are omitted;
  relax that filter or use the rooted/Linux capture path unconditionally. Record the
  advertised RTBuddy service IDs before assuming 14/16.
- Confirm:
  - which stream/service starts motion (14/16, or a device-specific service such as the
    Max 2 `devmotion6` service 6);
  - packet framing and sequence-varint shifts;
  - 58-byte sample presence, `payload[0] == 1`, batch size (up to five);
  - quaternion norm `x^2 + y^2 + z^2 <= 1.001` (Q15 rounding tolerance; loosen only if
    captures show more noise);
  - gravity magnitude approximately 1024 per g and direction consistent with the
    quaternion;
  - rotation rate is non-zero during motion and near zero at rest;
  - timestamp monotonicity and sample interval;
  - zero attitude after start and resumption after bud removal.
- Record known motions (nod, shake, tilt left/right, turn left/right, combined yaw+pitch,
  and roll) and verify the axis mapping and sign. Gravity alone cannot catch an inverted
  yaw/pitch mapping.

### 6.2 Unit tests

- Protobuf/frame parser: 80-byte and 81-byte packets (including the 42/44/46 vs 43/45/47
  offset shift), a 5-sample batch, truncated packets, zero-attitude packets, sequence
  varint crossing 127, mixed service-14 status and service-16 samples in one packet, and a
  45-byte stream-14 reply.
- Quaternion math: normalization, sign alignment, relative orientation, finite-difference
  angular velocity, rotation magnitude clamp to pi, angular-velocity clamp to +/-32 rad/s.
- Rotation-vector conversion and direction: known yaw/pitch/roll test vectors, plus an
  inverted-mapping test that must fail.
- HID input report golden bytes: rotation-vector and angular-velocity scaling, discontinuity
  wraparound, report ID.
- UUID byte order: `uuidFromMac("A4:C3:F0:12:34:56") ==
  UUID.fromString("00000000-0000-0000-4254-a4c3f0123456")`.
- Feature report decoding: reporting state, power state, interval 0..63 -> 10..100 ms.
- Host gating: No Events / Power Off / interval 0 -> no input reports and the app stops the
  AirPods stream.
- Both A2DP and LE-Audio matching paths across Android 13-15. The `dsa_over_bt_le_audio`
  flag is present in some Android 15-era `SpatializerHelper` builds and may be
  graduated/always-on on newer releases; cover both paths on real devices rather than
  relying on the flag name.
- Axis mapping: fixed test vectors through the converter.
- Arbitration: visualizer/gesture/system bridge reference counting; one consumer stopping
  does not stop a stream another consumer needs.
- A second AAP client stopping/restarting the shared stream does not corrupt pose or leave
  stale sensor reports.

### 6.3 UHID host test

- Start the helper with a synthetic pose stream.
- Read the HID descriptor and feature reports from `/dev/hidraw*`.
- Verify `dumpsys sensorservice` shows `android.sensor.head_tracker(37)` and
  `dumpsys audio` shows the head sensor attached to the AirPods route.
- Verify `SensorEvent` values and direction through `SensorManager`, not merely that the
  sensor enumerates.
- Verify input reports stop when reporting state is No Events or power is Off.
- Verify the maximum gap between reports is <= 40 ms (AOSP guidance) and the sample age is
  below the 120 ms stale threshold.
- Verify `UHID_DESTROY` removes the sensor and is idempotent.
- Test the `BUS_BLUETOOTH` identity and, if needed, `BUS_VIRTUAL` fallback.
- If using the AOSP `hid` command as a Shizuku/shell PoC, drive `hid -` interactively:
  provide report 2 in `feature_reports`, answer every SET_REPORT with
  `send_set_report_reply` (and update report 1 with `set_get_report_response`), and send
  report 1 input reports. A static input file cannot answer SET_REPORT.
- Test helper crash/app death: the sensor disappears, and the app can restart it cleanly.

### 6.4 Android integration test

- With a rooted target phone and AirPods 4 ANC:
  - `Spatializer.isHeadTrackerAvailable()` becomes true after the helper starts, with
    spatial audio/head tracking enabled and the AirPods as the active route;
  - `dumpsys audio` shows the head sensor; `logcat -s DynamicSensorHal` shows the HID
    sensor being created, and `logcat -s AS.SpatializerHelper` shows route association and
    the per-device Head tracking toggle state;
  - enabling/disabling Spatial Audio in Bluetooth settings changes the state;
  - route change away from AirPods tears down the sensor and a return recreates it;
  - no sensor is left behind after service stop or AirPods disconnect;
  - multipoint/call transitions do not leave a stale stream or sensor.
- Content check: play a 5.1/Atmos movie and stereo music separately. Confirm which content
  the OEM effect actually spatializes; do not claim stereo support without evidence.
- Listening test with spatial audio enabled: verify the soundfield stays fixed in space as
  the head turns and that left/right turns move the scene in the correct direction (not the
  inverse); check drift and measure end-to-end latency against the 150 ms AOSP target.
- Test with the screen off and with the app backgrounded (foreground service).
- Check battery impact over a 30-minute session.

### 6.5 Device validation log

- **2026-11-03 - Pixel 9 Pro (`caiman`), Android 17 / SDK 37, user build, `ro.debuggable=0`, no root.**
  - `adb shell id`: `uid=2000(shell)`, groups include `3011(uhid)`; `/dev/uhid` is
    `0660 uhid:uhid`; opening it from shell succeeds.
  - The AOSP `/system/bin/hid` command was used with a hand-built HID v1.0 head-tracker
    descriptor: feature report 2 = 23-byte `#AndroidHeadTracker#1.0` + 16-byte UUID;
    feature report 1 = reporting state / power state / report interval; input report 1 =
    3 x i16 rotation vector + 3 x i16 angular velocity + u8 discontinuity. Feature report
    data includes the report ID.
  - `DynamicSensorHal` logs `detectAndroidHeadTrackerSensor: #AndroidHeadTracker#1.0` and
    `return 1 sensors`.
  - `dumpsys sensorservice` shows:
    `LibrePods Head Tracker | Bluetooth 004c:004c | ver: 16777216 | type:
    android.sensor.head_tracker(37)`.
  - `dumpsys audio` reports `mHasSpatializerEffect:true`,
    `mIsHeadTrackingSupported:true`, supported mode
    `HEAD_TRACKING_MODE_RELATIVE_WORLD`, and `supports binaural:true`.
    `headtracker available:false` is expected here: the active route was the speaker and
    the test sensor used the standalone all-zero UUID.
  - The faux sensor was cleaned up; `dumpsys sensorservice` no longer shows it.
  - Pending: AirPods 4 ANC A2DP route + real-MAC UUID -> `headtracker available:true`;
    Shizuku UserService route; real AAP `0x17` stream; actual spatial rendering.
  - **AirPods 4 ANC route association:** with the AirPods connected over A2DP
    (`44:A7:F4:08:74:0F`) and Spatial Audio + Head tracking enabled in the Bluetooth
    device settings, the UHID sensor with the real-MAC UUID is associated:
    `dumpsys audio` shows `HT: true HTenabled: true`, `headtracker available:true`,
    `isSpatializerEnabled:true`, `mSpatLevel:1`; `dumpsys media.audio_policy` shows
    `HeadSensorHandle: 0x...`.
  - **OS-side blocker resolved with a test helper.** The AOSP `hid` command does not
    auto-reply to `UHID_SET_REPORT`, so the dynamic-sensor HAL's `enable()` blocked while
    setting report 1. `tools/uhid-headtracker/` now implements the complete feature-report
    state machine in Java and runs as shell UID 2000 via `app_process`.
  - **Verified with the helper (2026-11-03):** `dumpsys media.audio_policy` shows
    `HeadSensor: 0x...`, `ActualMode: WORLD_RELATIVE`, and `calculated 51 samples in
    1.0000 second(s)`; `dumpsys audio` shows `mActualHeadTrackingMode:
    HEAD_TRACKING_MODE_RELATIVE_WORLD`, `HT: true HTenabled: true`, and
    `headtracker available:true`. No `avc` denials for `uhid_device`/`hidraw_device`.
    Cleanup removes the sensor and `/data/local/tmp` artifacts.
  - **Activation gate:** a silent 5.1 `AudioTrack` probe is required to make AOSP activate
    the sensor; with no immersive track, `HeadSensor` stays `INVALID`. This confirms the
    stereo-content limitation in practice.
  - **Real AirPods 4 ANC `0x17` stream captured (2026-11-03).** With the AirPods in the
    user's ears, the app sent stream 14 (`0x0E`) and then stream 16 (`0x10`) 200 ms later;
    the accessory streamed full samples at ~25 Hz. Each motion sample is the 58-byte blob at
    `3A 3E 08 <service> 1A 3A`; the sample-carrying `cmd` observed in the capture used
    service 14, while service 16 also carried status sub-messages. Layout confirmed on
    AirPods 4: `01` version, u64 timestamp at 1, qxyz Q15 at 20/22/24, rotation rate at
    26/28/30, user acceleration at 38/40/42, gravity at 44/46/48 (|g| ~1020), counter u32 at
    54 in 5 ms ticks. The first samples carry a zero attitude and are skipped; a 99-byte
    packet can contain both status and a sample.
  - **Parser/pose conversion implemented in the app.** `HeadTrackingParser` walks the
    protobuf, returns every sample in a batched packet, reconstructs positive `w`, and
    rejects zero-attitude warm-up plus corrupt attitudes. `HeadTracking` captures a 25-sample
    neutral orientation, emits a relative rotation vector and angular velocity through
    `HeadTracking.pose`, and keeps the legacy pitch/yaw visualizer fields. Unit tests cover
    the real capture, batching, framing, near-identity/180-degree attitudes, malformed
    truncation, rotation-vector/angular-velocity math, calibration and recenter.
  - **Shizuku UserService bridge implemented (2026-11-03).** Shizuku 13.6.0 was installed
    and started in ADB mode. The app binds `HeadTrackerUserService` as shell UID 2000 through
    `Shizuku.bindUserService`; the service creates the same UHID head tracker, answers
    feature reports, and receives `HeadTracking.pose` over Binder. The app writes the UHID
    input report at 50 Hz from the latest 25 Hz AirPods sample and respects the host's
    Reporting State / Power State, the 250 ms stale-pose window, and the ±32 rad/s range.
  - **End-to-end verified on the Pixel with the real AirPods:** with the `System spatial
    audio (Shizuku)` setting enabled and a silent 5.1 `AudioTrack` probe, the service logged
    `SET_REPORT state=0x03 reporting=true power=true`; `SpatializerPoseController` showed
    `HeadSensor: 0x...` active, `ActualMode: WORLD_RELATIVE`, `calculated 51 samples in
    1.0000 second(s)`; `dumpsys audio` showed `mActualHeadTrackingMode:
    HEAD_TRACKING_MODE_RELATIVE_WORLD`, `HT: true HTenabled: true`, and
    `headtracker available:true`.
  - **Lifecycle:** while the setting is enabled and the AirPods are connected, the app keeps
    the `0x17` streams and the UHID device alive after the Head Tracking screen closes and
    after the post-connect gesture window; turning the setting off destroys the sensor.
    Verified on device: the sensor survived screen close and was recreated after off/on.
    This is still driven by the setting rather than a full reference-counted stream arbiter.
  - **Android rotation-vector axes:** the synthetic helper with `--axis x` moves the pose
    controller's `d_pitch`, `--axis y` moves `d_roll`, and `--axis z` moves `d_yaw` (43-44
    samples/s). The app's `HeadTracking.pose` is a world-frame quaternion delta (`q *
    neutral^-1`), so a nod/shake should map directly to X/Z; the remaining sign check needs
    real head motion.
  - **Still pending:** sign/axis confirmation with a real nod/shake; audible HRTF/effect
    output; host-driven gating of the AirPods `0x17` stream (the app currently streams while
    the setting is on even if the OS has not activated the sensor); minified-release
    on-device binding; Shizuku restart recovery; Oppo Find X9 and Logitech G Cloud
    validation.

### 6.6 CI

- `app/src/test` now exists with JUnit 4 tests for the head-tracking parser and pose math;
  `ci-android.yml` runs `./gradlew testFossDebugUnitTest` before the APK build.
- The native helper's UHID logic can be unit-tested on Linux with a fake `/dev/uhid` or a
  small harness; device tests remain manual.

## 7. Effort estimate

Assuming an experienced Android/native developer with access to a rooted target phone and
AirPods 4 ANC:

| Phase | Estimate |
| --- | --- |
| AirPods 4 protocol capture and axis verification | 1-3 days |
| Android unit-test harness (currently absent) | 1-2 days |
| Robust 0x17 parser + pose converter + unit tests | 2-4 days |
| UHID helper (native executable, report descriptor, feature/input lifecycle) | 3-5 days |
| Packaging/exec spike (`extractNativeLibs` or Magisk module) | 0.5-1 day |
| App integration, preconditions, UX, lifecycle | 2-3 days |
| Device validation, latency tuning, OEM phone testing | 3-5 days |
| **Total** | **12.5-23 working days (~2.5-4.5 weeks)** |

A proof-of-concept that only proves the OS path (dummy UHID pose -> `TYPE_HEAD_TRACKER` ->
spatializer) is 1-3 days. A proof-of-concept that only proves the AirPods 4 stream and
quaternion is 1-2 days with a device.

Linux user-space spatializer (PipeWire + SOFA + pose bridge) is a separate 1-2 week effort
for a basic system-wide sink; production quality is longer. Windows is out of scope.

## 8. Risks and open questions

### Risks

1. **AirPods 4 ANC stream format is unverified.** The model likely uses the same service
   14/16 stream, but its firmware may use a different service ID, sample layout, or sensor
   set. AirPods Max 2 already uses a dynamically discovered service 6. Gate the design on a
   capture and log the advertised services.
2. **Stereo content may not be spatialized.** AOSP tells OEMs not to spatialize stereo
   content through the system effect. If the target phone has no stereo upmix/spatialization
   toggle, head tracking will only affect multichannel/Atmos content, not ordinary music.
   This may invalidate the feature for the user's main use case.
3. **OEM spatializer support is the hard blocker.** Privileged `/dev/uhid` access + UHID
   only works if the phone's dynamic-sensor HAL and spatializer effect support head tracking.
   Many phones will not. There is no generic software fallback for system-wide audio.
4. **A2DP latency.** AOSP's head-tracking latency target is < 150 ms. In the LibrePods route
   the AirPods are on A2DP (AAC/SBC), not low-latency LE Audio/Opus. The spatializer predicts
   120 ms by default, but AirPods latency may still exceed the budget. This must be measured;
   root can tune `audio.spatializer.prediction_duration_ms`, but that is device-specific,
   global rather than per-device, read once when `SpatializerPoseController` is constructed,
   and needs an audioserver restart.
5. **AirPods 4 axis/scale unknowns.** The Q15 quaternion decoding is validated on AirPods Max
   2, not AirPods 4. Rotation-rate units and axes are not verified on any model. The
   converter must be validated with gravity and known motions, including direction.
6. **Privileged helper lifecycle, packaging and SELinux.** `/dev/uhid`/`CONFIG_UHID`
   availability, `CONFIG_HIDRAW`, the dynamic-sensor HAL, SELinux `hidraw_device` access,
   executing a helper from `nativeLibraryDir` (which may not contain an extracted file), and
   keeping it alive across app/service restarts all need device testing. A Magisk-module
   daemon may be needed for reliability. If Shizuku is the chosen route, its UserService must
   inherit shell UID, `u:r:shell:s0`, and the `AID_UHID` group; OEM SELinux may deny shell
   access to `/dev/uhid`, in which case full root is required.
7. **Ownership/multipoint conflicts.** Taking ownership and streaming continuously may
   conflict with multipoint, calls, or other devices. The bridge must stop cleanly and
   restore state. Do not force takeover until it is proven necessary.
8. **Battery and thermals.** Continuous 50-100 Hz sensor streaming plus a privileged helper
   has a cost. Only run when the host requests pose (reporting state All Events) and spatial
   audio is enabled.
9. **Privacy.** The persistent UUID exposes the AirPods BR/EDR MAC to any app that can read
   `Sensor.getUuid()`. This is inherent to the AOSP association scheme; document it.
10. **Small audience.** If root/shell access + OEM support + stereo-upmix support are all
    required, the set of compatible phones may be very small. Validate the target device
    before investing in the full implementation.
11. **Play Store policy.** The system route requires privileged access (root or Shizuku shell
    mode) and should remain in the FOSS build, consistent with the existing root module.

### Open questions for the user

- Which OS/device is the target: Android (which phone, OEM, Android version), Linux, or
  Windows?
- Is root acceptable? Is a Magisk module acceptable, or is Shizuku in ADB/shell mode enough?
  (Shizuku may avoid root if the target OEM allows shell access to `/dev/uhid`.)
- Is system-wide audio required, or is app-scoped spatial audio acceptable?
- What is the AirPods 4 ANC model/firmware, and is the phone's OEM spatializer known to
  support head tracking with third-party devices?
- Is the goal music/movies, games, or both? This changes the latency budget.
- Should this build on the draft `docs/android-head-tracker-hid.md` in PR #722, or replace it?

## 9. Recommendation

- **If the target is Android and root or Shizuku shell access is acceptable:** run the gate
  spikes first: (a) a dummy UHID sensor on the target phone becomes `TYPE_HEAD_TRACKER` and
  `isHeadTrackerAvailable()` becomes true; (b) the OEM effect spatializes 5.1/Atmos content
  and, if needed, stereo; (c) AirPods 4 ANC produces a valid Q15 quaternion at >= 50 Hz with
  verified axes; (d) decide root vs Shizuku by testing whether the chosen helper can open
  `/dev/uhid` on the target OEM build. Only then implement the parser/converter and UHID
  helper in the FOSS build, behind a clearly labelled "System head tracking (privileged,
  experimental)" setting.
- **If root is unacceptable but Shizuku is acceptable:** first run the shell-UID UHID smoke
  test. If it passes, implement the helper as a Shizuku UserService; if the OEM denies
  `uhid_device` to shell, the route requires full root.
- **If stereo music is the main goal and the target OEM does not spatialize stereo:** the
  Android system route cannot deliver it without an OEM upmix toggle. Consider the Linux
  PipeWire route or an app-scoped renderer instead; do not over-promise.
- **If neither root nor shell-UID `/dev/uhid` access is available, or OEM support is
  unavailable:** do not promise system-wide spatial audio. The only fallback is app-scoped
  HRTF, which the project has already excluded, or a per-app OpenTrack-style bridge on
  Linux/Windows.
- **If the target is Linux:** treat this as a PipeWire/OpenTrack userspace feature, not an
  OS integration, and scope it separately.

## 10. References

- LibrePods `docs/AAP Definitions.md`, Head Tracking section (lines 378-405).
- LibrePods PR #722, `docs/android-head-tracker-hid.md` (draft): Android head-tracker bridge
  contract (rooted route).
- LibrePods issue #719: head-tracked spatial audio, no-root and root routes.
- LibrePods PR #887 (closed): measured 0x17 request format, streams, sample rate and 58-byte
  layout; docs were closed because the repo docs are not maintained, but the measurements
  match `mutix`.
- `Gheop/mutix` `src/aap.rs`, `src/frame.rs`: request builder, protobuf parsing, 58-byte
  sample layout and batch tests.
- `FIocker/aap-head-tracker` `Program.cs`: Q15 quaternion reconstruction, neutral
  calibration, OpenTrack output. Its `README.md` describes the gravity-rotation validation
  on AirPods Max 2.
- AOSP `source.android.com/docs/core/interaction/sensors/head-tracker-hid-protocol`:
  HID v1.0/2.0 contract, report descriptor, UUID association, reporting rules.
- AOSP `frameworks/base/services/core/java/com/android/server/audio/SpatializerHelper.java`:
  `TYPE_HEAD_TRACKER`, UUID matching, `isHeadTrackerAvailable()`, and the A2DP/LE-Audio
  matching branch (the `dsa_over_bt_le_audio` flag is present in some Android 15-era builds
  and may be graduated/always-on on newer releases).
- AOSP `frameworks/base/services/core/java/com/android/server/audio/UuidUtils.java`:
  Bluetooth UUID construction from the identity address in display order.
- AOSP `frameworks/av/services/audiopolicy/service/SpatializerPoseController.cpp`:
  freshness timeout, prediction duration, pose/twist handling, auto-recenter.
- AOSP `hardware/libhardware/modules/sensors/dynamic_sensor/README.md`: dynamic-sensor HAL
  requirements, `hidraw`, SELinux.
- AOSP `hardware/libhardware/modules/sensors/dynamic_sensor/HidRawSensor.cpp`: head-tracker
  detection, `useUniqueIdForUuid`, report parsing.
- AOSP `system/core/rootdir/ueventd.rc`, `system/sepolicy/private/shell.te`, and
  `packages/modules/adb/daemon/main.cpp`: `/dev/uhid` permissions and shell-domain
  `uhid_device`/`AID_UHID` access.
- AOSP `frameworks/base/cmds/hid`: shell `hid` command, Bluetooth-bus UHID registration,
  input/feature reports and GET/SET report replies. Useful for a Shizuku/shell proof of
  concept if the command exists on the target build.
- AOSP `source.android.com/docs/core/audio/implement-spatial-audio`: A2DP/LE Audio,
  150 ms latency target, 20 ms report period, 40 ms maximum gap, and the stereo-content
  restriction.
- LibrePods PR #617 (open, against `linux/rust`): Rust head-tracking scaffold, gesture
  detector, AirPods Max ProtectedAccess gating.
- `w0nderful666/wf1000xm5-spatial-audio-android`: practical root/hidraw head-tracker bridge
  for a different headset; useful prior art for lifecycle and failure modes.
- `NicholasSlattery/sony-head-tracker`: Android Head Tracker HID discovery and OpenTrack
  output; confirms the protocol is usable outside the headset vendor.
- PipeWire 1.6 `sofa` spatializer filter and `qeqqe/spatial-track`: Linux user-space
  alternative.
- AOSP `Sensor.java`: `TYPE_HEAD_TRACKER = 37`; `SpatializerPoseController` 120 ms
  freshness/prediction and 6 s auto-recenter.
- AirPods 4/4 ANC tech specs (Apple Support 121203/121204): dynamic head tracking.
