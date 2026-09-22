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
| Full NDI's frames | a YUV_420_888 reader, packed to I420 in the bridge |

The log curve matters more than it sounds. Because it is applied in the tone
mapper rather than in a shader, it is on the picture **before** the encoder, so
it reaches the stream. A shader on the preview never could: a 10-bit dynamic
range profile is only legal against a PRIVATE or P010 surface, and a GL texture
is neither, so a GPU stage between camera and encoder caps the whole pipeline
at 8 bits however good the sensor is.

## Where it is

**Phase 1 and phase 3, built. v72.** Camera, 10-bit, log, LUTs, NDI HX and full
NDI, and the snap.

| Phase | What | State |
|---|---|---|
| 0 | Logging: trace file, crash reports | built, v68 |
| 1 | 10-bit camera, log curves, lenses, focus, light | built, v72 |
| 2 | Recording | **not planned.** This camera streams; it does not record |
| 3 | NDI HX and full NDI | built, v72 |
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
| `L1`–`L4` | the real lenses, named from each sensor's own 35mm equivalent. A Pixel does not put its ultra wide in `cameraIdList` — the back camera is one *logical* camera that fuses several and picks by zoom — so the physical ones are found through `getPhysicalCameraIds()` and selected by naming one on each OutputConfiguration |
| `LGHT` | the lamp, on the repeating request — `CameraManager.setTorchMode` is refused while this app holds the camera |
| `AF` / `MF` | the focus director: hold, notice, then rack over a beat, rather than the hunting the camera's own routine does |
| `PEAK` | edge detector, monitor only |
| `SNAP` | the sensor's own frame as a DNG, uncorrected, while the stream carries on |
| `LOG` | which log curve the tone mapper is applying: Rec.709, S-Log3, V-Log, LogC3, LogC4, Film Gen5 |
| `ROT` | a quarter turn of the preview, by hand, for a phone mounted sideways |
| `HX` / `FULL` | which kind of NDI, or neither. Two ends of one switch, because an NDI source is one stream |

Right rail: **eleven LUT slots, five at a time.** Tap an empty one to load a
`.cube` from storage; tap a loaded one to put it on the monitor; long press to
replace or empty it. Eleven because the ARRI LogC4 family is eleven files; five
at a time because eleven keys down the side of a phone are each too small to
hit with a thumb. The sixth key turns the page and carries which page it is on,
and the seventh is the gear.

The LUT is a **monitor** LUT, deliberately. The stream carries the log picture
the tone mapper produced, and the LUT is how the operator judges it — which is
what a broadcast camera does, and the only thing that is possible at 10 bits.

## Settings

Behind the gear at the foot of the right rail: the source name, whether to ask
for ten bit, the HX bitrate, the focus hold and rack times, the peaking colour
and sensitivity, the route that gets the trace off the phone, and the NDI
attribution the licence requires. Everything an operator touches *during* a
take is a key on a rail where it can be reached without looking; what is in
here is what is decided once.

## The picture and the wire

The geometry is on screen, under the picture:

    sensor 90 · disp 270 · rot 180 · buf 1920x1080 · view 1676x943

Those five numbers decide whether the preview is upright and whether it is
stretched, and this app has shipped one or the other wrong six times. Each of
those was diagnosed by holding a phone up to something rectangular and
arguing; now a single screenshot settles it.

The arithmetic behind them is not in the view any more. `Mechanism.previewFit`
and `Mechanism.previewRotation` are pure functions, and the suite asserts the
thing none of the six attempts was ever asked: **whatever the view, whatever
the buffer and whichever way it is turned, what reaches the screen has the
buffer's own shape.** A stretched picture is now a failing test rather than a
report from a shoot.

There was also a mechanism nobody had noticed, and it was not arithmetic at
all: **a TextureView sets its SurfaceTexture's default buffer size to the
view's own pixel size** on every layout. Setting it once when the camera opens
means the next layout quietly replaces it, and the camera then scales into a
shape nobody asked for. It is re-asserted on every size change.

And then the readout found the real one, which was never in the view at all.
**An ultra wide is a *physical* sub-lens and it publishes its own list of
output sizes** — often 4:3 only, because that is the shape of its sensor. The
app was reading the sizes of the *logical* camera and handing one of them to a
physical lens, which gets a frame that lens never offered: nothing refused,
nothing logged, and the lens's own picture squeezed into the shape that was
demanded. On one lens and not another, which is exactly how this looked for six
versions.

So the size comes from the lens actually being looked through, 16:9 is
preferred but never imposed, and **the picture box takes the picture's shape
rather than the picture being made to take the box's.** A 4:3 lens is shown at
4:3 and sent at 4:3; the rails simply get more black to sit in.


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
| `SettingsActivity.kt`, `Settings.kt` | what is decided once, off the camera screen |
| `UsbLink.kt` | the addresses a receiver can be pointed at, cable first |
| `Snap.kt` | RAW_SENSOR → `DngCreator` → Downloads |
| `LutSlots.kt` | the eleven slots |
| `PreviewEffects.kt` | the LUT and peaking, one shader, preview only |
| `RailButton.kt`, `AspectFrame.kt` | the keys and the 16:9 box they live beside |
| `CameraCatalogue.kt` | every lens this phone has, named the way a person would |
| `FocusDirector.kt`, `FocusSquareView.kt` | how focus behaves, as opposed to how it is set |
| `Mechanism.kt` | all pure maths. No Android imports, so all of it is tested |
| `LogCurves.kt`, `CubeLut.kt`, `ColourSpaces.kt` | the curves and the 33³ LUT work |
| `Trace.kt`, `TraceFormat.kt`, `CrashLog.kt`, `Downloads.kt` | phase 0, unchanged |

## Down the cable

Behind the gear: **USB tethering, and NDI over it.** Turn tethering on and the
phone becomes a network interface on the computer — a private link at USB
speed, with none of a hall's Wi-Fi in the way. Settings shows every address a
receiver could be pointed at, the cable first, because NDI discovers by mDNS
and a link made thirty seconds ago is exactly where a receiver is most likely
not to hear it.

**This app cannot be the phone's "Webcam" USB option, and no third-party app
can be.** That is `com.android.DeviceAsWebcam`, which lives in
`/system/priv-app` with the SYSTEM flag; presenting the phone as a UVC camera
means writing the USB gadget's configuration in configfs, which needs
`MANAGE_USB` — signature|privileged, with no public API behind it. It would
also be a step down: UVC is 1080p30 of 8-bit YUV, and this app already makes
10-bit HEVC. **The cable is worth having for its bandwidth, not for its
protocol.**

## Two things this phone taught the app

**A camera cannot write into an RGBA ImageReader.** `PixelFormat.RGBA_8888` is
not in Camera2's stream configuration map, so a session carrying one is refused
outright — and it takes the preview and the encoder down with it. On a real
Pixel 7 that was a black screen and "Camera session could not be configured",
caused by a reader that existed only for a mode nobody had switched on. RGBA
was right in the screen share, where the producer is a VirtualDisplay; a camera
never produces it.

**`onConfigureFailed` does not say what it disliked.** It is one call with no
argument, so a session carrying four targets that is refused tells you nothing
about which of the four did it. So the session is now offered as an ordered
list of combinations, giving up the targets in the order they can most afford
to be lost — full NDI, then RAW, then ten bit, then the encoder — and each
refusal is named in the trace. A phone that will not take everything still
shows a picture, and the keys for whatever did not survive go dark rather than
lighting for a target the session does not have.

## The NDI SDK

The NDI Advanced SDK lives in the private repo
`markoboskoauroville/mantra-ndi-sdk` and is pulled in by the workflow at build
time. Its licence forbids redistribution, so it is never committed here.

Powered by NDI. NDI is a registered trademark of Vizrt NDI AB.
