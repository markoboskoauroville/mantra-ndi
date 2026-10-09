# Mantra Remote for macOS

The camera's remote control on a Mac. It speaks **Mantra Link**, the same protocol the Android monitor uses (`shared/kotlin/com/mantraproductions/ndi/Link.kt`): plain TCP on port 48100, announced over Bonjour as `_mantralink._tcp`. It needs no NDI SDK, no adb and no developer mode.

- **The picture** is the camera's own screen, with its keys, faders and settings, as H.264. It's decoded by the Mac's hardware through `AVSampleBufferDisplayLayer`.
- **Click and drag** on the picture to tap and drag on the phone (keys, faders, marks, settings).
- **Pinch on the trackpad** to pinch on the phone. This resizes the armed mark.
- **REC** (or space) records, **BACK** (or Esc) goes back, and **CLEAN** asks for the clean picture without the keys.
- Cameras on the same network appear in the list. Over a USB tether, or when Bonjour can't reach the phone, type the phone's address (settings → NDI shows it).
- If no frame arrives for 2.5 s, the remote connects again by itself.

## Build

```sh
cd mac/MantraRemote
swift test               # the wire, byte for byte
./build-app.sh           # makes "Mantra Remote.app"
open "Mantra Remote.app"
```

This needs the Xcode command line tools (`xcode-select --install`). The first launch asks to allow local network access. Allow it, or the camera list stays empty.

CI builds the app on every push (`.github/workflows/mac.yml`). The zipped app is attached to the run as `mantra-remote-mac`.
