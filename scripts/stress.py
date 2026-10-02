"""stress.py <serial> — drive Mantra Manual Camera on a real phone, one risky change at a time, and report where it
freezes. After each step: the status line's fps (read off the screen through uiautomator) and every new REFUSED /
freeze line in the app's own trace. Written 2.10.2026 for the Nothing Phone 2a (item 31): "stress test my camera on
noting phone a to see where it freezes".

The keys are found by what they say (content-desc / text), not by coordinates, so the script survives layout changes.
"""
import re, subprocess, sys, time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "0005534BC000140"
ADB = ["/Users/markobosko/Library/Android/sdk/platform-tools/adb", "-s", SERIAL]
FILES = "/sdcard/Android/data/com.mantraproductions.ndi/files/"


def sh(*a, timeout=30):
    return subprocess.run(ADB + list(a), capture_output=True, text=True, timeout=timeout).stdout


def screen():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh("shell", "cat", "/sdcard/ui.xml")


def nodes(xml):
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        text = re.search(r' text="([^"]*)"', n).group(1)
        desc = re.search(r'content-desc="([^"]*)"', n).group(1)
        b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', n).group(1))))
        yield text, desc, ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def tap_word(word, xml=None):
    xml = xml or screen()
    for text, desc, (x, y) in nodes(xml):
        first = re.split(r"[ ,]", desc)[0] if desc else ""
        if text == word or first == word:
            sh("shell", "input", "tap", str(x), str(y))
            return True
    return False


def status():
    for text, desc, _ in nodes(screen()):
        if " fps" in text or "·" in text:
            return text
    return ""


def trace_file():
    return sh("shell", "ls", "-t", FILES).split()[0].strip()


def trace_lines(f):
    return sh("shell", "cat", FILES + f).splitlines()


def frame():
    import io
    from PIL import Image
    png = subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout
    im = Image.open(io.BytesIO(png)).convert("L")
    w, h = im.size
    return im.crop((w // 8, h // 5, w * 7 // 8, h * 4 // 5)).resize((120, 160))


def moving():
    """Is the picture alive? Two looks a second apart; a live sensor never gives two identical pictures."""
    a = frame(); time.sleep(1.0); b = frame()
    diff = sum(abs(x - y) for x, y in zip(a.getdata(), b.getdata())) / (120 * 160)
    return diff


report = []


def step(name, action, wait=3.5):
    f = trace_file()
    before = len(trace_lines(f))
    ok = action()
    time.sleep(wait)
    f2 = trace_file()
    new = trace_lines(f2)[before if f2 == f else 0:]
    bad = [l for l in new if "REFUSED" in l or "freeze" in l.lower() or "not supported" in l or "FAULT" in l]
    st = status()
    fps = round(moving(), 2)
    verdict = "FROZEN" if fps < 0.05 else "ok"
    line = f"{name:34s} pressed={ok!s:5s} motion={fps:<6}  {verdict}   [{st[:60]}]"
    print(line)
    for l in bad[-4:]:
        print("      ", l[24:].strip()[:150])
    report.append((name, ok, fps, verdict, bad))


if __name__ == "__main__":
    print(f"stress test on {SERIAL}")
    for i in range(6):
        step(f"LOG curve, step {i + 1}", lambda: tap_word("LOG"))
    for i in range(4):
        step(f"M (exposure mode), step {i + 1}", lambda: tap_word("M"))
    for i in range(3):
        step(f"focus key, step {i + 1}", lambda: tap_word("AF") or tap_word("TRK") or tap_word("MF"))
    for w in ["PEAK", "FALSE", "ZEBRA", "PEAK", "FALSE", "ZEBRA"]:
        step(f"{w} toggle", lambda w=w: tap_word(w), wait=2)
    # every lens
    xml = screen()
    tap_word("L", xml)
    time.sleep(1)
    lenses = [re.split(r"[ ,]", d)[0] for t, d, _ in nodes(screen()) if d and re.fullmatch(r"L\d", re.split(r"[ ,]", d)[0])]
    sh("shell", "input", "keyevent", "KEYCODE_BACK") if not lenses else None
    for lw in sorted(set(lenses)):
        step(f"lens {lw}", lambda lw=lw: (tap_word("L"), time.sleep(0.8), tap_word(lw))[-1], wait=5)
    print()
    print("SUMMARY")
    for name, ok, fps, verdict, bad in report:
        if verdict != "ok" or bad:
            print(f"  {name}: {verdict}, {len(bad)} refusal line(s)")
