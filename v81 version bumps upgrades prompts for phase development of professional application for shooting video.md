# v81: version bumps, upgrade prompts for the phased development of a professional application for shooting video

**Written 26.9.2026, at v81 (the clean feed).** Marko asked for a large update of the NDI camera
(MANTRA_NDI) in one message: fourteen features and fixes. This document divides the work into
phases. **Each phase is one version bump.** Each is tested on the Pixel 7 emulator and against the
manifest's four tests before it reaches him, and **the next phase starts only when he confirms that
this one works on his phone.**

Every phase below has three parts:

- **The prompt.** The words that start the phase, written so any session can pick it up cold.
- **Done means.** What he checks on his phone.
- **Tested before delivery.** What the session proves first on `Pixel_7_API_35`, with the rest of the
  manifest's gates (four-tests.md, delivery-gate.md G6).

## The forecast

| Phase | Version | What it is |
|---|---|---|
| 1 | **v82** | The screen: pinch the focus box, tap to focus when the box is hidden, the F-stop in full screen, no toast, only real lenses, peaking works, WB double tap reads the camera |
| 2 | **v83** | Settings with a live floating preview; screen rotation moves into settings |
| 3 | **v84** | GPU instead of CPU, everywhere it can be |
| 4 | **v85** | Automatic exposure that knows the log curve (the manufacturers' grey targets) |
| 5 | **v86** | The WB key: a grey-card sweep across every temperature, snapping to the most neutral |
| 6 | **v87** | HEVC for every recording, and 10-bit over NDI HX and full NDI |
| 7 | **v88** | The phone as a webcam for the MacBook Pro, clean, over USB |
| 8 | **v89** | The sister app (MANTRA_KELVIN v1) calibrates colour temperature; NDI camera imports it |
| 9 | **v90** | Tracking focus: a pattern and a search zone, on the GPU |

**Forecast: the last version is v90**, provided every phase passes on the first build. The
versioning rule (MANTRA_MANIFEST `modules/versioning.md`) gives every change a new number, so a fix
round inside a phase takes the next number and every later phase moves up by one. Example: if Phase 1
needs a second build, it becomes v83 and the forecast becomes v91. The table is updated when that
happens.

## The rules every phase follows

- **The APK is built by GitHub Actions** (`android-app.md` §1a). Unit tests and a compile check run
  on the Mac first, then a push, then CI signs and publishes the release. The emulator installs the
  CI artefact, never a desk build.
- **The emulator is the phone that is always there** (`~/.claude/CLAUDE.md`). Every phase is installed
  on `Pixel_7_API_35`, driven with `adb` (taps, pinches, rotations), and screenshotted. The session
  says plainly what the emulator cannot prove: the feel of a pinch under a thumb, a real sensor's
  white balance and noise, a real GPU's shader compiler, the NDI and USB links to the Mac.
- **The four tests** (`four-tests.md`): the mechanism alone (JUnit on the pure-Kotlin `Mechanism`
  file), the running app driven like a person, the ugly cases, and **the upgrade from v81 with its
  settings intact**. Then G6: a monkey run with a fixed seed, plus a soak.
- **Whole-number versions**, the number in `gradle.properties` only, the release `vN`, two kept.
- **Documents in the same turn**: `WHERE_WE_ARE.md`, `HANDOVER.md` in the repo, the hub's
  `HANDOVER.md`, `SESSIONS/<date>.md`, `~/Downloads/API/PROJECTS.md`.

---

## Phase 1: the screen (v82)

**The prompt.**

> MANTRA_NDI Phase 1. Seven changes, all on the camera screen.
> 1. **Pinch the focus rectangle** to make it bigger or smaller, continuously, from tiny (about the
>    size of an eye) to the whole picture. The size is remembered between runs and it sets the
>    area the camera focuses on. (Until v81 the camera was always
>    given a fixed patch of 16% of the frame whatever size the box was drawn, and a tap
>    was not turned for portrait or upside-down landscape; both are fixed here.)
> 2. **Tap to focus even when the rectangle is invisible.** When CTRL's zones are up, when the box is
>    hidden, and in the FULL clean feed, one tap on the picture focuses on that point. It stays a
>    single tap, so it must not steal the zones' drags or the double taps. In FULL, the double tap
>    still means "come back".
> 3. **Bug: in full screen the F-stop stays on the screen** while the manual controls are hidden.
>    Nothing but the picture may stay.
> 4. **Remove the toast** "Clean feed — double tap to come back". I know it.
> 5. **Lens keys only for the lenses this phone has.** L1 to L4 were always drawn with the missing
>    ones dark. Draw only as many as exist.
> 6. **Bug: peaking says "This phone will not run the preview shader"** on the Pixel 7 and the
>    Nothing Phone. It must work on both.
> 7. **Double tap on WB in manual mode** probes what the camera itself measures as the correct
>    white balance, then snaps the fader to that number and stays manual. (Written but never
>    committed in the v81 tree; finish and ship it. A long press hands WB back to continuous auto.)

**Done means.** Pinch, and the box grows and shrinks smoothly from tiny to full screen, and it is still
that size after a restart. With CTRL on, one tap focuses where he tapped. In FULL, one tap focuses and
a double tap comes back, with no toast and no F-stop. A phone with three lenses shows L1 to L3. PEAK
shows dots with no LUT loaded and with one. Double tap on WB shows "Reading the camera's white
balance…", then a Kelvin number, and the picture does not jump.

**Tested before delivery.** A JUnit test of the box-size arithmetic and the tap-versus-drag rule.
On the emulator: a pinch driven through `adb` (two-finger `input` events or `sendevent`), a
screenshot at each end; FULL entered with no toast and no iris line in the view dump
(`uiautomator dump`); PEAK on with no LUT gives a screenshot with coloured edges; lens keys counted
against the camera's own list (`dumpsys media.camera`); upgrade from v81 with settings kept; monkey
with 20,000 events.

**What was found while writing this.** The peaking fault is not a phone fault. `PreviewEffects.apply`
returns "refused" whenever **no LUT is loaded**, because the shader's LUT input is only filled from
an uploaded cube, so peaking alone fails on every phone. The F-stop fault is the once-a-second
refresh (`refreshZones`) switching the iris line back on without asking whether the screen is in
full mode.

---

## Phase 2: settings with a live floating preview, rotation moves there (v83)

**The prompt.**

> MANTRA_NDI Phase 2. **Screen rotation (ROT) leaves the main rail and goes into settings.** It is a
> test feature, but I want to keep my rotation. Inside settings, show a **tiny live preview of the
> camera as a floating overlay window**, the way YouTube or MX Player shrink the video into a
> corner, so when I change rotation, resolution, frame rate, bit depth or bit rate I see the change
> at once. It can be dragged to another corner. Leaving settings brings the full picture back
> without the camera closing.

**Done means.** No ROT key on the rail. The gear opens settings; the camera keeps running in a small
window over them; ROT in settings turns it at once; a new resolution or depth rebuilds the session
and the window shows the new picture; NDI and a running take are not interrupted by opening settings.

**Tested before delivery.** On the emulator: open settings, screenshot the window, press ROT four
times and screenshot each; change resolution and read the status line; drag the window; rotate the
phone with settings open. Upgrade from v82 keeps the per-lens rotation. Monkey inside settings.

**How.** Settings become a panel inside the camera screen rather than a second activity. A second
activity pauses the first, and a paused camera screen closes the camera, which is why there can be no
preview there today. The picture shrinks into a corner of the same window, so the camera session is
never torn down.

---

## Phase 3: the GPU instead of the CPU (v84)

**The prompt.**

> MANTRA_NDI Phase 3. **Anything this app does on the CPU that can be done on the GPU moves to the
> GPU.** Audit every per-frame path: full NDI frame packing, the sharpness measurement the focus
> director reads, the histogram and scopes, the LUT and peaking (already a GPU shader), white-balance
> analysis. Move each one that is per-frame work to shaders (GLES 3 or Vulkan compute, or
> RenderEffect/AGSL for the monitor). Measure CPU load and temperature before and after on the
> emulator and on my phone, and tell me what stayed on the CPU and why.

**Done means.** The trace shows each path and where it runs. CPU use and the phone's temperature after
ten minutes of full NDI plus recording are lower than v83 on the same phone, with the numbers written
down (G7: budgets).

**Tested before delivery.** Before/after CPU (`dumpsys cpuinfo`, `top -H`) for a 10-minute soak on the
emulator, frame rate unchanged, the picture compared pixel by pixel between the CPU and GPU paths on
a still frame (the conversion must be exact, not "close").

**Worth knowing.** Most of this app is already on dedicated silicon: the ISP does demosaic, denoise
and the log curve; the hardware encoder does HEVC; the monitor LUT and peaking are one GPU shader.
The CPU work left is mainly the full NDI path copying YUV planes, and whatever measures sharpness and
colour from the preview. This phase builds the GPU stage that Phases 5, 6 and 9 reuse.

---

## Phase 4: automatic exposure that knows the log curve (v85)

**The prompt.**

> MANTRA_NDI Phase 4. Research, and check by calculation, the **correct exposure for each log curve
> built in** (Rec.709, S-Log3, V-Log, LogC3, LogC4, Blackmagic Film Gen5), from the manufacturers'
> own specifications: where 18% grey and 90% white should land. Then **make automatic exposure
> expose for the chosen curve**, so choosing a log changes where auto exposure puts the picture,
> exactly as the manufacturer specifies for correct exposure.

**Done means.** With a grey card filling the box, auto exposure in each curve lands grey on that
curve's published code value (for example S-Log3 41% IRE / 420 of 1023, LogC3 ~39%, V-Log 42%), read
off the histogram or the on-screen grey readout, within a stated tolerance.

**Tested before delivery.** Test 1: `LogCurves.middleGrey` against each manufacturer's published
number (table in the repo with sources). The exposure-compensation arithmetic as a pure function. On
the emulator: switch curves and read the AE target and compensation from the capture result in the
trace. What only his phone proves: the real sensor landing on the card.

**Worth knowing.** Camera2 auto exposure aims at a fixed brightness. The lever is
`CONTROL_AE_EXPOSURE_COMPENSATION` (in the phone's steps, usually 1/3 or 1/6 stop), or, where it is
too coarse, a closed loop that meters the box and drives manual ISO/shutter to the target.

---

## Phase 5: the WB key, a grey-card sweep (v86)

**The prompt.**

> MANTRA_NDI Phase 5. A new key, **WB**. I point the camera at a grey card, a white wall, or a grey
> shirt and press it. The camera takes a snapshot of that picture, **sweeps through every white
> balance temperature the sensor offers**, and finds the one where R, G and B are most in balance.
> I watch the numbers run on screen, and at the end it snaps back to the most probable one and stays
> manual. (The double tap on the WB zone from Phase 1 remains the quick "ask the camera once".)

**Done means.** Press WB and the fader visibly runs from tungsten to daylight with the number counting,
then settles; the patch reads neutral (the trace prints R:G:B at the winner); pressing twice gives
the same answer within 50 K.

**Tested before delivery.** Test 1: the neutrality score and the search (coarse sweep, then fine
around the best) against synthetic patches with a known answer. On the emulator: the sweep's numbers
in the trace. Ugly cases: a coloured card (it must say "not neutral enough", not invent a number),
too dark, clipped, pressed during a sweep, lens changed during a sweep.

**Worth knowing.** The sweep applies each temperature through the existing anchor (WhiteBalance.kt)
and measures the chosen patch on the GPU (Phase 3). One frame per step, so a 40-step coarse sweep plus
a fine one takes about two seconds at 30 fps.

---

## Phase 6: HEVC for every recording, and 10-bit NDI HX and full (v87)

**The prompt.**

> MANTRA_NDI Phase 6. **Enforce HEVC for every recording**, 8-bit included, so files are stored in
> the more efficient codec; fall back to H.264 only on a phone that has no HEVC encoder, and say so.
> Then **enable 10-bit streaming over NDI HX and full NDI**: HX sends HEVC Main10, full NDI sends a
> 10-bit frame (P216) instead of 8-bit I420.

**Done means.** Every take is `hvc1`, and `ffprobe` on the Mac reads `hevc`, `yuv420p10le` when 10-bit
is set. The NDI receiver on the Mac (NDI Studio Monitor or MANTRA_NDI_MONITOR) reports 10-bit for HX
and for full.

**Tested before delivery.** `ffprobe` of a take pulled off the emulator; the recording bit rate as a
setting apart from the wire. Ugly cases: HEVC refused, disk full mid-take, NDI switched mid-take.

**Worth knowing.** Today 8-bit recording uses H.264 because the encoder is shared with the wire.
And the handover records that on the Pixel 7, **the camera session refused 10-bit together with the
full-NDI reader**, so 10-bit full NDI needs the GPU stage from Phase 3 (camera → GPU → P216), not a
second camera output. If the phone still refuses, the phase says so with the trace.

---

## Phase 7: the phone as a webcam for the MacBook Pro (v88)

**The prompt.**

> MANTRA_NDI Phase 7. I want the **clean output of this camera as a webcam on my MacBook Pro** over
> the USB cable, so I can pick it in Zoom. In settings I enable Webcam, plug in, and the Mac sees it.
> Build it in the best possible quality, from 10-bit 4K downwards, and tell me every setting I need
> on the phone and on the Mac.

**Done means.** With the cable in and Webcam on, Zoom on the Mac lists a camera that shows the clean
feed (no rails, the LUT if chosen), at the best size Zoom accepts, and it survives unplugging and
plugging back in.

**Tested before delivery.** The emulator stands in for the phone (its network reaches the Mac);
the Mac side is tested with Photo Booth, QuickTime and Zoom.

**Worth knowing, researched before the phase, not after** (and already verified on the bench at v71, see PROJECTS.md). Android 14's built-in "USB webcam" mode
(Pixel) is a system service that opens the camera itself. **It cannot carry another app's picture**, so
it would show the plain camera, not this app's clean feed. The honest routes:
1. **A Mac receiver we build**: a macOS Camera Extension (CoreMediaIO) that takes this app's stream
   over the USB link (USB tethering or `adb forward`) and presents it as "MANTRA Camera" to Zoom. This
   gives the best quality and our own control. It needs signing with an Apple Developer ID for a
   system extension, which is **the question for Marko**: is there a developer account?
2. **With no new Mac code**: NDI over the USB tether into OBS (DistroAV NDI plugin), then OBS Virtual
   Camera into Zoom. It works today, but OBS has to run.
Zoom sends 1080p at most, so 4K 10-bit matters for the Mac's own recording, not for the call. The
phase says this plainly.

---

## Phase 8: the sister app calibrates colour temperature (MANTRA_KELVIN v1, NDI camera v89)

**The prompt.**

> A sister app for the NDI camera, its own repository, **MANTRA_KELVIN**. It calibrates the NDI
> camera's white-balance fader to real light temperature. I have a calibrated light source. I set it
> to a value, enter that value in the app, and the app records the camera's reading as a calibration
> point. I add as many points as I want, then export a calibration file. I import it in the NDI
> camera, and from then on the fader reads exactly what my light meter reads. The sister app has
> the exposure controls: ISO and shutter speed (aperture where a lens has it; phones do not).

**Done means.** Three or more points (for example 3200, 4300 and 5600 K) are entered and exported as
`<phone>-<lens>.kelvin.json`. Imported into NDI camera, the fader at each calibrated light reads
that light's number, and between points it interpolates. The calibration is per lens and per phone
and says so.

**Tested before delivery.** Test 1: the interpolation (monotone, exact at the points, refuses one
point and non-monotone input). The file's round trip. On the emulator: export from MANTRA_KELVIN,
import in NDI camera, the fader's number checked. Upgrade: a phone with no calibration behaves as
v88.

**How a point is measured.** At each reference light the app locks exposure, reads the camera's own
neutral answer (AWB gains and colour matrix, the same anchor the NDI camera uses) over a grey card,
and stores (reference K, the camera's gains, its estimated K). The NDI camera's fader then maps
"true K" to the gains the camera needs.

---

## Phase 9: tracking focus (v90)

**The prompt.**

> MANTRA_NDI Phase 9. **Focus that tracks a pattern**, like the trackers in After Effects, DaVinci
> Resolve and Avid. The focus rectangle becomes a tracking mark: an inner square, the **pattern**,
> and an outer square, the **search zone**. When I set it, the camera saves the pattern, and from
> frame to frame (every frame, every second or every third, set in settings so the phone does not
> overheat) it looks for the pattern only inside the search zone, moves the mark with it, and
> **refocuses only when the change is big enough to matter**, with a tolerance I can set. It runs on
> the GPU or the AI cores, never the CPU. Every parameter goes in settings so I can experiment:
> pattern size, search size, frames between searches, match confidence, focus tolerance, and how
> fast the mark follows.

**Done means.** He draws the mark on a face or an object, walks it across the frame, and the mark
follows. Focus changes only when the subject's distance changes past the tolerance; the mark says
LOST when the match drops below the confidence, and does not wander.

**Tested before delivery.** Test 1: the matcher (normalised cross-correlation) on synthetic frames with
a known shift, including rotation, scale and noise limits and the "lost" case. On the emulator: its
virtual scene moves when the emulated phone is tilted, so a mark can be followed and logged. G7:
the GPU time per search and the temperature over a 10-minute soak.

**Worth knowing.** The matching is normalised cross-correlation of the pattern over the search zone,
the method those editors use, as a GPU compute shader. The Tensor chip's AI cores are reached
through LiteRT/NNAPI and are built for fixed neural networks, not for a template that changes on
every tap; for this job the GPU is the dedicated hardware. If a later version wants a learned tracker,
a small model on the AI cores is the upgrade path, and the doc will say so then.

---

## Log of what happened

| Date | Version | Phase | Result |
|---|---|---|---|
| 26.9.2026 | v81 | — | Document written; Phase 1 started |
| 26.9.2026 | v82 | 1 | Built by CI, tested on the Pixel 7 emulator (four tests, monkey 20,000). **Waiting for Marko's confirmation on his phone** |
