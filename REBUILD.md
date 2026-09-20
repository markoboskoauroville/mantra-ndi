# Mantra NDI — rebuild from scratch

This file is the brief for starting the app again. The old code is in git
history if anything is ever wanted from it; nothing in it should be trusted or
copied without rereading it, because a lot of it is half-finished and the
half-finished parts look finished.

---

## 1. What the app is

An Android broadcast camera for Baba's own phones over Wi-Fi. Not for a store.

Four jobs, in this order of importance:

1. **Local camera** — 10-bit HDR, manual controls, log curves
2. **Record** — to the phone, at a chosen format
3. **Stream** — NDI out over Wi-Fi
4. **Remote and monitor** — drive another phone's camera, or watch one

Package `com.mantraproductions.ndi`. Repo `markoboskoauroville/mantra-ndi`
(public).

## 2. The NDI SDK

The **NDI Advanced SDK** lives in a separate private repo,
`markoboskoauroville/mantra-ndi-sdk`. The GitHub Actions workflow pulls it in
at build time using a token stored in repo secrets.

**It must never be committed to the public repo.** Its licence forbids
redistribution. The workflow has a gate that fails the build if the runtime or
the JNI bridge is missing from the APK, and a `.gitignore` that keeps the SDK
out; keep both.

The SDK is a C++ API. The app talks to it through a small JNI bridge
(`ndi_bridge.cpp` for sending, `ndi_recv_bridge.cpp` for receiving). Those two
files in the old code are the one part worth reading: the NDI side of them
worked.

## 3. How building works

There is no local Android SDK. **Every build is GitHub Actions.**

1. Edit, commit, push
2. The workflow builds, runs the gates, signs, and publishes a release
3. About four and a half minutes later the APK is at
   `https://github.com/markoboskoauroville/mantra-ndi/releases/download/vN/N-mantra-ndi-vN.apk`

`appVersion` in `gradle.properties` must be **one higher than the last
released version**, or the build is refused. Read the releases API first; do
not assume.

Signing key fingerprint, which must never change or every installed copy has to
be uninstalled by hand:
`53f963193bdffa9e7ca0ab56af8e9e83da758928e6e4557abb31d139cf88a5f1`

## 4. Logging is phase zero

Build this **before the camera**, not after. The old app went ten versions
without it and every one of those versions was a guess.

- A trace file written **as things happen**, not buffered, to
  `context.getExternalFilesDir(null)`. No permission, cannot be refused,
  reachable in any file manager under
  `Android/data/com.mantraproductions.ndi/files/`
- A line for every step of startup, every control change with its values, and
  every camera request that is refused, with the reason
- Crash reports to **Downloads via MediaStore**. A raw `File` path into DCIM or
  Downloads is silently refused on Android 10 and later; the old app's crash
  reporter never wrote a single file because of this
- Catch `Throwable`, not `Exception`. A rejected shader arrives as an `Error`

An on-screen state panel would be better still: whether the camera is open,
which controls resolved, the last refused request. Getting the state out of the
phone is the whole difficulty of this project.

## 5. Phases

**Do not start a phase until the one before it is confirmed working on the
phone.** Each phase is its own release, tested before the next begins.

### Phase 1 — 10-bit HDR camera, and nothing else
A preview that is the right way up, the right shape, and does not freeze.
Manual ISO, shutter, white balance, focus, zoom, all working and all verifiable.

Done when: the picture is upright in landscape, survives backgrounding and
returning, survives rotation, and every fader visibly changes the image.

### Phase 2 — Recording
Format chosen from what the phone reports, not from a hard-coded list.
Storage and dropped-frame telemetry on screen.

Done when: a file is recorded, plays elsewhere, and the format matches what was
chosen.

### Phase 3 — NDI streaming
Send from the phone, receive in vMix or Studio Monitor.

Done when: another machine sees the source and shows the picture, and the
bandwidth is measured rather than guessed.

### Phase 4 — Remote control
One phone drives another's camera over NDI metadata.

### Phase 5 — Monitor
Watch an NDI source. No camera controls in this mode.

## 6. What the old build got wrong

Every one of these cost at least a day. They are facts about this phone and
this API, not opinions.

**One camera path only.** The old app had two — RootEncoder for 8-bit and
Camera2 direct for 10-bit — and nearly every bug came from that. Controls were
wired to one. Orientation was handled by one. Preview sizing by one. Use Camera2
directly, always; 8-bit is the same pipeline without the HDR profile.

**`supportsManualSensor()` reports false on the Pixel 7** and the sensor takes
manual requests anyway. Do not gate controls on capability flags. Send the
request and let the camera refuse it.

**A refused repeating request stops the camera.** It does not fail politely: the
preview freezes and stays frozen. Clamp every value to the reported range, and
keep the last accepted request so it can be restored when one is refused.

**White balance is two halves.** Gains *and* a colour correction matrix. The
matrix is calibrated per sensor and per illuminant and cannot be computed. Send
gains with somebody else's matrix and the picture goes green with no way back.
Use `CONTROL_AWB_MODE` presets, which move both halves together.

**`COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` requires the transform.** Setting the
mode and supplying only gains is a contract violation and stops the camera.

**Camera2 delivers frames at the sensor's mounting angle.** Nothing corrects it
for a TextureView. This was never solved in the old app across six attempts.
Solve it in phase 1 and test it by rotating the phone. Give the operator a
manual override from the first version, because the automatic answer has been
wrong repeatedly and a sideways picture is not something to debug around.

**The session's surface dies with the TextureView.** Release the pipeline when
the surface goes and rebuild against the new one, or the picture comes back
frozen.

**The camera session is lost while backgrounded** even with a foreground
service. Rebuild it on resume rather than trying to detect whether it survived.

**`BluetoothDevice.getName()` needs `BLUETOOTH_CONNECT`** and throws from the
scan callback on the main thread, closing the app. Use the advertisement's own
name.

**Nothing expensive on the camera callback thread.** A search that ran a few
thousand logarithms per second there was the app's sluggishness.

**`getHighSpeedVideoFpsRangesFor` is the slow-motion path** and returns nothing
for normal sizes. Frame rate ceilings come from `getOutputMinFrameDuration`.

**`MediaMuxer` writes no QuickTime.** MP4, WebM, 3GPP, Ogg, HEIF. A MOV option
can only ever be an MP4 with the wrong extension.

**Never ship a setting that does nothing.** Two did: a lens picker whose choice
the pipeline ignored, and that MOV option. Wire it or leave it out.

## 7. Things worth keeping from the old code

These were built, tested and are correct. Reuse the logic, rewrite the wiring.

- `Mechanism.kt` — pure maths with no Android imports, so all of it is
  unit-tested: shutter and ISO mapping, drop-frame timecode, waveform,
  sharpness, rack easing
- `CubeLut.kt` and `ColourSpaces.kt` — 33³ `.cube` parsing and generation,
  tetrahedral interpolation, gamut matrices derived from published primaries.
  The log-to-709 correction needs the gamut matrix, not just a curve; a 1D
  curve alone turns saturated colour into a cartoon
- `LtcEncoder.kt` / `LtcDecoder.kt` — SMPTE timecode as audio, round-trip
  tested. Simulate codecs in Python before spending a build on them; that
  found two real bugs here
- `BandwidthTest.kt` — real TCP throughput between two phones
- The JNI bridges and the CI gates

## 8. Working rules

- **Verify every edit landed.** Several builds failed because an edit reported
  success and had half applied. `grep` for what was added before committing
- **One phase, one release, tested on the phone.** No building forward on
  unconfirmed work
- **Ask the app, not the code.** If something does not work, the trace file
  should already say why. If it does not, that is the first thing to fix
