# Mantra NDI — handover

Read [REBUILD.md](REBUILD.md) first. This file is what the current session
decided and what is not tested.

## State

**v66. Phase 0, logging. No camera in this build.**

The previous build ended at v65 and is in git history. It was not extended;
it was taken apart, because the half-finished parts of it looked finished.
63 Kotlin files were deleted.

## What was kept, and why

| Kept | Why |
|---|---|
| `Mechanism.kt` | Pure maths, 74 cases, no Android imports |
| `CubeLut.kt`, `ColourSpaces.kt`, `LogCurves.kt`, `Histogram.kt` | The 33³ LUT work and the colour maths. 56 cases between them |
| `CameraCommand.kt` | Holds `CameraState`, which `MechanismTest` covers. Pure. Phase 4's wire format |
| `ndi_bridge.cpp`, `ndi_recv_bridge.cpp` | The NDI side of these worked. Also what keeps the artefact gate satisfied |
| The CI gates | Extended, not replaced |

Dropped to git history, to come back with the phase that uses them:
`LtcEncoder.kt`, `LtcDecoder.kt`, `LtcEngine.kt`, `Timecode.kt` (phase 4),
`BandwidthTest.kt` and `RecordingHealth.kt` (phases 3 and 2). `KeyRing*` went
entirely; nothing in the rebuild needs it.

## Decisions this session

**`MechanismTest` did not compile as inherited.** It reached into
`BandwidthTest` and `RecordingHealth`, which are phase 2 and 3 code, so
"the mechanism, alone" was not alone. Twelve test functions were cut and
travel back with their code.

**RootEncoder is gone**, with JitPack and ConstraintLayout. REBUILD.md §6:
one camera path only, Camera2 directly. Phase 1 starts from nothing rather
than from a library whose Camera2 handling has to be fought.

**The insets are set up in phase 0 rather than phase 1.** `picture` is the
full-bleed layer the preview will fill; `content` is the layer that takes
`systemBars() or displayCutout()`. The values are written to the trace. The
last build put the faders under the bars because half of this was done.

**Every camera's `SENSOR_ORIENTATION` and `MANUAL_SENSOR` flag is traced at
startup.** No permission, nothing opened. The number phase 1 has to get right
is in the file from the first version rather than being guessed at six times.

**`appVersion` was 65 and v65 was already released.** Check the releases API,
always; the build refuses a number that is already out.

## The gates

G1–G13 as before. Three new ones over the logging, each broken on purpose and
watched to go red before being trusted:

- **G14** `TraceFormat.kt` imports no Android, so the clock and the columns
  are testable on a desk
- **G15** the trace is unbuffered — no `BufferedWriter`, no
  `BufferedOutputStream` in `Trace.kt`
- **G16** `Trace.kt`, `CrashLog.kt` and `Downloads.kt` catch `Throwable` and
  narrow it nowhere
- **G17** `Downloads.kt` reaches the public folder through MediaStore

**G15 fired on its first run against correct code**, because `Trace.kt`'s
comment explains why it does *not* use a `BufferedWriter` and the gate matched
the explanation. `verify.py` now strips comments before any check that reads
what the code does. This is `checking-the-checks.md`, "a check that matches
its own comment", met again.

## NOT TESTED

Everything below is code inspection only. Nothing in this build has run on a
phone.

- **The trace file has never been written on a device.** The formatting is
  covered by 31 unit cases; the file, the directory and the permissions are not
- **No crash has been taken.** Both crash keys are unexercised. Whether the
  report reaches Downloads while the process is dying is the open question of
  this phase, and it is the one thing phase 0 exists to settle
- **The MediaStore path is unproven on Android 16.** The API 26–28 branch will
  never run on a Pixel 7 and is untested anywhere
- **The insets are unmeasured.** They must be looked at on the phone in both
  navigation modes, per `system-bars.md` §5, and the lowest key pressed rather
  than merely seen
- **Rotation is logged, not handled.** There is nothing yet for it to break
- **The Android half has never been compiled locally**, because it cannot be.
  Only CI has an Android SDK
