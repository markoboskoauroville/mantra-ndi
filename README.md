### [Download the latest build](https://github.com/markoboskoauroville/mantra-ndi/releases/latest)

# Mantra NDI

An Android broadcast camera for Baba's own phones over Wi-Fi. 10-bit HDR out of
the phone's own pipeline, NDI HX or full NDI over the network, eleven LUT slots
down one side and the camera's controls down the other, and a RAW still to the
phone whenever the shot is worth keeping.

**Building a different NDI app from these repos?** Start with
[NDI_ANDROID_GUIDE.md](NDI_ANDROID_GUIDE.md): the SDK, the build, the
thirty-minute restart, and the Android rules that fail silently.

**Before writing any code here, read [REBUILD.md](REBUILD.md)** — it carries the
faults from the first attempt that each cost a day, and every one of them is
still true about this phone and this API.

## What it is

Everything between the sensor and the wire is silicon the Pixel already has.
Nothing is reimplemented:

| The job | What does it |
|---|---|
| Demosaic, denoise, white balance | the phone's own ISP |
| The log curve | the ISP's tone mapper, `TONEMAP_MODE_CONTRAST_CURVE` |
| Exposure | the phone's own auto exposure, pinned to a constant frame rate |
| 10-bit | HLG10 on every streaming target, set before the session exists |
| The codec | the hardware HEVC Main10 encoder |
| The LUT and peaking | one GPU shader on the preview |
| The RAW still | the platform's own `DngCreator` |

The log curve matters more than it sounds. Because it is applied in the tone
mapper rather than in a shader, it is on the picture **before** the encoder, so
it reaches the stream. A shader on the preview never could: a 10-bit dynamic
range profile is only legal against a PRIVATE or P010 surface, and a GL texture
is neither, so a GPU stage between camera and encoder caps the whole pipeline
at 8 bits however good the sensor is.

## Where it is

**Phase 1 and phase 3, built. v69.** Camera, 10-bit, log, LUTs, NDI HX and full
NDI, and the snap.

| Phase | What | State |
|---|---|---|
| 0 | Logging: trace file, crash reports | built, v68 |
| 1 | 10-bit camera, log curves, lenses, focus, light | built, v69 |
| 2 | Recording | **not planned.** This camera streams; it does not record |
| 3 | NDI HX and full NDI | built, v69 |
| 4 | Remote control over NDI metadata | not started |
| 5 | Monitor mode | not started |

## The two rails

A phone's screen is about 20:9 and a broadcast picture is 16:9, so a black
margin exists whether or not anything is put in it. The keys go there, which is
how thirty controls fit on screen without one of them sitting on the shot.

**Grey is off, green is on, dark is a key this phone cannot honour.** That is
the whole of the interface language, and it is why the keys can be this small.

Left rail, in order:

| Key | What |
|---|---|
| `L1`–`L4` | the physical lenses, named from each sensor's own 35mm equivalent. A Pixel 7 lights three and darkens `L4`; a 7 Pro lights all four |
| `LGHT` | the lamp, on the repeating request — `CameraManager.setTorchMode` is refused while this app holds the camera |
| `AF` / `MF` | the focus director: hold, notice, then rack over a beat, rather than the hunting the camera's own routine does |
| `PEAK` | edge detector, monitor only |
| `SNAP` | the sensor's own frame as a DNG, uncorrected, while the stream carries on |
| `LOG` | which log curve the tone mapper is applying: Rec.709, S-Log3, V-Log, LogC3, LogC4, Film Gen5 |
| `ROT` | a quarter turn of the preview, by hand, for a phone mounted sideways |
| `HX` / `FULL` | which kind of NDI, or neither. Two ends of one switch, because an NDI source is one stream |

Right rail: **eleven LUT slots.** Tap an empty one to load a `.cube` from
storage; tap a loaded one to put it on the monitor; long press to replace or
empty it. Eleven because the ARRI LogC4 family is eleven files.

The LUT is a **monitor** LUT, deliberately. The stream carries the log picture
the tone mapper produced, and the LUT is how the operator judges it — which is
what a broadcast camera does, and the only thing that is possible at 10 bits.

## The picture and the wire

What leaves this app is the sensor's own landscape frame: the encoder's surface
is a camera target with nothing between them. So the stream is that frame
whichever way the phone is held, and **the preview shows exactly that** rather
than helpfully turning it upright in portrait — which it did on its first run,
showing a tall sliver in the middle of a wide box while the far end got a full
landscape frame.

## Building

`droid` builds, installs and launches locally in about forty seconds; releases
are GitHub Actions.

1. `python3 scripts/verify.py` — must print `all checks passed`
2. `droid test` — the gates and the unit tests
3. Set `appVersion` in `gradle.properties` to one above the last release
4. Commit, push, wait about four and a half minutes

Signing fingerprint, which never changes or every installed copy has to be
removed by hand:
`53f963193bdffa9e7ca0ab56af8e9e83da758928e6e4557abb31d139cf88a5f1`

## Files

| File | What it holds |
|---|---|
| `CameraPipeline.kt` | camera → encoder → NDI, and which targets are live |
| `CaptureEngine.kt` | the session, the profile, the tone curve, focus, the lamp, the still |
| `HdrVideoEncoder.kt` | hardware HEVC Main10 into a surface the camera writes |
| `NdiSender.kt`, `cpp/ndi_bridge.cpp` | both NDI paths, compressed and whole-frame |
| `Snap.kt` | RAW_SENSOR → `DngCreator` → Downloads |
| `LutSlots.kt` | the eleven slots |
| `PreviewEffects.kt` | the LUT and peaking, one shader, preview only |
| `RailButton.kt`, `AspectFrame.kt` | the keys and the 16:9 box they live beside |
| `CameraCatalogue.kt` | every lens this phone has, named the way a person would |
| `FocusDirector.kt`, `FocusSquareView.kt` | how focus behaves, as opposed to how it is set |
| `Mechanism.kt` | all pure maths. No Android imports, so all of it is tested |
| `LogCurves.kt`, `CubeLut.kt`, `ColourSpaces.kt` | the curves and the 33³ LUT work |
| `Trace.kt`, `TraceFormat.kt`, `CrashLog.kt`, `Downloads.kt` | phase 0, unchanged |

## The NDI SDK

The NDI Advanced SDK lives in the private repo
`markoboskoauroville/mantra-ndi-sdk` and is pulled in by the workflow at build
time. Its licence forbids redistribution, so it is never committed here.

Powered by NDI. NDI is a registered trademark of Vizrt NDI AB.
