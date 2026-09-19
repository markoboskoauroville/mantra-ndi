# Mantra NDI — handover

Everything a new session needs to continue. Read this first, then `scripts/verify.py`.

## What it is

An Android broadcast camera. It sends NDI, records to the phone, monitors other
NDI sources, and drives a remote camera over NDI metadata. Built for Baba's own
phones over Wi-Fi, not for distribution.

- Repo `markoboskoauroville/mantra-ndi` (public), package `com.mantraproductions.ndi`
- NDI Advanced SDK lives in the private repo `markoboskoauroville/mantra-ndi-sdk`
  and is pulled in at build time; its licence forbids redistribution, so it must
  never be committed here
- Built only by GitHub Actions. Every push builds, gates and publishes an APK
- Signing fingerprint `53f963193bdffa9e7ca0ab56af8e9e83da758928e6e4557abb31d139cf88a5f1`

## How to work on it

1. Edit, then `python3 scripts/verify.py` — it must print `all checks passed`
2. Read the version already released, set `appVersion` to one above it
3. Commit with a message that explains *why*, not what
4. Push, wait about four and a half minutes, read the run's logs
5. The release APK appears at
   `https://github.com/markoboskoauroville/mantra-ndi/releases/download/vN/N-mantra-ndi-vN.apk`

**Check every edit landed.** Several builds in this project have failed because
an edit reported success and had half applied, leaving duplicate declarations.
`grep` for what was added before committing.

## The gates (`scripts/verify.py` and the workflow)

G1–G12 cover the source. Then:
- **G13** no duplicate `android:id` in a layout — data binding rejects it
  silently to a reader
- The workflow refuses an APK that is unsigned, signed with a different key,
  missing the NDI runtime or the JNI bridge, or carrying a version already
  released

A gate that fires on everything is worse than no gate. G14 tried to catch
duplicate Kotlin declarations, flagged 180 legitimate locals, and was removed.

## Architecture

```
Camera2 → CaptureEngine → HdrPipeline
                            ├── record encoder  → Mp4Recorder → DCIM/Mantra NDI
                            ├── stream encoder  → NdiStream → ndi_bridge.cpp
                            └── preview Surface → PreviewEffects (GPU shader)
NDI in  → ndi_recv_bridge.cpp → MonitorEngine → same preview Surface
```

**Only one thing may own the preview surface.** The mode decides which. Two
producers on one `SurfaceTexture` is why a remote camera's name appeared and its
picture never did.

### Files that matter

| File | What it holds |
|---|---|
| `Mechanism.kt` | All pure maths. No Android imports, so it is all testable |
| `PreviewEffects.kt` | One GPU shader: monitor LUT, focus peaking, waveform |
| `HdrPipeline.kt` | Camera session, both encoders, the surface list |
| `TimecodeView.kt` | The status line across the top |
| `LtcEncoder/LtcDecoder/LtcEngine` | Timecode as audio |
| `RecordingHealth.kt` | Space left, time left, dropped frames |
| `BandwidthTest.kt` | Real throughput between two phones |
| `RecordingFormats.kt` | Formats probed from the phone, never hard-coded |
| `CameraCatalogue.kt` | Every real lens, named by 35mm equivalent |

## Rules this app is built on

- **Never write a setting that does nothing.** Two have shipped and both were
  caught later: a lens picker that stored a choice while the pipeline opened
  camera zero, and a MOV option Android's muxer cannot write
- **Probe the hardware, do not assume it.** Frame rate ceilings come from
  `getOutputMinFrameDuration`, *not* `getHighSpeedVideoFpsRangesFor`, which is
  the slow-motion path and returns nothing for normal sizes
- **Nothing expensive on the camera callback thread.** A Planckian search ran
  5,000 logarithms per second there and was the app's sluggishness
- **Preview-only effects go on the View, never the capture request.** A tone
  curve reaches the encoder and bakes itself into the file
- **Colour and words say the same thing twice.** Colour alone fails for anyone
  who cannot separate green from white; small text alone fails at arm's length
- **Measure, do not guess.** Simulate a codec in Python before spending a CI
  cycle on it; that found two real LTC bugs

## Current state (v51)

Working: NDI send and receive, three modes plus System, monitor LUT, focus
peaking, GPU waveform, vectorscope, three-way grade, LTC timecode master and
follower, NDI frame timecode, dual encoders for stream and record, bandwidth
test, recording health, lens catalogue, hardware-probed formats, X and RST.

## Outstanding

Newest first. The top four are from the latest session and not yet built.

1. **Status as three letters beside the take length** — `REC` only while
   recording, and the other states equally short, next to the number in the
   middle. Currently the long word overlaps the picture
2. **Focus box snapping** — it does not land where it is tapped
3. **Rename `BOX` to `FOCUS`** on the left bar
4. **A LUT per log curve** — the operator uploads a `.cube` for each curve and
   the monitor LUT uses it instead of the computed inverse. Check the
   application against industry practice while doing it
5. **Screen streamer mode** — MediaProjection for the picture; remote control
   needs an AccessibilityService and only works between two copies of this app
6. **Timecode follower without watching** — a sending camera should subscribe to
   the master at metadata-only bandwidth just for the clock. This is what makes
   one master and many cameras actually work
7. **Draggable timecode**
8. **Bandwidth figures shown beside each bitrate choice** — `BandwidthTest`
   already computes them

### Ruled out, with reasons

- **Tentacle Sync BLE** — the protocol is licensed, not published. LTC over
  audio replaced it and is better: it is what the hardware already outputs
- **ARCore depth focus** — ARCore takes the camera; it cannot coexist with our
  Camera2 session. `DEPTH16` is the honest path if depth is ever wanted
- **MOV** — `MediaMuxer` writes no QuickTime at all

### Known limitation worth stating

The log curves flatten what the ISP has already processed; they are not applied
to raw sensor data. MotionCam Pro captures `RAW_SENSOR`/`RAW10` and encodes log
from that, which is why its files grade further. Matching it means a second
pipeline, not a setting.
