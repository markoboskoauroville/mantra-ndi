# Momentary updates: what Marko asked on 27.9.2026, 10:54, after testing v89 on his Pixel

Written before any code, so that when the quota runs out and he says **"continue"**, the next
session starts here. Each item has a status line; update it as the work moves.

His two screenshots (not in this public repo, they show his face) are on the Mac at
`~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/c96e4e97-image.png` (the camera, portrait,
lens L5 24mm, FOCUS FIXED, i.e. the selfie lens, 3264x1836 10-bit HEVC, ISO 41 on M, SHTR 1/65 on A,
HM) and `.../d9576ff5-image.png` (the take `mantra-20260927-105404` playing in the phone's own player:
the picture is turned wrong).

## The requests, in his words and what they mean

### 1. BUG: a take shot in portrait plays upside down (180° off)

> "When I'm recording into the portrait mode, actual orientation of the file, it's opposite 180
> degrees of what I was shooting. Look at the screenshot. So you need to write right orientation in
> the file so it's not upside down when I'm playing inside the player."

Seen on the **selfie lens** (L5, fixed focus). The rotation written into the MP4 (the orientation
hint / display matrix) is wrong by 180° for that case. Check: the front camera's sensor orientation
(270 on a Pixel, the back is 90), the front camera's mirror, and whether the phone's rotation is added
or subtracted for front vs back. Must be right for every lens, both orientations, and both ways of
holding landscape. Test: record on the emulator with the front and back cameras in portrait and
landscape and read the rotation out of each file (`ffprobe` side data / `rotate` tag).

**Status:** done in **v90** (27.9.2026). `Mechanism.recordingRotation` → `MediaMuxer.setOrientationHint`.
Emulator: portrait take 90° (upright), landscape (display 270) 180°, both read back with ffprobe and
matching the monitor. The selfie lens is proven by unit tests only (the emulator has no front
camera): **his Pixel is the test**.

### 2. Shutter: presets in degrees under the fader

> "After the shutter, you need to put buttons. Slider stays, but now we have presets in degrees.
> 0 degrees, 90 degrees, 180 degrees, 270 degrees, and 360 degrees. And these presets are nudging
> the slider of shutter speed to the right shutter speed."

A row of five keys under the SHTR fader: **0° 90° 180° 270° 360°**. Shutter angle → exposure time =
angle / 360 × 1 / fps (at 25 fps: 90° = 1/100, 180° = 1/50, 270° = 1/33, 360° = 1/25). The fader
stays and moves to the value. A preset puts shutter on M. **0°** has no exposure time of its own
(zero light): it is read as the shortest shutter the sensor allows. Anything the sensor or the frame
rate cannot honour is clamped, and the fader shows where it landed.

**Status:** to do, v90.

### 3. White balance: A is a one-shot measurement, nothing is remembered

> "You should not remember my white balance, manual one, after I set to auto. So when I press auto,
> my slider will jump to the automatic position and then go back to the manual. I don't want to
> track white balance, this feature I don't need. When I press auto, it will just stay auto for a
> few seconds until it finds the white balance from the camera, and then it will switch back to
> manual automatically."

WB's A is no longer a mode. Pressing A: the camera's AWB runs for a few seconds until it settles,
the fader follows to that Kelvin, then WB goes back to **M** by itself and stays there. No stored
manual value comes back afterwards, and nothing tracks the light continuously. In AUTO mode (the M
key) WB behaves the same way: measured once, then held.

**Status:** done in **v90**. WB left the M key's AUTO/HM/FM count; the camera measures once on
opening; the probe waits for AWB converged (20 frames minimum, 90 maximum, 6 s deadline). Emulator:
started → 5003 K → M in 0.65 s.

### 4. ISO: presets at the camera's base ISO (and a second native ISO where there is one)

> "Under ISO, I want presets based on base ISO of the camera in question. So this is the point in
> camera which is the least noise. So if this, for this camera is 200 or 100, just give me preset.
> Maybe it has two. There are two cameras which has dual native ISO. And then I can just jump from
> highlight to low light."

Keys under the ISO fader, per lens. What Android publishes: `SENSOR_INFO_SENSITIVITY_RANGE` (its
lower end is the sensor's base, the cleanest ISO) and `SENSOR_MAX_ANALOG_SENSITIVITY` (above it the
gain is digital, more noise and nothing gained). Android does **not** publish a dual native ISO, so
the keys are **BASE** (the range's lower end) and **HIGH** (the top of analog gain: the low-light
preset), each showing its number. Say this truthfully to him. A preset puts ISO on M.

**Status:** done in **v90**. Emulator: BASE 100, HIGH 1600; BASE lit, ISO on M.

### 5. His general direction

> "We are bringing more features which are professional to this app. Please update with the
> requested features and fix the bugs."

## The plan after this

- **v90**: items 1–4 above.
- **v91**: the player inside the app (was v90), then scopes v92, settings with a floating preview
  v93, the GPU stage v94, log-aware auto exposure v95, the grey-card WB sweep v96, the Mac webcam v97,
  MANTRA_KELVIN v98, tracking focus v99. The phase document holds the details.

## Seen in his screenshots, not asked (for later)

- The status line at the top is unreadable over a bright sky (grey on white), and so is IRIS.
- He was on L5 and the M key said HM with ISO on M: half manual works on his Pixel.
- The recording drive shows 78.1 GB / 3:40 h: the storage key works on his phone.

---

# Second message, 27.9.2026, about 11:10: output routing, professional controls, core fixes

His text, kept whole so nothing is lost:

> **Feature Request: Output Routing, Professional Controls, and Core Fixes**
>
> **1. Previous Updates Integration.** Please read the 'momentary_updates.md' file. Implement all
> updates and architectural changes listed in that document before proceeding with the new features
> below.
>
> **2. Unified Output Routing and Settings Switchboard.** We are streamlining the application's output
> engine to support four distinct, parallel destinations. To declutter the main user interface,
> destination-specific configurations are moving into the Settings menu. The four supported output
> destinations are Stream to File (Local Recording), Stream over USB (OBS Camera), Stream via NDI, and
> Stream to YouTube. The Settings view will act as a routing switchboard featuring independent,
> collapsible activation switches for each destination. When a switch is turned on, its specific
> configuration panel expands. For NDI, this includes options to select between Full NDI and NDI HX.
> For the new YouTube channel, the expanded panel allows the user to enter all necessary stream
> details and authenticate directly from the application. When a destination is disabled, its
> settings remain hidden to keep the interface clean. The previous NDI toggle on the main UI must be
> removed entirely and integrated into this new switchboard.
>
> **3. Master Action Trigger and Telemetry.** Activating a destination in the Settings menu only arms
> that specific channel. Streaming or recording to any configured destination will not commence until
> the user presses the master Record button on the main interface. The user must be able to mix and
> match any simultaneous combination of the four destinations. For example, a user can arm YouTube and
> USB streaming while leaving Local Recording disabled, and both armed streams will start perfectly
> synchronized upon pressing Record. A new streaming status line must be integrated at the top of the
> main camera view. This telemetry overlay will provide real-time feedback on how the application is
> sending images, clearly indicating which of the four destinations are currently active and
> receiving data once the master Record button is engaged.
>
> **4. Professional Exposure Controls: Shutter and ISO Presets.** To enhance professional usability,
> we are introducing discrete preset buttons beneath the existing faders. For the Shutter, add a row
> of five preset buttons in degrees: 0°, 90°, 180°, 270°, and 360°. Selecting a preset will calculate
> the correct exposure time based on the current frame rate (e.g., 180° at 25fps sets the shutter to
> 1/50) and smoothly nudge the fader to that exact value, instantly switching the shutter to Manual
> (M) mode. The 0° preset will snap to the absolute shortest exposure time the sensor allows. Anything
> the sensor or frame rate cannot honor will be clamped, with the fader reflecting the final value.
> For ISO, add preset buttons beneath the fader based on the specific camera's hardware capabilities.
> Since the Android API does not explicitly publish dual native ISO, the presets will be labeled BASE
> (pulling the lowest clean ISO from the sensor's sensitivity range) and HIGH (pulling the maximum
> analog sensitivity before digital gain is applied). Selecting either will lock the ISO into Manual
> (M) mode and display its corresponding number.
>
> **5. One-Shot White Balance Measurement.** The White Balance Auto (A) mode is being redesigned into a
> one-shot measurement tool rather than a continuous tracking feature. When the user presses Auto, the
> camera's automatic white balance will run for a few seconds to evaluate the scene. Once it settles
> on a Kelvin value, the fader will jump to match that value, and the system will automatically lock
> back into Manual (M) mode. It will no longer remember or revert to previous manual settings, nor
> will it continuously track color temperature changes.
>
> **6. Critical Bug Fix: Portrait Orientation Matrix.** Recordings captured in portrait mode using the
> front-facing (selfie/L5) camera are currently playing upside down (180 degrees off) in standard
> media players. The application must accurately calculate and write the correct orientation hint
> (display matrix) into the MP4 file's metadata. This calculation must account for the specific
> sensor orientation (which differs between front and back cameras) and mirror configurations to
> ensure right-side-up playback across all lenses and device holding positions.
>
> **7. App Icon Update.** Update the application icon by modifying the existing asset. Do not create a
> new design or replace the base icon. Keep the original design exactly as it is, but add a distinct
> orange outline (using Mantra's UI color palette, e.g., #ff9800) strictly around two elements: the
> outer edge of the main camera body; the outer edge of the inner lens circle. Apply these stroke
> modifications directly to the existing vector/SVG files in the project repository.

## How it is split into versions (whole numbers, one confirmed step at a time)

| Version | What | Status |
|---|---|---|
| **v90** | Items 4, 5, 6, 7 (and 1–4 of the first message, which are the same things): shutter-angle presets, BASE / HIGH ISO presets, one-shot WB, the orientation written right into the MP4, the icon with the orange outlines | **released 27.9.2026, tested on the emulator** (monkey 90091 20,000 clean). One display fault (M key after a preset) fixed for v91 |
| **v91** | The switchboard in settings (File, USB, NDI, YouTube, each a switch that expands its panel; NDI HX / Full inside it); the NDI key leaves the main screen; the record key becomes the master trigger for every armed destination; a telemetry line at the top naming what is being sent. File and NDI wired first | **released 27.9.2026, tested on the emulator in portrait**: switchboard and panels; FILE + NDI HX started in one call and stopped together (118 frames saved); NDI alone; nothing armed says so; telemetry dim / white / red dot with numbers; monkey 91091 20,000 clean. FILE is armed by default so the key still records after the upgrade. Found and fixed for the next build: the status line said 0.0 fps when idle, and it now sits on a dark band too |
| **v92** | **USB (OBS camera)**: the picture to OBS on the Mac over the USB cable. Open question for him: which route OBS should receive (NDI over the USB tether already works; a plain SRT/RTMP feed into OBS's Media Source needs no plug-in; a true "OBS camera" source is the Mac companion of the old v97 plan) | to do, ask |
| **v93** | **YouTube**: RTMPS to YouTube's ingest with the stream key; "authenticate from the app" = Google sign-in with the YouTube Data API to create the broadcast and fetch the key. That needs a Google Cloud OAuth client for the app, which Marko has to create (a step for him) | to do, needs his OAuth client |
| v94 … | the in-app player, scopes, settings preview, the GPU stage, log AE, grey-card WB, KELVIN, tracking (the phase document, each moved on by the new versions) | later |

**Status of this second message:** saved 27.9.2026; v90 and v91 released the same day. **Next:
v92 USB and v93 YouTube, both waiting on a choice of his** (see the table): which route USB takes to
OBS, and whether YouTube starts with the stream key alone (no Google sign-in, works at once) or waits
for a Google Cloud OAuth client he creates for the sign-in.

**The icon (item 7):** the v88 drawing unchanged, plus two #FF9800 strokes, 2 units wide, one unit
inside the body's outer edge and the lens circle's outer edge (r 10.5), so the outline's outer side
is exactly the old edge and the symbol stays in the 42 box (`res/drawable/ic_launcher_foreground.xml`).
"Inner lens circle" was read as the lens (the dark ring's outer edge), not the small white glass.

## His answers, 27.9.2026 afternoon (after v91)

- **USB (OBS camera): the Mac companion app.** A small Mac app receives the phone over the USB cable
  and appears as a camera in OBS, Zoom and everything else (the old webcam plan; research in
  `NDI_LICENSING_AND_WEBCAM.md`). The biggest of the four; its own repository.
- **YouTube: the stream key first.** He pastes the key from YouTube Studio into the YouTube panel once;
  the record key goes live. Google sign-in can come later.

**Order, therefore (renumbered: v92 and v93 went to his icon corrections):** **v94 = YouTube by stream key** (all on the phone: an RTMPS publisher fed by the
same encoder as the file and NDI, with sound); **then USB, the phone's side of the Mac companion**
(and the companion app in its own repository) is **v95**. Swapped from the first split because YouTube is
phone-only and finishes first; the Mac app is the longer road.

## Third message, 27.9.2026, about 15:40 (after v92's icon)

1. **"Camera body should be gray. That's the rule of the icon."** Keep the drawing, the orange lens
   ring and the thin orange body outline; the body itself is grey, as on his launcher (light grey on
   the dark ground). **Status:** done in **v93** (#B4BABF).
2. **White balance, three screenshots from his Pixel** (L2 25 mm, ISO 50, 1/682, HLG 10-bit, 4K,
   outdoors in afternoon daylight, WB on M): the fader at the far right (value hidden under the
   screenshot thumbnail), at **4100 K**, and at **3200 K**. *"Let me know what has changed in the white
   balance in these images after I'm moving the slider."* Screenshots on the Mac at
   `~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/573d21fa-image.png`, `e4713b3d-image.png`,
   `aa18b794-image.png`. **Status: analysed 27.9.2026, a fault found, the fix needs his trace.**

   **What the pictures measure** (mean colour of the pavement, bottom of each frame; G = 1):
   | fader | whites R/G, B/G | mid-tones R/G, B/G | darks R/G, B/G |
   |---|---|---|---|
   | far right (6500 K; the number was under the thumbnail) | 1.00, 1.01 | 0.97, 1.06 | 0.90, 1.15 |
   | 4100 K | 1.00, 1.00 | 1.00, 0.98 | 0.96, 0.99 |
   | 3200 K | 1.01, 1.00 | 1.04, 0.99 | 1.03, 1.06 |
   Moving the fader **right made the picture a little bluer, left a little warmer**, a few percent at
   most, with a green-cyan lean at 4100 K. At the far right the top-left corner went **red-magenta**
   (R/G 1.91): a colour-shading fault at the edge of the lens.

   **What it should do** (our own maths, run on a white object in 5600 K daylight): 3200 K → strongly
   blue (B/G 1.72), 4100 K → blue (1.33), 5600 K → neutral, 6500 K → slightly warm (R/G 1.08). The
   convention of every camera: the number is the light you are correcting for, so tungsten on a
   daylight scene turns it blue. **Our gains are right; the Pixel is not putting them on the
   picture** in this mode (10-bit HLG, 4K). What little moved is most likely the colour matrix half
   alone, which moves the other way. The trace's `white balance readback` line (the gains and the
   correction mode the camera says it used) settles it: export the trace (settings → trace) after
   moving WB, or plug the phone in so the session can read it.
3. **"From now on, under the camera, write the version number in the icon, so in the icon I can already
   see what my version is."** A rule for every build: the build generates the icon with `vNN` painted
   under the camera from `appVersion`. **Status:** done in **v93** (and grey body, item 1): the icon is
   a template in `app/src/main/icon`, `build.gradle.kts` draws the number; seen on the emulator's
   launcher as "v93".

## Fourth message, 27.9.2026, evening

> "Please build the next version and write inside the settings when there is a version number. It
> is basically a hidden link. If I click on the version number, it will take me to the GitHub latest
> release APK page."

The version number in settings (top right, and at the bottom) opens the repository's latest release
page on GitHub, where the APK is. **Status:** done in **v94**: the number at the top right of settings
opens `https://github.com/markoboskoauroville/mantra-ndi/releases/latest`; proven on the emulator (a
tap started Chrome on that address). YouTube moves to v96.

## Fifth message, 27.9.2026, evening: the icon, literally

> "For the next icon and default icon, literally vectorize this image. Literally make the same camera
> shape. Literally make the same body color and just add a thin orange outline around the camera body
> and fill up this space between lens and camera with orange color. Literally."

His picture: `~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/f8c7d4c6-image.png` (the launcher's
themed rendering of our icon: a grey camera on a dark slate circle). To do: trace it — the same body
shape, the same grey, the same background colour — then a thin orange outline around the body, and the
dark ring between the lens glass and the body filled orange. The version number stays under the camera
(his earlier rule). **Status:** done in **v95**: traced against the 72-unit circle (the circle is 160
px in his picture): flash tab, chamfered corner, hump x 48.8–59.6 up to y 39.8, body x 36.7–71.8 y
42.5–66.8, lens centre (54.2, 55.1) ring r 6.7–9.6, grey #A8ADAF on slate #263238; orange ring, a
1-unit orange outline inside the body's edge; lifted 4 units for the number. Seen on the emulator's
launcher reading v95. Not yet done: the SNAP key on the rail still draws the v88 camera; it should
become this one.

## Sixth message, 27.9.2026, evening: the white balance trace

> "Here is the trace from the Google Pixel 7 phone and it doesn't work. Interestingly enough, on my
> Nothing Phone 2a, the white balance slider is working. Please diagnose what is the issue and what are
> specifics of Pixel Phone 7."

Trace: `~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/93abab3b-trace-2026-09-27-164207.txt`.
**Status: diagnosed; v96 released with the fix, to be proven on his Pixel.**

**What the trace shows.** Pixel 7, Android 16, v95, lens L1 = logical camera 0, physical lens 3 (the
ultra wide), 3840x2160 10-bit HEVC. The camera **accepted** manual white balance (readback: AWB mode 0
= off, correction mode 0 = transform matrix), but the gains it reported hardly moved and moved the
wrong way: asked 5812 K → red 2.340 / blue 1.645; 4768 K → 2.389 / 1.499; 3292 K → 2.328 / 1.487. For
3292 K our maths sends about red 1.44 / blue 2.34 (daylight turns blue). The probe at start also
clamped at 6500 K: the auto gains it anchored to did not fit this lens's calibration.

**What is special about the Pixel 7.** Its lenses are *physical sub-cameras* of one logical camera
(`[0:3]`, `[0:4]`, `[1:5]` in the trace; only the "fused" L2 and L5 are the logical cameras
themselves). The app routes the picture to the physical lens (`setPhysicalCameraId`), but every
colour key was set on the **logical** request only, and the anchor and readback were read from the
**logical** result, which can describe another sensor. A sub-camera keeps its own colour pipeline and
only reads keys set on it with `setPhysicalCameraKey`. The Nothing Phone (2a) exposes each lens as its
own camera, so a plain request reaches it, which is why the fader works there.

**v96:** the request is built naming the physical lens, every white balance key is set on the lens
too, the anchor / probe / readback come from the lens's own result, the gains sent are traced in full,
and eight frames later they are compared with what the lens used. If a lens still ignores them (the
fused logical camera in 10-bit HLG may be that case: his screenshots were on L2), the screen says so
and the fader switches to the camera's own presets instead of moving a number and not the picture.
Emulator: the check says "followed", no false alarm; monkey 96096 20,000 clean. **His test:** L1, L2
and L3 on the Pixel, WB from 3200 K to 6500 K; the trace's `white balance check` lines say which lens
followed.

## Seventh message, 27.9.2026, evening: "GPU stage and log exposure"

Asked "what is next upgrade" (answer: YouTube by stream key), he chose instead: **the GPU stage and log
exposure**. Order now: **v97 the GPU stage** (phase 4 of the phase document), **v98 automatic exposure
that knows the log curve** (phase 5); YouTube, the Mac companion and the rest move after them.
**Status:** **v97 released** (the GPU stage): `GpuStage.kt`; the take has its own encoder and bit rate,
the stream its own (settings: Picture path GPU STAGE / DIRECT, stream bit rate). Every output carries the
raw sensor orientation, so rotation is unchanged. 10-bit through the GPU only with GL_EXT_YUV_target +
RGBA1010102 + BT.2020 HLG surfaces (YUV read as numbers, Media3's matrix), else the direct path, traced.
Emulator (8-bit only): picture, take, FILE + NDI HX, monkey clean. CPU on the emulator 29 % vs 18 % direct
(emulated GL, two encoders) — **his Pixel gives the real figure; if the 10-bit colours look wrong there,
Settings → Picture path → DIRECT.** Not moved to the GPU yet, and why: the full-NDI YUV packing (it still
comes from its own camera reader), the focus and brightness samples (tiny reads of the preview). v98 next.

## Eighth message, 27.9.2026, 18:00: manual controls only work on L2

> "On pixel phone, manual controls now in GPU mode only works with lens 2. Other lenses no or little
> bit, I'm not sure, but there are not big changes at all."

His screenshots (v97): L2 25 mm FM, ISO 1013, 1/316, focus 0.18 m, WB 4600 K — works; L1 17 mm FM, ISO
1604, 1/1286 — the picture hardly answers. Files: `~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/`
`59af1afb-image.png`, `2e73f372-image.png`. **Diagnosis:** L2 (and L5) are the Pixel's logical cameras;
every other lens is a physical sub-camera, and v96 moved only the white balance keys onto the lens.
ISO, shutter, frame duration, focus, AE / AF modes and the tone curve were still set on the logical
request only. **Status:** fixed in v98 (with log exposure): every key the lens accepts per lens is
copied onto it at each request.

**v98 released, 27.9.2026 18:10:** (1) every key the camera lists as per-lens is copied onto the
physical lens at every request (ISO, shutter, frame duration, focus, AE / AF modes, tone curve, white
balance), and ISO / shutter / focus are read back from the lens; (2) **log-aware auto exposure**: while
exposure is automatic on a log curve, the focus box is read as a grey card twice a second and exposure
compensation steers grey onto the maker's number (S-Log3 41 %, V-Log 42 %, LogC3 39 %, LogC4 28 %, Film
Gen5 38 %), never hunting (one step only when it brings grey closer, step learnt from the last move);
the status line reads e.g. `grey 39% · S-Log3 41%`. Emulator: loop settled in one move at 39 % vs 41 %,
monkey clean. **Open on his phone:** the tone curve treats its input as display-referred; Camera2 says
linear. If grey sits far below target with compensation at its limit, that is the answer, and the curve
gets fixed next. **His test:** L1, L3, L4 in FM — ISO and shutter must now move the picture; the trace
line "lens N takes its own: …" lists what each lens accepts.


## Ninth message, 27.9.2026, evening: "let's develop a tracking focus"

Asked what is next (YouTube was proposed), he chose **tracking focus** (Phase 9 of the phase document).
**Status: v99 released, 27.9.2026 19:45.** AF key: AF → TRK → MF. In TRK the pinched focus box is the
pattern, a thin outer square the search zone; a tap sets it. GPU stage: luma copy (256 wide) of each
frame, NCC per search place in a fragment shader against a kept reference frame, scores (≤ 64×64) read
back, sub-pixel peak. The mark follows by the set fraction, refocuses past the tolerance, and turns red
with LOST below the confidence (stays put). Settings: search zone ×, frames between searches, confidence,
tolerance, follow speed. Tests: known shift to ⅓ px, exposure change + noise, flat / unrelated = lost,
screen↔sensor exact inverse. Emulator: LOST on a flat wall (correct), 1.000 on a window, monkey clean;
its scene does not move, so **following a moving subject is for his phone.** Found and fixed: the pattern
copy (glCopyTexSubImage2D) came back flat (LESSONS 9). Also fixed on the way: the AF region mapped the
screen through "front camera" instead of what the monitor shows (they differ since the GPU stage).
Needs the GPU stage (Settings → Picture path).

## 2.10.2026, 02:20 — white balance, calibrated against the G815's LEDs (asked in the G815_LIGHTS session)

> Also, I'm using my own app. This is my own camera NDI camera. If colors don't match, you need to also update my camera because white balancing is not functioning now. We can use opportunity with these LED lights which are the colors of the light pure spectrum to also calibrate my camera so it gives the true colors. Now white balance is a bit off, it's almost there but it's bit off. So fix my camera and fix my keyboard application.

- The camera looks down on the Logitech G815; the keyboard can show known pure colours per key (G815_LIGHTS).
  Use them as a reference to measure and correct the white balance. — **Status:** open (after the keyboard app)

> (2.10.2026, 02:55) Please also fix my NDI camera white balance algorithm using this keyboard so it needs to recognize the right colors. Please fix my app camera app, it's in repository and we have here our tester which is an emulator of Android. Now we can do it all here. Please let's fix it.

- MEASURED 2.10.2026 through scrcpy of his Pixel 7 (the camera looking down on the G815): keys sent magenta
  (ff00ff) show BLUE, white shows bluish, orange (ff6000) shows red, yellow shows olive. To his own eye the keys
  are right (magenta, white, orange), so the error is the camera's: too much blue, too little green/red balance.
  — **Status:** started 2.10.2026

> (2.10.2026, 03:10) now instead of using an emulator, you can use my real Pixel phone. It's now in debugging mode, and we're going to fix the camera. You can do anything through my screen copy because it's a full-featured touchscreen. Make the camera use the right white balance first. Test is with the keyboard, and then I give you precise light with precise colors, and we're going to make this white balance perfect

- The real Pixel 7 over adb + scrcpy, not the emulator. First the right white balance, tested against the G815;
  then Marko gives a precise light with precise colours and it is made perfect. — **Status:** started
- Diagnosis (read-only study, 2.10.2026): "A" is the Pixel's whole-frame AWB (no region of its own,
  CONTROL_AWB_REGIONS never set) and it neutralises the warm room lamp, so the self-lit LEDs read blue;
  anchorKelvin clamps at 3200 K (WhiteBalance.kt:48-49); LEDs over-exposed so red clips and CCM crosstalk
  eats orange's green. Plan: A becomes a spot white balance on the focus box (sampleGreyCard,
  MainActivity.kt:1331, to return mean R G B, linearised, clipped pixels rejected; gains iterated until
  R=G=B in the box), Kelvin range widened to ~2500-10000 K.

- **v100 (2.10.2026):** A with the focus box out = the camera's own measurement, then THE SPOT: the box's lit,
  unclipped pixels read in linear light, red and blue gain moved by G/R and G/B, up to 5 rounds until the box
  is neutral (±2 %); the result becomes the fader's anchor. With the box put away, A is as before.
  — **Status:** built, unit-tested (3 new tests); to be tried on his Pixel against a white G815 key

## 2.10.2026, 03:45 — exposure circle, white balance rectangle, flashlight, settings reorganised

> And also you need to build new features, which is a circle in the image, which is actually point of exposure, automatic exposure. So I can limit circle is exposure and the square is focus, as is it now. And the size of both of them, default size, can be set in settings. And also at the top of the settings, put one icon with the flashlight, and that icon is turning on the flash. I don't want it to take over my user interface, but under settings can be the first thing. So the first thing important in the settings are something I'm using all the time, and that's the settings of the first focus size, then this round exposure window, and then light. After that we have 8-bit/10-bit switch, and after that we have a resolution. And we need to remove interface orientation from the settings. It, it can be always at 0, so we can remove that part. And let's reorganize the settings and add the new features after white balance is done.Also, to the white balance, we need to add a 3rd rectangle, and that's the white balance rectangle. So I can point that rectangle or anything gray or white in the scene, tap on it, and it will, it will do white balancing.

1. White balance first (v100 = the spot on the focus box; being tested). — **Status:** in test
2. A THIRD shape, the WHITE BALANCE RECTANGLE: point it at anything grey or white, tap it, and it white-balances
   on what is inside it (replaces v100's use of the focus box). — **Status:** open, next
3. A CIRCLE = the exposure point (auto exposure meters inside it); the square stays focus. — **Status:** open
4. Settings: default size of the focus square and of the exposure circle. — **Status:** open
5. Settings, at the very top: a flashlight icon that turns the torch on (not on the main screen). — **Status:** open
6. Settings order: focus size, exposure circle size, light; then 8-bit/10-bit; then resolution. — **Status:** open
7. Remove "interface orientation" from settings; always 0. — **Status:** open

## 2.10.2026, 03:55 — three shapes, three functions, and their keys on the left

> But since I want to distinguish the shapes on the screen, we should be very different. So we have the same as visual languages Android phone. So the square is circle will be the exposure and triangle will be the white balance. So we have 3 different shapes in the in the, in this app for the 3 different functions. And since they are so important, you need to put their, their shapes, icons, on the left or right side of the user interface. I would say left side, left side. And they will have 2 states. Uh, one state is grayed out, it means it's not showing on the screen. Second state is white, it means it's shown on the screen. And third state is orange, which means the pinch action is changing the size of that one. So this orange is toggle, so only one I can change with pinching action, only one of those guys. And same for focus, for white balance. And the gray balance or white balance action is tapping. So when I tap, it reads and it locks. The whole app doesn't have any tracking at all. It just— user needs to tap, it reads, it probes the right value, and then it stays there. And the same visual language as for focusing. While searching, it's orange. When it's locked, then it's green.

8. Three shapes: SQUARE = focus, CIRCLE = exposure, TRIANGLE = white balance (replaces item 2's rectangle). — **Status:** v101 = the triangle; the circle with item 3
9. Their three icons on the LEFT rail. Grey = not on the picture; white = on the picture; orange = the one a
   pinch resizes (only one at a time, a toggle). — **Status:** open (v102)
10. White balance is a tap: it reads, probes, LOCKS and stays — nothing tracks. Orange while searching, green
    when locked, as the focus square. — **Status:** v101

## 2.10.2026, 04:05 — a feature that freezes another phone says "not available" and goes back

> Also, one more time, one more thing, because this app is compatible with Pixel 7 mostly, and on other phones it can freeze when using some features. And map should detect freezing and say just to user feature not supported and not freeze. So we have this issue on Nothing Phone 2A when I change to low curves, then it freezes. I think we solved this problem, but check the MDs, what was with that. And if we, if we can apply this feature instead of freezing the screen, just to say this feature is not available and go back to the old settings.

11. A freeze watchdog: when a change (e.g. a log curve on the Nothing Phone 2a) stops the picture, say "this
    feature is not available on this phone" and go back to the settings before it. Check the MDs for what was
    done about the Nothing Phone's log-curve freeze. — **Status:** open (after the white balance test)

## 2.10.2026, 04:15 — one L key for the lenses

> This lenses chooser is taking too much space, so we should simply— L, only one icon. When user press L, they uncollapse. User choose one lens and then it collapse again. So then we have space for our square and triangle.

12. The lens keys L1..L5 fold into ONE key, L: a press unfolds them, choosing a lens folds them again; the room
    goes to the square, circle and triangle keys. — **Status:** open (with the rail keys, item 9)

- **MEASURED v102 on his Pixel, 2.10.2026 03:51** (keyboard all white, triangle on it, one tap):
  r/g 1.724 → 1.372 → 0.947 → 1.009, b/g 0.764 → 0.897 → 1.004 → 1.011, "locked: neutral after 3 steps".
  Pure colours afterwards through the camera: red, green, cyan, blue right; orange pale (legends clipped),
  yellow leans green, magenta violet. Next: his precise light, and exposure for the LEDs. — **Status:** item 1
  and 10 done for the white; colour accuracy of saturated colours open
- **Re-measured 03:52 with truly white keys** (the first run had his orange M2 bank under the triangle — he had
  switched banks): locked neutral after 6 steps (r/g 1.018, b/g 0.998). Through the camera then: red, orange,
  yellow, green, cyan, blue, magenta and white all read as themselves. — **Status: white balance DONE.**
- **v103 (2.10.2026):** the three marks — square focus, CIRCLE exposure (tap: meters there, locks; orange then
  green), TRIANGLE white balance — and their keys on the left rail drawn as the marks: grey off the picture,
  white on it, orange = the one a pinch resizes; a tap steps grey → white → orange → grey. One L key (whisper:
  the lens in use) opens the lens keys and choosing one closes them. Items 3, 8, 9, 12. — **Status:** built
- **v104 (2.10.2026):** settings start with the light (a torch icon at the top: grey off, green on, kept on
  while the camera runs), then the sizes of the focus square, the exposure circle and the triangle, then
  8-bit/10-bit and resolution; "Turn the preview" removed, the turn always 0. The exposure circle waits at least
  6 frames before it believes "settled" (v103 locked after 1). THE FREEZE WATCH FOR EVERYTHING: the request that
  last made a frame is kept; frames stopping 1.2 s after any change put it back and the screen says "That
  setting is not available on this phone — put back as it was" (items 4, 5, 6, 7, 11). Not provable on the
  Pixel (nothing freezes there); the curve's own v83 watch, proven on the Nothing, stays. — **Status:** built

## 2.10.2026, 04:22 — a tap anywhere is for the armed shape, also in full screen

> So when we are talking about red triangle, red square, and red circle, the one which is in pinch action, also in the full screen when I remove everything, the tap still works. So tapping is always 2 actions: it moves the shape to the position and do automatic correction and then locks.

13. The armed shape (orange key — "red" in his words — the one the pinch resizes) is also the one a tap on the
    picture serves: the tap MOVES that shape to where it landed, runs its automatic correction (focus, exposure
    or white balance) and LOCKS. The same in FULL screen, where nothing is drawn. — **Status:** v105
- **v105 (2.10.2026):** a tap anywhere on the picture serves the armed (orange) shape — square focus, circle exposure, triangle white balance — moving it there, correcting, locking; in the clean feed and under CTRL too (item 13). — **Status:** built

## 2.10.2026, 04:30 — hide the outputs line; the status line names the armed shape and says where it locked

> Also, you need to give me toggle in the settings to hide the streaming status display, which is USB, NDI, file, and so on. And always when the active triangle is there— triangle, square, circle— at the top status line, you need to write exposure circle. white balance triangle, focusing square, and then when it reads the value and locks the shape in the top status, you need to write exact number where is it locked, on which focus distance, what is the white balance color, and what is the exposure level.

> after everything is done, please remind me to plug into the screen copy my notiphone 2a so you can freeze it for sure with some functions

14. A settings switch that hides the outputs line (FILE USB NDI YT). — **Status:** v106
15. The top status line names the armed shape ("focusing square", "exposure circle", "white balance triangle")
    and, when it locks, the exact value: focus distance, white balance (K and gains), exposure (ISO, shutter, EV).
    — **Status:** v106
16. At the end: remind Marko to plug the Nothing Phone 2a into the screen copy, to test the freeze watch on a
    phone that really freezes. — **Status:** reminder pending
- **v106 (2.10.2026):** settings switch SHOW ON THE CAMERA for the outputs line; the status line starts with the armed shape (FOCUSING SQUARE / EXPOSURE CIRCLE / WHITE BALANCE TRIANGLE) and, once locked, its numbers: focus distance in m and dioptres, ISO + shutter + EV, K + gains (items 14, 15). — **Status:** built

## 2.10.2026, 04:50 — the rail keys wear the colour of their shape

> please add another feature so the side buttons of circle square, triangle should follow the colors on the screen. They should be in sync with the same icons. Of course, when it's off, it's grayed out, so let them be in sync

17. The □ ○ △ keys take the colour their shape has on the picture at that moment (white idle, orange
    searching, green locked, red failed); grey when the shape is off. The armed one (the pinch's and the tap's)
    is then told by a bar under its key, since orange now means "searching". — **Status:** v107
- **v107 (2.10.2026):** the □ ○ △ keys wear their shape's colour of the moment (white, orange searching, green locked, red failed), grey when off; a bar under the armed one (item 17). — **Status:** proven on his Pixel 04:44 (white → orange with the bar → green; the circle locked after 34 frames)

## 2.10.2026, 04:55 — letters inside the shapes

> you need to write mnemonics inside the shapes. So first square is F, then the second circle is E, and third one triangle is W

18. A letter inside each mark key — PROVEN v108 on his Pixel 04:58: F in the square (focus), E in the circle (exposure), W in the triangle (white
    balance), in the key's own colour. — **Status:** v108 (the rail keys; the shapes on the picture stay empty so
    they cover nothing of the subject, unless he asks)

## 2.10.2026, 05:00 — the triangle filled instead of W

> w doesn't look good because it's smaller. Instead of w, just put fill the triangle inside with white colorOr

19. No W: the triangle key is a FILLED triangle (white when idle; it keeps following its shape's colour —
    orange searching, green locked, grey off — so the keys stay in sync, item 17). F and E stay. — **Status:** proven v109 on his Pixel 05:05

> (05:08) result is not good. The idea is that the triangle has a fill which is white, but the outline changes its color between green, white, and yellow. Now the whole triangle is green. What's going on

20. Correction of 19: the triangle's FILL is always white; only its OUTLINE follows the shape's colour (white,
    orange, green, red). Off: grey outline and a grey fill. — **Status:** v110
    — v110 PROVEN 05:12 on his Pixel: white inside, outline orange while reading, green locked.

## 2.10.2026, 05:12 — the torch, and the top of settings as one line of toggles

> so the torch is actually not working, and in the torch line next to the settings we need to add the basic parameters of the camera. That's the frame rate, bit depth, resolution. Right there at the top, and all buttons now will be toggle. So we need only one button per setting, and focus square, exposure circle, and white balance triangle. I am missing the icon behind each on the left side of each, and everything can fit in one line because the size will be a drop-down menu. You go by 25% that's the scale. Every 25% you add value inside the drop-down menu

21. The torch does not work for him: find out why and fix. — **Status:** investigating
22. Settings' top line, beside the torch: FRAME RATE, BIT DEPTH, RESOLUTION, each ONE toggle button (a tap steps
    to the next value). — **Status:** v111
23. Focus square, exposure circle, white balance triangle: their shape icon on the left of each, all three in ONE
    line, the size a drop-down in steps of 25 % (25, 50, 75, 100). — **Status:** v111

## 2.10.2026, 05:15 — settings as quick toggles above tabs; the status line takes the free space

> when my outputs are gone, the status line has a gap. Top status line, it needs to move always aligning to or using the free space available to go to the edge of the screen also, we need to better organize the settings in sections and name sections accordingly. This settings title is not needed in settings. We can use this real estate more, so we remove this text and see the sections. For example, when we use organize it better, simply let's say for example display. So on the display, we can have picking, we can have hiding the streaming destinations. Anything that was shown on display should be under display. Then camera settings, all related to the camera. So the idea is in the area where there is this torch and when there is this settings text which is going to be deleted, what I say there will be toggles for adjusting camera settings. These are quick settings, and down there are better organized settings

> and I prefer to tab the interface rather than scrolling. One, so organizing settings in tabs. Above these tabs there is a quick settings toggles, and that's our new user interface for settings

24. The status line moves up into the outputs line's place when that is hidden (no gap). — **Status:** v111
25. No "SETTINGS" title. At the top: QUICK SETTINGS, one line of toggles (torch, frame rate, bit depth,
    resolution), and the three marks with their icons and 25 % size drop-downs (items 22, 23). — **Status:** v111
26. Below: TABS instead of scrolling, sections named by what they are about: DISPLAY (everything shown on the
    screen: peaking, zebra, the outputs line…), CAMERA (everything about the camera), and the rest by subject.
    — **Status:** v111
- **v111 (2.10.2026):** settings rebuilt: no title; QUICK row (torch that lights at once through the camera manager, FPS / BIT / RESOLUTION toggles, MIN, version); the three marks' icons with 25 % size drop-downs in one line; TABS CAMERA · OUTPUTS · DISPLAY · MORE. Camera screen: the status line spans the width and moves up when the outputs line is hidden (items 21–26). — **Status:** proven on his Pixel 05:10: the torch lights from settings at once (Android: "Torch for camera id 0 turned on") and stays on in the camera; tabs, toggles and drop-downs shown; status line at the top with no gap. Why the torch "did not work": the camera is closed while settings shows, so v104 lit it only after returning

## 2.10.2026, 05:12 — bit rates under CAMERA, named by what they are for; margins

> bit rate should be part of the camera settings. Don't call it bitrate at all, because I can see it says megabits per second. That's the bitrate. Just say recording and streaming, and by the number I understand what it does

> also, if you look at settings a graphical design, we can see that there is no margin between left and right side. So I'm looking at output and file touching the screen. The toggle switch is just glued to the right margin. Please adjust margins so they look decent, a few pixels

27. The two bit rates move to the CAMERA tab, labelled RECORDING and STREAMING (the number says Mbit/s). — **Status:** v112
28. Settings margins: nothing touches the screen's edges; switches not glued to the right. — **Status:** v112
- **v112 (2.10.2026):** RECORDING and STREAMING (the two bit rates) open the CAMERA tab; settings margins 28 dp inside the bars' insets (items 27, 28). — **Status:** proven on his Pixel 05:21 (CAMERA opens with RECORDING 50 Mbit/s and STREAMING 16 Mbit/s; OUTPUTS and FILE inset, switches off the edge)

## 2.10.2026, 05:25 — the sister app: an NDI monitor that remote-controls this camera

> After building this, please make a sister app. A sister app is just one NDI monitor which can connect to this camera when NDI is enabled, and then user choose this NDI stream. So user can choose NDI stream very simple, double tapping in the middle of the screen. It reads all the streams from the network And user can choose this camera, and then this camera is just giving all the same interface to remote user, but nothing is recorded. Everything is remote controlling. So let's build NDI monitor for this camera and any, and any other NDI stream in the network. This camera is just one of NDI streams, but since we have its code, we can automatically go to remote controlling mode.

29. A SISTER APP: an NDI monitor for any NDI source on the network; a double tap in the middle lists the sources
    and one is chosen. When the source is this camera, the monitor shows the camera's whole interface and drives
    it remotely (nothing is recorded on the monitor). REBUILD.md's Phase 4 (remote control over NDI metadata) and
    Phase 5 (monitor). — **Status:** open; platform to confirm with Marko
