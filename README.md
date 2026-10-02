### [Download the latest build](https://github.com/markoboskoauroville/mantra-ndi/releases/latest)

# Mantra Manual Camera

**A broadcast camera in your pocket. Real manual control, real log, a clean feed, and NDI out, from
the phone you already own.**

Most camera apps give you either a pretty automatic picture or a wall of manual sliders. Mantra
Manual Camera gives you both, parameter by parameter, and then sends the picture where a production
needs it: to a card, to NDI, to a screen-share, to OBS.

## Why it is worth €22

**Manual where you want it, automatic where you don't.** ISO, shutter, focus and colour temperature
each run on their own, on thick mixer-style faders across the picture, each with its own A / M switch.
Take one over and leave the rest to the camera: half manual (HM), with the phone's own ISO or shutter
priority where it has one. Take them all: full manual. No other phone camera lets you hand
exposure to the camera while you pull focus yourself, on the same screen, with one thumb.

**Log curves from the cinema cameras you grade alongside.** Sony S-Log3, Panasonic V-Log, ARRI LogC3
and LogC4, Blackmagic Film Gen5, computed from the manufacturers' published equations, applied in
the phone's own tone mapper, so they are in the file and on the stream, not just on the monitor.
*Coming next:* automatic exposure that knows the curve and puts middle grey exactly where each
manufacturer says it belongs.

**10-bit HLG HEVC, straight from the phone's own silicon, and a filmic look without trying.** With no log
curve chosen, the picture is the phone's own broadcast HLG: a soft highlight shoulder like negative
film, a wide colour gamut, and none of the multi-frame smartphone processing that flattens light. The ISP demosaics, the hardware encoder
compresses. Every take is HEVC, which is half the size of H.264 at the same quality.

**NDI HX and full NDI.** The phone appears as a camera in vMix, OBS, Wirecast, NDI Studio Monitor,
anything that speaks NDI. Over Wi-Fi, or over a USB cable with no network at all.

**A truly clean feed.** One key, FULL, and there is nothing on the glass but the picture: no keys, no
meters, no system bars, no messages. Mirror the phone to a computer (screen copy, OBS) and the image
is ready to broadcast as it is.

**Focus you can point at.** Tap anywhere to focus there, even with the focus mark hidden. Pinch the
focus mark from the size of an eye to the whole frame. A focus director that holds, then racks
over a beat you choose instead of hunting like a phone.

**A LUT library, focus peaking, false colour and zebra, side by side.** Load your own .cube files in
settings and switch between them with one key; peak edges in the colour you choose; read exposure
in false colour (middle grey green, skin pink, clipping red) or with zebra from the level you set. Both are drawn by the GPU on the monitor only, so the recording
keeps its full range.

**White balance like a cinema camera.** Kelvin from tungsten to daylight, anchored to the camera's own
calibration so it never goes green. Double tap to take the camera's own reading once and keep it.
*Coming next:* a grey-card sweep that finds the most neutral temperature by itself, and a companion
app that calibrates the fader to a colour meter.

**A camera's readouts, not a phone's.** Record-run timecode in HH:MM:SS:FF at the bottom middle, always white, with REC beside it turning red while rolling. The
free space on the recording drive and how many hours and minutes are left at the current bit rate,
always on screen. Record to the phone or to any drive it can see, a USB-C SSD included. PLAY opens
the last take at once. One key reads LANDSCAPE or PORTRAIT and turns the whole interface, so every label
stays upright, whatever the phone's own rotation lock says.

**Every lens, one key each.** Wide, main, telephoto, selfie: whatever your phone physically has,
and nothing it doesn't.

**Honest when a phone can't.** A curve the lens cannot run, a size the encoder will not take, a lens
with no focus motor: the app says so in words on the screen and carries on, instead of freezing or
pretending.

*Coming:* waveform and vectorscope tiles, the phone as a webcam for a Mac (Zoom, Teams, Meet), tracking focus that follows a pattern
across the frame, and a live settings preview.

## Tested on

**Google Pixel 7** is the reference phone: every feature is developed and tested on it first, and a
feature is not called done until it works there. It is also tested on the Pixel 7 emulator before
every release, with 20,000-touch stress runs. **Nothing Phone (2a)** is being tested. Phones differ
in what their cameras will do, so the store version will have a **free trial**: try every feature on
your own phone before you pay.

---

## For developers

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

Left rail (the top bar when the phone is upright), in order:

| Key | What |
|---|---|
| `L1`–`L5` | the real lenses, only the ones this phone has, named from each sensor's own 35mm equivalent. A Pixel does not put its ultra wide in `cameraIdList` — the back camera is one *logical* camera that fuses several and picks by zoom — so the physical ones are found through `getPhysicalCameraIds()` and selected by naming one on each OutputConfiguration |
| `AF` / `TRK` / `MF` | AF: the focus director — hold, notice, then rack over a beat, rather than the hunting the camera's own routine does. TRK: **tracking focus** — the box becomes a mark that follows a subject by pattern matching on the GPU, refocuses only past a set tolerance and says LOST when it loses it. MF: manual |
| `LOG` | the tone curve: HLG or STD (the phone's own), S-Log3, V-Log, LogC3, LogC4, Film Gen5; a curve a lens cannot run is taken back with "not supported". With exposure on auto, **auto exposure lands a grey card on each maker's own number** (S-Log3 41 %, V-Log 42 %, LogC3 39 %, LogC4 28 %) and the status line shows it |
| `M` | AUTO, HM (half manual) or FM (full manual), read off the ISO, shutter and focus A / M switches |
| `CTRL` | the four mixer faders — ISO, shutter, focus, white balance — each with its own A / M switch; **shutter presets in degrees** (0°, 90°, 180°, 270°, 360°) and **ISO presets** (BASE, the sensor's cleanest, and HIGH, the top of its analog gain) under the faders; white balance's A measures the scene once and holds it |
| `FULL` | the clean feed: nothing on the glass but the picture; a double tap comes back |

At the top of the picture a telemetry line names every output — FILE, USB, NDI, YT — dim when not
armed, white when armed, a red dot with its numbers while it is sending.

Right rail (the bottom bar when upright):

| Key | What |
|---|---|
| record | the master trigger: a white circle; red while every output armed in settings (file, NDI, and later USB and YouTube) runs, started together |
| `PLAY` | the last take |
| the camera icon | SNAP: the picture as a PNG beside the takes |
| `LANDSCAPE` / `PORTRAIT` | turns the interface |
| `LUT` | switches between the LUTs loaded in settings; long press opens the library |
| `PEAK` `FALSE` `ZEBRA` | focus peaking, false colour, zebra — monitor only, on the GPU |
| gear | settings: the output switchboard (FILE, USB, NDI HX / FULL, YOUTUBE), LUT library, record folder (any drive), ROT, zebra level, the picture path (GPU stage or direct), the take's and the stream's own bit rates, tracking focus. The version number at the top is a link to the latest release |
| storage | free space on the recording drive and the time left at the current bit rate |

## The picture and the wire

The geometry is written to the trace every time the preview is laid out (it was on screen until
v88):

    sensor 90 · disp 270 · rot 180 · buf 1920x1080 · view 1676x943

Those five numbers decide whether the preview is upright and whether it is
stretched, and this app has shipped one or the other wrong six times. Each of
those was diagnosed by holding a phone up to something rectangular and
arguing; now one line of the trace settles it.

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

## Mantra Monitor, the sister app (since v113, 2.10.2026)

Every release carries two APKs: `N-mantra-manual-camera-vN.apk` and `N-mantra-monitor-vN.apk`. **Mantra Monitor**
(`monitor/`, `com.mantraproductions.ndi.monitor`) watches any NDI HX source on the network — a double tap in the middle
lists them — and when the source is this camera it becomes its remote control: the camera's own keys, status line and
marks, a key tap presses it on the camera, a picture tap goes to the camera's armed mark, HX − / + set the stream's bit
rate live, and its own line shows the fps and Mbit/s it receives. Nothing is recorded on the monitor. Shared code:
`shared/kotlin` (CameraCommand/CameraState, RailButton). Stress test: `scripts/stress.py <serial>`.
