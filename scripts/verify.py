#!/usr/bin/env python3
"""Structural checks a compiler will not run.

android-app.md: the checks that fail the build for reasons Kotlin cannot see.
Each one prints its own line and can fail on its own.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FAILS = []


def code_only(text):
    """The source with its comments taken out.

    checking-the-checks.md: a check that matches its own comment. G15 fired
    on the first run because Trace.kt's KDoc explains why it does NOT use a
    BufferedWriter, and the gate read the explanation as the fault. A gate
    over what the code DOES must not be able to see what the code SAYS.
    """
    out = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", out)


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail and not ok else ""))
    if not ok:
        FAILS.append(name)


def _git_tracked():
    """Every path git has under version control, or nothing if this is no repo."""
    try:
        out = subprocess.run(
            ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True
        ).stdout
        return [line for line in out.splitlines() if line]
    except Exception:
        return []


def main():
    # G1 — the version is written in exactly one place
    props = (ROOT / "gradle.properties").read_text()
    versions = re.findall(r"^appVersion=(\d+)$", props, re.M)
    check("G1 appVersion present and whole", len(versions) == 1 and versions[0].isdigit(),
          f"found {versions}")

    gradle = (ROOT / "app/build.gradle.kts").read_text()
    check("G1 versionCode reads appVersion", "versionCode = appVersion" in gradle)
    check("G1 versionName reads appVersion", "versionName = appVersion.toString()" in gradle)
    check("G1 no hardcoded version", not re.search(r'versionName\s*=\s*"[\d.]+"', gradle))

    # G2 — the mechanism stays free of Android so Test 1 runs on a desk
    mechanism = ROOT / "app/src/main/java/com/mantraproductions/ndi/Mechanism.kt"
    check("G2 Mechanism.kt exists", mechanism.exists())
    if mechanism.exists():
        text = mechanism.read_text()
        # Real import lines only; the module's own prose mentions the rule.
        android_imports = re.findall(r"^\s*import android\.", text, re.M)
        check("G2 Mechanism imports no Android", not android_imports,
              f"{len(android_imports)} android imports")

    # G3 — the signing key is never committed
    #
    # Asked of git rather than of the filesystem. A local build legitimately
    # has the key and the SDK on disk — that is how an APK gets made here —
    # and the thing being prevented is committing them to a public repo, which
    # is a question only git can answer. Globbing the tree made this gate fail
    # on every machine that could actually build the app, which is the kind of
    # gate people learn to ignore.
    tracked = set(_git_tracked())
    tracked_keys = [p for p in tracked if p.endswith((".p12", ".jks", ".keystore"))]
    check("G3 no keystore in the tree", not tracked_keys, str(tracked_keys))
    gitignore = (ROOT / ".gitignore").read_text()
    check("G3 signing dir ignored", "signing/" in gitignore)

    # G4 — the licensed SDK is never committed
    sdk = [p for p in tracked
           if (p.startswith("app/src/main/jniLibs/") and p.endswith(".so"))
           or (p.startswith("app/src/main/cpp/ndi/") and p.endswith(".h"))]
    check("G4 no NDI SDK in the tree", not sdk, f"{len(sdk)} files")

    # G5 — Test 1 exists and is not a token gesture
    test_dir = ROOT / "app/src/test/java/com/mantraproductions/ndi"
    test_files = list(test_dir.glob("*Test.kt"))
    check("G5 Test 1 exists", bool(test_files))
    cases = sum(len(re.findall(r"@Test", f.read_text())) for f in test_files)
    check("G5 Test 1 has a floor of cases", cases >= 20, f"{cases} cases")

    # G12 — the colour maths must import nothing from Android either, or the
    # curves can only be checked on a phone, which means they are not checked.
    for pure in ("LogCurves.kt", "CubeLut.kt", "Histogram.kt", "WhiteBalance.kt"):
        path = ROOT / "app/src/main/java/com/mantraproductions/ndi" / pure
        if path.exists():
            bad = re.findall(r"^\s*import android\.", path.read_text(), re.M)
            check(f"G12 {pure} is pure", not bad, f"{len(bad)} android imports")

    # G6 — every native method declared in Kotlin has a JNI symbol in C++
    cpp = "\n".join(p.read_text() for p in (ROOT / "app/src/main/cpp").glob("*.cpp"))
    missing = []
    for kt in (ROOT / "app/src/main/java/com/mantraproductions/ndi").glob("*.kt"):
        holder = kt.stem
        for method in re.findall(r"external fun (\w+)", kt.read_text()):
            if f"_{holder}_{method}(" not in cpp:
                missing.append(f"{holder}.{method}")
    check("G6 every external fun has a JNI symbol", not missing, ", ".join(missing))

    # G7 — the NDI attribution the licence requires is still in the UI
    layouts = "\n".join(p.read_text() for p in (ROOT / "app/src/main/res/layout").glob("*.xml"))
    # The strings too, not only the layouts. A TextView carrying
    # @string/ndi_mark puts the attribution on the screen just as surely as a
    # hard-coded one, and a gate that cannot see it teaches people to inline
    # text that belongs in resources.
    ui_text = layouts + "\n" + "\n".join(
        p.read_text() for p in (ROOT / "app/src/main/res/values").glob("*.xml")
    )
    check("G7 NDI attribution present", "ndi.video" in ui_text and "Vizrt" in ui_text)

    # G8 — every Activity in the source is declared in the manifest
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
    undeclared = []
    for kt in (ROOT / "app/src/main/java/com/mantraproductions/ndi").glob("*Activity.kt"):
        if f'.{kt.stem}"' not in manifest:
            undeclared.append(kt.stem)
    check("G8 activities declared", not undeclared, ", ".join(undeclared))

    # G9 — custom views used in layouts actually exist
    missing_views = []
    for view in set(re.findall(r"<com\.mantraproductions\.ndi\.(\w+)", layouts)):
        if not (ROOT / f"app/src/main/java/com/mantraproductions/ndi/{view}.kt").exists():
            missing_views.append(view)
    check("G9 custom views exist", not missing_views, ", ".join(missing_views))

    # G11 — updatable.md: the expected certificate is recorded, so CI can
    # compare rather than merely print. Without this the app silently becomes
    # un-updatable and nobody finds out until an install is refused.
    pin = ROOT / "SIGNING_FINGERPRINT.txt"
    digest = pin.read_text().strip() if pin.exists() else ""
    check("G11 signing fingerprint pinned", len(digest) == 64 and
          all(c in "0123456789abcdef" for c in digest), digest[:16])

    workflow = (ROOT / ".github/workflows/build.yml").read_text()
    check("G11 CI compares the fingerprint", "SIGNING_FINGERPRINT.txt" in workflow)
    check("G11 CI fails without a key", "No signing key" in workflow)

    # G10 — every layout is well formed. A duplicate attribute is valid enough
    # for a text editor and fatal to the resource merger.
    import xml.dom.minidom
    bad_layouts = []
    for layout in (ROOT / "app/src/main/res").rglob("*.xml"):
        try:
            xml.dom.minidom.parse(str(layout))
        except Exception as exc:
            bad_layouts.append(f"{layout.name}: {exc}")
    check("G10 all XML resources parse", not bad_layouts, "; ".join(bad_layouts))

    # G13 — no id declared twice in one layout. The data binding generator
    # rejects it, and it is the natural result of an edit that inserts a block
    # twice, which a human reading the file will not notice.
    duplicate_ids = []
    for layout in (ROOT / "app/src/main/res/layout").glob("*.xml"):
        ids = re.findall(r'android:id="@\+id/(\w+)"', layout.read_text())
        seen = set()
        for name in ids:
            if name in seen:
                duplicate_ids.append(f"{layout.name}:{name}")
            seen.add(name)
    check("G13 no duplicate ids in a layout", not duplicate_ids, ", ".join(duplicate_ids))

    # --- the logging. Phase zero of the rebuild, and the three faults below
    # are the ones that cost ten versions of guessing in the last build.

    src = ROOT / "app/src/main/java/com/mantraproductions/ndi"

    # G14 — the trace formatting must stay testable on a desk. The moment it
    # imports Android, the clock and the columns can only be checked by
    # reading a file off a phone, which means they are not checked.
    fmt = src / "TraceFormat.kt"
    check("G14 TraceFormat.kt exists", fmt.exists())
    if fmt.exists():
        bad = re.findall(r"^\s*import android\.", fmt.read_text(), re.M)
        check("G14 TraceFormat imports no Android", not bad, f"{len(bad)} android imports")

    # G15 — the trace is written as it happens. A BufferedWriter holds the
    # last few kilobytes in memory, and those are exactly the lines that say
    # what the app was doing when it died.
    trace = src / "Trace.kt"
    check("G15 Trace.kt exists", trace.exists())
    if trace.exists():
        text = code_only(trace.read_text())
        buffered = [w for w in ("BufferedWriter", "BufferedOutputStream") if w in text]
        check("G15 the trace is unbuffered", not buffered, ", ".join(buffered))

    # G16 — a rejected shader arrives as an Error and walks past a catch on
    # Exception. The three files whose whole job is to survive a fault must
    # catch Throwable, and must not narrow it anywhere.
    narrowed = []
    caught = []
    for name in ("Trace.kt", "CrashLog.kt", "Downloads.kt"):
        path = src / name
        if not path.exists():
            narrowed.append(f"{name} missing")
            continue
        text = code_only(path.read_text())
        if re.search(r"catch\s*\(\s*\w+\s*:\s*Exception\s*\)", text):
            narrowed.append(name)
        if "Throwable" not in text:
            caught.append(name)
    check("G16 the survivors catch Throwable", not caught, ", ".join(caught))
    check("G16 no catch narrowed to Exception", not narrowed, ", ".join(narrowed))

    # G17 — the public folder is reached through MediaStore. A raw File path
    # into Downloads is refused silently from Android 10, which is why the old
    # crash reporter never wrote a single file.
    downloads = src / "Downloads.kt"
    if downloads.exists():
        text = code_only(downloads.read_text())
        check("G17 Downloads goes through MediaStore",
              "MediaStore.Downloads.EXTERNAL_CONTENT_URI" in text)

    print()
    if FAILS:
        print(f"{len(FAILS)} check(s) failed: {', '.join(FAILS)}")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
