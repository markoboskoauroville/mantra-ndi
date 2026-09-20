### [Download the latest build](https://github.com/markoboskoauroville/mantra-ndi/releases/latest)


**Starting again: read [REBUILD.md](REBUILD.md) first.** It carries the phases, the SDK location, and the faults that cost days in the previous build.

# Mantra NDI

Turns the phone into an NDI camera: hardware H.264/H.265 straight from
MediaCodec to the network via the NDI Advanced SDK, with manual sensor
controls and saved capture profiles.

Phase 1 of three. NDI Monitor and NDI Screen Capture come later — the screen
source is already available in the pipeline library, so phase 3 is mostly UI.

## Architecture

```
RootEncoder (Apache-2.0)
  Camera2Source ──► GL ──► MediaCodec H.264/H.265 ──┐
  MicrophoneSource ──────► MediaCodec AAC ──────────┤
                                                     ▼
                                    NdiStream : StreamBase
                                      getVideoDataImp / getAudioDataImp
                                                     ▼
                                    NdiSender (JNI) ──► ndi_bridge.cpp
                                      NDIlib_compressed_packet_t
                                      NDI Advanced SDK
```

RootEncoder handles Camera2, the hardware encoders, rotation, the GL preview
path and years of device-specific fixes. Its own RTMP/RTSP/SRT/UDP classes all
work by subclassing `StreamBase` and forwarding encoded frames to a transport;
`NdiStream` does exactly that, with NDI as the transport.

| File | What it does |
| --- | --- |
| `NdiStream.kt` | StreamBase subclass, routes encoded frames to the bridge |
| `NdiStreamClient.kt` | StreamBaseClient stub (NDI owns transport internally) |
| `NdiSender.kt` | JNI façade, degrades gracefully when the SDK is absent |
| `ndi_bridge.cpp` | Packs frames into NDI compressed packets |
| `ProControls.kt` | Manual ISO/shutter/WB/focus, CFR lock, lens selection |
| `CaptureProfile.kt` | Named presets (Cinema 24, PAL 25, …) with persistence |
| `NdiSendService.kt` | Foreground service, owns the stream and the reconnect timer |

## Building without the NDI SDK

This is the default state of a fresh clone, and what CI builds. The native
half is skipped, `BuildConfig.NDI_SDK_PRESENT` is false, and you get an
installable APK where camera, preview, profiles and manual controls all work —
only sending is disabled. Useful for testing the camera side on a real phone
before the SDK is in place.

## Adding the NDI SDK

1. Register at https://ndi.video and request the **NDI Advanced SDK for
   Android**. The form is self-serve; the download link is per-user.
2. Copy into place (both paths are gitignored, the SDK isn't ours to redistribute):
   - `include/Processing.NDI.Lib.h` → `app/src/main/cpp/ndi/include/`
   - `lib/arm64-v8a/libndi.so` → `app/src/main/jniLibs/arm64-v8a/`
3. Build again — CMake picks it up automatically.

The standard (non-Advanced) NDI runtime won't work here. It has no compressed
send path, so it expects raw pixels and does SpeedHQ encoding itself, which
would mean throwing out hardware encoding and pushing ~100+ Mbps at 1080p.
That's what KlakNDI and similar projects do; it's the wrong trade over Wi-Fi.

## The 30-minute trial cap

Without a registered vendor ID the Advanced SDK stops a sender after 30
minutes — a technical limit, not a distribution restriction. A vendor ID needs
a distribution agreement and a royalty, which makes no sense for a personal,
non-distributed app.

So `NdiSendService` recreates the sender on the same source name every 25
minutes (`RECONNECT_INTERVAL_MS`) and forces a keyframe right after.
`ndi_bridge.cpp` swaps instances under a mutex, so it's safe while the encoder
threads are pushing frames. On the receiving end the source blips and comes
back; it then runs indefinitely.

## Still to verify on device

- **The two TODOs in `ndi_bridge.cpp`.** The compressed-packet layout is from
  NDI's docs and is solid; which frame struct the packet attaches to before the
  send call needs checking against the real header. Everything else is written.
- **Audio.** KlakNDI dropped NDI audio on mobile entirely over noise and delay
  problems. Expect this to need work.
- **NDI's preview stream.** The Advanced SDK's compressed path wants both a
  full-bandwidth and a 640-wide preview stream. Only the full stream is wired.
  `prepareVideo`'s `recordWidth`/`recordHeight` params plus a custom
  `RecordController` look like the clean way to get a second encoder output —
  worth trying once the main path sends anything at all.
- **Bitrates** in `CaptureProfile.defaults` are educated guesses, untested
  against a real Wi-Fi link.

## Building

CI-only, no local Android toolchain assumed. `.github/workflows/build.yml`
installs NDK/CMake, builds with a pinned Gradle (no wrapper jar committed) and
uploads the debug APK as a build artifact. Push, or run it from the Actions tab.
