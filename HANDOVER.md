# Mantra NDI — handover

Read [REBUILD.md](REBUILD.md) first. This file is what the current session
decided and what is not tested.

## State

**v67. Phase 0, logging. No camera in this build.**

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

## v67: the three faults the phone showed

Phase 0 was confirmed working on the phone at v66. Both crash kinds produced
a report, both reached `Download/Mantra NDI`, and the run that died carried
its own fault lines in its own trace file. These three came out of reading
the actual output rather than the code.

1. **The tail was counted in lines and printed as lines.** One FAULT entry
   carries a stack seventeen lines long, so a report saying "the last 20
   trace lines" sat over a block a reader counts thirty-seven of. It is
   counted in entries and now says entries.
2. **The stack was in every report twice**, once under what killed it and
   once again in the tail. The tail is now snapshotted before the fault is
   written, so it reads as what the app was doing up to the moment it died
   and the stack appears once.
3. **`insets applied` was written twice** with identical values, because
   Android offers the insets more than once for one layout pass. Only a
   change is written now.

**Not done, and worth knowing:** the report tail is capped by the ring at 400
entries. That was fine at 20 and will make a large crash file once the camera
is logging. Left alone deliberately rather than changed unasked.

## What the phone actually reported, against REBUILD.md

REBUILD.md §6 says `supportsManualSensor` answers false on this phone. The
Pixel 7 reports `MANUAL_SENSOR` **true** in
`REQUEST_AVAILABLE_CAPABILITIES`, on both cameras, measured at v66. The old
note must be about a different call, and §6 does not say which. The rule
stands either way: send the request and let the camera refuse it.

Sensor orientation, measured: **back 90, front 270.**

## NOT TESTED

Everything below is code inspection only. Nothing in this build has run on a
phone.

The trace file, both crash kinds and the MediaStore route were all confirmed
on the phone at v66. What remains unproven:

- **The three v67 fixes have not run on the phone.** They are covered by unit
  cases for the wording and the count; the tail snapshot and the inset
  de-duplication are code inspection only
- **Portrait only.** The phone was not rotated at v66, so no
  `configuration changed` line has ever been produced by a real turn
- **Three-button navigation was not tried.** `system-bars.md` §5 wants both
  modes, and the lowest key pressed rather than merely seen
- **The API 26–28 Downloads branch** will never run on a Pixel 7 and is
  untested anywhere
- **The Android half has never been compiled locally**, because it cannot be.
  Only CI has an Android SDK
