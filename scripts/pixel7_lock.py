"""pixel7_lock.py — the Pixel 7 is shared with the DJ Mantra project (another Claude session tests com.djmantra.app on
the same phone). One project at a time, through the lock on Marko's Mac: ~/pixel7/pixel7-lock.sh (state in
~/pixel7/lock/info, history in ~/pixel7/log.txt; rules in djmantra_app docs/PIXEL7_SHARING.md).

The phone scripts (field_test.py, ndi_soak.py) take the lock before their first adb command and give it back when
they end, however they end, with this app stopped, the screen on and the home screen showing. Never touch
com.djmantra.app.
"""
import atexit, os, subprocess, sys

LOCK = os.path.expanduser("~/pixel7/pixel7-lock.sh")
PROJECT = "camera"
_held = False


def take(minutes, what, wait=False):
    """Exit 0 from the lock script: the phone is ours. Exit 1: someone else has it (printed); we stop here."""
    global _held
    if not os.path.exists(LOCK):
        sys.exit(f"{LOCK} not found: the Pixel 7 is shared, and without the lock this script will not touch it.")
    r = subprocess.run([LOCK, "wait" if wait else "take", PROJECT, str(int(minutes)), what], text=True)
    if r.returncode != 0:
        sys.exit("The Pixel 7 is in use by another project (see above). Try again later, or run with --wait.")
    _held = True
    print(f"pixel7 lock: taken for {int(minutes)} min ({what})", flush=True)


def extend(minutes):
    if _held:
        subprocess.run([LOCK, "extend", PROJECT, str(int(minutes))])


def give(adb):
    """Stop the camera app, screen on, home screen, then give the lock back. Safe to call twice."""
    global _held
    if not _held:
        return
    for args in (["shell", "am", "force-stop", "com.mantraproductions.ndi"],
                 ["shell", "settings", "put", "system", "accelerometer_rotation", "1"],
                 ["shell", "input", "keyevent", "KEYCODE_WAKEUP"],
                 ["shell", "input", "keyevent", "KEYCODE_HOME"]):
        subprocess.run(adb + args, capture_output=True, timeout=30)
    subprocess.run([LOCK, "give", PROJECT])
    _held = False
    print("pixel7 lock: given back (app stopped, screen on, home screen)", flush=True)


def hold_for_this_run(adb, minutes, what, wait=False):
    """Take now and give at exit (normal end, failure, Ctrl-C)."""
    take(minutes, what, wait)
    atexit.register(give, adb)
