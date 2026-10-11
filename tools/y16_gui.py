#!/usr/bin/env python3
"""
Boson Thermal raw video processor (GUI).

Open a raw recording (.y16 + .csv from the phone app), scrub through it, and choose how it is rendered
exactly the way the phone app renders its normal video: MIN/MAX, CURVE or AUTO tone mapping, with any
combination of White-hot / Black-hot / Ironbow palettes (at least one). Export the result as MP4
(needs ffmpeg) or a PNG sequence, one output per selected palette.

  pip install numpy PySide6
  python y16_gui.py [recording.y16]

Keys: Space play/pause, Left/Right step one frame, Shift+Left/Right step ten, Home/End jump.
"""
import dataclasses
import os
import shutil
import subprocess
import sys
import threading
import time

import numpy as np
from PySide6.QtCore import QPointF, QRectF, QSettings, Qt, QThread, QTimer, Signal
from PySide6.QtGui import QAction, QColor, QImage, QKeySequence, QPainter, QPalette, QPen, QShortcut
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QFileDialog, QFormLayout, QGroupBox, QHBoxLayout, QLabel,
    QMainWindow, QMessageBox, QProgressDialog, QPushButton, QRadioButton, QSlider, QSpinBox,
    QStyle, QStyleOptionSlider, QVBoxLayout, QWidget,
)

import boson_core as core

FAST_WARMUP = 15        # shorter warm-up used while the timeline is being dragged
SEQUENTIAL_LIMIT = 120   # advance frame by frame (cheap) instead of re-warming up for jumps this small


# ------------------------------------------------------------------------------------------ helpers

def settings_key(st):
    """The parts of ToneSettings that matter for the current mode (so unrelated edits don't re-warm-up)."""
    if st.mode == "CURVE":
        return ("CURVE", st.min_range, st.curve_t, st.curve_m, st.curve_s)
    if st.mode == "AUTO":
        return ("AUTO", st.min_range, st.auto_sens)
    return ("MIN/MAX", st.min_range)


def fmt_time(sec):
    m, s = divmod(max(0.0, sec), 60.0)
    return f"{int(m):02d}:{s:05.2f}"


def rgb_to_qimage(rgb):
    h, w, _ = rgb.shape
    rgb = np.ascontiguousarray(rgb)
    return QImage(rgb.data, w, h, w * 3, QImage.Format_RGB888).copy()


# --------------------------------------------------------------------------------------- workers

class RenderWorker(QThread):
    """Renders the most recently requested frame; older pending requests are dropped."""
    rendered = Signal(int, object, object)   # frame index, grey (H,W) uint8, info dict

    def __init__(self):
        super().__init__()
        self._cond = threading.Condition()
        self._req = None
        self._quit = False
        self._mm_id = None
        self._proc = None
        self._last_key = None
        self._last_idx = None
        self._last_grey = None
        self._last_info = None
        self._last_exact = True

    def request(self, mm, width, height, idx, settings, fast=False):
        with self._cond:
            self._req = (mm, width, height, idx, dataclasses.replace(settings), fast)
            self._cond.notify()

    def stop(self):
        with self._cond:
            self._quit = True
            self._cond.notify()
        self.wait(3000)

    def run(self):
        while True:
            with self._cond:
                while self._req is None and not self._quit:
                    self._cond.wait()
                if self._quit:
                    return
                mm, w, h, idx, st, fast = self._req
                self._req = None
            try:
                grey, info = self._render(mm, w, h, idx, st, fast)
            except Exception as e:  # keep the thread alive; report in the status bar
                self.rendered.emit(idx, None, {"error": repr(e)})
                continue
            self.rendered.emit(idx, grey, info)

    def _render(self, mm, w, h, idx, st, fast):
        if self._proc is None or id(mm) != self._mm_id:
            self._proc = core.ThermalProcessor(w, h)
            self._mm_id = id(mm)
            self._last_key = self._last_idx = self._last_grey = None
        proc = self._proc
        key = settings_key(st)
        if key == self._last_key and self._last_idx == idx and self._last_grey is not None \
                and (self._last_exact or fast):
            return self._last_grey, self._last_info
        proc.settings = st
        exact = not fast
        if key == self._last_key and self._last_idx is not None and \
                self._last_idx < idx <= self._last_idx + SEQUENTIAL_LIMIT:
            g = None
            for i in range(self._last_idx + 1, idx + 1):
                g = proc.process(mm[i], output=i == idx)
            exact = self._last_exact
        else:
            g = core.render_frame(mm, idx, proc, FAST_WARMUP if fast else core.WARMUP_FRAMES)
            exact = not fast or idx <= FAST_WARMUP
        info = {
            "mode": st.mode, "lo": float(proc.lo), "hi": float(proc.hi),
            "base": proc.range_base, "span": proc.range_span,
            "bulk": proc.bulk, "strength": proc.strength, "sigma": proc.sigma, "approx": not exact,
        }
        self._last_key, self._last_idx, self._last_grey, self._last_info = key, idx, g, info
        self._last_exact = exact
        return g, info


class ExportWorker(QThread):
    progress = Signal(int, int)
    finished_ok = Signal(list)
    failed = Signal(str)

    def __init__(self, rec, settings, palettes, start, end, scale, fmt, outdir, base):
        super().__init__()
        self.rec, self.settings, self.palettes = rec, dataclasses.replace(settings), list(palettes)
        self.start_i, self.end_i, self.scale, self.fmt = start, end, scale, fmt
        self.outdir, self.base = outdir, base
        self.cancel = False

    def run(self):
        rec = self.rec
        short = {"White-hot": "white", "Black-hot": "black", "Ironbow": "iron"}
        tag = {"MIN/MAX": "minmax", "CURVE": "curve", "AUTO": "auto"}[self.settings.mode]
        procs, paths = {}, []
        try:
            for pal in self.palettes:
                stem = os.path.join(self.outdir, f"{self.base}_{tag}_{short[pal]}")
                if self.fmt == "mp4":
                    path = stem + ".mp4"
                    vf = [] if self.scale == 1 else ["-vf", f"scale=iw*{self.scale}:ih*{self.scale}:flags=bilinear"]
                    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                           "-video_size", f"{rec.width}x{rec.height}", "-framerate", str(rec.nominal_fps),
                           "-i", "-"] + vf + ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", path]
                    procs[pal] = subprocess.Popen(cmd, stdin=subprocess.PIPE)
                else:
                    path = stem + "_frames"
                    os.makedirs(path, exist_ok=True)
                    procs[pal] = path
                paths.append(path)
            mm = rec.open_memmap()
            proc = core.ThermalProcessor(rec.width, rec.height)
            proc.settings = self.settings
            total = self.end_i - self.start_i
            for i, g in core.iter_grey(mm, proc, self.start_i, self.end_i):
                if self.cancel:
                    raise RuntimeError("cancelled")
                for pal in self.palettes:
                    rgb = core.apply_palette(g, pal)
                    if self.fmt == "mp4":
                        procs[pal].stdin.write(rgb.tobytes())
                    else:
                        core.write_png(os.path.join(procs[pal], f"frame_{i:06d}.png"), core.upscale(rgb, self.scale))
                self.progress.emit(i - self.start_i + 1, total)
            self._close_ffmpeg(procs)
            self.finished_ok.emit(paths)
        except Exception as e:
            self._close_ffmpeg(procs, kill=True)
            self.failed.emit(str(e))

    @staticmethod
    def _close_ffmpeg(procs, kill=False):
        for p in procs.values():
            if isinstance(p, subprocess.Popen):
                try:
                    p.stdin.close()
                except Exception:
                    pass
                if kill:
                    p.kill()
                p.wait()


# ----------------------------------------------------------------------------------------- widgets

class PreviewWidget(QWidget):
    """Shows one tile per selected palette, scaled to fit."""

    def __init__(self):
        super().__init__()
        self.setMinimumSize(320, 256)
        self.tiles = []   # list of (name, QImage)

    def set_tiles(self, tiles):
        self.tiles = tiles
        self.update()

    def paintEvent(self, _):
        p = QPainter(self)
        p.fillRect(self.rect(), QColor(12, 12, 14))
        if not self.tiles:
            p.setPen(QColor(150, 150, 150))
            p.drawText(self.rect(), Qt.AlignCenter, "Open a .y16 recording (File > Open, or drop it here)")
            return
        n = len(self.tiles)
        iw, ih = self.tiles[0][1].width(), self.tiles[0][1].height()
        label_h = 22 if n > 1 else 0
        W, H = self.width() - 8, self.height() - 8
        best = None
        for cols in range(1, n + 1):
            rows = (n + cols - 1) // cols
            s = min((W / cols) / iw, (H / rows - label_h) / ih)
            if best is None or s > best[0]:
                best = (s, cols, rows)
        s, cols, rows = best
        s = max(s, 0.05)
        tw, th = iw * s, ih * s
        cell_w, cell_h = W / cols, H / rows
        p.setRenderHint(QPainter.SmoothPixmapTransform, True)
        for k, (name, img) in enumerate(self.tiles):
            r, c = divmod(k, cols)
            x = 4 + c * cell_w + (cell_w - tw) / 2
            y = 4 + r * cell_h + (cell_h - th - label_h) / 2 + label_h
            p.drawImage(QRectF(x, y, tw, th), img)
            if n > 1:
                p.setPen(QColor(210, 210, 210))
                p.drawText(QRectF(x, y - label_h, tw, label_h), Qt.AlignCenter, name)


class CurveWidget(QWidget):
    """Plots the tone curve for the current T, M, S."""

    def __init__(self):
        super().__init__()
        self.setFixedHeight(110)
        self.t, self.m, self.s = core.DEF_T, core.DEF_M, core.DEF_S
        self.enabled_curve = False

    def set_values(self, t, m, s):
        self.t, self.m, self.s = t, m, s
        self.update()

    def paintEvent(self, _):
        p = QPainter(self)
        r = QRectF(6, 6, self.width() - 12, self.height() - 12)
        p.fillRect(self.rect(), QColor(24, 24, 28))
        p.setPen(QPen(QColor(60, 60, 66), 1))
        for f in (0.25, 0.5, 0.75):
            p.drawLine(QPointF(r.left() + f * r.width(), r.top()), QPointF(r.left() + f * r.width(), r.bottom()))
            p.drawLine(QPointF(r.left(), r.bottom() - f * r.height()), QPointF(r.right(), r.bottom() - f * r.height()))
        p.setPen(QPen(QColor(90, 90, 96), 1, Qt.DashLine))
        p.drawLine(QPointF(r.left(), r.bottom()), QPointF(r.right(), r.top()))
        for v, col in ((self.t, QColor(80, 150, 255)), (self.m, QColor(120, 220, 120)), (self.s, QColor(255, 150, 80))):
            p.setPen(QPen(col, 1))
            x = r.left() + v * r.width()
            p.drawLine(QPointF(x, r.top()), QPointF(x, r.bottom()))
        u = np.linspace(0, 1, 160)
        y = core.curve_eval(u, self.t, self.m, self.s)
        col = QColor(255, 200, 60) if self.enabled_curve else QColor(120, 120, 120)
        p.setPen(QPen(col, 2))
        pts = [QPointF(r.left() + a * r.width(), r.bottom() - b * r.height()) for a, b in zip(u, y)]
        p.drawPolyline(pts)


class MarkedSlider(QSlider):
    """Timeline slider that also shows the export start/end marks."""

    def __init__(self):
        super().__init__(Qt.Horizontal)
        self.marks = (None, None)

    def paintEvent(self, e):
        super().paintEvent(e)
        if self.maximum() <= self.minimum():
            return
        opt = QStyleOptionSlider()
        self.initStyleOption(opt)
        g = self.style().subControlRect(QStyle.CC_Slider, opt, QStyle.SC_SliderGroove, self)
        p = QPainter(self)
        for v, col in zip(self.marks, (QColor(80, 220, 120), QColor(255, 110, 90))):
            if v is None:
                continue
            x = g.left() + (v - self.minimum()) / (self.maximum() - self.minimum()) * g.width()
            p.setPen(QPen(col, 2))
            p.drawLine(int(x), g.top() - 4, int(x), g.bottom() + 4)


# ------------------------------------------------------------------------------------- main window

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Boson Thermal - raw video processor")
        self.setAcceptDrops(True)
        self.resize(1180, 760)
        self.rec = None
        self.mm = None
        self.settings = core.ToneSettings()
        self.grey = None
        self.shown_index = -1
        self.ffmpeg = shutil.which("ffmpeg")
        self.q = QSettings("BosonThermal", "y16_gui")
        self.export_worker = None
        self._built = False
        self.play_t0 = 0.0
        self.play_i0 = 0
        self.timer = QTimer(self)
        self.timer.setInterval(15)
        self.timer.timeout.connect(self._tick)
        self.worker = RenderWorker()
        self.worker.rendered.connect(self._on_rendered)
        self.worker.start()
        self._build_ui()
        self._built = True
        self._restore()
        self._sync_controls()

    # ---- UI ---------------------------------------------------------------------------------------
    def _build_ui(self):
        m = self.menuBar().addMenu("&File")
        a = QAction("&Open recording…", self)
        a.setShortcut(QKeySequence.Open)
        a.triggered.connect(self.open_dialog)
        m.addAction(a)
        a = QAction("&Quit", self)
        a.setShortcut(QKeySequence.Quit)
        a.triggered.connect(self.close)
        m.addAction(a)

        central = QWidget()
        self.setCentralWidget(central)
        outer = QHBoxLayout(central)

        # left: preview + transport
        left = QVBoxLayout()
        self.preview = PreviewWidget()
        left.addWidget(self.preview, 1)
        self.slider = MarkedSlider()
        self.slider.setEnabled(False)
        self.slider.valueChanged.connect(self._slider_changed)
        self.slider.sliderReleased.connect(self._slider_released)
        left.addWidget(self.slider)
        row = QHBoxLayout()
        self.btn_prev = QPushButton("◀")
        self.btn_play = QPushButton("Play")
        self.btn_next = QPushButton("▶")
        for b in (self.btn_prev, self.btn_play, self.btn_next):
            b.setEnabled(False)
        self.btn_prev.clicked.connect(lambda: self.step(-1))
        self.btn_next.clicked.connect(lambda: self.step(1))
        self.btn_play.clicked.connect(self.toggle_play)
        self.loop = QCheckBox("Loop")
        self.loop.setChecked(True)
        self.time_label = QLabel("-")
        self.time_label.setMinimumWidth(260)
        row.addWidget(self.btn_prev)
        row.addWidget(self.btn_play)
        row.addWidget(self.btn_next)
        row.addWidget(self.loop)
        row.addStretch(1)
        row.addWidget(self.time_label)
        left.addLayout(row)
        self.info_label = QLabel("")
        self.info_label.setStyleSheet("color: #9aa0a6;")
        left.addWidget(self.info_label)
        outer.addLayout(left, 1)

        # right: controls
        right = QVBoxLayout()
        right_w = QWidget()
        right_w.setFixedWidth(330)
        right_w.setLayout(right)
        outer.addWidget(right_w)

        tone = QGroupBox("Tone mapping")
        tl = QVBoxLayout(tone)
        self.radios = {}
        for name, tip in (("MIN/MAX", "Linear stretch of the 0.1%..99.95% range"),
                          ("CURVE", "Same range through an adjustable three-handle curve"),
                          ("AUTO", "MIN/MAX until a hot/cold object crowds the scene, then adaptive compression")):
            rb = QRadioButton(name)
            rb.setToolTip(tip)
            rb.toggled.connect(self._tone_changed)
            self.radios[name] = rb
            tl.addWidget(rb)
        self.radios["MIN/MAX"].setChecked(True)
        f = QFormLayout()
        self.min_range = QComboBox()
        for v in core.MIN_RANGE_CHOICES:
            self.min_range.addItem(f"{v} counts", v)
        self.min_range.currentIndexChanged.connect(self._tone_changed)
        f.addRow("Minimum range", self.min_range)
        tl.addLayout(f)
        right.addWidget(tone)

        self.curve_box = QGroupBox("Curve")
        cl = QVBoxLayout(self.curve_box)
        self.curve_plot = CurveWidget()
        cl.addWidget(self.curve_plot)
        self.curve_sliders = {}
        self.curve_labels = {}
        for key, name in (("t", "Toe  T"), ("m", "Mid  M"), ("s", "Shoulder  S")):
            r = QHBoxLayout()
            lab = QLabel(name)
            lab.setFixedWidth(80)
            sl = QSlider(Qt.Horizontal)
            sl.setRange(0, 1000)
            val = QLabel("")
            val.setFixedWidth(40)
            sl.valueChanged.connect(lambda v, k=key: self._curve_slider(k, v))
            r.addWidget(lab)
            r.addWidget(sl, 1)
            r.addWidget(val)
            cl.addLayout(r)
            self.curve_sliders[key] = sl
            self.curve_labels[key] = val
        rb = QPushButton("Reset curve")
        rb.clicked.connect(self._reset_curve)
        cl.addWidget(rb)
        right.addWidget(self.curve_box)

        self.auto_box = QGroupBox("AUTO")
        al = QFormLayout(self.auto_box)
        self.auto_sens = QComboBox()
        for n in core.AUTO_SENS_NAMES:
            self.auto_sens.addItem(n)
        self.auto_sens.setCurrentIndex(1)
        self.auto_sens.currentIndexChanged.connect(self._tone_changed)
        al.addRow("Compression", self.auto_sens)
        self.auto_info = QLabel("-")
        self.auto_info.setWordWrap(True)
        al.addRow("This frame", self.auto_info)
        right.addWidget(self.auto_box)

        pal = QGroupBox("Palettes (at least one)")
        pl = QVBoxLayout(pal)
        self.pal_boxes = {}
        for name in core.PALETTE_NAMES:
            cb = QCheckBox(name)
            cb.setChecked(name == "White-hot")
            cb.toggled.connect(lambda checked, n=name: self._palette_toggled(n, checked))
            self.pal_boxes[name] = cb
            pl.addWidget(cb)
        right.addWidget(pal)

        ex = QGroupBox("Export")
        el = QFormLayout(ex)
        self.sp_start = QSpinBox()
        self.sp_end = QSpinBox()
        for sp in (self.sp_start, self.sp_end):
            sp.setRange(0, 0)
            sp.valueChanged.connect(self._range_changed)
        rs = QHBoxLayout()
        rs.addWidget(self.sp_start, 1)
        b = QPushButton("Here")
        b.clicked.connect(lambda: self.sp_start.setValue(self.slider.value()))
        rs.addWidget(b)
        el.addRow("Start frame", rs)
        re_ = QHBoxLayout()
        re_.addWidget(self.sp_end, 1)
        b = QPushButton("Here")
        b.clicked.connect(lambda: self.sp_end.setValue(self.slider.value()))
        re_.addWidget(b)
        el.addRow("End frame", re_)
        self.scale = QComboBox()
        for s in (1, 2, 3, 4):
            self.scale.addItem(f"{s}×  ({320 * s}×{256 * s})", s)
        self.scale.setCurrentIndex(1)
        el.addRow("Size", self.scale)
        self.fmt = QComboBox()
        self.fmt.addItem("MP4 (H.264, needs ffmpeg)", "mp4")
        self.fmt.addItem("PNG sequence", "png")
        if not self.ffmpeg:
            self.fmt.model().item(0).setEnabled(False)
            self.fmt.setCurrentIndex(1)
            self.fmt.setToolTip("ffmpeg was not found on PATH; MP4 export is unavailable.")
        el.addRow("Format", self.fmt)
        self.btn_export = QPushButton("Export video…")
        self.btn_export.clicked.connect(self.export_video)
        self.btn_frame = QPushButton("Save current frame…")
        self.btn_frame.clicked.connect(self.save_frame)
        for b in (self.btn_export, self.btn_frame):
            b.setEnabled(False)
        el.addRow(self.btn_export)
        el.addRow(self.btn_frame)
        right.addWidget(ex)
        right.addStretch(1)

        for key, ck in (("Space", self.toggle_play), ("Left", lambda: self.step(-1)), ("Right", lambda: self.step(1)),
                        ("Shift+Left", lambda: self.step(-10)), ("Shift+Right", lambda: self.step(10)),
                        ("Home", lambda: self.slider.setValue(0)), ("End", lambda: self.slider.setValue(self.slider.maximum()))):
            QShortcut(QKeySequence(key), self, activated=ck)

    # ---- persistence ------------------------------------------------------------------------------
    def _restore(self):
        mode = self.q.value("tone", "MIN/MAX")
        if mode in self.radios:
            self.radios[mode].setChecked(True)
        i = self.min_range.findData(int(self.q.value("min_range", 64)))
        if i >= 0:
            self.min_range.setCurrentIndex(i)
        self.auto_sens.setCurrentIndex(int(self.q.value("auto_sens", 1)))
        try:
            t, m, s = (float(self.q.value(k, d)) for k, d in (("ct", core.DEF_T), ("cm", core.DEF_M), ("cs", core.DEF_S)))
            if core.curve_valid(t, m, s):
                self.settings.curve_t, self.settings.curve_m, self.settings.curve_s = t, m, s
        except (TypeError, ValueError):
            pass
        pals = str(self.q.value("palettes", "White-hot")).split("|")
        pals = [p for p in pals if p in self.pal_boxes] or ["White-hot"]
        for n, cb in self.pal_boxes.items():
            cb.blockSignals(True)
            cb.setChecked(n in pals)
            cb.blockSignals(False)

    def _save_prefs(self):
        self.q.setValue("tone", self.settings.mode)
        self.q.setValue("min_range", int(self.settings.min_range))
        self.q.setValue("auto_sens", self.settings.auto_sens)
        self.q.setValue("ct", self.settings.curve_t)
        self.q.setValue("cm", self.settings.curve_m)
        self.q.setValue("cs", self.settings.curve_s)
        self.q.setValue("palettes", "|".join(self.selected_palettes()))

    # ---- control handlers -------------------------------------------------------------------------
    def selected_palettes(self):
        return [n for n in core.PALETTE_NAMES if self.pal_boxes[n].isChecked()]

    def _palette_toggled(self, name, checked):
        if not self.selected_palettes():
            cb = self.pal_boxes[name]
            cb.blockSignals(True)
            cb.setChecked(True)
            cb.blockSignals(False)
            self.statusBar().showMessage("At least one palette must stay selected.", 3000)
            return
        self._save_prefs()
        self._show_current()   # instant: re-colour the cached frame

    def _tone_changed(self, *_):
        if not self._built:
            return
        for name, rb in self.radios.items():
            if rb.isChecked():
                self.settings.mode = name
        self.settings.min_range = float(self.min_range.currentData())
        self.settings.auto_sens = self.auto_sens.currentIndex()
        self._sync_controls()
        self._save_prefs()
        self.request_render()

    def _curve_slider(self, key, v):
        s = self.settings
        val = v / 1000.0
        t, m, sh = s.curve_t, s.curve_m, s.curve_s
        gap = core.CURVE_GAP
        if key == "t":
            t = min(max(val, core.T_MIN), m - gap)
        elif key == "m":
            m = min(max(val, t + gap), sh - gap)
        else:
            sh = min(max(val, m + gap), core.S_MAX)
        s.curve_t, s.curve_m, s.curve_s = t, m, sh
        self._sync_controls()
        self._save_prefs()
        if s.mode == "CURVE":
            self.request_render()

    def _reset_curve(self):
        s = self.settings
        s.curve_t, s.curve_m, s.curve_s = core.DEF_T, core.DEF_M, core.DEF_S
        self._sync_controls()
        self._save_prefs()
        if s.mode == "CURVE":
            self.request_render()

    def _sync_controls(self):
        s = self.settings
        vals = {"t": s.curve_t, "m": s.curve_m, "s": s.curve_s}
        for k, sl in self.curve_sliders.items():
            sl.blockSignals(True)
            sl.setValue(int(round(vals[k] * 1000)))
            sl.blockSignals(False)
            self.curve_labels[k].setText(f"{vals[k]:.2f}")
        self.curve_plot.set_values(s.curve_t, s.curve_m, s.curve_s)
        self.curve_plot.enabled_curve = s.mode == "CURVE"
        self.curve_plot.update()
        self.curve_box.setEnabled(s.mode == "CURVE")
        self.auto_box.setEnabled(s.mode == "AUTO")

    def _range_changed(self, *_):
        if self.rec is None:
            return
        a, b = self.sp_start.value(), self.sp_end.value()
        self.slider.marks = (a, b)
        self.slider.update()
        self._update_labels()

    # ---- opening ----------------------------------------------------------------------------------
    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()

    def dropEvent(self, e):
        for u in e.mimeData().urls():
            p = u.toLocalFile()
            if p.lower().endswith(".y16"):
                self.open_file(p)
                return

    def open_dialog(self):
        start = str(self.q.value("last_dir", ""))
        path, _ = QFileDialog.getOpenFileName(self, "Open raw recording", start, "Raw recordings (*.y16);;All files (*)")
        if path:
            self.open_file(path)

    def open_file(self, path):
        try:
            rec = core.open_recording(path)
        except Exception as e:
            QMessageBox.warning(self, "Cannot open", f"{path}\n\n{e}")
            return
        self.pause()
        self.rec = rec
        self.mm = rec.open_memmap()
        self.q.setValue("last_dir", os.path.dirname(path))
        self.setWindowTitle(f"{os.path.basename(path)} - Boson Thermal raw video processor")
        n = rec.frames
        self.slider.blockSignals(True)
        self.slider.setRange(0, n - 1)
        self.slider.setValue(0)
        self.slider.blockSignals(False)
        for sp, v in ((self.sp_start, 0), (self.sp_end, n - 1)):
            sp.blockSignals(True)
            sp.setRange(0, n - 1)
            sp.setValue(v)
            sp.blockSignals(False)
        for w in (self.slider, self.btn_prev, self.btn_play, self.btn_next, self.btn_export, self.btn_frame):
            w.setEnabled(True)
        self.grey = None
        self.shown_index = -1
        self._range_changed()
        self.statusBar().showMessage(
            f"{rec.width}x{rec.height}, {n} frames, {rec.nominal_fps} fps nominal"
            + (f", camera {rec.meta['camera_serial']}" if "camera_serial" in rec.meta else ""))
        self.request_render()

    # ---- transport --------------------------------------------------------------------------------
    def _slider_changed(self, _):
        self._update_labels()
        self.request_render(fast=self.slider.isSliderDown())

    def _slider_released(self):
        self.request_render()

    def step(self, d):
        if self.rec is None:
            return
        self.pause()
        self.slider.setValue(min(max(self.slider.value() + d, 0), self.slider.maximum()))

    def toggle_play(self):
        if self.rec is None:
            return
        if self.timer.isActive():
            self.pause()
        else:
            if self.slider.value() >= self.slider.maximum():
                self.slider.setValue(0)
            self.play_i0 = self.slider.value()
            self.play_t0 = self.rec.timestamp(self.play_i0)
            self._wall0 = self._now()
            self.btn_play.setText("Pause")
            self.timer.start()

    def pause(self):
        self.timer.stop()
        self.btn_play.setText("Play")

    @staticmethod
    def _now():
        return time.perf_counter()

    def _tick(self):
        if self.rec is None:
            return
        elapsed = self._now() - self._wall0
        target_t = self.play_t0 + elapsed
        # map elapsed media time back to a frame index using the recorded timestamps
        i = self.slider.value()
        n = self.rec.frames
        while i + 1 < n and self.rec.timestamp(i + 1) <= target_t:
            i += 1
        if i >= n - 1 and self.rec.timestamp(n - 1) <= target_t:
            if self.loop.isChecked():
                self.play_i0 = 0
                self.play_t0 = self.rec.timestamp(0)
                self._wall0 = self._now()
                i = 0
            else:
                self.pause()
        if i != self.slider.value():
            self.slider.setValue(i)

    def _update_labels(self):
        if self.rec is None:
            self.time_label.setText("-")
            return
        i = self.slider.value()
        a, b = self.sp_start.value(), self.sp_end.value()
        self.time_label.setText(
            f"{fmt_time(self.rec.timestamp(i))} / {fmt_time(self.rec.timestamp(self.rec.frames - 1))}"
            f"    frame {i} / {self.rec.frames - 1}    export {a}-{b} ({max(0, b - a + 1)} frames)")

    # ---- rendering --------------------------------------------------------------------------------
    def request_render(self, fast=False):
        if self.rec is None:
            return
        self.worker.request(self.mm, self.rec.width, self.rec.height, self.slider.value(), self.settings, fast)

    def _on_rendered(self, idx, grey, info):
        if grey is None:
            self.statusBar().showMessage("render error: " + info.get("error", "?"))
            return
        self.grey = grey
        self.shown_index = idx
        self.last_info = info
        self._show_current()
        txt = f"range {info['base']:.0f}–{info['base'] + info['span']:.0f} counts (span {info['span']:.0f})"
        self.info_label.setText(f"{info['mode']}   {txt}" + ("   (preview quality while dragging)" if info.get("approx") else ""))
        if info["mode"] == "AUTO":
            self.auto_info.setText(
                f"bulk {info['bulk']:.2f}, compression {info['strength'] / core.AUTO_S_MAX * 100:.0f}%, noise σ {info['sigma']:.1f}")
        else:
            self.auto_info.setText("-")

    def _show_current(self):
        if self.grey is None:
            self.preview.set_tiles([])
            return
        self.preview.set_tiles([(n, rgb_to_qimage(core.apply_palette(self.grey, n))) for n in self.selected_palettes()])

    # ---- export -----------------------------------------------------------------------------------
    def _base_name(self):
        return os.path.splitext(os.path.basename(self.rec.path))[0]

    def save_frame(self):
        if self.rec is None or self.grey is None:
            return
        start = str(self.q.value("export_dir", os.path.dirname(self.rec.path)))
        d = QFileDialog.getExistingDirectory(self, "Folder for the PNG(s)", start)
        if not d:
            return
        self.q.setValue("export_dir", d)
        sc = self.scale.currentData()
        short = {"White-hot": "white", "Black-hot": "black", "Ironbow": "iron"}
        tag = {"MIN/MAX": "minmax", "CURVE": "curve", "AUTO": "auto"}[self.settings.mode]
        out = []
        for n in self.selected_palettes():
            p = os.path.join(d, f"{self._base_name()}_{tag}_{short[n]}_f{self.shown_index:06d}.png")
            core.write_png(p, core.upscale(core.apply_palette(self.grey, n), sc))
            out.append(p)
        self.statusBar().showMessage("Saved " + ", ".join(os.path.basename(p) for p in out), 8000)

    def export_video(self):
        if self.rec is None or self.export_worker is not None:
            return
        a, b = self.sp_start.value(), self.sp_end.value()
        if b < a:
            QMessageBox.information(self, "Export", "End frame must not be before the start frame.")
            return
        fmt = self.fmt.currentData()
        if fmt == "mp4" and not self.ffmpeg:
            QMessageBox.information(self, "Export", "ffmpeg was not found on PATH. Choose PNG sequence, or install ffmpeg.")
            return
        start = str(self.q.value("export_dir", os.path.dirname(self.rec.path)))
        d = QFileDialog.getExistingDirectory(self, "Folder for the exported files", start)
        if not d:
            return
        self.q.setValue("export_dir", d)
        self.pause()
        pals = self.selected_palettes()
        w = ExportWorker(self.rec, self.settings, pals, a, b + 1, self.scale.currentData(), fmt, d, self._base_name())
        self.export_worker = w
        dlg = QProgressDialog(f"Exporting {b - a + 1} frames × {len(pals)} palette(s)…", "Cancel", 0, b - a + 1, self)
        dlg.setWindowModality(Qt.WindowModal)
        dlg.setMinimumDuration(0)
        dlg.canceled.connect(lambda: setattr(w, "cancel", True))
        w.progress.connect(lambda done, total: dlg.setValue(done))

        def done(paths):
            dlg.close()
            self.export_worker = None
            self.statusBar().showMessage("Exported: " + ", ".join(os.path.basename(p) for p in paths), 15000)
            QMessageBox.information(self, "Export finished", "Wrote:\n" + "\n".join(paths))

        def failed(msg):
            dlg.close()
            self.export_worker = None
            if msg != "cancelled":
                QMessageBox.warning(self, "Export failed", msg)
            else:
                self.statusBar().showMessage("Export cancelled", 5000)

        w.finished_ok.connect(done)
        w.failed.connect(failed)
        w.start()

    def closeEvent(self, e):
        self.pause()
        if self.export_worker is not None:
            self.export_worker.cancel = True
            self.export_worker.wait(3000)
        self.worker.stop()
        super().closeEvent(e)


def apply_dark(app):
    app.setStyle("Fusion")
    p = QPalette()
    p.setColor(QPalette.Window, QColor(37, 37, 41))
    p.setColor(QPalette.WindowText, QColor(225, 225, 225))
    p.setColor(QPalette.Base, QColor(24, 24, 28))
    p.setColor(QPalette.AlternateBase, QColor(37, 37, 41))
    p.setColor(QPalette.Text, QColor(225, 225, 225))
    p.setColor(QPalette.Button, QColor(52, 52, 58))
    p.setColor(QPalette.ButtonText, QColor(225, 225, 225))
    p.setColor(QPalette.Highlight, QColor(255, 180, 40))
    p.setColor(QPalette.HighlightedText, QColor(20, 20, 20))
    p.setColor(QPalette.ToolTipBase, QColor(50, 50, 55))
    p.setColor(QPalette.ToolTipText, QColor(225, 225, 225))
    p.setColor(QPalette.Disabled, QPalette.Text, QColor(120, 120, 120))
    p.setColor(QPalette.Disabled, QPalette.ButtonText, QColor(120, 120, 120))
    p.setColor(QPalette.Disabled, QPalette.WindowText, QColor(120, 120, 120))
    app.setPalette(p)


def main():
    app = QApplication(sys.argv)
    apply_dark(app)
    w = MainWindow()
    w.show()
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        w.open_file(sys.argv[1])
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
