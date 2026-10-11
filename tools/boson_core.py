"""
Shared processing for the Boson Thermal PC tools: a numpy port of the phone app's pipeline, so a raw
recording can be rendered exactly the way the phone renders its normal video.

  Y16 frame -> tone map (MIN/MAX, CURVE or AUTO) -> 8-bit grey index -> palette LUT -> RGB

Ports of: ToneMapper.kt (MIN/MAX and CURVE), ToneCurve.kt, AutoMapper.kt, Palettes.kt.
Like the phone, the tone mappers are stateful: the display range, noise estimate, AUTO strength and
AUTO curve are low-passed from frame to frame. Process frames in order for an exact match with the
phone; to start at an arbitrary frame, "warm up" on the ~60 frames before it (see WARMUP_FRAMES).

Requires numpy.
"""
import os
import re
import struct
import sys
import zlib
from dataclasses import dataclass, field

import numpy as np

F32 = np.float32

PALETTE_NAMES = ("White-hot", "Black-hot", "Ironbow")
TONE_NAMES = ("MIN/MAX", "CURVE", "AUTO")
AUTO_SENS_NAMES = ("Gentle", "Normal", "Strong")
MIN_RANGE_CHOICES = (64, 150, 300, 600)

# Frames to process before the one you want, when starting mid-clip (state converges to well under 1 grey level).
WARMUP_FRAMES = 60


# ---------------------------------------------------------------------------------------- palettes

def _ironbow():
    stops = [
        (0.00, 0, 0, 0), (0.14, 24, 0, 86), (0.28, 86, 0, 140), (0.42, 150, 10, 130),
        (0.56, 205, 40, 80), (0.68, 236, 90, 25), (0.80, 250, 150, 0), (0.90, 255, 210, 50),
        (1.00, 255, 255, 255),
    ]
    lut = np.zeros((256, 3), np.uint8)
    for i in range(256):
        t = F32(i) / F32(255)
        k = 0
        while k < len(stops) - 2 and t > F32(stops[k + 1][0]):
            k += 1
        a, b = stops[k], stops[k + 1]
        u = float(np.clip((t - F32(a[0])) / F32(b[0] - a[0]), 0, 1))
        for c in range(3):
            lut[i, c] = int(np.clip(int(F32(a[c + 1]) + F32(b[c + 1] - a[c + 1]) * F32(u)), 0, 255))
    return lut


def _gray(inverted):
    g = np.arange(256, dtype=np.uint8)
    if inverted:
        g = 255 - g
    return np.stack([g, g, g], axis=1)


PALETTES = {
    "White-hot": _gray(False),
    "Black-hot": _gray(True),
    "Ironbow": _ironbow(),
}

# Short names accepted on the command line.
PALETTE_ALIASES = {
    "white": "White-hot", "white-hot": "White-hot", "whitehot": "White-hot", "w": "White-hot",
    "black": "Black-hot", "black-hot": "Black-hot", "blackhot": "Black-hot", "b": "Black-hot",
    "iron": "Ironbow", "ironbow": "Ironbow", "i": "Ironbow",
}


def parse_palettes(text):
    """'white,iron' / 'all' -> ['White-hot', 'Ironbow'] (order of PALETTE_NAMES, at least one)."""
    if text is None or not text.strip():
        return ["White-hot"]
    if text.strip().lower() == "all":
        return list(PALETTE_NAMES)
    out = set()
    for tok in re.split(r"[,\s+]+", text.strip().lower()):
        if not tok:
            continue
        if tok not in PALETTE_ALIASES:
            raise ValueError(f"unknown palette '{tok}' (use white, black, iron or all)")
        out.add(PALETTE_ALIASES[tok])
    if not out:
        raise ValueError("at least one palette is required")
    return [n for n in PALETTE_NAMES if n in out]


def apply_palette(grey_idx, palette_name):
    """uint8 index image (H, W) -> RGB uint8 (H, W, 3)."""
    return PALETTES[palette_name][grey_idx]


# ----------------------------------------------------------------------------------- tone curve

CURVE_N = 4096
CURVE_U0 = -2.0
CURVE_U1 = 3.0
CURVE_K = F32((CURVE_N - 1) / (CURVE_U1 - CURVE_U0))
DEF_T, DEF_M, DEF_S = 0.12, 0.5, 0.88
CURVE_GAP = 0.05
T_MIN = 0.02
S_MAX = 0.98


def curve_valid(t, m, s):
    return t >= T_MIN - 1e-9 and t + CURVE_GAP <= m + 1e-9 and m + CURVE_GAP <= s + 1e-9 and s <= S_MAX + 1e-9


def curve_eval(u, t, m, s):
    """ToneCurve.eval for an array of u (float64 math, as the Kotlin does)."""
    u = np.asarray(u, dtype=np.float64)
    z = np.where(
        u < t, t * np.exp((u - t) / t),
        np.where(u > s, s + (1.0 - s) * (1.0 - np.exp(-(u - s) / (1.0 - s))), u),
    )
    gamma = np.log(0.5) / np.log(m)
    with np.errstate(divide="ignore", invalid="ignore"):
        out = np.where(z <= 0.0, 0.0, np.power(np.maximum(z, 1e-300), gamma))
    return out


def build_curve_lut(t, m, s):
    u = CURVE_U0 + (CURVE_U1 - CURVE_U0) * np.arange(CURVE_N) / (CURVE_N - 1)
    e = curve_eval(u, t, m, s).astype(F32)
    return np.clip((e * F32(255) + F32(0.5)).astype(np.int64), 0, 255)


# ---------------------------------------------------------------------------------------- settings

@dataclass
class ToneSettings:
    mode: str = "MIN/MAX"          # one of TONE_NAMES
    min_range: float = 64.0
    curve_t: float = DEF_T
    curve_m: float = DEF_M
    curve_s: float = DEF_S
    auto_sens: int = 1             # 0 gentle, 1 normal, 2 strong


# ------------------------------------------------------------------------------------- processor

NB = 1024
AUTO_S_MAX = 0.7
_PLATEAU = 0.02
_NOISE_K = 0.012
_KERNEL_SIGMA = 2.0
_DIFF_BINS = 1024


def _make_kernel():
    r = int(4.0 * _KERNEL_SIGMA + 0.5)
    x = np.arange(-r, r + 1, dtype=np.float64)
    k = np.exp(-x * x / (2.0 * _KERNEL_SIGMA * _KERNEL_SIGMA))
    return k / k.sum()


_KERNEL = _make_kernel()


class ThermalProcessor:
    """Stateful frame processor. process(frame) -> uint8 grey index image (H, W)."""

    def __init__(self, width, height):
        self.w, self.h = width, height
        self.total = width * height
        self.settings = ToneSettings()
        self._curve_cache_key = None
        self._curve_lut = None
        self.reset()

    # -- state -----------------------------------------------------------------------------------
    def reset(self):
        self._have_range = False
        self.lo = 0.0
        self.hi = 0.0
        self.range_base = 0.0
        self.range_span = 1.0
        self._have_auto = False
        self._strength_sm = 0.0
        self._sigma_sm = 6.0
        self._lut_sm = None
        self.bulk = 1.0
        self.strength = 0.0
        self.sigma = 0.0
        self._last_mode = None

    def process(self, frame, output=True):
        """frame: (H, W) uint16 array. Returns uint8 (H, W) grey index.

        With output=False only the temporal state is advanced (used for warm-up frames) and None is
        returned; this skips the per-pixel mapping, about half the work.
        """
        st = self.settings
        if st.mode != self._last_mode:
            # The phone starts the newly selected mapper from a fresh state.
            self._have_range = False
            self._have_auto = False
            self._last_mode = st.mode
        v = np.ascontiguousarray(frame, dtype=np.uint16).astype(np.int64).ravel()
        if st.mode == "AUTO":
            idx = self._auto(v, output)
        else:
            idx = self._tone(v, st.mode == "CURVE", output)
        if idx is None:
            return None
        return idx.reshape(self.h, self.w).astype(np.uint8)

    # -- MIN/MAX and CURVE (ToneMapper.kt) -------------------------------------------------------
    def _tone(self, v, curve, output=True):
        st = self.settings
        total = self.total
        hist = np.bincount(v >> 2, minlength=16384)
        cum = np.cumsum(hist)
        low_target = max(1, total // 1000)
        high_target = total - max(1, total // 2000)
        lo_bin = int(np.searchsorted(cum, low_target, side="left"))
        hi_bin = int(np.searchsorted(cum, high_target, side="left"))
        lo_v = F32(lo_bin * 4.0)
        hi_v = F32(hi_bin * 4.0 + 3.0)
        if not self._have_range:
            self.lo, self.hi = F32(lo_v), F32(hi_v)
            self._have_range = True
        else:
            self.lo = F32(self.lo + (lo_v - self.lo) * F32(0.1))
            self.hi = F32(self.hi + (hi_v - self.hi) * F32(0.1))
        base = F32(self.lo)
        rng = F32(self.hi - self.lo)
        mr = F32(st.min_range)
        if rng < mr:
            base = F32((self.lo + self.hi) / F32(2) - mr / F32(2))
            rng = mr
        self.range_base, self.range_span = float(base), float(rng)
        if not output:
            return None
        vf = v.astype(F32)
        if not curve:
            scale = F32(255) / rng
            g = ((vf - base) * scale).astype(np.int64)
            return np.clip(g, 0, 255)
        key = (st.curve_t, st.curve_m, st.curve_s)
        if key != self._curve_cache_key:
            self._curve_lut = build_curve_lut(*key)
            self._curve_cache_key = key
        inv = F32(1) / rng
        idx = ((((vf - base) * inv) - F32(CURVE_U0)) * CURVE_K).astype(np.int64)
        return self._curve_lut[np.clip(idx, 0, CURVE_N - 1)]

    # -- AUTO (AutoMapper.kt) --------------------------------------------------------------------
    def _auto(self, v, output=True):
        st = self.settings
        total = self.total
        hist = np.bincount(v, minlength=65536)
        cum = np.cumsum(hist)
        vmin, vmax = int(v.min()), int(v.max())

        # noise: median |difference of horizontally adjacent pixels| on every other row
        img = v.reshape(self.h, self.w)
        d = np.abs(img[::2, 1:] - img[::2, :-1]).ravel()
        d = np.minimum(d, _DIFF_BINS - 1)
        dh = np.bincount(d, minlength=_DIFF_BINS)
        nd = d.size
        med = int(np.searchsorted(np.cumsum(dh), nd // 2, side="right"))
        sig_target = float(np.clip(med * 1.048, 2.0, 30.0))

        low_target = max(1, total // 1000)
        high_target = total - max(1, total // 2000)
        p5_target = int(total * 0.05)
        p95_target = int(total * 0.95)
        lo_v = int(np.searchsorted(cum, low_target, side="left"))
        hi_v = int(np.searchsorted(cum, high_target, side="left"))
        p5_v = int(np.searchsorted(cum, p5_target, side="left"))
        p95_v = int(np.searchsorted(cum, p95_target, side="left"))

        first = not self._have_auto
        if first:
            self.lo, self.hi = F32(lo_v), F32(hi_v)
            self._sigma_sm = sig_target
        else:
            self.lo = F32(self.lo + (F32(lo_v) - self.lo) * F32(0.1))
            self.hi = F32(self.hi + (F32(hi_v) - self.hi) * F32(0.1))
            self._sigma_sm += (sig_target - self._sigma_sm) * 0.1
        base = F32(self.lo)
        rng = F32(self.hi - self.lo)
        mr = F32(st.min_range)
        if rng < mr:
            base = F32((self.lo + self.hi) / F32(2) - mr / F32(2))
            rng = mr
        self.range_base, self.range_span = float(base), float(rng)

        bulk = float(np.clip((p95_v - p5_v) / float(rng), 0.0, 1.0))
        b_hi, b_lo = {0: (0.22, 0.08), 2: (0.40, 0.15)}.get(st.auto_sens, (0.30, 0.10))
        t = float(np.clip((b_hi - bulk) / (b_hi - b_lo), 0.0, 1.0))
        s_target = AUTO_S_MAX * t * t * (3.0 - 2.0 * t)
        if first:
            self._strength_sm = s_target
        else:
            self._strength_sm += (s_target - self._strength_sm) * 0.1
        s = self._strength_sm
        self.bulk, self.strength, self.sigma = bulk, s, self._sigma_sm

        # histogram of normalised value u in NB bins
        vv = np.arange(vmin, vmax + 1)
        cnt = hist[vmin:vmax + 1]
        inv_r = 1.0 / float(rng)
        b = ((vv.astype(F32) - base).astype(np.float64) * inv_r * NB).astype(np.int64)
        b[vv < float(base)] = 0
        b = np.clip(b, 0, NB - 1)
        dens = np.bincount(b, weights=cnt, minlength=NB).astype(np.float64)
        dens = np.minimum(dens, _PLATEAU * total)
        r = len(_KERNEL) // 2
        sm = np.convolve(np.pad(dens, r, mode="symmetric"), _KERNEL, mode="valid")
        psum = sm.sum()
        if psum <= 0.0:
            psum = 1.0

        slope = s * sm / psum * NB + (1.0 - s)
        cap = max(_NOISE_K * float(rng) / self._sigma_sm, 1.2)
        for _ in range(8):
            slope = np.minimum(slope, cap)
            deficit = NB - slope.sum()
            if deficit <= 1e-6 * NB:
                break
            room = slope < cap - 1e-9
            n = int(room.sum())
            if n == 0:
                break
            slope[room] += deficit / n
        y = ((np.cumsum(slope) - slope / 2.0) / NB).astype(F32)

        if first:
            self._lut_sm = y.copy()
        else:
            self._lut_sm = (self._lut_sm + (y - self._lut_sm) * F32(0.25)).astype(F32)
        self._have_auto = True
        if not output:
            return None
        out_idx = np.clip((self._lut_sm * F32(255) + F32(0.5)).astype(np.int64), 0, 255)

        scale = F32(NB / float(rng))
        bi = ((v.astype(F32) - base) * scale).astype(np.int64)
        bi[v.astype(F32) < base] = 0
        return out_idx[np.clip(bi, 0, NB - 1)]


# ------------------------------------------------------------------------------------ recordings

@dataclass
class Recording:
    path: str
    width: int
    height: int
    nominal_fps: int
    frames: int
    meta: dict = field(default_factory=dict)
    rows: list = field(default_factory=list)

    @property
    def frame_bytes(self):
        return self.width * self.height * 2

    def open_memmap(self):
        return np.memmap(self.path, dtype="<u2", mode="r",
                         shape=(self.frames, self.height, self.width))

    def timestamp(self, i):
        """Seconds from the first frame, from the csv if present, else from nominal fps."""
        if self.rows and i < len(self.rows) and self.rows[0][2] > 0:
            return (self.rows[i][2] - self.rows[0][2]) / 1e6
        return i / float(self.nominal_fps)


def read_meta(y16_path):
    """Parses the .csv next to a .y16 (and/or the filename). Raises ValueError if size is unknown."""
    base = os.path.splitext(y16_path)[0]
    meta, rows = {}, []
    m = re.search(r"_(\d+)x(\d+)_gray16le_(\d+)fps", os.path.basename(y16_path))
    if m:
        meta["width"], meta["height"], meta["nominal_fps"] = m.group(1), m.group(2), m.group(3)
    csv_path = base + ".csv"
    if os.path.exists(csv_path):
        with open(csv_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("#"):
                    for k, val in re.findall(r"(\w+)=([^\s,]+)", line):
                        meta[k] = val
                elif line and not line.startswith("file_index"):
                    a = line.split(",")
                    try:
                        rows.append((int(a[0]), int(a[1]), int(a[2]), int(a[3])))
                    except (ValueError, IndexError):
                        pass
    if "width" not in meta:
        raise ValueError("cannot determine width/height (need the .csv or the original filename)")
    return meta, rows


def open_recording(y16_path):
    meta, rows = read_meta(y16_path)
    w, h = int(meta["width"]), int(meta["height"])
    fb = w * h * 2
    n = os.path.getsize(y16_path) // fb
    if n < 1:
        raise ValueError("file holds no complete frame")
    return Recording(y16_path, w, h, int(meta.get("nominal_fps", 60)), n, meta, rows)


def render_frame(rec_mm, index, processor, warmup=WARMUP_FRAMES):
    """Process frame `index` (with warm-up) and return its grey index image.

    The processor state is advanced exactly as the phone would, starting `warmup` frames earlier
    (or at frame 0 for early frames, which is then an exact match).
    """
    start = max(0, index - warmup)
    processor.reset()
    g = None
    for i in range(start, index + 1):
        g = processor.process(rec_mm[i], output=i == index)
    return g


def iter_grey(rec_mm, processor, start, end, warmup=WARMUP_FRAMES):
    """Yield (index, grey_index_image) for frames start..end-1.

    Frames before `start` are processed (not yielded) so the state matches the phone. When
    start == 0 this is exactly what the phone computed while recording.
    """
    processor.reset()
    for i in range(max(0, start - warmup), end):
        g = processor.process(rec_mm[i], output=i >= start)
        if i >= start:
            yield i, g


def write_png(path, rgb):
    """Minimal PNG writer for an (H, W, 3) uint8 array (no extra dependencies)."""
    rgb = np.ascontiguousarray(rgb, dtype=np.uint8)
    h, w, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[y].tobytes() for y in range(h))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


def upscale(rgb, factor):
    """Nearest-neighbour integer upscale (for stills; MP4 export lets ffmpeg filter)."""
    if factor <= 1:
        return rgb
    return np.repeat(np.repeat(rgb, factor, axis=0), factor, axis=1)
