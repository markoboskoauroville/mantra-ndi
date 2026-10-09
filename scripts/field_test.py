"""field_test.py <ip:port> [apk] — v135 field test on a real phone over WIRELESS adb only (the USB-C port is for a
microphone or the tether). Written 9.10.2026 for Marko's Pixel 7 with a Hollyland receiver in its USB-C port.

What it checks, each scenario from a clean start (`am start -S` with the app's debug extras, see
MainActivity.applyTestExtras), reading the app's own trace:
  - the camera opens, the picture moves, nothing crashes (CrashLog files, the process alive)
  - NDI over WI-FI in HX and FULL: the sender is pinned to wlan0 ("NDI sender on wlan0 ...")
  - the sound: which input Android routed to ("audio routed to ..."), mono and stereo, and that it went out
    inside the stream ("NDI LIVE ... sound on sent N" rising), or not at all with SOUND IN THE STREAM off
  - USB CABLE with no tether: the stream waits and says so, the camera keeps running
  - a 10 s take: pulled back and read with ffprobe (when the Mac has it): an audio track, its channels
  - optional, with --manual: hot-plug the microphone, and the USB tether (cable in, tethering on, out, in)
  - the Mac sees the NDI source (dns-sd -B _ndi._tcp)

Prints a PASS / FAIL table and writes it to field-tests/<date>/REPORT.md with the screenshots beside it.
"""
import datetime, os, re, shutil, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixel7_lock

TARGET = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else os.environ.get("PHONE", "")
APK = next((a for a in sys.argv[2:] if a.endswith(".apk")), None)
MANUAL = "--manual" in sys.argv
ADB_BIN = shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
ADB = [ADB_BIN, "-s", TARGET]
PKG = "com.mantraproductions.ndi"
FILES = f"/sdcard/Android/data/{PKG}/files/"
OUT = os.path.join("field-tests", datetime.datetime.now().strftime("%Y-%m-%d-%H%M") + "-v135")
os.makedirs(OUT, exist_ok=True)
results = []


def sh(*a, timeout=40):
    r = subprocess.run(ADB + list(a), capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  — {detail}" if detail else ""), flush=True)


def ask(prompt):
    if not MANUAL:
        return False
    input(f"\n>>> {prompt}\n    press Enter when done ")
    return True


def trace_file():
    names = [n for n in sh("shell", "ls", "-t", FILES).split() if n.endswith(".txt") or "trace" in n]
    return names[0] if names else ""


def trace():
    f = trace_file()
    return sh("shell", "cat", FILES + f, timeout=60) if f else ""


def crashes():
    return [n for n in sh("shell", "ls", FILES).split() if "crash" in n.lower()]


def alive():
    return bool(sh("shell", "pidof", PKG).strip())


def shot(name):
    png = subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True, timeout=40).stdout
    path = os.path.join(OUT, name + ".png")
    open(path, "wb").write(png)
    return path


def moving():
    try:
        import io
        from PIL import Image
    except ImportError:
        return None
    def frame():
        png = subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True, timeout=40).stdout
        im = Image.open(io.BytesIO(png)).convert("L")
        w, h = im.size
        return im.crop((w // 8, h // 5, w * 7 // 8, h * 4 // 5)).resize((120, 160))
    a = frame(); time.sleep(1.0); b = frame()
    return sum(abs(x - y) for x, y in zip(a.getdata(), b.getdata())) / (120 * 160)


def launch(**extras):
    args = ["shell", "am", "start", "-S", "-W", "-n", f"{PKG}/.MainActivity"]
    for k, v in extras.items():
        args += ["--es", k, str(v)]
    sh(*args)


def live_lines(text):
    return re.findall(r"NDI LIVE .*", text)


def sent_counts(text):
    return [int(m) for m in re.findall(r"sound on sent (\d+)", text)]


def scenario(name, wait=16, **extras):
    before = set(crashes())
    launch(**extras)
    time.sleep(wait)
    t = trace()
    shot(name)
    new_crash = set(crashes()) - before
    record(f"{name}: no crash, app alive", alive() and not new_crash, ", ".join(new_crash))
    return t


# --- 0. the link -------------------------------------------------------------------------------------------------
if not TARGET:
    sys.exit("usage: field_test.py <phone-ip:port> [app.apk] [--manual] [--wait]")
# The Pixel 7 is shared with DJ Mantra: the lock first, before any adb command; given back at the end, however it ends.
pixel7_lock.hold_for_this_run(ADB, 45 if MANUAL else 25, "camera v136 field test", wait="--wait" in sys.argv)
print(subprocess.run([ADB_BIN, "connect", TARGET], capture_output=True, text=True).stdout.strip())
model = sh("shell", "getprop", "ro.product.model").strip()
record("wireless adb reaches the phone", bool(model) and "error" not in model.lower(), model)
usb_serials = [l.split()[0] for l in subprocess.run([ADB_BIN, "devices"], capture_output=True, text=True).stdout
               .splitlines()[1:] if l.strip() and ":" not in l.split()[0]]
record("no USB adb in the way (Wi-Fi only)", not usb_serials, ", ".join(usb_serials) or "none")

if APK:
    out = sh("install", "-r", "-g", APK, timeout=300)
    record("install " + os.path.basename(APK), "Success" in out, out.strip().splitlines()[-1] if out.strip() else "")
version = re.search(r"versionName=(\S+)", sh("shell", "dumpsys", "package", PKG))
record("installed version is 135 or later", bool(version) and int(version.group(1)) >= 135,
       version.group(1) if version else "not installed")
for p in ("CAMERA", "RECORD_AUDIO"):
    sh("shell", "pm", "grant", PKG, "android.permission." + p)

inputs = sh("shell", "dumpsys", "audio")
usb_in = re.findall(r"(?i)(USB[_ ](?:HEADSET|DEVICE)[^\n]{0,80})", inputs)
print("audio inputs Android reports (USB):", usb_in[:4] or "none")
external_present = bool(usb_in)

# --- 1. Wi-Fi, HX, sound AUTO --------------------------------------------------------------------------------------
t = scenario("1-wifi-hx-auto", ndi_arm=1, ndi_kind=1, ndi_transport="WIFI", ndi_audio=1, audio_source="auto",
             audio_stereo=0, file_arm=1)
d = moving()
record("1 picture is moving", d is None or d > 0.6, f"diff {d:.2f}" if d is not None else "PIL missing, not measured")
record("1 sender pinned to Wi-Fi", bool(re.search(r"NDI sender on wlan\d", t)),
       (re.findall(r"NDI sender on .*", t) or ["no sender line"])[-1])
routed = re.findall(r"audio routed to ([^,\n]*)", t)
record("1 AUTO routes to the USB-C device" if external_present else "1 AUTO routes to the phone",
       bool(routed) and (routed[-1].startswith("USB-C") == external_present), routed[-1] if routed else "no route line")
time.sleep(12)
t = trace()
counts = sent_counts(t)
record("1 sound goes out inside the stream", len(counts) >= 2 and counts[-1] > counts[0] > 0,
       f"sent {counts[:1]} → {counts[-1:]}; " + (live_lines(t)[-1] if live_lines(t) else "no NDI LIVE line"))
drops = [int(m) for m in re.findall(r"dropped (\d+) · from", t)]
record("1 sound drops stay small", not drops or drops[-1] <= max(5, (counts[-1] if counts else 0) // 50),
       f"dropped {drops[-1] if drops else 0}")

# --- 2. the phone's microphone while the USB one is in ----------------------------------------------------------
t = scenario("2-wifi-hx-phone", ndi_arm=1, ndi_kind=1, ndi_transport="WIFI", ndi_audio=1, audio_source="phone")
routed = re.findall(r"audio routed to ([^,\n]*)", t)
record("2 PHONE MIC is used when chosen", bool(routed) and routed[-1].startswith("PHONE"),
       routed[-1] if routed else "no route line")

# --- 3. stereo ---------------------------------------------------------------------------------------------------
t = scenario("3-wifi-hx-stereo", ndi_arm=1, ndi_kind=1, ndi_transport="WIFI", ndi_audio=1, audio_source="auto",
             audio_stereo=1)
ch = re.findall(r"audio meter running at 48000 Hz, (\d)ch", t)
record("3 stereo opens two channels (or says why not)", bool(ch) and (ch[-1] == "2" or "stereo refused" in t),
       f"{ch[-1] if ch else '?'}ch")

# --- 4. full NDI with sound ----------------------------------------------------------------------------------------
t = scenario("4-wifi-full", wait=22, ndi_arm=1, ndi_kind=2, ndi_transport="WIFI", ndi_audio=1, audio_source="auto",
             audio_stereo=0)
record("4 FULL streams with sound", any("FULL" in l and "sound on" in l for l in live_lines(t)),
       live_lines(t)[-1] if live_lines(t) else "no NDI LIVE line")

# --- 5. sound off ------------------------------------------------------------------------------------------------
t = scenario("5-wifi-hx-silent", wait=22, ndi_arm=1, ndi_kind=1, ndi_transport="WIFI", ndi_audio=0)
record("5 SOUND IN THE STREAM off sends none", any("sound off sent 0" in l for l in live_lines(t)),
       live_lines(t)[-1] if live_lines(t) else "no NDI LIVE line")

# --- 6. USB chosen, no tether: waits, camera unharmed ----------------------------------------------------------
t = scenario("6-usb-no-cable", ndi_arm=1, ndi_kind=1, ndi_transport="USB", ndi_audio=1, audio_source="auto")
record("6 USB without tether waits and says so", "NDI waiting" in t or "no cable" in t,
       (re.findall(r"NDI waiting.*|no cable.*", t) or ["nothing said"])[-1])
record("6 no sender on Wi-Fi when USB is chosen", not re.search(r"NDI sender on wlan", t.split("TEST settings")[-1]))
d = moving()
record("6 the camera keeps running", d is None or d > 0.6, f"diff {d:.2f}" if d is not None else "not measured")

# --- 7. a take with sound ------------------------------------------------------------------------------------------
launch(ndi_arm=1, ndi_kind=1, ndi_transport="WIFI", ndi_audio=1, audio_source="auto", audio_stereo=1, file_arm=1)
time.sleep(10)
size = sh("shell", "wm", "size")
m = re.search(r"(\d+)x(\d+)", size)
# The record key: the test taps it through uiautomator by its description, not by coordinates.
sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
ui = sh("shell", "cat", "/sdcard/ui.xml")
rec = re.search(r'content-desc="[^"]*(?:[Rr]ecord)[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui)
if rec:
    x = (int(rec.group(1)) + int(rec.group(3))) // 2; y = (int(rec.group(2)) + int(rec.group(4))) // 2
    sh("shell", "input", "tap", str(x), str(y)); time.sleep(10); sh("shell", "input", "tap", str(x), str(y)); time.sleep(3)
    t = trace()
    snd = re.findall(r"take's sound: ([\d.]+) s, (\d)ch", t)
    record("7 the take has sound", bool(snd) and float(snd[-1][0]) > 8, f"{snd[-1][0]} s, {snd[-1][1]}ch" if snd else "no sound line")
    clip = sh("shell", "ls", "-t", "/sdcard/DCIM/Mantra Manual Camera/").split()
    clip = [c for c in clip if c.endswith(".mp4")]
    if clip and shutil.which("ffprobe"):
        local = os.path.join(OUT, clip[0])
        subprocess.run(ADB + ["pull", "/sdcard/DCIM/Mantra Manual Camera/" + clip[0], local], capture_output=True)
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,channels,duration",
                                "-of", "compact", local], capture_output=True, text=True).stdout
        record("7 ffprobe finds an audio track", "codec_type=audio" in probe, probe.replace("\n", " | ")[:200])
else:
    record("7 record key found on screen", False, "not in the uiautomator dump; tap it by hand")

# --- 8. the Mac sees the source ------------------------------------------------------------------------------------
launch(ndi_arm=1, ndi_kind=1, ndi_transport="WIFI", ndi_audio=1, audio_source="auto", audio_stereo=0)
time.sleep(10)
if shutil.which("dns-sd"):
    p = subprocess.Popen(["dns-sd", "-B", "_ndi._tcp"], stdout=subprocess.PIPE, text=True)
    time.sleep(6); p.terminate()
    seen = p.stdout.read()
    record("8 the Mac sees the NDI source (mDNS)", "Add" in seen, " ".join(re.findall(r"_ndi\._tcp\.\s+(.*)", seen))[:160])

# --- 9. by hand: hot-plug the microphone, then the USB tether -----------------------------------------------------
if ask("Pull the Hollyland receiver OUT of the phone"):
    time.sleep(3); t = trace()
    record("9 pulled out → the phone microphone", bool(re.search(r"audio: pulled out.*PHONE MIC", t)))
    ask("Plug the Hollyland receiver back IN")
    time.sleep(3); t = trace()
    record("9 plugged in → back to the USB-C device", bool(re.search(r"audio: plugged in.*→ USB-C", t)))
if ask("Microphone out. Connect the phone to the Mac with the USB cable and turn on USB tethering "
       "(Settings → Network → Hotspot & tethering → USB tethering)"):
    launch(ndi_arm=1, ndi_kind=1, ndi_transport="USB", ndi_audio=1, audio_source="auto")
    time.sleep(15); t = trace()
    on = re.findall(r"NDI sender on ((?:ncm|rndis|usb)\d+ [\d.]+)", t)
    record("10 USB: the sender is on the tether", bool(on), on[-1] if on else "no USB sender line")
    ask("Pull the USB cable out")
    time.sleep(5); t = trace()
    record("10 cable out → the stream stops and waits", "NDI stopped" in t.split("TEST settings")[-1])
    ask("Plug it back in and turn USB tethering on again")
    time.sleep(8); t = trace()
    on2 = re.findall(r"NDI sender on ((?:ncm|rndis|usb)\d+ [\d.]+)", t)
    record("10 cable back → the stream comes back on the tether", len(on2) >= 2, on2[-1] if on2 else "")

# --- report --------------------------------------------------------------------------------------------------------
open(os.path.join(OUT, "trace-last.txt"), "w").write(trace())
passed = sum(1 for _, ok, _ in results if ok)
lines = [f"# v135 field test, {model}, over wireless adb {TARGET}", "",
         f"{passed} of {len(results)} passed.", "", "| | check | detail |", "|---|---|---|"]
lines += [f"| {'PASS' if ok else 'FAIL'} | {n} | {d} |" for n, ok, d in results]
open(os.path.join(OUT, "REPORT.md"), "w").write("\n".join(lines) + "\n")
print(f"\n{passed} of {len(results)} passed. Report: {OUT}/REPORT.md")
