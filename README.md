### [Download the latest build](https://github.com/markoboskoauroville/mantra-ndi/releases/latest)

# Mantra NDI

An Android broadcast camera for Baba's own phones over Wi-Fi. 10-bit HDR with
real manual controls, recording to the phone, NDI out, and one phone driving
or watching another.

**Being rebuilt from scratch, phase by phase. Read [REBUILD.md](REBUILD.md)
before writing any code** — it carries the phases, where the SDK lives, and
the faults from the previous attempt that each cost a day.

## Where it is

**Phase 0, logging. Complete at v68.** There is no camera in this build.

| Phase | What | State |
|---|---|---|
| 0 | Logging: trace file, crash reports, the state panel | built and confirmed on the phone, v68 |
| 1 | 10-bit HDR camera and nothing else | not started |
| 2 | Recording | not started |
| 3 | NDI streaming | not started |
| 4 | Remote control over NDI metadata | not started |
| 5 | Monitor mode | not started |

A phase does not begin until the one before it is confirmed working on the
phone.

## Phase 0: how to get the state out of the phone

Three routes, because the whole difficulty of this project is seeing what the
app is doing.

**On the screen.** The panel is the trace, live, newest at the bottom.
Refusals and faults in red, control changes in amber.

**In a file manager.** `Android/data/com.mantraproductions.ndi/files/`, one
`trace-<date>-<time>.txt` per run of the app, written as things happen rather
than buffered. The last twelve runs are kept.

**In Downloads.** Crash reports go to `Download/Mantra NDI` through
MediaStore, which is the only route an app has to a public folder from
Android 10 onward. The **Export trace** key puts the current trace there too,
so the route is proven without having to crash.

The two crash keys are deliberate. One throws an `Exception`, the other an
`Error`, because a rejected shader arrives as an `Error` and walks past a
catch on `Exception` — which is how the previous reporter lost every fault it
was written for.

## Files

| File | What it holds |
|---|---|
| `TraceFormat.kt` | The clock, the columns, the filenames, the report. No Android, so Test 1 runs on a desk |
| `Trace.kt` | The trace file itself. Unbuffered, cannot throw, never called from a camera callback |
| `CrashLog.kt` | The uncaught handler. Catches `Throwable`, writes twice, chains to the system |
| `Downloads.kt` | The MediaStore route into the public Downloads folder |
| `MainActivity.kt` | The state panel |
| `Mechanism.kt` | All pure maths. No Android imports, so all of it is tested |
| `CubeLut.kt`, `ColourSpaces.kt`, `LogCurves.kt`, `Histogram.kt` | The 33³ LUT work and the colour maths, carried forward from the previous build |
| `CameraCommand.kt` | The NDI metadata command format, for phase 4 |
| `cpp/ndi_bridge.cpp`, `cpp/ndi_recv_bridge.cpp` | The JNI bridges. The NDI side of these worked |

The colour files, `CameraCommand.kt` and the bridges are carried but not yet
wired to anything. They are here because they are correct and tested, not
because this build uses them.

## Building

There is no local Android SDK and no APK is ever built on a desk. Every build
is GitHub Actions.

1. `python3 scripts/verify.py` — must print `all checks passed`
2. Read the releases API and set `appVersion` in `gradle.properties` to one
   above the last released number
3. Commit, push, wait about four and a half minutes, read the run's log
4. The APK appears at
   `https://github.com/markoboskoauroville/mantra-ndi/releases/download/vN/N-mantra-ndi-vN.apk`

Test 1 also runs on any machine with a JDK, in about a second, because the
files it covers import nothing from Android:

```bash
kotlinc -cp junit-4.13.2.jar app/src/main/java/com/mantraproductions/ndi/{Mechanism,LogCurves,CubeLut,ColourSpaces,Histogram,CameraCommand,TraceFormat}.kt \
    app/src/test/java/com/mantraproductions/ndi/*.kt -include-runtime -d test1.jar
java -cp test1.jar:junit-4.13.2.jar:hamcrest-core-1.3.jar org.junit.runner.JUnitCore \
    com.mantraproductions.ndi.MechanismTest
```

Signing fingerprint, which never changes or every installed copy has to be
removed by hand:
`53f963193bdffa9e7ca0ab56af8e9e83da758928e6e4557abb31d139cf88a5f1`

## The NDI SDK

The NDI Advanced SDK lives in the private repo
`markoboskoauroville/mantra-ndi-sdk` and is pulled in by the workflow at build
time. Its licence forbids redistribution, so it is never committed here. The
workflow fails the build if the runtime or the bridge is missing from the APK,
and `.gitignore` keeps the source out. Both stay.

Powered by NDI. NDI is a registered trademark of Vizrt NDI AB.
