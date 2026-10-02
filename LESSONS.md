# LESSONS: what this camera taught, so nobody learns it twice

Newest first. Each lesson is one fault that shipped or nearly did, what it looked like from outside,
and the rule that prevents it.

## 1. The sound's clock is the encoder's clock, not the sensor's (v83 → v84, 26.9.2026)

**What he saw:** sound and picture out of sync. **In the file:** the sound started 7664 s after the
picture.

v83 read `SENSOR_INFO_TIMESTAMP_SOURCE`, found REALTIME on the Pixel, and stamped the sound with
`elapsedRealtimeNanos`. But the camera framework converts frames going into a **video encoder**
surface to the **monotonic** clock, whatever the sensor's own source. The gap between the two clocks
is the time the phone has slept, hours on a phone that has been in a pocket.

**Rule:** the sound of a take is stamped on `System.nanoTime`, counted from the sample total.
**And the emulator cannot catch this:** its camera already uses the monotonic clock, so both clocks
agree there. A sync test on the emulator proves the counting, never the clock. Only a real phone
that has slept since boot proves the clock.

## 2. An AAC slot is smaller than a microphone read (v82 → v83)

**What he heard:** crackle, and on the Nothing a sped-up voice. **In the file:** 386 AAC frames, each
40 ms after the last, each holding 21.3 ms; 8.2 s of sound in a 15.4 s take.

The meter reads 40 ms at a time; one encoder input slot holds 1024 samples. `put(... coerceAtMost
(capacity))` silently kept the first half, and each piece was stamped on arrival. **Rule:** feed
every byte across as many slots as it takes, wait for a slot rather than skipping, stamp from the
sample count. **Check with ffprobe**: AAC packets exactly 1024/48000 s apart, and as many seconds of
sound as of picture.

## 3. A camera may accept what it cannot run, and just stop (v83)

**What he saw:** the picture froze when LOG changed curve on the Nothing Phone (2a). No exception,
no failed capture. **Rule:** after a request that may be unsupported (a tone curve), count frames;
none within a second means refused: put the last working request back and say so on the screen.

## 4. Every uniform shader in AGSL must be bound (v81 → v82)

**What he saw:** "This phone will not run the preview shader" on a Pixel 7 and a Nothing Phone.
Peaking alone was refused on every phone, because the LUT input (`uniform shader curve`) was only
bound when a LUT was loaded. **Rule:** bind every `uniform shader`, with a 1x1 bitmap when it will
not be read.

## 5. The screen is not the sensor (v81 → v82)

The focus region was a fixed 16% patch, taken as if screen and sensor were the same way up and the
same shape. **Rule:** a region goes screen → sensor through the rotation (plus the front camera's
mirror) and through the stream's crop of the active array (16:9 out of 4:3). Pure functions in
`Mechanism`, with a test for each quarter turn.

## 6. `fullSensor` ignores the rotation lock (v84)

`android:screenOrientation="fullSensor"` turns the screen even with auto-rotate off. `fullUser`
follows the phone's own setting, which is what an operator expects.

## 7. A unit test cannot see onCreate's order (v88)

**What happened:** v88 passed 249 unit tests and CI, then crashed on the first launch: a new call in
`onCreate` (`showTimecode(false)`) read `pipeline.fps` two lines before `pipeline` was made, a
`lateinit` not yet initialised. **Rule:** anything added to `onCreate` reads only what is already
built above it, or guards with `::x.isInitialized`; and the CI APK is launched on the emulator
before anything else is said about a release. A released number is never reused: the fix is the
next version (v89).

## 8. A physical lens has its own colour keys (v96)

**What he saw:** on the Pixel 7 the white balance fader moved the number and not the picture, while
on the Nothing Phone (2a) it worked. The Pixel's lenses are physical sub-cameras of a logical camera;
the stream went to the lens but the colour keys were set on the logical request, and the readback was
read from the logical result. **Rule:** when a stream names a physical lens, build the request with
`createCaptureRequest(template, setOf(physicalId))`, set per-lens keys with `setPhysicalCameraKey`, and
read that lens's answer from `physicalCameraTotalResults[physicalId]`. Then check that what was sent is
what was used; a control that is silently ignored must say so and fall back.
**v98 widened it:** not only colour — every control (ISO, shutter, frame duration, focus, AE/AF modes,
tone curve) must reach a physical lens. The general rule: copy each key from
`CameraCharacteristics.getAvailablePhysicalCameraRequestKeys()` onto the lens at every request, and read
the lens's own result back.

## 9. Read what the GPU wrote before trusting it (v99)

**What happened:** the tracker said LOST on a clearly textured window. Every score read back was exactly
0: the pattern, copied out of the luma framebuffer with `glCopyTexSubImage2D`, arrived flat on the
emulator's GPU, with no GL error. **Rule:** when a GPU stage misbehaves, read back a row of each
intermediate (`glReadPixels`) into the trace before theorising; and prefer drawing into a texture over
copying between them. The pattern now lives in a kept reference frame drawn by the same shader.


## 10. White balance on a spot: the pixels must not change, and the picture is not linear (v100 → v102)

**What he saw:** v100's spot swung r/g 1.54 → 0.16 → 3.19 → 0.18 and never settled; v101 said "nothing lit"
over a lit keyboard. **Causes:** (1) the population of "lit, unclipped" pixels changed every round as gains
pushed red keys over the clipping line; (2) on the 10-bit HLG path the picture answers a gain change far more
than linearly (≈ gain³); (3) `getBitmap(192, 108)` of a PORTRAIT view squashed ~17 screen rows into one, so
thin lit legends averaged into black. **Rule:** choose the pixels once and keep them; learn the response
exponent from each pair of rounds and step by it, capped ×1.25; read the texture in its own shape. Measured:
locked in 3–6 steps on his Pixel, and the colours through the camera then matched his eye.

## 11. "Converged" can be the state left from before (v103)

The exposure circle locked "after 1 frame": AE_STATE was still CONVERGED from the old region. **Rule:** after
a new metering region or trigger, ignore the state for a few frames (6) before believing it.

## 12. A test is only as good as the scene under it (2.10.2026)

The first white-balance proof ran on his ORANGE keyboard bank (he had switched banks), so the camera made
orange white and everything leaned blue. **Rule:** before a colour test, confirm the reference really is the
reference (here: set the keys white and read the bank back).

## 13. A camera driver can crash and close the camera; the app must open it again (v115/v116)

**Measured on the Nothing Phone 2a:** a log curve stops the picture, then "Camera error 4" closes the device; the app
went on sending to a closed camera — the freeze he saw. **Rule:** after a fatal camera error, take back the risky step
that preceded it (refused for good, kept across starts), say so, and reopen the camera with growing waits — the camera
service needs seconds to come back ("Could not read lens 0" on an immediate retry).

## 14. Never share a lock between the video and anything that can wait on the network (v123)

v117 polled and sent the remote control's metadata under the native lock the video frames use. A metadata send that
waited on the network held the video behind it: 10–19 fps for five seconds, then nothing. **Rule:** metadata on its own
lock; creating or destroying the sender takes both.

## 15. A live bit-rate drop can silence an encoder without an error (v124)

The Pixel's HEVC encoder (VBR) gave no more frames after setParameters from 50 to 16 Mbit/s. **Rule:** request a
keyframe after a rate change, and watch the encoder's output while streaming: no frame for two seconds → restart.

## 16. Measure the air before blaming the app (2.10.2026)

Dropouts of 2–7 s were the PIXEL not sending (/proc/net/dev), with both phones on 2.4 GHz channel 2 and the Pixel at
−68 dBm (86 Mbit/s link): a stream through the router crosses the air twice. **Rule:** read both phones' link (dumpsys
wifi: frequency, RSSI, link speed) and the sender's own byte counter before changing code; on 2.4 GHz keep HX at
12–16 Mbit/s, or use 5 GHz / the camera phone's hotspot. Also: adb `input tap` twice cannot make a double tap; a real
double click on the scrcpy window can.
