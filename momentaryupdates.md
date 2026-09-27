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

**Status:** to do, v90.

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

**Status:** to do, v90.

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

**Status:** to do, v90.

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
| **v90** | Items 4, 5, 6, 7 (and 1–4 of the first message, which are the same things): shutter-angle presets, BASE / HIGH ISO presets, one-shot WB, the orientation written right into the MP4, the icon with the orange outlines | to do |
| **v91** | The switchboard in settings (File, USB, NDI, YouTube, each a switch that expands its panel; NDI HX / Full inside it); the NDI key leaves the main screen; the record key becomes the master trigger for every armed destination; a telemetry line at the top naming what is being sent. File and NDI wired first | to do |
| **v92** | **USB (OBS camera)**: the picture to OBS on the Mac over the USB cable. Open question for him: which route OBS should receive (NDI over the USB tether already works; a plain SRT/RTMP feed into OBS's Media Source needs no plug-in; a true "OBS camera" source is the Mac companion of the old v97 plan) | to do, ask |
| **v93** | **YouTube**: RTMPS to YouTube's ingest with the stream key; "authenticate from the app" = Google sign-in with the YouTube Data API to create the broadcast and fetch the key. That needs a Google Cloud OAuth client for the app, which Marko has to create (a step for him) | to do, needs his OAuth client |
| v94 … | the in-app player, scopes, settings preview, the GPU stage, log AE, grey-card WB, KELVIN, tracking (the phase document, each moved on by the new versions) | later |

**Status of this second message:** saved 27.9.2026; v90 started.
