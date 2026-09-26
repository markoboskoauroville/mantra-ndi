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
