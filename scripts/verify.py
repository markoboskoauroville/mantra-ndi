#!/usr/bin/env python3
"""Structural checks a compiler will not run.

android-app.md: the checks that fail the build for reasons Kotlin cannot see.
Each one prints its own line and can fail on its own.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FAILS = []


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail and not ok else ""))
    if not ok:
        FAILS.append(name)


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
    tracked_keys = list(ROOT.glob("**/*.p12")) + list(ROOT.glob("**/*.jks")) + \
        list(ROOT.glob("**/*.keystore"))
    tracked_keys = [p for p in tracked_keys if ".git" not in p.parts]
    check("G3 no keystore in the tree", not tracked_keys, str(tracked_keys))
    gitignore = (ROOT / ".gitignore").read_text()
    check("G3 signing dir ignored", "signing/" in gitignore)

    # G4 — the licensed SDK is never committed
    sdk = list(ROOT.glob("app/src/main/jniLibs/**/*.so")) + \
        list(ROOT.glob("app/src/main/cpp/ndi/**/*.h"))
    check("G4 no NDI SDK in the tree", not sdk, f"{len(sdk)} files")

    # G5 — Test 1 exists and is not a token gesture
    test = ROOT / "app/src/test/java/com/mantraproductions/ndi/MechanismTest.kt"
    check("G5 Test 1 exists", test.exists())
    if test.exists():
        cases = len(re.findall(r"@Test", test.read_text()))
        check("G5 Test 1 has a floor of cases", cases >= 20, f"{cases} cases")

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
    check("G7 NDI attribution present", "ndi.video" in layouts and "Vizrt" in layouts)

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

    print()
    if FAILS:
        print(f"{len(FAILS)} check(s) failed: {', '.join(FAILS)}")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
