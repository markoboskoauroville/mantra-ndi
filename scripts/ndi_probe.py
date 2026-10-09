"""ndi_probe.py — the receiving end of the NDI testing ground (v136). Runs on the Mac, receives the camera's NDI
stream exactly as OBS or vMix would, and measures it every second. Marko, 9.10.2026: "It works for some time, then
the picture stops and audio is continuing ... The sound has drops ... We need to create some kind of testing ground
to test NDI stability."

    python3 scripts/ndi_probe.py --source "Pixel 7" --seconds 600 [--csv out.csv] [--json]

Needs the NDI runtime for macOS (free): the NDI SDK for Apple (https://ndi.video/for-developers/ndi-sdk/), or
`brew install --cask libndi`. Point NDI_LIB at libndi.dylib if it lives somewhere unusual.

Every second it prints (or with --json, writes one JSON line) what arrived:
  video  frames, the longest gap between two frames (ms), the frame size (so portrait / landscape is checked)
  audio  samples per second against the sample rate, gaps (audio arriving more than 80 ms later than the frame before
         it lasts: what a receiver hears as a drop), level in dBFS
  and a verdict: OK, PICTURE STALL (no frame for 1 s while sound goes on: the bug), SOUND GAP, SILENT, NOTHING.
At the end, a summary; the exit code is 1 when there was any stall or the sound was less than 98 % complete.
"""
import argparse, ctypes, ctypes.util, json, math, os, sys, time

# --- the NDI runtime, through ctypes -------------------------------------------------------------------------------

CANDIDATES = [
    os.environ.get("NDI_LIB", ""),
    "/Library/NDI SDK for Apple/lib/macOS/libndi.dylib",
    "/usr/local/lib/libndi.dylib",
    "/opt/homebrew/lib/libndi.dylib",
    "/Library/Application Support/NewTek/NDI/libndi.dylib",
    ctypes.util.find_library("ndi") or "",
]


def load():
    for path in CANDIDATES:
        if path and os.path.exists(path):
            return ctypes.CDLL(path), path
    sys.exit("No NDI runtime found. Install the NDI SDK for Apple or `brew install --cask libndi`, or set NDI_LIB.")


class Source(ctypes.Structure):
    _fields_ = [("p_ndi_name", ctypes.c_char_p), ("p_url_address", ctypes.c_char_p)]


class FindCreate(ctypes.Structure):
    _fields_ = [("show_local_sources", ctypes.c_bool), ("p_groups", ctypes.c_char_p), ("p_extra_ips", ctypes.c_char_p)]


class RecvCreate(ctypes.Structure):
    _fields_ = [("source_to_connect_to", Source), ("color_format", ctypes.c_int), ("bandwidth", ctypes.c_int),
                ("allow_video_fields", ctypes.c_bool), ("p_ndi_recv_name", ctypes.c_char_p)]


class Video(ctypes.Structure):
    _fields_ = [("xres", ctypes.c_int), ("yres", ctypes.c_int), ("FourCC", ctypes.c_int),
                ("frame_rate_N", ctypes.c_int), ("frame_rate_D", ctypes.c_int), ("picture_aspect_ratio", ctypes.c_float),
                ("frame_format_type", ctypes.c_int), ("timecode", ctypes.c_int64), ("p_data", ctypes.c_void_p),
                ("line_stride_in_bytes", ctypes.c_int), ("p_metadata", ctypes.c_char_p), ("timestamp", ctypes.c_int64)]


class Audio(ctypes.Structure):
    _fields_ = [("sample_rate", ctypes.c_int), ("no_channels", ctypes.c_int), ("no_samples", ctypes.c_int),
                ("timecode", ctypes.c_int64), ("p_data", ctypes.POINTER(ctypes.c_float)),
                ("channel_stride_in_bytes", ctypes.c_int), ("p_metadata", ctypes.c_char_p), ("timestamp", ctypes.c_int64)]


class Meta(ctypes.Structure):
    _fields_ = [("length", ctypes.c_int), ("timecode", ctypes.c_int64), ("p_data", ctypes.c_char_p)]


FRAME_NONE, FRAME_VIDEO, FRAME_AUDIO, FRAME_META, FRAME_ERROR = 0, 1, 2, 3, 4
BANDWIDTH_HIGHEST, COLOR_FASTEST = 100, 100


def bind(lib):
    lib.NDIlib_initialize.restype = ctypes.c_bool
    lib.NDIlib_find_create_v2.restype = ctypes.c_void_p
    lib.NDIlib_find_create_v2.argtypes = [ctypes.POINTER(FindCreate)]
    lib.NDIlib_find_wait_for_sources.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
    lib.NDIlib_find_get_current_sources.restype = ctypes.POINTER(Source)
    lib.NDIlib_find_get_current_sources.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32)]
    lib.NDIlib_recv_create_v3.restype = ctypes.c_void_p
    lib.NDIlib_recv_create_v3.argtypes = [ctypes.POINTER(RecvCreate)]
    lib.NDIlib_recv_capture_v2.restype = ctypes.c_int
    lib.NDIlib_recv_capture_v2.argtypes = [ctypes.c_void_p, ctypes.POINTER(Video), ctypes.POINTER(Audio),
                                           ctypes.POINTER(Meta), ctypes.c_uint32]
    lib.NDIlib_recv_free_video_v2.argtypes = [ctypes.c_void_p, ctypes.POINTER(Video)]
    lib.NDIlib_recv_free_audio_v2.argtypes = [ctypes.c_void_p, ctypes.POINTER(Audio)]
    lib.NDIlib_recv_free_metadata.argtypes = [ctypes.c_void_p, ctypes.POINTER(Meta)]
    lib.NDIlib_recv_destroy.argtypes = [ctypes.c_void_p]
    lib.NDIlib_find_destroy.argtypes = [ctypes.c_void_p]


def find(lib, wanted, extra_ips, timeout):
    fc = FindCreate(True, None, extra_ips.encode() if extra_ips else None)
    f = lib.NDIlib_find_create_v2(ctypes.byref(fc))
    deadline = time.time() + timeout
    names = []
    while time.time() < deadline:
        lib.NDIlib_find_wait_for_sources(f, 1000)
        n = ctypes.c_uint32(0)
        srcs = lib.NDIlib_find_get_current_sources(f, ctypes.byref(n))
        names = [srcs[i].p_ndi_name.decode(errors="replace") for i in range(n.value)]
        for i in range(n.value):
            if wanted.lower() in names[i].lower():
                return f, Source(srcs[i].p_ndi_name, srcs[i].p_url_address), names[i]
    lib.NDIlib_find_destroy(f)
    sys.exit(f"No NDI source matching '{wanted}' in {timeout} s. Seen: {names or 'none'}")


# --- the measurement -----------------------------------------------------------------------------------------------

class Second:
    def __init__(self):
        self.frames = 0
        self.max_gap_ms = 0.0
        self.size = ""
        self.samples = 0
        self.rate = 0
        self.channels = 0
        self.audio_gaps = 0
        self.audio_gap_ms = 0.0
        self.sumsq = 0.0
        self.count = 0
        self.meta = 0


def level_db(sumsq, count):
    if count == 0 or sumsq <= 0:
        return -120.0
    return 20 * math.log10(math.sqrt(sumsq / count))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default="", help="part of the NDI source name (default: the first found)")
    ap.add_argument("--seconds", type=float, default=300)
    ap.add_argument("--extra-ips", default="", help="the phone's address, when discovery cannot reach it (USB tether)")
    ap.add_argument("--csv", default="")
    ap.add_argument("--json", action="store_true", help="one JSON line per second on stdout (for ndi_soak.py)")
    a = ap.parse_args()

    lib, path = load()
    bind(lib)
    if not lib.NDIlib_initialize():
        sys.exit("NDIlib_initialize failed (CPU not supported?)")
    f, src, name = find(lib, a.source, a.extra_ips, 20)
    rc = RecvCreate(src, COLOR_FASTEST, BANDWIDTH_HIGHEST, False, b"mantra-ndi-probe")
    recv = lib.NDIlib_recv_create_v3(ctypes.byref(rc))
    if not recv:
        sys.exit("NDIlib_recv_create_v3 failed")
    if not a.json:
        print(f"probe: {name} via {path}", flush=True)

    csv = open(a.csv, "w") if a.csv else None
    if csv:
        csv.write("t,frames,max_gap_ms,size,samples,rate,channels,audio_gaps,audio_gap_ms,level_db,verdict\n")

    v, au, m = Video(), Audio(), Meta()
    start = time.monotonic()
    tick = start + 1
    sec = Second()
    last_video = None
    last_audio_at = None
    last_audio_len_ms = 0.0
    totals = dict(seconds=0, stalls=0, sound_gaps=0, samples=0, expected=0, longest_video_gap_ms=0.0,
                  sizes=set(), verdicts={})
    while time.monotonic() - start < a.seconds:
        t = lib.NDIlib_recv_capture_v2(recv, ctypes.byref(v), ctypes.byref(au), ctypes.byref(m), 50)
        now = time.monotonic()
        if t == FRAME_VIDEO:
            if last_video is not None:
                sec.max_gap_ms = max(sec.max_gap_ms, (now - last_video) * 1000)
            last_video = now
            sec.frames += 1
            sec.size = f"{v.xres}x{v.yres}"
            lib.NDIlib_recv_free_video_v2(recv, ctypes.byref(v))
        elif t == FRAME_AUDIO:
            sec.samples += au.no_samples
            sec.rate, sec.channels = au.sample_rate, au.no_channels
            # A sound gap is what a receiver hears as a drop: no audio for longer than its buffer covers. Counted on
            # the Mac's clock (arrival), beyond the length of the frame before it plus 80 ms of slack; the sender's
            # own timestamps are its send times, which jitter by design and are not a timeline.
            if last_audio_at is not None:
                late_ms = (now - last_audio_at) * 1000 - last_audio_len_ms
                if late_ms > 80:
                    sec.audio_gaps += 1
                    sec.audio_gap_ms += late_ms
            last_audio_len_ms = au.no_samples * 1000 / au.sample_rate if au.sample_rate else 0
            last_audio_at = now
            if au.p_data and au.no_samples > 0:
                step = max(1, au.no_samples // 64)
                for i in range(0, au.no_samples, step):
                    s = au.p_data[i]
                    sec.sumsq += s * s
                    sec.count += 1
            lib.NDIlib_recv_free_audio_v2(recv, ctypes.byref(au))
        elif t == FRAME_META:
            sec.meta += 1
            lib.NDIlib_recv_free_metadata(recv, ctypes.byref(m))

        if now >= tick:
            gap_now = (now - last_video) * 1000 if last_video else 1e9
            sec.max_gap_ms = max(sec.max_gap_ms, min(gap_now, 1e6))
            sound_alive = last_audio_at is not None and now - last_audio_at < 1.0
            if sec.frames == 0 and not sound_alive:
                verdict = "NOTHING"
            elif sec.max_gap_ms > 1000 and sound_alive:
                verdict = "PICTURE STALL"
            elif sec.audio_gaps:
                verdict = "SOUND GAP"
            elif not sound_alive:
                verdict = "SILENT"
            else:
                verdict = "OK"
            row = dict(t=round(now - start, 1), frames=sec.frames, max_gap_ms=round(sec.max_gap_ms),
                       size=sec.size, samples=sec.samples, rate=sec.rate, channels=sec.channels,
                       audio_gaps=sec.audio_gaps, audio_gap_ms=round(sec.audio_gap_ms),
                       level_db=round(level_db(sec.sumsq, sec.count), 1), verdict=verdict)
            totals["seconds"] += 1
            totals["stalls"] += verdict == "PICTURE STALL"
            totals["sound_gaps"] += sec.audio_gaps
            totals["samples"] += sec.samples
            totals["expected"] += sec.rate or 48000
            totals["longest_video_gap_ms"] = max(totals["longest_video_gap_ms"], row["max_gap_ms"] if sec.frames else 0)
            if sec.size:
                totals["sizes"].add(sec.size)
            totals["verdicts"][verdict] = totals["verdicts"].get(verdict, 0) + 1
            if a.json:
                print(json.dumps(row), flush=True)
            else:
                print(f"{row['t']:7.1f}s  video {sec.frames:3d} fps  gap {row['max_gap_ms']:5d} ms  {sec.size:>10}  "
                      f"audio {sec.samples:6d}/{sec.rate or 0} {sec.channels}ch  gaps {sec.audio_gaps}  "
                      f"{row['level_db']:6.1f} dBFS  {verdict}", flush=True)
            if csv:
                csv.write(",".join(str(row[k]) for k in ["t", "frames", "max_gap_ms", "size", "samples", "rate",
                                                         "channels", "audio_gaps", "audio_gap_ms", "level_db",
                                                         "verdict"]) + "\n")
                csv.flush()
            sec = Second()
            tick += 1

    lib.NDIlib_recv_destroy(recv)
    lib.NDIlib_find_destroy(f)
    completeness = totals["samples"] / totals["expected"] if totals["expected"] else 0
    summary = dict(source=name, seconds=totals["seconds"], picture_stalls=totals["stalls"],
                   sound_gaps=totals["sound_gaps"], sound_complete=round(completeness, 4),
                   longest_video_gap_ms=totals["longest_video_gap_ms"], sizes=sorted(totals["sizes"]),
                   verdicts=totals["verdicts"])
    print(json.dumps({"summary": summary}) if a.json else "\nsummary: " + json.dumps(summary, indent=1), flush=True)
    sys.exit(1 if totals["stalls"] or completeness < 0.98 else 0)


if __name__ == "__main__":
    main()
