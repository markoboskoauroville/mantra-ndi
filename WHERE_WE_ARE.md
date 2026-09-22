# Where we are — Mantra NDI

Written as we go, so an interrupted session can be picked up cold. Newest at
the top. The long-form history is in the hub's `HANDOVER.md`; this is the
working state of *this* app.

---

## 22.9.2026, midday — v74 in progress

### Shipped and on his phone

| Version | What it settled |
|---|---|
| v69 | The camera exists. 10-bit, log, LUTs, HX/FULL, SNAP, two rails |
| v70 | Black screen fixed — **an RGBA ImageReader is not a legal camera output** |
| v71 | Geometry readout on screen; USB section; sensor angle from the physical lens |
| v72 | Size chosen from the physical lens, picture box takes the picture's shape |
| v73 | **DISTORTION_CORRECTION_MODE** on; ten bit no longer given up before full NDI |

Signed APKs are kept at `~/Developer/APK/MANTRA_NDI/mantra-ndi-v7N-release-<date>.apk`,
each verified against the pinned fingerprint `53f963…a5f1`.

### What his Pixel 7 has proven

- **10-bit works.** `1920x1080 10-bit HEVC`, HLG10 session, hardware Main10.
- **Five lenses enumerate**: ultra wide 17mm `[0:3]`, wide 25mm fused `[0]`,
  main 50mm `[0:4]`, front 21mm `[1:5]`, front 24mm fused `[1]`.
- **`rotate-and-crop` offers only NONE** on this phone, so there is no free way
  to turn the *stream* in the camera pipeline.
- **The session will not take everything at 10-bit.** It settles on
  *without full NDI, 10-bit* — so full NDI and 10-bit are mutually exclusive
  here, and the fallback now prefers 10-bit, which is the right way round.
- **`DISTORTION_CORRECTION_MODE` offers 0,1** — OFF and FAST, no HIGH_QUALITY.
  FAST is applied.

### The four things he reported on v73, and where each stands

1. **"Image still stretched, although while streaming over NDI it's normal."**
   *This sentence solved it.* Same buffer, same camera — so the camera is
   innocent and only the preview's transform is wrong. `setTransform` is in the
   view's coordinates and does **not** follow the view when the view resizes;
   it was only being recomputed on surface-size changes. A TextureView inside a
   weighted LinearLayout is measured more than once before it settles, so the
   matrix could be worked out against one width and left applied to another —
   which, scaled about the centre, is exactly a squeeze. **Fixed:** recomputed
   on every layout change via `addOnLayoutChangeListener`. *Unverified on his
   phone.*
2. **"When I change lenses, my streaming is interrupted."** Confirmed in the
   trace: `lens requested 2` → `stream off` → `pipeline stopped`. **Fixed:** the
   mode is remembered across the rebuild and restored on `onReady`, and
   `stop(keepSource = true)` leaves the NDI source open so a receiver sees a
   freeze rather than a lost input. *Unverified.*
3. **"LUTs are not applied to my NDI output."** Was deliberate (monitor LUT) —
   he wants it global. **Built:** `Mechanism.toneCurveThroughCube` walks the
   cube's neutral axis and hands each channel's answer to `TonemapCurve`, which
   takes a curve per channel. That puts the LUT's **tone and colour balance** on
   the wire at no cost and keeps 10-bit. **What it cannot carry** is the rest of
   the cube — how a LUT treats a saturated red differently from a grey of the
   same brightness is three-dimensional, and a per-channel curve has one
   dimension. The monitor still shows the cube exactly. *Unverified.*
4. **Manual controls.** **Built:** `ControlZones.kt` — four invisible columns
   over the picture (ISO, SHUTTER, IRIS, FOCUS), a small word and value at the
   foot of each, drag up for more. `M` on the rail toggles manual/auto exposure
   and starts from whatever auto had settled on, so the picture does not jump.
   `CTRL` toggles the columns, and while they are on **the focus box is put
   away** — exclusive on purpose, because a tap that could mean two things means
   neither. **The iris column is inert on a phone**: the lens has one aperture,
   so it shows `f/1.85` dimmed rather than pretending to move. *Unverified.*

### Still open

- **Whether the preview stretch is actually gone.** Needs one screenshot of the
  geometry line plus his eye on the picture.
- **NDI over USB into OBS.** The source *is* reachable: TCP 5960/5961 open on
  `10.104.218.171` over `en19` ("Pixel 7"), and `dns-sd -B _ndi._tcp` sees
  `LOCALHOST (Pixel 7 Camera)`. But `droid ndi-find` found nothing, which is not
  yet explained — **the phone reports its machine name as `LOCALHOST`**, which
  some receivers treat as a local source. Worth setting a real machine name.
- **Wi-Fi vs LAN streaming setting** he asked for. Verified in the SDK header:
  `NDIlib_send_create_t` has only name, groups and clocking — **there is no
  interface selection**, so NDI binds to everything and no SDK call can stop it.
  What can be done: refuse to start unless the chosen link is up, name the
  source after the link, and offer one tap to the Wi-Fi panel.
- **MANTRA_MACKIE**: he asked for the command bulb's position to be settable
  (first/second screen, top/bottom/left/right, radio buttons in settings). Not
  started. `blob.lua` has `home()` at top-centre of Live's screen and a
  remembered drag position; the setting would replace `home()` with a chosen
  corner.

### The rule that keeps being relearned

Ask the app, not the code. Every one of the last four faults was named by the
trace or by one sentence from him about what the phone actually did, and every
hour lost was spent reasoning about what it ought to do.
