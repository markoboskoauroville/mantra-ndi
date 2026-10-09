"""ndi_soak.py <phone-ip:port> — THE NDI TESTING GROUND (v136). Drives the camera through what breaks a stream, over
wireless adb only, while ndi_probe.py receives it on this Mac and measures every second. Marko, 9.10.2026: "please
do a stress test of the NDI stream ... the NDI stream is buggy. The sound has drops ... We need to create some kind
of testing ground to test NDI stability."

    python3 scripts/ndi_soak.py 192.168.1.40:41234 --source "Pixel 7" [--minutes 20] [--phases baseline,rotate,...]

Phases (each from the running app, the probe never disconnects; a phase passes when the probe saw no PICTURE STALL
outside the allowed settle time, the sound was at least 98 % complete, and the frame size matched the orientation):
  baseline     HX, sound AUTO, the phone untouched                                    (--minutes, default 10)
  rotate       portrait / landscape every 20 s for 2 min: the frame must turn (w<h in portrait), 3 s settle each
  background   HOME for 90 s, then back: the stream must go on behind other apps
  screenoff    the screen off for 90 s, then on
  full         full NDI for 2 min (the heavier wire)
  hxagain      back to HX for 1 min (the second spell of HX, which once sent undecodable frames)
Writes field-tests/<date>-soak/: probe.jsonl (every second), events.txt, trace-last.txt (the phone's own lines
about the stream: NDI LIVE, stream watch, ndi video flushes) and REPORT.md.
"""
import argparse, datetime, json, os, re, shutil, subprocess, sys, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixel7_lock

ap = argparse.ArgumentParser()
ap.add_argument("phone", help="wireless adb address, ip:port")
ap.add_argument("--source", default="", help="part of the NDI source name")
ap.add_argument("--minutes", type=float, default=10, help="length of the baseline phase")
ap.add_argument("--phases", default="baseline,rotate,background,screenoff,full,hxagain")
ap.add_argument("--extra-ips", default="", help="the phone's address when discovery cannot reach it (USB tether)")
ap.add_argument("--wait", action="store_true", help="wait for the shared Pixel 7 instead of giving up when it is busy")
a = ap.parse_args()

ADB_BIN = shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
ADB = [ADB_BIN, "-s", a.phone]
PKG = "com.mantraproductions.ndi"
FILES = f"/sdcard/Android/data/{PKG}/files/"
OUT = os.path.join("field-tests", datetime.datetime.now().strftime("%Y-%m-%d-%H%M") + "-soak")
os.makedirs(OUT, exist_ok=True)
HERE = os.path.dirname(os.path.abspath(__file__))


def sh(*args, timeout=40):
    r = subprocess.run(ADB + list(args), capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


events = open(os.path.join(OUT, "events.txt"), "w")
t0 = time.monotonic()


def event(text):
    line = f"{time.monotonic() - t0:8.1f}s  {text}"
    print(line, flush=True)
    events.write(line + "\n"); events.flush()


# --- the probe, in the background --------------------------------------------------------------------------------

rows = []
probe_cmd = [sys.executable, os.path.join(HERE, "ndi_probe.py"), "--json", "--seconds", "100000"]
if a.source: probe_cmd += ["--source", a.source]
if a.extra_ips: probe_cmd += ["--extra-ips", a.extra_ips]
log = open(os.path.join(OUT, "probe.jsonl"), "w")


def read_probe(p):
    for line in p.stdout:
        log.write(line); log.flush()
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if "t" in row:
            row["wall"] = time.monotonic() - t0
            rows.append(row)
            if row["verdict"] != "OK":
                print(f"          probe: {row['verdict']}  frames {row['frames']} gap {row['max_gap_ms']} ms "
                      f"size {row['size']} sound gaps {row['audio_gaps']}", flush=True)


def window(start, end):
    return [r for r in rows if start <= r["wall"] < end]


results = []


def judge(name, start, end, settle=(), want_portrait=None):
    """settle: (wall time, seconds) windows in which a short stall is allowed (an encoder made again on a turn)."""
    w = window(start, end)
    if not w:
        results.append((name, False, "the probe saw nothing")); return
    def settling(r):
        return any(s <= r["wall"] < s + d for s, d in settle)
    stalls = [r for r in w if r["verdict"] in ("PICTURE STALL", "NOTHING") and not settling(r)]
    samples = sum(r["samples"] for r in w)
    expected = sum((r["rate"] or 48000) for r in w)
    complete = samples / expected if expected else 0
    gaps = sum(r["audio_gaps"] for r in w)
    longest = max((r["max_gap_ms"] for r in w if r["frames"]), default=0)
    fps = sum(r["frames"] for r in w) / len(w)
    ok = not stalls and complete >= 0.98
    detail = f"{len(w)} s, {fps:.1f} fps, longest picture gap {longest} ms, stalls {len(stalls)}, " \
             f"sound {complete * 100:.1f} % complete, {gaps} sound gaps"
    if want_portrait is not None:
        sizes = [r["size"] for r in w if r["size"] and not settling(r)]
        wrong = [s for s in sizes if (lambda x, y: (y > x) != want_portrait)(*map(int, s.split("x")))]
        detail += f", sizes {sorted(set(sizes))}"
        ok = ok and not wrong
    results.append((name, ok, detail))
    print(("PASS  " if ok else "FAIL  ") + name + "  — " + detail, flush=True)


def launch_hx(kind=1):
    sh("shell", "am", "start", "-S", "-W", "-n", f"{PKG}/.MainActivity", "--es", "ndi_arm", "1", "--es", "ndi_kind",
       str(kind), "--es", "ndi_audio", "1", "--es", "audio_source", "auto")


def front():
    sh("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")


def orient(portrait):
    sh("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    sh("shell", "settings", "put", "system", "user_rotation", "0" if portrait else "1")


# --- go ------------------------------------------------------------------------------------------------------------

# The Pixel 7 is shared with DJ Mantra: the lock first, for the run's length plus a margin, given back at exit.
phase_minutes = {"baseline": a.minutes, "rotate": 2.5, "background": 2, "screenoff": 2, "full": 2.2, "hxagain": 1.2}
need = sum(phase_minutes.get(p, 2) for p in a.phases.split(",")) + 3
pixel7_lock.hold_for_this_run(ADB, min(max(15, need), 60), "camera NDI soak", wait=a.wait)
model = sh("shell", "getprop", "ro.product.model").strip()
event(f"phone {model} at {a.phone}")
orient(False)
launch_hx(1)
event("camera started: NDI HX, sound AUTO, landscape")
time.sleep(8)
probe = subprocess.Popen(probe_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
threading.Thread(target=read_probe, args=(probe,), daemon=True).start()
time.sleep(10)
if not rows:
    event("the probe has seen nothing yet; is the source name right, is the Mac on the same network?")

phases = a.phases.split(",")
try:
    if "baseline" in phases:
        s = time.monotonic() - t0; event(f"baseline: {a.minutes} min untouched")
        time.sleep(a.minutes * 60)
        judge("baseline", s, time.monotonic() - t0)

    if "rotate" in phases:
        s = time.monotonic() - t0; event("rotate: portrait / landscape every 20 s")
        settles = []
        for i in range(6):
            portrait = i % 2 == 0
            orient(portrait)
            at = time.monotonic() - t0
            settles.append((at, 3))
            event(f"  {'portrait' if portrait else 'landscape'}")
            time.sleep(20)
            judge(f"rotate {i + 1} {'portrait' if portrait else 'landscape'}", at, time.monotonic() - t0,
                  settle=[(at, 3)], want_portrait=portrait)
        orient(False)
        time.sleep(5)

    if "background" in phases:
        s = time.monotonic() - t0; event("background: HOME for 90 s")
        sh("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(90)
        front()
        event("  back in front")
        time.sleep(15)
        judge("background", s, time.monotonic() - t0, settle=[(s, 2), (s + 90, 3)])

    if "screenoff" in phases:
        s = time.monotonic() - t0; event("screen off for 90 s")
        sh("shell", "input", "keyevent", "KEYCODE_SLEEP")
        time.sleep(90)
        sh("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        sh("shell", "wm", "dismiss-keyguard")
        front()
        event("  screen on")
        time.sleep(15)
        judge("screen off", s, time.monotonic() - t0, settle=[(s, 2), (s + 90, 3)])

    if "full" in phases:
        launch_hx(2)
        s = time.monotonic() - t0; event("full NDI for 2 min")
        time.sleep(10)   # a fresh start: the probe reconnects
        s2 = time.monotonic() - t0
        time.sleep(110)
        judge("full NDI", s2, time.monotonic() - t0)

    if "hxagain" in phases:
        launch_hx(1)
        event("HX again for 1 min")
        time.sleep(10)
        s2 = time.monotonic() - t0
        time.sleep(50)
        judge("HX again", s2, time.monotonic() - t0)
finally:
    probe.terminate()
    sh("shell", "settings", "put", "system", "accelerometer_rotation", "1")
    names = [n for n in sh("shell", "ls", "-t", FILES).split() if n.endswith(".txt")]
    trace = sh("shell", "cat", FILES + names[0], timeout=60) if names else ""
    keep = [l for l in trace.splitlines() if re.search(r"NDI LIVE|stream watch|ndi video|stream encoder|stream orientation|"
                                                       r"background|preview back|stream service|audio|fault|REFUSED", l)]
    open(os.path.join(OUT, "trace-last.txt"), "w").write("\n".join(keep) + "\n")

passed = sum(1 for _, ok, _ in results if ok)
report = [f"# NDI soak, {model}, {datetime.datetime.now():%Y-%m-%d %H:%M}", "",
          f"{passed} of {len(results)} phases passed.", "", "| | phase | measured |", "|---|---|---|"]
report += [f"| {'PASS' if ok else 'FAIL'} | {n} | {d} |" for n, ok, d in results]
report += ["", "Every second is in probe.jsonl; the phone's own lines about the stream are in trace-last.txt."]
open(os.path.join(OUT, "REPORT.md"), "w").write("\n".join(report) + "\n")
print(f"\n{passed} of {len(results)} phases passed. Report: {OUT}/REPORT.md")
sys.exit(0 if passed == len(results) else 1)
