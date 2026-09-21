# Building an NDI app for Android, from Baba's repos

For any Claude session starting a new NDI app for Baba, or picking up one of
the existing ones. Everything here was read out of working code in
`mantra-ndi` and `mantra-ndi-screen-share-android`, or measured on the phone.
Where something is not verified, it says so.

Baba is a broadcast professional and a beginner programmer. Explain in plain
words, give him commands to paste rather than choices to make, and never tell
him something works until you have seen a green build.

## 1. The rule that keeps a stream alive: restart before thirty minutes

The NDI Advanced SDK stops a sender after **thirty minutes** when no vendor ID
is registered. It does not report this. The source simply leaves the network,
every receiver loses it, and the phone goes on believing it is sending.

**The fix: reopen the NDI sender every 25 minutes, on the same source name,
and keep doing it for as long as the stream runs.** Each reopen starts a fresh
thirty-minute window, so the stream can run indefinitely.

Do it before the cap, never after it. After the cap the source has already
gone and vMix shows the drop. Before the cap, the receiver reconnects on its
own and the picture returns within one keyframe.

What makes it seamless, all three needed:

1. **Make before break.** The bridge creates the new sender first, swaps it in
   under a mutex, then destroys the old one. There is never a moment with no
   sender. See `nativeCreate` in `ndi_bridge.cpp`.
2. **Same source name.** vMix and Studio Monitor reconnect to a name. A new
   name is a new source, and somebody has to pick it by hand.
3. **A keyframe every second** (`KEY_I_FRAME_INTERVAL = 1`), with SPS/PPS
   attached to every keyframe. A receiver joining the new sender waits at most
   one second for a picture it can decode.

Only the NDI sender is recreated. The camera, the screen capture and the
encoder keep running.

The reference implementation is the `refresh` runnable in
`mantra-ndi-screen-share-android`, `ScreenShareService.kt`: interval
`REFRESH_INTERVAL_MS = 25 minutes`, re-posts itself, writes a trace line each
time. **Not yet tested on the phone past thirty minutes.** The first long test
should run for at least an hour with vMix watching, and the trace should show
two `reopening the NDI source` lines with the picture continuous across both.

Registering a vendor ID with Vizrt removes the cap. Until that happens, this
rule is not optional.

## 2. Where the SDK lives

Private repo `markoboskoauroville/mantra-ndi-sdk`. It holds exactly:

    include/*.h
    lib/arm64-v8a/libndi_advanced.so

**Its licence forbids redistribution. It is never committed to any public
repo.** Every app repo carries these lines in `.gitignore`:

    app/src/main/jniLibs/
    app/src/main/cpp/ndi/

and a CI gate that fails the build if the runtime or the bridge is missing
from the APK.

Note the library name: the Android SDK ships `libndi_advanced.so`, not
`libndi.so`.

## 3. Getting the SDK into a build

There is no local Android SDK on Baba's machines. Every APK is built by GitHub
Actions. The workflow clones the SDK repo, copies the two folders into place,
builds, and publishes a release.

**Use a read-only deploy key**, one keypair per app repo, never a personal
token. A token in a public repo's secrets that leaks costs the whole account;
a deploy key that leaks costs read access to the SDK and nothing else.

The reference is `mantra-ndi-screen-share-android`, `.github/workflows/build.yml`,
step "Fetch the NDI SDK". It also pins GitHub's host keys from
`api.github.com/meta` rather than trusting `ssh-keyscan`, which is the better
way. `mantra-ndi` itself still uses the older `NDI_SDK_TOKEN` and should move
to a deploy key when it reaches phase 3.

After the clone, the files go here, and nowhere else:

    app/src/main/cpp/ndi/include/          <- include/*.h
    app/src/main/jniLibs/arm64-v8a/        <- libndi_advanced.so

## 4. Secrets: only Baba can set them

A Claude Code session cannot write repository secrets or create deploy keys.
The harness refuses both, correctly. Do not look for a way around it. Prepare
the exact commands and hand them to Baba to paste into the Mac's **Terminal**
app, one at a time.

Each app needs three secrets:

| Secret | What it holds |
|---|---|
| `SIGNING_KEYSTORE_B64` | The app's own `.p12` keystore, base64 |
| `SIGNING_PASSWORD` | Its password |
| `NDI_SDK_DEPLOY_KEY` | The private half of the read-only deploy key |

Two traps in setting them:

- **A password read from a file carries its trailing newline.** Gradle reads
  `SIGNING_PASSWORD` from the environment exactly as given, so the newline
  becomes part of the password and the build fails with "keystore password
  was incorrect", which looks like the wrong keystore. Strip it:
  `tr -d '\r\n' < password-file | gh secret set SIGNING_PASSWORD`
- **Never put a secret on a command line.** Pipe it in or let `gh` prompt, so
  it does not land in shell history.

## 5. Native build

`app/build.gradle.kts` checks whether the SDK is present before it builds any
native code:

    val ndiSdkPresent = file("src/main/cpp/ndi/include/Processing.NDI.Lib.h").exists()

If the clone put the headers anywhere else, the native step is quietly
skipped and the artefact gate fails with "bridge missing". That reads like an
SDK problem and is a path problem.

Toolchain as used: NDK `27.0.12077973`, CMake `3.22.1`, ABI `arm64-v8a` only.

`CMakeLists.txt`, as in `mantra-ndi`:

    add_library(ndi_bridge SHARED ndi_bridge.cpp ndi_recv_bridge.cpp)
    target_include_directories(ndi_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/ndi/include)
    add_library(ndi_advanced SHARED IMPORTED)
    set_target_properties(ndi_advanced PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi_advanced.so)
    find_library(log-lib log)
    target_link_libraries(ndi_bridge ndi_advanced ${log-lib})

Include `Processing.NDI.Advanced.h`, not only `Lib.h`: the compressed send API
lives there.

## 6. Sending: pass the encoder's output straight through

The Advanced SDK accepts H.264 and HEVC as they come out of `MediaCodec`, so
the phone never touches raw pixels on the CPU. This is what keeps a phone cool
enough to stream for hours.

    source (camera or screen) -> MediaCodec input Surface -> encoded packets
                              -> NDIlib_send_send_video_scatter

The details that are easy to get wrong, all written against the SDK's own
`NDIlib_Send_H264` example:

- Each packet is prefixed by an `NDIlib_compressed_packet_t` header, passed
  through a scatter list so nothing is copied. `packet.version` is
  `sizeof(NDIlib_compressed_packet_t)`.
- The frame's FourCC picks the stream: `*_highest_bandwidth` for the full
  stream, `*_lowest_bandwidth` for the preview stream NDI requires.
- SPS/PPS (and VPS for HEVC) go on keyframes as the packet's extra data.
  Some encoders deliver them as a separate config buffer, some inside the
  first keyframe. Handle both.
- `clock_video = false`. The encoder's timestamps pace the stream.

The working bridges are `ndi_bridge.cpp` (send, tally, metadata) and
`ndi_recv_bridge.cpp` (receive) in `mantra-ndi`. Copy them, then rename the
JNI function names to the new app's package.

## 7. Android rules that fail silently

- **Multicast lock.** NDI discovery is mDNS over UDP multicast, and Android
  drops multicast on Wi-Fi unless something holds a `WifiManager.MulticastLock`.
  Without it the phone sends and nobody can see it. Needs
  `CHANGE_WIFI_MULTICAST_STATE`. Take it before creating the sender, and trace
  whether it is held.
- **Foreground service with the right type.** A camera app needs
  `foregroundServiceType="camera"`, a screen share needs `"mediaProjection"`,
  each with its matching `FOREGROUND_SERVICE_*` permission. Without it Android
  stops the stream when the screen goes off.
- **Screen capture consent is single-use** on recent Android, by Android's
  own documentation; not yet tested in these repos. Ask for it each time
  sharing starts, and start the foreground service before calling
  `getMediaProjection`.
- **A still screen sends nothing.** A virtual display only produces frames
  when something changes, so the encoder goes quiet and receivers time out.
  `MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER` fixes it. See `Pipelines.kt` in
  the screen-share repo.
- **The NDI licence requires attribution** in the app: "Powered by NDI. NDI is
  a registered trademark of Vizrt NDI AB." and `ndi.video`. A source gate
  fails the build without it.

## 8. Build logging first

Before any camera or capture code, copy the phase-zero logging from
`mantra-ndi`: `TraceFormat.kt`, `Trace.kt`, `CrashLog.kt`, `Downloads.kt`,
`MantraApp.kt`, and their tests. The screen-share app already did.

What it gives: a trace file written as things happen, under
`Android/data/<package>/files`, and crash reports in `Download/<app name>`
through MediaStore. A raw file path into Downloads is silently refused on
Android 10 and later, and a catch on `Exception` misses an `Error`, which is
how an earlier build shipped ten versions with no crash log at all.

When something fails on the phone, the trace should already say why. If it
does not, fix the logging first.

## 9. Signing and releases

- Each app has its **own keystore**, and its fingerprint never changes, or
  every installed copy has to be uninstalled by hand. The fingerprint lives in
  `SIGNING_FINGERPRINT.txt` as **lowercase hex with no colons**, because that
  is what `apksigner` prints and what the gate compares against. `keytool`
  prints uppercase with colons; convert it.
- The debug build is signed with the app's own key, so `assembleDebug` gives
  a proper, updatable APK.
- `appVersion` in `gradle.properties` must be exactly one higher than the last
  released version. Read the releases API, do not assume.
- The APK lands at
  `releases/download/vN/N-<app>-vN.apk`, downloadable without a login.
- A commit that only changes documents should say `[skip ci]` in its message,
  or it triggers a build that the version gate rejects.

## 10. The test phone

Google Pixel 7, Android 16, API 36. Measured:

- Back camera sensor orientation 90, front 270.
- `REQUEST_AVAILABLE_CAPABILITIES` includes `MANUAL_SENSOR`: true on both
  cameras. Send manual requests and let the camera refuse them; do not gate
  controls on capability flags.
- Status bar inset 136 px, navigation bar 126 px, measured in three-button
  navigation, upright. Landscape at rotation 270 gives left 126, top 74,
  right 136, bottom 0.

## Where to look

| For | Repo | File |
|---|---|---|
| The thirty-minute restart | screen-share | `ScreenShareService.kt`, `refresh` |
| Deploy key fetch, pinned host keys | screen-share | `.github/workflows/build.yml` |
| Screen capture to encoder | screen-share | `Pipelines.kt` |
| Send and receive bridges | mantra-ndi | `app/src/main/cpp/` |
| Logging | mantra-ndi | `Trace.kt`, `CrashLog.kt`, `Downloads.kt` |
| CI gates | mantra-ndi | `scripts/verify.py`, `.github/workflows/build.yml` |
| How Baba wants software built | MANTRA_MANIFEST | `START_HERE.md` |
