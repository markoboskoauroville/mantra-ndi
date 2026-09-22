# Where we are — Mantra NDI

Written as we go, so an interrupted session can be picked up cold. Newest at
the top. The long-form history is in the hub's `HANDOVER.md`; this is the
working state of *this* app.

---

## 22.9.2026, early afternoon — v76, the rotation cause found in his own trace

### The rotation. Found, and it was never the formula.

`cam 0,-1,-1,0` on his screen is the whole answer. A `SurfaceTexture` carries a
transform matrix from its producer; a `TextureView` **applies that matrix before
any of this app's code runs**; and the Pixel 7's camera puts **a quarter turn**
in it. A camera that turns nothing reports `1,0,0,-1` — the vertical flip alone,
because a texture's origin is at the bottom and a screen's is at the top.

So seven attempts at this argued about `sensorOrientation` against the display —
which is the right formula, for a buffer that arrives as the sensor read it —
and every one of them was adding a correct rotation **on top of one that was
already there**.

His own numbers say it twice:

| held | formula says | he dialled ROT to | total |
|---|---|---|---|
| across, `disp 90` | 0 | +270 | **270** |
| upright, `disp 0` | 90 | +270 | **0** |

Both are the formula **minus 90**, and 90 is exactly what the matrix decodes to.

`Mechanism.producerRotation(matrix)` decodes it — pure, with the real matrix off
his phone in the test suite, including the near-±1 form the crop produces. It is
read on every frame and acted on only when it changes, because the matrix is
empty until frames flow. A phone whose camera turns nothing decodes 0 and gets
the old answer unchanged, asserted over all sixteen sensor/display pairs.

### Recording was never broken — the file was in the wrong folder

Line 694 of his trace:

    11:51:19.690  CONTROL  record  stop → 577 frames, 0 refused, mantra-20260922-115059.mp4

577 frames, none refused, 19.5 s ≈ 29.6 fps. It went to **Movies/Mantra NDI**;
he looked in **DCIM/Mantra NDI**, which is where every camera on a phone puts
its footage and where he had been told to look. Takes go to DCIM now. A take
nobody can find is a take that did not happen.

### The microphone was never asked for

`REFUSED audio meter — no microphone permission`, four times in his trace. The
pair of permissions sits beside `openCamera`, and on a phone that already had
this app installed the camera was granted long ago, so that branch is never
reached and **no dialog ever appears**. The microphone is now asked for where it
is needed, once, when the meter tries to start.

### The controls are horizontal faders now

They were four invisible vertical columns, and that was wrong twice over: the
values sat at the foot of the picture where the geometry readout lives, so the
two overlapped; and a phone held across is wide and short, so a vertical drag
had the *short* side of the screen to travel in while the long side sat empty.

Four rows now, stacked from the top beside `L1`: **name, value, then the track
running away to the right** with the whole width of a landscape screen in it.
Right is more. A knob shows where the value sits in its own travel, on a log
scale, because these are stops — a linear knob across 50..12800 never leaves the
left edge in a room. A full sweep is six stops. `IRIS` and a fixed lens's
`FOCUS` keep their row and their value, dimmed, with no track at all.

The status line is back on the **top edge**, centred. The middle of the picture
is where the subject is.

### Confirmed working on his phone in v75

- **Focus.** `requested 2.453 dioptres → reached 0.315` — the lens moves.
  `focus: lens travel 0.0, logical travel 9.523809, AF modes 0,1,2,3,4,5,
  manual yes` is the line that proves the v75 diagnosis: the ultra wide has no
  travel of its own, the logical camera has 9.52, and reading only the first is
  what made focus dead for six versions.
- **Recording.** 577 frames, 0 refused.

### Worth telling him

The session came up **`everything, 8-bit`** with no 10-bit attempt in the trace
at all — which means **ten bit is switched off in Settings**, not refused by the
phone. v74 was giving 10-bit HEVC. One tick in the gear puts it back.

---

## 22.9.2026, afternoon — v75, landscape, the take, and the focus bug named

### What he asked for, in his words

> "focus is buggy. It doesn't change focus at all."
> "we are changing from vertical orientation to horizontal because in horizontal
> rotation there is much more space and I want to see parameters in the middle
> of the screen. Down there, they're hidden and they're overlapping."
> "when I switch lenses, I can see f-stop, but slider should be removed."
> "add recording. I need a record button ... on the right side action bar. There
> is already a vu meter built in in the old app. Take the code for the vu meter."

### The focus bug, found

`minimumFocusDistance()` read `LENS_INFO_MINIMUM_FOCUS_DISTANCE` from the
**physical sub-lens** alone. A Pixel's ultra wide — the lens on `L1`, the one he
was looking through — reports **0** there, because it has no focus motor of its
own. So `setManualFocus` returned `false` before it reached the camera, on every
single drag, **and it returned in silence**: no request, no `REFUSED` line,
nothing in the trace at all. That is why the trace from 11:21 has four `focus
mode` lines from the `AF` key and **not one focus distance line** in six minutes
of him dragging the column.

A capture request goes to the **logical** camera, not to the sub-lens, so the
logical camera's travel is what it is validated against. Both are now read, the
larger is used, and both go in the trace at open with the AF modes beside them.
A lens that genuinely cannot focus now says **FIXED** in the column, dims, and
refuses the finger, instead of letting the number move while the picture does
not. Every focus request is traced, applied or refused, with the distance the
lens actually reached.

### Landscape, and the readouts

The activity is `sensorLandscape`. The portrait layout branch is gone with it:
16:9 across the height, rails in the black at both ends. Turning the phone end
for end still works and still does not rebuild the session.

The four values moved from the **foot** of the columns to the **top**, level
with `L1` — the foot of the picture is where the geometry readout lives, which
is exactly the overlap in his screenshot. The status line moved to the **middle
of the picture**, centred, which is the one place nothing else on this screen
covers. `IRIS` shows the f-stop for every lens and has no fader behind it: a
phone has one aperture, and a column that lights up and does nothing is worse
than one that never answers. Same order, ISO → SHUTTER → IRIS → FOCUS.

`ROT` is remembered between runs now.

### Recording, and the meter

`REC` is a red circle at the top of the right rail — filled, with the running
time inside, while a take runs.

**Recording is independent of both NDI keys.** The encoder is in the session
whether or not anything is being sent, so a take costs a file write and the same
frames go to the wire and to the card. Nothing is encoded twice. The muxer gets
the encoder's own `MediaFormat` and its own output buffers, not the byte arrays
NDI is handed. The file opens on the first keyframe, never mid-GOP. It is
written through a MediaStore descriptor into **Movies/Mantra NDI**, `IS_PENDING`
until the muxer has closed it, because a half-written MP4 has no moov atom and
opens nowhere.

The VU meter is the old build's, arithmetic unchanged, down the inside edge of
the picture with −6 and −18 marked. What changed is where its samples come from:
the old build handed the microphone back and forth between a meter and an
encoder, and a handover is a thing that can fail — when it failed, the take had
no sound and the meter said it did. **One reader now.** It meters every buffer
and, while a take runs, passes that same buffer to the AAC encoder, so the meter
and the file cannot disagree. It runs whenever the app is in front, not only
during a take.

### The rotation — what the numbers actually say

He says the rotation is still wrong. His own screenshot says the preview is
`rot 0`, `buf 1920x1080`, `view 1788x1006`, `squeeze 1.000` — a full-frame 16:9
buffer filling a 16:9 view with no distortion. **A frame cannot be a quarter
turn out and still fill that view without stretching**, so whatever is wrong
there, it is not a 90° error at that moment.

Rather than guess a seventh time, the geometry line now also prints **`cam`** —
the producer's own transform matrix. A `TextureView` applies the camera's
transform before anything in this app runs, so if the camera pre-rotated the
frame, our rotation is added to one already applied. `1,0,0,1` means no rotation
and the frame arrived as the sensor read it. **One screenshot of that field now
settles the angle for good.**

*Needed from him:* if it still looks wrong, **how** — upside down, mirrored, or
a quarter turn — because those are three different causes.

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
