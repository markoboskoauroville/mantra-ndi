# NDI licensing for a paid app, and the Mac webcam without a developer account

Researched 26.9.2026 for Mantra Manual Camera (Google Play, €22). Primary sources are listed at the end. **Not legal advice: the NDI answers must be confirmed in writing by NDI.**

## Q1: NDI licensing

**The standard NDI SDK: free, but it probably does not cover a phone app.** The current license PDF (downloads.ndi.tv, "NDI SDK License Agreement") grants a "nonexclusive royalty-free license" to build and distribute "Products".
- "Products" are defined as software on "general purpose computing platforms such as servers, desktops, and laptops".
- Clause 1b(iii) excludes products on "an operating system typically used for embedded devices, which includes ... Android, ... IOS". It adds: "In order to use the SDK for these exclusions, You must enter into a commercial license agreement with NDI."
- The wording is aimed at embedded hardware, so it is ambiguous for a phone app from Google Play. Read strictly, a paid Android app falls outside the free grant. I am not certain; ask licensing@ndi.video in writing.
- In any case the standard SDK does not help here: HX passthrough (see below) needs the Advanced SDK.

**Obligations when shipping NDI** (docs.ndi.video SDK Licensing page, plus the license):
- Put a link to ndi.video next to every place where NDI is chosen in the app, and also on the website and in the documentation.
- Write "NDI®" at the first use on a page, with the line "NDI® is a registered trademark of Vizrt NDI AB". The same line goes in the About box.
- Using "NDI" inside the product name needs NDI's permission. The name "Mantra Manual Camera" avoids this.
- The NDI tools themselves may not be redistributed; linking to ndi.video/tools is allowed.
- Bundle the libraries privately. On Android that means `libndi.so` inside the APK (arm64-v8a etc.; API level 24 or higher, with NsdManager running).
- Always use the newest SDK when releasing.
- The app's own EULA must contain the terms of section 3d:
  - no modification or reverse engineering of the SDK or its protocols
  - no way around its technical limits
  - NDI's notices kept
  - warranty and liability disclaimed on NDI's behalf
  - export control
  - an NDI copyright notice
- NDI may test the product and may terminate the license if it performs badly. Apps must stay fully interoperable.
- Licensing the H.264, HEVC and AAC codecs is **your** responsibility. On Android the platform's MediaCodec encoders are licensed through the device maker. Treat that as a lawyer question, not as settled.

**The Advanced SDK (needed for NDI HX):**
- Sending already-compressed H.264 or HEVC (`NDIlib_compressed_packet_t`, send_video_v3/scatter) is an Advanced SDK feature. The FAQ lists "Compressed stream handling, including HX3 Passthrough".
- It is "NDI Advanced, our commercial license". Development is free, but trial builds on Android and iOS stop after **5 minutes** of runtime.
- Commercial use requires a **License ID** from NDI. Since NDI 6 it replaces the older Vendor ID and is tied to a SKU in your contract. It comes from licensing@ndi.video or sales@ndi.video.
- **Money: yes, it is a negotiated commercial agreement, and NDI publishes no price.** NDI's own page says "volume-based pricing models, the more you sell, the less you pay". That points to per-unit or per-volume fees (royalties) under a contract, not a free license. I found no published per-copy rate or self-service "pay per sold copy" portal.
- Certification: "All NDI Advanced licensees are automatically eligible to enter the certification program", and the certification badges come from that program. The license lists "NDIHX™" as an NDI trademark, so the "NDI HX" name or logo should be used only as NDI's guidelines allow. Ask NDI.
- **Android and Google Play:** the Advanced SDK supports Android (the 5-minute trial rule names Android). I found no ban on Google Play. The official NDI Camera app and paid third-party HX camera apps sold for about $20 show that this is done.

**Bottom line for Q1:**
- Full NDI alone: free with attribution, but a written confirmation from NDI is needed because of the Android exclusion.
- NDI HX from the hardware encoder: a signed NDI Advanced contract with a License ID and negotiated volume-based fees. Email sales@ndi.video with the expected volume and the €22 price before building a business case.

## Q2: the phone as a webcam on the Mac without a Developer ID

This Mac already has, according to `systemextensionsctl list`:
- the OBS Virtual Camera extension, `[activated enabled]`, team 2MMRE5MTB8
- the NDI Camera Extension 6.3.2, `[activated waiting for user]` (installed, but not yet switched on)
- Iriun

**(b) OBS's camera extension, driven by our own app. Best quality, a little code, one-time setup.**
- OBS 30 and later installs a CMIO extension with two streams:
  - a source stream, which Zoom reads
  - a **sink** stream (`CMIOExtensionStream(direction: .sink)`)
- The OBS plugin (plugins/mac-virtualcam/src/obs-plugin/plugin-main.mm) feeds it like this:
  1. Walk `kCMIOHardwarePropertyDevices` and find the device whose UID is `OBSCameraDeviceUUID` = `7626645E-4425-469E-9D8B-97E0FA59AC75` (the Info.plist of the installed extension shows the same value).
  2. Take its second stream, the sink.
  3. Call `CMIOStreamCopyBufferQueue`, then `CMIODeviceStartStream`.
  4. Push frames with `CMSimpleQueueEnqueue(queue, CMSampleBuffer)`.
- `OBSCameraStreamSink.authorizedToStartStream(for:)` simply `return true`. There is **no team-ID or signature check on the client**. OBS does not need to be running.
- pyvirtualcam's `native_macos_obs_cmioextension` does exactly this from Python, which proves it works.
- The advertised format is fixed at 1920×1080 BGRA at 60 fps, so send 1080p (pyvirtualcam sends UYVY). The device name is "OBS Virtual Camera".
- GPL: our own client only calls Apple's public CoreMediaIO API against a separate process and copies no OBS code, so it is not a derivative work. Do not paste in OBS or pyvirtualcam (GPL-2) code; write it from scratch.
- Drawbacks:
  - The user must install OBS, press "Start Virtual Camera" once, and allow it in System Settings. On this Mac that is already done.
  - The camera's name is OBS's.
  - The UUID could change in a future OBS release.

**(a) NDI Virtual Input (NDI Tools 6.3.2). Zero code, least control.**
- It is a signed CMIO extension. The first launch asks for the extension, and you must click "Open System Settings", then turn it on under General → Login Items & Extensions → Camera Extensions.
- The source is chosen in the app's menu with a preview, and the app appears to remember the last source (`lastKnownSource` in the binary).
- I found no CLI, URL scheme or documented defaults key for choosing the source automatically.
- The resolution and frame-rate choices are only "various", undocumented; I did not check them.
- Quality: it decodes NDI or HX well. It works well when the phone is the only NDI source.

**(c) Our own CMIO extension, signed ad hoc. Not viable for users.**
- A system extension has to be signed with a Developer ID and notarized, and the entitlement to install one needs a paid account.
- Loading an unsigned one needs `systemextensionsctl developer on`, and that needs **SIP turned off**. Nobody should ask customers to do that.
- The old DAL plug-ins are switched off from macOS 14.1. The only way back is Recovery mode with `system-override legacy-camera-plugins-without-sw-camera-indication=on`. Zoom loads DAL plug-ins only after that change.
- Rejected.

**(d) Others:**
- Syphon Virtual Webcam is built on the old OBS DAL plug-in, so it is dead from 14.1 on.
- CamTwist is DAL-based and old; same fate.
- Iriun and Camo are signed but closed; they do not accept frames from other apps.
- Nothing free beats OBS's sink.

**Ranking (effort, then quality):**
1. (b): one-time OBS setup, about 200 lines of Swift, full 1080p60, automatic.
2. (a): zero code, one toggle, but the source is picked by hand.
3. (d): legacy, not recommended.
4. (c): not possible without SIP off.

## Recommendation

Build **"Mantra Webcam"**, a menu-bar Swift app, as a client of the OBS sink:
- It finds the phone automatically with the standard NDI SDK receiver on the Mac. A desktop is the SDK's normal case, so this is royalty-free with attribution.
- It decodes to CVPixelBuffers and scales them to 1920×1080.
- It sends them into the "OBS Virtual Camera" sink.
- Zoom selects "OBS Virtual Camera".
- Keep USB as a later option (adb forward plus the same sink).

What the user does once:
1. Install OBS Studio.
2. Click Start Virtual Camera, allow it in System Settings, then quit OBS.
3. Open our app. Because it is unsigned, macOS requires System Settings → Privacy & Security → "Open Anyway" the first time.

Fallback with no code at all: turn on the NDI Camera Extension, which is already installed here, and pick the phone once in NDI Virtual Input.

## Sources

- https://downloads.ndi.tv/SDK/NDI_SDK/NDI%20SDK%20License%20Agreement.pdf (current)
- https://downloads.ndi.tv/SDK/NDI_SDK/NDI%20License%20Agreement.pdf (November 2024)
- https://docs.ndi.video/all/developing-with-ndi/sdk/licensing
- https://docs.ndi.video/all/developing-with-ndi/advanced-sdk/licensing
- https://docs.ndi.video/all/faq/sdk/what-are-the-differences-between-the-ndi-sdk-and-the-ndi-advanced-sdk
- https://docs.ndi.video/all/developing-with-ndi/sdk/release-notes (License ID replaces Vendor ID)
- https://ndi.video/for-developers/ndi-advanced/ (volume-based pricing)
- https://docs.ndi.video/all/developing-with-ndi/advanced-sdk/using-h.264-h.265-and-aac-codecs/sending-video-frames
- https://docs.ndi.video/all/developing-with-ndi/sdk/platform-considerations
- https://docs.ndi.video/all/using-ndi/ndi-tools/ndi-tools-for-mac/virtual-input
- https://docs.ndi.video/all/using-ndi/obs-and-3rd-party-software/using-ndi-tools-as-a-virtual-camera-in-mac.md
- https://github.com/obsproject/obs-studio/tree/master/plugins/mac-virtualcam/src (camera-extension/*.swift, obs-plugin/plugin-main.mm)
- https://github.com/letmaik/pyvirtualcam/blob/main/pyvirtualcam/native_macos_obs_cmioextension/virtual_output.hpp
- https://eclecticlight.co/2023/10/27/how-sonoma-14-1-could-stop-your-camera-working/
- https://developer.apple.com/forums/thread/710046 and https://developer.apple.com/forums/thread/736838 (a camera extension needs notarization)
- https://github.com/TroikaTronix/Syphon-Virtual-Webcam
