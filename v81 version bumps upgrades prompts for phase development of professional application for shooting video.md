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

**Replanned 26.9.2026, after his first test of v82 on the Pixel 7 and the Nothing Phone (2a).** He
added: the take's sound is broken and plays sped up, SNAP must be a PNG beside the takes, a log curve
freezes the Nothing, the app is renamed **Mantra Manual Camera** for Google Play at €22, the manual
faders become thick mixer faders with A/M per parameter (half manual and full manual), the README
becomes the store's advertisement, NDI licensing for a paid app is researched, the Pixel is the
priority (never compromise it for another phone), and the webcam becomes a Mac companion app
because there is no Apple developer account. The order is now:

| Phase | Version | What it is |
|---|---|---|
| 1 | v82 | The screen: pinch box, tap to focus hidden, F-stop, toast, real lenses, peaking, WB probe (**built, tested on the emulator**) |
| 1b | **v83** | His test's faults: the take's sound, HEVC for every take, SNAP as PNG beside the takes, log freeze → "not supported", bit rate to 100, the rename, the README advertisement, NDI licensing researched |
| 1c | **v84** | His second test: sound out of sync (v83 stamped it on the boot clock, 7664 s off), constant frame rate forced, rotation follows the phone's auto-rotate lock |
| 1d | **v85** | The interface reorganised: right rail = REC, PLAY (last take), SNAP, LGHT, SHOOT (auto / landscape / portrait, labels upright), LUT switcher, PEAK, FALSE colour, ZEBRA, gear, storage (free space + time left); LUT library, record folder (any drive, USB SSD) and ROT in settings; record-run timecode; the LOG key says HLG or STD instead of "Rec.709" |
| 1e | **v86** | v85's two findings on the emulator: key labels clipped in portrait (now shrink to fit), the LUT key dead with an empty library (now opens settings) |
| 2 | **v87** | Mixer faders, thick, the number moving with the fader; **A / M per parameter**; modes AUTO, HM (half manual), FM (full manual); no more meaning in a double tap |
| 2b | **v88** | **His cosmetic list (26.9.2026, evening, from his Pixel on v86)**: remove the geometry line at the bottom; timecode to the bottom middle, always white; beside it a status word, REC (red while recording) or PLAY; no numbers inside the record key: a white circle idle, red recording; SNAP becomes the app's own icon; LGHT removed; SHOOT becomes a plain LANDSCAPE / PORTRAIT toggle (no AUTO). Plus two findings of v87: M reads HM at start (focus starts manual), fader names weak over a bright picture |
| 2c | **v89** | **The player inside the app**: PLAY turns the screen into a player for the takes (the same screen, not another app), with the LUT switcher working on playback |
| 3b | **v90** | The measuring corner: tiny **waveform** and **vectorscope** tiles on the right side (GPU, no CPU), beside the tools |
| 3 | **v91** | Settings with a floating live preview (ROT already moved there in v85) |
| 4 | **v92** | The GPU stage: one camera output fanned out on the GPU to the monitor, the NDI encoder and **a separate recording HEVC encoder at its own high bit rate** (sensor to storage, not the stream); full NDI packed on the GPU; 10-bit full NDI (P216) |
| 5 | **v93** | Automatic exposure that knows the log curve |
| 6 | **v94** | The WB key: a grey-card sweep |
| 7 | **v95** | The Mac companion app: the phone as a webcam in Zoom, no Apple developer account |
| 8 | **v96** | MANTRA_KELVIN, the colour-temperature calibration app, and its import |
| 9 | **v97** | Tracking focus on the GPU |

**Forecast: the last version is v97** (v84 and v86 were fix rounds; v85, v88, v89 and v90 were added on 26.9.2026 at his request). Every fix round adds one.

## Tomorrow starts here (written 26.9.2026, closing the day)

**State:** v87 is released (CI green, signed with the pinned key, copy in `~/Developer/APK/MANTRA_NDI/`).
He has tested up to **v86 on his Pixel** (his screenshot: 5 lenses, 4K 3840x2160, recording, 77.9 GB /
3:39 h left, the zones working). v87 has **not** been on his phone yet, and on the emulator it was only
installed and looked at: the monkey, a drag test and a run of the half-manual loop are still to do.

**Next, in order:** finish testing v87 (monkey 20,000, drags, A/M switches, the M cycle, the HM loop in
the trace) → build **v88**, his cosmetic list plus the two v87 findings → **v89**, the player → then
the table from v90 on, one phase per version, each confirmed on his phone before the next.

## Every request of 26.9.2026, and where it stands

| # | He asked | Where it stands |
|---|---|---|
| 1 | Pinch the focus rectangle, tiny to full screen | **v82**, tested on emulator; four pinches full → tiny |
| 2 | Perfect exposure per log curve, from the manufacturers' specs; auto exposure follows the curve | **v93** (Phase 5) |
| 3 | F-stop stays on screen in full screen | **v82** fixed |
| 4 | Tap focuses even when the rectangle is invisible | **v82** (zones up and FULL) |
| 5 | HEVC for recordings | **v83** (every take HEVC, H.264 only as fallback) |
| 6 | 10-bit over NDI HX and full NDI | **v92** (Phase 4/4b; HX already Main10 when 10-bit) |
| 7 | Peaking "will not run the preview shader" on Pixel 7 and Nothing | **v82** fixed (an unbound shader input); works on his Nothing |
| 8 | Everything possible on the GPU, not the CPU | **v92** (Phase 4); false colour, zebra, peaking, LUT already GPU |
| 9 | Remove the clean-view toast | **v82** done |
| 10 | Lens keys only for lenses that exist | **v82** done (his Pixel shows L1–L5) |
| 11 | Rotation (ROT) into settings; tiny floating live preview in settings | ROT in settings **v85**; floating preview **v91** |
| 12 | Phone as a webcam for the MacBook Pro (Zoom), best quality | **v95**: Swift companion feeding OBS's signed virtual camera (no Apple account; research in NDI_LICENSING_AND_WEBCAM.md) |
| 13 | Sister app to calibrate colour temperature against a calibrated light, export/import | **v96** (MANTRA_KELVIN, its own repo) |
| 14 | WB key: grey-card sweep through every temperature, snap to the most neutral | **v94** |
| 15 | WB double tap = the camera's own reading, then manual | **v82**; in **v87** it is WB's A→M switch |
| 16 | Tracking focus: pattern + search zone, GPU/AI cores, tolerance, settings | **v97** |
| 17 | Test on the Pixel 7 emulator against the manifest's stress tests | done for every build (four tests, monkey 20,000); v87 partly |
| 18 | This document, one version per phase, a forecast of the last | this file |
| 19 | Sound distorted, crackling, sped up on the Nothing | **v83** (half of every buffer lost) |
| 20 | SNAP: PNG in the same folder as the takes | **v83** |
| 21 | Bit rate: file and stream equal; record HEVC from the sensor | equal since v83 (one encoder, up to 100 Mbit/s); a separate recording encoder from the sensor **v92** |
| 22 | Mixer faders, thick, number moving; A/M per parameter; AUTO / HM / FM; no tap confusion | **v87** |
| 23 | README as a Google Play advertisement (€22), unique features, tested on Pixel, trial version | **v83**, extended v85/v87; keep it true at every release |
| 24 | Log curve freezes the Nothing: say "not supported" instead | **v83** (watchdog; to be confirmed on the Nothing) |
| 25 | Rename to Mantra Manual Camera for Google Play | **v83** (package id kept) |
| 26 | NDI: can it be paid per sold copy | researched **v83**: HX needs a paid Advanced SDK contract, volume pricing, no published per-copy price; write to sales@ndi.video |
| 27 | The Pixel is the priority; never compromise it for another phone | the rule for every phase |
| 28 | No Apple developer account: companion app / OBS / NDI Tools route | **v95** plan |
| 29 | Sound and picture out of sync (v83) | **v84** fixed (monotonic clock) |
| 30 | Constant frame rate, forced | **v84** |
| 31 | Rotate only when Android's auto-rotate is on | **v84** (`fullUser`) |
| 32 | Explain the colour science (why it looks filmic) | answered in chat: the phone's own HLG, soft highlight shoulder, no multi-frame processing; README "the look" |
| 33 | Orientation key so labels read upright | **v85** SHOOT; **v88** becomes LANDSCAPE / PORTRAIT only |
| 34 | LUT slots off the rail into settings, one LUT switcher | **v85** |
| 35 | Tell the truth about HLG | **v85** LOG says HLG / STD |
| 36 | Right rail: REC, PLAY, orientation, LUT switcher; tools: vectorscope, waveform, false colour, zebra, peaking | **v85** except waveform/vectorscope → **v90** |
| 37 | Timecode while recording, plus a tiny waveform | timecode **v85** (bottom middle, white, REC/PLAY word **v88**); waveform **v90** |
| 38 | Free space in MB and time left on the chosen drive | **v85** |
| 39 | Recording folder configurable, external drive | **v85** (system folder picker; a real USB SSD still to be tried by him) |
| 40 | Cosmetics: geometry line gone, timecode middle white, REC/PLAY word, plain record circle, SNAP = app icon, LGHT gone, LANDSCAPE / PORTRAIT | **v88** |
| 41 | PLAY plays inside the app, with LUTs | **v89** |

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

## Phase 1b: what his first test found (v83)

**The prompt** (his words, 26.9.2026, condensed).

> The recording's sound is distorted, bits missing, crackling, and on the Nothing the file plays sped
> up. SNAP should be a PNG of the picture in the same folder as the video. Record HEVC. On the Nothing,
> changing log freezes the picture: say "not supported" instead. Rename the app **Mantra Manual
> Camera**; it will be sold on Google Play for €22, so the README is its advertisement. Research
> whether NDI can be paid per sold copy. The Pixel is the priority.

**Done means.** A take's sound is continuous, in sync, at normal speed on both phones; `ffprobe` shows
AAC frames 21.3 ms apart and as many seconds of sound as of picture. Takes are HEVC. SNAP writes
`mantra-<date>.png` into `DCIM/Mantra Manual Camera`, beside the takes. A curve the lens cannot run
comes back in about a second with "not supported", and LOG skips it after that. The launcher says
Mantra Manual Camera.

## Phase 2: mixer faders, A / M per parameter, half and full manual (v87)

**The prompt.**

> MANTRA_NDI Phase 2. Redesign the manual sliders to **look like faders on an audio mixer, very
> thick**, in their zones, the number updating while the fader moves. Next to each fader an **A / M**
> switch: that parameter automatic or manual. The global M key becomes a mode: **AUTO** (all
> automatic), **HM** half manual (each parameter as its own A/M says), **FM** full manual. No gesture
> may be ambiguous: a single tap focuses, a drag moves a fader, A/M is a button, and a double tap
> means nothing any more. The WB probe becomes pressing M on WB while it is on A (the camera's
> reading is taken, then held).

**Done means.** Each fader is thick enough to grab without looking; each A/M switch works alone;
HM shows exactly which are manual; the numbers move live; nothing happens on a double tap.

**Tested before delivery.** Test 1: the mode rules (AUTO/HM/FM against the four A/M states) as pure
functions. Emulator: drags on each fader, every A/M switch, modes cycled, screenshots; upgrade from
v83 keeps each parameter's value; monkey.

## Phase 2b: his cosmetic list (v88)

**The prompt** (his words, 26.9.2026, with his Pixel's screenshot of v86 recording in 4K).

> Remove the line at the bottom: sensor, display, camera, rotation, buffer, view, squeeze. It is gone.
> The timecode comes to the middle, and the timecode is always white. Next to the timecode we have a
> status, REC, which becomes red when it is active, or PLAY. Remove the running number inside the
> record button, because it is now duplicated: when not recording it is just a white circle, when
> recording it becomes red. SNAP is renamed into an icon: put the icon of this application. The light
> (LGHT) is gone; the user can do that with the phone. SHOOT LAND is not good: it just says LANDSCAPE
> or PORTRAIT and toggles between the two. The rest is good.

**Also in this build (found on v87):** the M key reads HM at start-up because the focus director
starts in manual focus; either start focus in AF or leave focus out of "AUTO" (decide with him; the
cleanest is focus starts A). The fader names are grey on a bright picture: give them the same
shadow and weight as the numbers, or a dark band behind each fader row.

**Done means.** No geometry line (keep it in the trace only). Bottom middle: `REC 00:00:57:22`, the
word red while rolling, the timecode white always. Record key: white circle / red circle, no number.
SNAP is the app icon. No LGHT key. The orientation key reads LANDSCAPE or PORTRAIT.

## Phase 2c: the player inside the app (v89)

**The prompt.**

> PLAY turns this application into a player and plays the take inside the same screen. On the player
> I can apply LUTs.

**How.** A player surface in the picture's place (MediaPlayer or Media3 into a TextureView), with the
same GPU shader for the LUT (and peaking, false colour, zebra, which are worth having on playback
too). Play / pause, scrub, previous / next take in the recording folder, and back to the camera. The
status word beside the timecode says PLAY, and the timecode shows the take's position. The camera
stays open underneath (NDI keeps sending) or is paused, to be decided by what the Pixel allows.

**Done means.** PLAY shows the last take full screen in the app, the LUT key changes its look, the
timecode runs with the playback, and one key returns to the camera without restarting it.

## Phase 3: settings with a live floating preview (v91)

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

## Phase 4: the GPU stage and a separate recording encoder (v92)

**The prompt.**

> MANTRA_NDI Phase 4. **The take is recorded straight from the sensor to storage by its own HEVC
> encoder, at its own high bit rate, not copied from the NDI stream.** A camera session has at most
> three processed outputs (preview, encoder, full-NDI reader), so a second encoder needs one camera
> output fanned out on the GPU. Keep 10-bit through it (HLG, P010 external texture, RGBA1010102/
> BT.2020 EGL surfaces, as Media3 does on Android 13+); prove it on the Pixel first. And **anything this app does on the CPU that can be done on the GPU moves to the
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

## Phase 5: automatic exposure that knows the log curve (v93)

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

## Phase 6: the WB key, a grey-card sweep (v94)

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

## Phase 4b: 10-bit NDI HX and full (part of Phase 4, v92; HEVC for every take was v83)

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

## Phase 7: the phone as a webcam for the MacBook Pro (v95)

**The prompt.**

> MANTRA_NDI Phase 7. I want the **clean output of this camera as a webcam on my MacBook Pro**, so I
> can pick it in Zoom. **I have no Apple developer account**, so build a **Swift companion app for the
> Mac** that finds the phone by itself (NDI discovery, or the USB cable) and hands its picture to a
> virtual camera that is already signed (NDI Tools' own virtual input, or OBS's virtual camera, used
> without OBS running if its camera extension accepts frames from another app); reuse OBS's open
> code where it helps. As few clicks as possible: install once, then plug in and it is there. Best
> quality from 10-bit 4K downwards. Tell me every setting on the phone and on the Mac.

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

## Phase 8: the sister app calibrates colour temperature (MANTRA_KELVIN v1, camera v96)

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
v93.

**How a point is measured.** At each reference light the app locks exposure, reads the camera's own
neutral answer (AWB gains and colour matrix, the same anchor the NDI camera uses) over a grey card,
and stores (reference K, the camera's gains, its estimated K). The NDI camera's fader then maps
"true K" to the gains the camera needs.

---

## Phase 9: tracking focus (v97)

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
| 26.9.2026 | v82 | 1 | Built by CI, tested on the Pixel 7 emulator (four tests, monkey 20,000) |
| 26.9.2026 | v83 | 1b | His test: peaking works on the Nothing; the take's sound lost half of every buffer (ffprobe: 386 AAC frames 40 ms apart, 8.2 s of sound in 15.4 s); SNAP unfindable; log froze the Nothing. All fixed in v83; tested on the emulator (take measured, PNG, curves, monkey 20,000). tested by him 26.9: no crackle; sound out of sync |
| 26.9.2026 | v84 | 1c | Sound back on the monotonic clock with counted samples; CFR grid; fullUser rotation. Tested on the emulator (clock proven only on a real phone). His test: in sync |
| 26.9.2026 | v85 | 1d | Interface reorganised; tested on the emulator: FALSE, ZEBRA, SHOOT, timecode, PLAY, LUT library through the file picker, monkey 20,000 |
| 26.9.2026 | v86 | 1e | Labels fit, LUT key opens settings when empty |
| 26.9.2026 | v87 | 2 | Mixer faders, A / M per parameter, AUTO / HM / FM; native priority where the phone has it, the app's loop elsewhere. CI green; on the emulator installed over v86 and the faders drawn. **Not yet done: the monkey, a drag test, the HM loop run.** Findings: M reads HM at start; names weak over bright picture. Development closed for the day here |
