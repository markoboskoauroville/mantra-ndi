# Where we are — Mantra NDI

Written as we go, so an interrupted session can be picked up cold. Newest at
the top. The long-form history is in the hub's `HANDOVER.md`; this is the
working state of *this* app.

---

## 26.9.2026, evening: v83, Mantra Manual Camera, and what his first test found

He tested v82 on the Pixel 7 and the Nothing Phone (2a): peaking works on the Nothing. Then:
the take's sound is distorted and on the Nothing plays sped up (his file, measured with ffprobe);
SNAP is nowhere to be found; a log curve freezes the Nothing; record HEVC; rename the app **Mantra
Manual Camera** for Google Play at €22; the README becomes the store page; research NDI licensing;
the Pixel is the priority; the webcam becomes a Mac companion because there is no Apple account.

- **The sound.** `AacEncoder.feed` copied one input slot (1024 samples, 21.3 ms) out of each 40 ms
  microphone read and stamped it on arrival, so half the sound was thrown away and the rest was
  spread over the take. His file: 386 AAC frames, every one 40 ms after the last, 8.2 s of sound in
  15.4 s. Now every byte is fed across as many slots as it needs, a slot is waited for rather than
  skipped, and each is stamped from the sample count on the camera's own clock
  (`SENSOR_INFO_TIMESTAMP_SOURCE`: the boot clock on the Pixel). Emulator take: 549 intervals, all
  21.3 ms; 11.73 s of sound in 11.79 s of picture; "nothing dropped".
- **HEVC for every take.** `preferHevc` was always false, so every 8-bit take was H.264. HEVC now,
  H.264 only if HEVC refuses the size. One encoder still makes the take and the HX stream, so they
  have the same bit rate (now 2 to 100 Mbit/s, default 24). The separate recording encoder, straight
  from the sensor at its own rate, needs the GPU stage: a session has only three processed outputs.
  That is Phase 4 (v86).
- **SNAP is a PNG** of the picture at the camera's resolution, the way it is shown, without the
  interface, in `DCIM/Mantra Manual Camera/` beside the takes.
- **The log freeze.** A camera may accept a tone curve it cannot run and simply stop sending frames.
  After a curve change the frames are counted; none within a second puts back the previous request
  and says "not supported on this lens", and LOG skips that curve on that lens afterwards.
- **The name.** Launcher, folders, release file and notes. The package id stays
  `com.mantraproductions.ndi`: changing it makes a different app to Android and to Google Play.
- **Research**: `NDI_LICENSING_AND_WEBCAM.md`. NDI HX needs a paid Advanced SDK contract with a
  License ID and volume pricing (no published per-copy price); even the free SDK's license excludes
  Android without a commercial agreement. The webcam: OBS's signed camera extension accepts frames from
  any app, so a Swift companion needs no Apple account.

**Tested.** 229 unit tests. CI signed with the pinned key. Emulator: v82 used, then v83 over it, box
size and ROT kept; a 12 s take measured; SNAP PNG pulled and viewed; all six curves applied with no
false alarm; monkey seed 83083, 20,000 events: no crash, no ANR, and all five takes it made open,
HEVC, with no uneven audio frame.

**Not tested.** The freeze fix on the Nothing itself (the emulator runs every curve). The Pixel's real
microphone and 10-bit HEVC.

---

## 26.9.2026 — v82, Phase 1 of the big update: the screen

Marko sent fourteen features and fixes in one message. They are split into nine
phases, one version each, in
`v81 version bumps upgrades prompts for phase development of professional application for shooting video.md`
(forecast: the last is v90). **The next phase starts only when he confirms this one on his phone.**

**What v82 does.**
- **Pinch the focus box**, from an eye's size (`Mechanism.BOX_MIN`, 6% of the short side) to the
  whole picture; square up to the short side, then it widens to the frame. Kept in prefs
  (`focusBoxSize`). About four pinches take it from full to tiny: Android's pinch detector stops
  counting at about 27 mm between fingers, so each pinch roughly halves it.
- **The camera is given the box as drawn.** Until v81 `focusAtNormalisedPoint` sent a fixed 16%
  patch, taken as if the screen and the sensor were the same way up and the same shape. Now the
  box goes through `sensorToViewDegrees` (Android's preview formula plus ROT), `viewRegionToSensor`
  (portrait, upside down, the front camera's mirror) and `streamRegionToArray` (the 16:9 picture is
  the middle of a 4:3 sensor). All pure, 15 new cases.
- **One tap focuses when the box is hidden**: with CTRL's zones up (a single tap, confirmed after
  the 320 ms double-tap window, so a double tap on a zone never racks) and in FULL
  (`onSingleTapConfirmed`; the double tap still comes back). This reverses v81's "one tap does
  nothing in FULL", on his word.
- **The F-stop no longer returns in FULL**: `refreshZones` runs once a second and switched the iris
  line back on without asking about full screen.
- **No toast** entering FULL. (Android's own "Viewing full screen / Got it" appears once per app;
  no app can remove it; one "Got it" ends it for good.)
- **Lens keys only for real lenses.**
- **Peaking works with no LUT.** Every `uniform shader` in AGSL must be bound; with no cube the
  code returned "refused", so peaking alone failed on every phone. A 1x1 strip is bound now.
- **WB double tap = the camera's own reading, once** (the uncommitted v81 work, finished): 20 frames
  of AWB, read the anchor, back to manual at that Kelvin. A 3 s deadline answers if the camera never
  does. A long press is continuous auto.

**Tested.** Test 1: 225 cases green; the harness was made to fail on purpose (two rules broken, 3
red, restored). On `Pixel_7_API_35`, with the CI-signed APK: v81 installed and used first (PEAK
reproduced "This phone will not run the preview shader"; ROT set), v82 installed over it while
running, ROT kept. Raw multi-touch via `sendevent` (adb root): pinch to full (1.777 = the picture's
aspect) and to the floor (0.06), size kept across a force-stop; tap with zones up and in FULL,
both in the trace; FULL for 3 s with CTRL up shows no iris and no toast; WB double tap answered
5003 K in 0.66 s. Monkey, seed 82082, 20,000 events: no crash, no ANR.

**Not tested, and why.** The emulator's camera focuses on a flat picture, so whether the region
now lands on the right part of a *real* sensor upright and upside down is for his Pixel 7. The
feel of the pinch under a thumb. Peaking on the Nothing Phone's GPU.

**Waiting for Marko.** 1. Pinch the box both ways. 2. CTRL up, tap a subject: does it focus there.
3. FULL, tap to focus, double tap back; no F-stop. 4. PEAK with no LUT. 5. WB double tap. 6. Hold the
phone upright and tap near the top of the picture: it should focus there (it did not before).

---

## 22.9.2026, night — v81, the clean feed, and one key for NDI

*"I'm using screen copy to broadcast my camera, not our NDI, so I need the pure
full screen mode without anything on the screen, not even the VU meter.
Nothing, not even parts of the interface of the phone."*

### FULL: the picture and nothing else on the glass

When the wire is the phone's own screen, **every pixel this app draws is on the
wire** — so the question is not which controls to shrink but which to take away,
and the answer is all of them: both rails, the status line, the geometry
readout, the audio meter, the zones, the focus box. **And Android's own status
and navigation bars with them.**

The picture keeps its shape. It is centred on black at the largest size that
does not stretch it, because a stretched picture is the fault this app has
shipped most often and a receiver can crop black but cannot undo a squeeze.

**The camera is untouched.** A take goes on being written and NDI goes on being
sent while the screen is clean; this is a mode of the *screen* and of nothing
else.

**Its only control is invisible, because a key would be a thing on the screen.**
A **double tap anywhere** brings the camera back, and so does the back key. One
tap is what a phone gets by accident while it is being carried, so one tap does
nothing at all — which also means a hand on the glass during a take cannot rack
the lens or move an exposure. The bars are hidden transient-by-swipe rather than
immovably, so a phone left in this mode is never trapped in it, and they are
re-hidden after a rotation and after the app has been away, because they come
back on their own.

**The one thing no app can take off his screen** is Android's green camera
indicator: it is drawn by SystemUI, above everything, and there is no API for
it. It can be switched off over the same cable that carries the screen copy —
`adb shell cmd device_config put privacy camera_mic_icons_enabled false`, which
lasts until the phone is restarted.

### NDI is one key now

HX and full were always **the two ends of one switch** — an NDI source is one
stream, and a receiver is either given compressed access units or whole frames.
Two keys made that look like two independent things that might both be on. One
key, **NDI**, cycling off → HX → full → off, with its small word saying which;
a mode this phone cannot offer is stepped over rather than being a tap that does
nothing. That is also what freed the key below it to be `FULL`.

---

## 22.9.2026, night — v80, the manual camera he actually asked for

Eight faults in one pass, and seven of them turned out to be one sentence each
once the mechanism was found. His words are the headings.

### "The whole range is not used. The slider stops two thirds of the way along"

His shutter zone stopped in the middle of its own track, and the reason is
exact. The knob was drawn from **what the camera reported**, while the drag
moved **a fixed six stops per swipe** — two different instruments wearing one
hat. A camera will not expose for longer than one frame, so at 30fps it clamps
at 1/30 whatever the sensor claims, and 1/30 is **two thirds of the way along**
a range whose other end is his sensor's eleven microseconds: **1/92030**, the
number he read out.

So the position is the instrument now. `Mechanism.valueAtPosition` and
`positionOfValue` are a pair, the knob goes exactly where the thumb puts it, and
the value is read off the knob. And the track is the range the camera will
actually honour: **1/8000 to one frame interval**, because nobody has ever
chosen a ninety-thousandth of a second and the whole cost of offering it is
precision everywhere else. Change the frame rate and the slow end of the track
moves with it.

### "I want to decide how many frames a second I am writing my file"

There was no such control: 30 was written in the code with nothing on any screen
to say so, which on a camera is not a default but a missing control. **24 / 25 /
30 / 50 / 60**, in the gear, filtered against what the lenses publish *and* will
hold steady — a range like `[15,30]` is the one that lets a dim room halve the
rate to keep the picture bright, and on a live stream that is worse than a dark
picture. It sets the recording, the stream and the shutter ceiling together.

### "Focus is unfunctional" — and it was three different lenses

*"Lens one doesn't react. Lens two is reacting. Lens four is fixed."* All three
are the same line of code. `minimumFocusDistance()` took the **larger** of the
sub-lens's travel and the parent's, and one line earlier had already collapsed
**null** and **0f** into the same number. An ultra wide has no focus motor and
says so by publishing zero — and was handed the *main* lens's travel instead.
The fader moved, the number moved, the logical camera accepted the request, and
the glass never did anything.

Three answers now, not two: the lens being looked through has the last word
whenever it publishes one **including when its answer is "none"**, and only a
lens that publishes nothing falls back to its parent. A fixed lens says **FIXED**
on its zone and says so on the status line when it is dragged. And there is a
new instrument for the case nobody can predict: the request and what the capture
result says the lens *reached* are compared a beat after every pull, and a lens
that is not following says so on screen.

### "White balance is just some nonsense making my picture green" — the third time

v65 moved the gains and left the matrix. v77 computed both from the sensor's
published calibration, which is right in principle and still wrong by whatever
the absolute model is out by — and **an absolute error in white balance on a
Bayer sensor has exactly one colour**, because green is the channel with twice
the samples.

So the fader stops being absolute. **The camera's own automatic answer is the
anchor**: the gains and the colour matrix its makers chose, for this scene,
caught in the capture result at the moment the operator took it over. The
calibration is then used for the one thing it is unarguably good for — *the
shape of the change* from one temperature to the next — and the fader carries
the camera's own answer along that shape. At the anchor the picture is exactly
what auto was showing, to the last digit; away from it both halves move out of
one interpolation, so they still cannot disagree. It is the same discipline as
`M`: leave auto from where auto had got to.

The trace now reads the change **back**: `white balance readback: awb mode …,
correction mode …, gains …, transform reported`. If it is ever green again, that
line says which half the camera dropped.

### "I want zones back, horizontal, and maximise the slider length"

The zones were already horizontal; what was wrong was the room. The name and the
value were two fixed columns **180 density pixels wide** and the track began
after them — a third of the travel spent on two words. The words are small and
sit **above** the track now, and the track runs the full width of the picture,
edge to edge.

**A double tap hands the zone back to the camera.** It was a single tap, and a
single tap is what a thumb does by accident while it is finding the band it
wants; losing manual exposure mid-shot because a finger brushed the glass is not
a control. A double tap always means *auto*, never "the other one" — a gesture
that toggles is a gesture whose result has to be checked afterwards. Dragging
still takes the parameter over, and still starts from where the camera was.

### "Only text, so the buttons are invisible and can be much closer"

He asked for this at the very beginning. A box round a word costs an outline, a
corner radius, an inset and a margin, and every one of those is taken off the
word inside it. The keys are **text and nothing else** now, **13sp instead of
9sp** — half as big again — and grey/green says the rest without a border.
**LGHT and SNAP moved to the right rail, under REC**, which is two keys' worth
of height shared among the ten left on the other side and puts the lamp and the
stills where a right thumb already is.

### "Turn the phone upside down in landscape and it doesn't follow"

It could not. Turning a phone end for end takes the display from 90° to 270° and
changes **nothing else**: same orientation, same window size, so no
configuration change and no layout change arrive — and the preview transform was
only ever recomputed when one of those did. A **`DisplayManager.DisplayListener`**
hears exactly that case and nothing else.

### "The selfie camera is 180 degrees rotated"

A front camera hands over a **mirrored** frame, and `producerRotation` read the
reflection as a half turn: it saw the negative entry and answered 180, so half a
turn that was never there was taken off the angle. Lens four came up upside down
while the three rear lenses were right, from one missing determinant.

A reflection is not a rotation. The mirror is taken off first — `R · flipX` is
the rotation on its own — and reported separately, so the preview can put it back
the other way and show what the wire is carrying. The geometry line says
`mirrored` and `front` now, so one screenshot settles it. And **`ROT` is
remembered per lens**: a sensor mounted unusually is corrected once, on the lens
it belongs to, and correcting one never turns another.

### The settings, at the top

**MIN**, three letters, top right, green while the screen is quiet — one key
rather than two, because two keys for one two-state thing is a question asked
twice. Beside it, **the version**, where he looks for it after an install
instead of at the bottom of a long scroll.

*Needed from him:* **1.** The shutter zone end to end — the whole track should
be usable now, 1/8000 at the left to 1/30 (or 1/24, or 1/60) at the right.
**2.** Frame rate in the gear, then a take, and whether the file is at the rate
he chose. **3.** White balance: sweep it against something white and say whether
it is green anywhere. **4.** Lens 4, the selfie: upright now, or still upside
down — and either way **one screenshot of the geometry line**, which now says
`mirrored` and `front`. **5.** The phone end for end in landscape. **6.** The
left rail: is the text big enough.

---

## 22.9.2026, evening — v79, the portrait strip, the fader's real range, the settings

*"So landscape mode is working good."* — and his trace confirms the rest of v78:
white balance ran **continuous** the whole session, interpolating between his
sensor's own anchors, and the take landed in **DCIM/Mantra NDI**.

### The portrait strip — the last of the rotation bug

Held upright, the picture was a narrow strip again: `disp 0 · cam 90 · rot 0 ·
view 1080x608 · squeeze 0.316 STRETCHED`.

The black box the picture sits in was given **the buffer's own shape, 16:9,
always**. Held across that is right, because the camera's quarter turn and ours
cancel — 90 + 270 = 0 — and a 16:9 frame comes out 16:9. **Held upright they do
not cancel:** 90 + 0 = 90, so what reaches the screen is 9:16, and a 9:16 picture
fitted inside a 16:9 box is a strip with black on all four sides.

`Mechanism.shownAspect(bufW, bufH, producer, applied)` counts **every** turn and
the box takes that shape. Upright, the box is tall and the picture fills the
width, which is what every camera app on a phone does. `holdBufferSize` no longer
sets the aspect at all — only the one place that knows the total turn does.

### The fader's real range

*"I need 3300 until 5500, or whatever are default for tungsten."*

It ran **2000K to 10000K**, and most of that was travel nobody wants and nothing
could honour. His trace says why: `calibration published (2856K and 6504K)` —
Standard A and D65. **Beyond those two anchors there is nothing left to
interpolate between**, so the ends of the old sweep were the same clamped matrix
over and over while the number went on moving.

**3200K to 6500K** now: tungsten at the left, and the top is where the
measurement stops. Every point on the fader is inside the sensor's own span.

### The settings screen, in his order

*"What I changed the most is stream bitrate. That should be first."*

1. **Stream bitrate** — it was four blocks down, under two things that get set
   once and left. What is touched most often goes at the top.
2. **Depth and resolution on one line** — `8-bit | 10-bit` beside
   `720p | 1080p | 1440p | 4K`. Both are answers to "what shape is the picture",
   both are two or three words wide, and stacking them cost half a screen.
3. Then the rest.

The ten-bit **switch is gone**. *"Ask for 10-bit. Who should I ask? Not ask."* He
is right: a toggle asks a question with a right answer and leaves the operator to
work out what "off" means. Two labelled choices say what the camera will do.

**MINIMAL and VERBOSE**, two small words in the header, remembered. The help text
is worth having once and in the way for ever after — it is a mode of the screen,
not a setting. Every hint carries a tag rather than being listed by id, so a hint
added later is covered without anybody remembering.

---

## 22.9.2026, late afternoon — v78, the squash, the zones, resolution, both ways up

### The squash — the other half of the rotation bug

v77 got the picture the right way up and left it in a strip a third of the screen
wide. He saw it immediately: *"This is the right picture, just it is squashed. If
you stretch it edge to edge, it will be correct."* He was right, and here is why.

**A producer transform moves texture coordinates, not the view.** A
`TextureView` still draws its quad at the view's own size, so a camera that
transposes the frame hands over content whose width and height have swapped
**while the quad has not**. v76 took the camera's quarter turn off the *angle* —
which is why the picture came up the right way round — but went on computing the
*fit* against 1920x1080 when what had arrived was 1080x1920. So the picture was
fitted as though it were portrait, and pillarboxed into the middle.

`Mechanism.effectiveBuffer(w, h, producerDegrees)` swaps the shape when the
camera turned a quarter, and the fit, the squeeze readout and the box are all
told the swapped one. The test asserts his exact case end to end: view 1788x1006,
buffer 1920x1080, camera 90 → rotation 270 **and a displayed aspect of 16:9**,
not 9:16 in a strip.

### The zones are zones now

*"You gave me f-stop as control without slider. Nonsense. And it takes the space
of the slider."* Quite right. **The iris is out of the faders**, and reads in the
top right corner beside the other facts — a phone has one aperture; it is a fact
about the lens, not something anybody can set.

**Four faders, and every one has a track**: ISO, SHUTTER, FOCUS, WB. The white
balance slider was missing because it only appeared once the row had been tapped
off AUTO — a fader that has to be armed before it exists is a fader that looks
broken. **Dragging any of them now takes it**: drag ISO and manual exposure comes
on, drag WB and it comes off auto. A tap still hands it back.

### Resolution

*"I don't have any control over resolution... It's always 1920."* It was pinned
in the code with a paragraph about NDI over a hall's Wi-Fi — sound reasoning for
the wire, and wrong for a phone recording to its own card. It is a decision, so
it is a setting: **720p / 1080p / 1440p / 4K UHD**, in the gear.

The list is built from what this phone's lenses **actually publish**, not from
numbers somebody typed, because a resolution a lens does not have is a session it
refuses and a black screen to diagnose. It decides the recording and the stream
together and takes effect when the camera next opens. The trace now prints every
16:9 width a lens offers, so the list can be checked against the phone.

### Both ways up

*"Please make this app work in portrait mode and landscape mode... like any basic
app should be on a phone."* Back to `fullSensor`, and the portrait branch of the
layout is back with it: rails down the sides in landscape, bands above and below
in portrait.

It was locked to landscape for two versions to get the rotation argument down to
one case while it was being solved. It is solved, so the lock has done its job.

---

## 22.9.2026, afternoon — v77, colour temperature, tungsten to daylight

He asked for the fader he had before: *"manual control for colour and light
temperature. Left side tungsten, right side daylight. This range please add."*

### Why this was not simply a slider

**v65 is the reason, and it is written into this repo's history.** White balance
went green twice, and the cause was structural rather than a bad number: *gains
are only half of white balance.* The other half is a colour correction matrix,
calibrated per sensor and per illuminant. Supplying our gains beside somebody
else's matrix leaves the two disagreeing, and on a Bayer sensor a disagreement
reads as **green**, because green is the channel with twice the samples. No
slider position undid it, because the slider was only moving one of the halves.

v65's answer was to give up the sweep and use the camera's six presets. That was
the right trade then, and it is still the fallback. But the conclusion drawn with
it — that the matrix *"is not something an app can compute"* — was too
pessimistic, and that is what has changed.

### What v77 does instead

A sensor that can produce a DNG **must publish its own calibration**: two
reference illuminants, and for each a colour matrix and a forward matrix,
measured by the people who built it. Interpolating between those two in **mired**
is what the DNG specification says to do and what every raw converter has always
done. Both halves then come out of *one* blend, from the sensor's own numbers,
so **they cannot disagree** — which is the only thing that was ever wrong.

`WhiteBalance.kt` is pure and under the purity gate beside `LogCurves` and
`CubeLut`. Fifteen tests, including the one that states the v65 bug outright:
*both halves come out of the same blend* — the gains must return the light that
the interpolated matrix says the sensor sees to the grey axis, exactly.

### The half-millimetre that is a green picture

Tungsten is a black body; **daylight is not.** The sky is lit by a filament and
scattered by air, and it sits measurably above the Planckian locus — about
**0.005 in y at 6500K, which is a green cast**. D50, D55 and D65 are points on
the *daylight* curve and not on the black-body one.

So the fader is on the Planckian locus at the warm end, the CIE daylight locus at
the cool end, and crossfades between them over 3500–4500K, because the two are
0.007 apart in y where they meet and a step that size part way along a fader is a
visible lurch in the picture's colour. Asserted at Standard A, D50, D55 and D65
to within 0.001, and asserted to have no step anywhere in its travel.

### The fader itself

A fifth row under FOCUS. **Left is tungsten, right is daylight**, 2000K to
10000K, travelling in **mired** — a hundred Kelvin at the warm end moves the knob
more than three times as far as a hundred Kelvin at the daylight end, which is
how the eye works and why every colour meter ever made reads in reciprocal
degrees.

**A tap on a row hands that parameter back to the camera, or takes it.** There is
no room on the rail for a key per parameter and there should not be one: the
place to say "you take this" about white balance is the white balance fader. It
works on ISO, SHUTTER and FOCUS too, from either end, alongside the keys they
already have.

The fader says which route it took. If the sensor publishes no calibration it
falls back to the nearest preset and says so on screen, rather than pretending to
a resolution it does not have. The temperature is put back after a lens change,
because a new session rebuilds the request from the template and would otherwise
revert to auto in silence.

*Needed from him:* **is it green anywhere?** Sweep it slowly end to end against
something white. The trace now names the route at open — `white balance: AWB
modes …, calibration published (2856K and 6504K), continuous yes` — so if it goes
green, that line and the per-change `gains` line say which half did it.

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
