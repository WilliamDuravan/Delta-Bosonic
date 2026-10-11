#!/usr/bin/env python3
"""
Tools for Boson Thermal raw recordings (<name>.y16 + <name>.csv).

  python3 y16_tools.py info    REC.y16                      # sizes, timing, dropped-frame report
  python3 y16_tools.py mkv     REC.y16 [-o out.mkv]         # lossless FFV1 16-bit video (needs ffmpeg)
  python3 y16_tools.py preview REC.y16 [options]            # tone-mapped, coloured MP4 (needs ffmpeg + numpy)
  python3 y16_tools.py png     REC.y16 --frame N [options]  # one tone-mapped, coloured frame as PNG (numpy)
  python3 y16_tools.py tiff    REC.y16 --start 0 --count 10 [--outdir frames]   # 16-bit TIFFs

preview / png render the way the phone renders its normal video:
  --tone    minmax (default) | curve | auto
  --palette white,black,iron   any combination, or 'all'. One output per palette (default: white).
            names: white (White-hot), black (Black-hot), iron (Ironbow)
  --curve   T,M,S              CURVE handles, defaults 0.12,0.5,0.88
  --auto-sens gentle|normal|strong   how readily AUTO compression engages (default normal)
  --min-range N                narrowest span stretched to full contrast (default 64, as on the phone)
  --scale N                    output size multiple of 320x256 (default 2, as the phone's MP4)
  --start A --end B            frame range (B exclusive). Frames before A are used to warm up the
                               tone state so the result matches what the phone produced.
Starting at frame 0 reproduces the phone's own processing exactly. For a GUI, run y16_gui.py.

The .csv next to the .y16 (same base name) is read automatically; width/height are also parsed
from the filename if the csv is missing.
"""
import argparse
import os
import re
import statistics
import struct
import subprocess
import sys


def read_meta(y16_path):
    base = os.path.splitext(y16_path)[0]
    meta, rows = {}, []
    csv_path = base + ".csv"
    m = re.search(r"_(\d+)x(\d+)_gray16le_(\d+)fps", os.path.basename(y16_path))
    if m:
        meta["width"], meta["height"], meta["nominal_fps"] = m.group(1), m.group(2), m.group(3)
    if os.path.exists(csv_path):
        with open(csv_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("#"):
                    for k, v in re.findall(r"(\w+)=([^\s,]+)", line):
                        meta[k] = v
                elif line and not line.startswith("file_index"):
                    a = line.split(",")
                    rows.append((int(a[0]), int(a[1]), int(a[2]), int(a[3])))
    else:
        print("note: no .csv found next to the .y16; using filename for dimensions", file=sys.stderr)
    if "width" not in meta:
        sys.exit("cannot determine width/height (need the .csv or the original filename)")
    meta["width"], meta["height"] = int(meta["width"]), int(meta["height"])
    meta["nominal_fps"] = int(meta.get("nominal_fps", 60))
    meta["frame_bytes"] = meta["width"] * meta["height"] * 2
    return meta, rows


def cmd_info(a):
    meta, rows = read_meta(a.file)
    fb = meta["frame_bytes"]
    size = os.path.getsize(a.file)
    n = size // fb
    print(f"file: {a.file}")
    print(f"size: {size/1e6:.1f} MB = {n} frames of {meta['width']}x{meta['height']} gray16le"
          + (f"  (+{size % fb} stray bytes!)" if size % fb else ""))
    print(f"nominal fps: {meta['nominal_fps']}   camera serial: {meta.get('camera_serial','?')}")
    if not rows:
        return
    if len(rows) != n:
        print(f"WARNING: csv has {len(rows)} rows but file holds {n} frames")
    t = [r[2] for r in rows]
    if len(t) > 1 and t[-1] > t[0]:
        dur = (t[-1] - t[0]) / 1e6
        print(f"duration: {dur:.2f} s   measured rate: {(len(t)-1)/dur:.2f} fps")
    usb = [r[1] for r in rows]
    lost = sum(max(0, b - a_ - 1) for a_, b in zip(usb, usb[1:]))
    print(f"frames lost inside the app (usb_frame gaps): {lost}")
    pts = [r[3] for r in rows if r[3] >= 0]
    if len(pts) > 2:
        d = [(b - a_) % (1 << 32) for a_, b in zip(pts, pts[1:])]
        med = statistics.median(d)
        gaps = sum(max(0, round(x / med) - 1) for x in d if med > 0 and x > 1.5 * med)
        print(f"camera timestamp step (median): {med:.0f} ticks; frames missing per camera timestamps: {gaps}")
    if "stopped" in meta:
        print(f"recording stopped early: {meta['stopped']}")


def cmd_mkv(a):
    meta, _ = read_meta(a.file)
    out = a.output or os.path.splitext(a.file)[0] + ".mkv"
    cmd = ["ffmpeg", "-y", "-f", "rawvideo", "-pixel_format", "gray16le",
           "-video_size", f"{meta['width']}x{meta['height']}", "-framerate", str(meta["nominal_fps"]),
           "-i", a.file, "-c:v", "ffv1", out]
    print(" ".join(cmd))
    subprocess.run(cmd, check=True)


def _tone_settings(a):
    import boson_core as core
    mode = {"minmax": "MIN/MAX", "curve": "CURVE", "auto": "AUTO"}[a.tone]
    t, m, sh = core.DEF_T, core.DEF_M, core.DEF_S
    if a.curve:
        try:
            t, m, sh = (float(x) for x in a.curve.split(","))
        except ValueError:
            sys.exit("--curve needs three numbers: T,M,S (e.g. 0.12,0.5,0.88)")
        if not core.curve_valid(t, m, sh):
            sys.exit(f"--curve invalid: need {core.T_MIN} <= T, T+{core.CURVE_GAP} <= M, "
                     f"M+{core.CURVE_GAP} <= S, S <= {core.S_MAX}")
    sens = ["gentle", "normal", "strong"].index(a.auto_sens)
    return core.ToneSettings(mode=mode, min_range=a.min_range, curve_t=t, curve_m=m, curve_s=sh, auto_sens=sens)


def _palettes(a):
    import boson_core as core
    try:
        return core.parse_palettes(a.palette)
    except ValueError as e:
        sys.exit(str(e))


def _out_paths(a, palettes, ext):
    """One output path per palette. -o is a path (one palette) or a base name (several)."""
    tag = {"minmax": "minmax", "curve": "curve", "auto": "auto"}[a.tone]
    short = {"White-hot": "white", "Black-hot": "black", "Ironbow": "iron"}
    base = os.path.splitext(a.output)[0] if a.output else os.path.splitext(a.file)[0] + "_" + tag
    if a.output and len(palettes) == 1:
        return {palettes[0]: a.output}
    return {p: f"{base}_{short[p]}{ext}" for p in palettes}


def cmd_preview(a):
    import shutil
    import boson_core as core
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg not found on PATH (needed for MP4 output). Use the png command for stills.")
    rec = core.open_recording(a.file)
    palettes = _palettes(a)
    proc = core.ThermalProcessor(rec.width, rec.height)
    proc.settings = _tone_settings(a)
    start = max(0, a.start)
    end = rec.frames if a.end is None else min(a.end, rec.frames)
    if start >= end:
        sys.exit("empty frame range")
    outs = _out_paths(a, palettes, ".mp4")
    vf = ["-vf", f"scale=iw*{a.scale}:ih*{a.scale}:flags=bilinear"] if a.scale != 1 else []
    procs = {}
    for pal, path in outs.items():
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
               "-video_size", f"{rec.width}x{rec.height}", "-framerate", str(rec.nominal_fps), "-i", "-"] + vf + \
              ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", path]
        procs[pal] = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    mm = rec.open_memmap()
    n = end - start
    try:
        for i, g in core.iter_grey(mm, proc, start, end):
            for pal, p in procs.items():
                p.stdin.write(core.apply_palette(g, pal).tobytes())
            done = i - start + 1
            if done % 100 == 0 or done == n:
                print(f"\r{done}/{n} frames", end="", file=sys.stderr, flush=True)
    except BrokenPipeError:
        sys.exit("\nffmpeg exited early; run again without -loglevel error to see why")
    finally:
        for p in procs.values():
            try:
                p.stdin.close()
            except Exception:
                pass
        for p in procs.values():
            p.wait()
    print(file=sys.stderr)
    for pal, path in outs.items():
        print(f"wrote {path}  ({pal})")


def cmd_png(a):
    import boson_core as core
    rec = core.open_recording(a.file)
    palettes = _palettes(a)
    if not 0 <= a.frame < rec.frames:
        sys.exit(f"--frame must be 0..{rec.frames - 1}")
    proc = core.ThermalProcessor(rec.width, rec.height)
    proc.settings = _tone_settings(a)
    g = core.render_frame(rec.open_memmap(), a.frame, proc)
    outs = _out_paths(a, palettes, f"_f{a.frame:06d}.png")
    for pal, path in outs.items():
        if a.output and len(palettes) == 1:
            path = a.output
        core.write_png(path, core.upscale(core.apply_palette(g, pal), a.scale))
        print(f"wrote {path}  ({pal})")


def tiff16(raw, w, h):
    entries = 9
    data_off = 8 + 2 + entries * 12 + 4
    def se(tag, v): return struct.pack("<HHIHH", tag, 3, 1, v, 0)
    def le(tag, v): return struct.pack("<HHII", tag, 4, 1, v)
    ifd = (se(256, w) + se(257, h) + se(258, 16) + se(259, 1) + se(262, 1) +
           le(273, data_off) + se(277, 1) + se(278, h) + le(279, len(raw)))
    return b"II" + struct.pack("<HI", 42, 8) + struct.pack("<H", entries) + ifd + struct.pack("<I", 0) + raw


def cmd_tiff(a):
    meta, _ = read_meta(a.file)
    w, h, fb = meta["width"], meta["height"], meta["frame_bytes"]
    os.makedirs(a.outdir, exist_ok=True)
    with open(a.file, "rb") as f:
        f.seek(a.start * fb)
        for i in range(a.count):
            buf = f.read(fb)
            if len(buf) < fb:
                break
            path = os.path.join(a.outdir, f"frame_{a.start + i:06d}.tif")
            with open(path, "wb") as o:
                o.write(tiff16(buf, w, h))
    print("wrote TIFFs to", a.outdir)


def _add_render_args(sp):
    sp.add_argument("-o", "--output", help="output path (one palette) or base name (several palettes)")
    sp.add_argument("--tone", choices=["minmax", "curve", "auto"], default="minmax")
    sp.add_argument("--palette", default="white", help="white,black,iron (any combination) or all")
    sp.add_argument("--curve", help="T,M,S handles for --tone curve (default 0.12,0.5,0.88)")
    sp.add_argument("--auto-sens", choices=["gentle", "normal", "strong"], default="normal")
    sp.add_argument("--min-range", type=float, default=64.0)
    sp.add_argument("--scale", type=int, default=2, choices=[1, 2, 3, 4])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, fn in (("info", cmd_info), ("mkv", cmd_mkv), ("preview", cmd_preview), ("png", cmd_png), ("tiff", cmd_tiff)):
        sp = sub.add_parser(name)
        sp.add_argument("file")
        sp.set_defaults(fn=fn)
        if name == "mkv":
            sp.add_argument("-o", "--output")
        if name == "preview":
            _add_render_args(sp)
            sp.add_argument("--start", type=int, default=0)
            sp.add_argument("--end", type=int, default=None)
        if name == "png":
            _add_render_args(sp)
            sp.add_argument("--frame", type=int, default=0)
        if name == "tiff":
            sp.add_argument("--start", type=int, default=0)
            sp.add_argument("--count", type=int, default=1)
            sp.add_argument("--outdir", default="frames")
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
