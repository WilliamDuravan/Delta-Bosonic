package com.example.bosonthermal

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Everything non-UI: USB streaming, display, snapshots, MP4 and raw recording, FPN calibration, serial.
 *
 * Threads (unchanged from v0.8): USB reader only copies frames (raw recorder offer, calibration, hub
 * publish); a render thread and a record thread each have their own [FrameMapper]; the raw recorder
 * has a writer thread; serial runs on a single-thread executor. Nothing here touches Views.
 */
class CameraEngine(
    private val ctx: Context,
    private val usb: UsbManager,
    private val preview: PreviewSurface,
    private val listener: Listener
) {
    interface Listener {
        /** Any thread. Something visible changed (recording stopped, stream died...). */
        fun onEngineChanged()
        /** Any thread. A short user-facing message. */
        fun onMessage(msg: String)
    }

    private val hub = FrameHub(W * H * 2)
    private var streamer: UvcBulkStreamer? = null
    private var streamFps60 = true

    // FPN calibration (diagnostics).
    @Volatile var fpnOffset: IntArray? = null
        private set
    @Volatile var fpnEnabled = true
    @Volatile private var calSum: IntArray? = null
    @Volatile var calRemaining = 0
        private set

    // Display.
    private var renderThread: Thread? = null
    @Volatile private var renderRunning = false
    @Volatile private var renderedFrames = 0L
    private val bmp: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(W * H)
    private val mapper = FrameMapper(W, H, true)

    private class SnapReq(val normal: Boolean, val raw: Boolean)
    @Volatile private var snapReq: SnapReq? = null

    // Recording.
    private var recThread: Thread? = null
    @Volatile private var recRunning = false
    @Volatile private var recorder: Mp4Recorder? = null
    @Volatile private var rawRec: RawRecorder? = null
    @Volatile private var recFrames = 0L
    @Volatile var recStartMs = 0L
        private set
    @Volatile var recordingRaw = false
        private set
    @Volatile var recordingNormal = false
        private set

    // Serial.
    private val serialExec = Executors.newSingleThreadExecutor()
    private var serial: BosonSerial? = null

    // Rates.
    @Volatile var usbFps = 0.0
        private set
    @Volatile var mbPerSec = 0.0
        private set
    @Volatile var displayFps = 0.0
        private set
    private var prevT = 0L
    private var prevOk = 0L
    private var prevBytes = 0L
    private var prevRendered = 0L

    val isStreaming: Boolean get() = streamer?.streaming == true
    val hasStreamer: Boolean get() = streamer != null
    val isRecording: Boolean get() = recordingNormal || recordingRaw

    // ------------------------------------------------------------------ device / stream

    fun findDevice(): UsbDevice? {
        for (d in usb.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass == 14 && itf.interfaceSubclass == 2) return d
            }
        }
        return null
    }

    /** Returns null when streaming started (or already running), else the reason. */
    fun start(): String? {
        if (streamer != null) return null
        val dev = findDevice() ?: return "No UVC device attached"
        if (!usb.hasPermission(dev)) return "No USB permission"
        mapper.reset()
        hub.reset()
        prevT = 0L; prevOk = 0L; prevBytes = 0L; prevRendered = 0L
        renderedFrames = 0L
        val fps60 = AppSettings.fps60
        streamFps60 = fps60
        LogStore.log("starting Y16 320x256 @ " + (if (fps60) 60 else 30) + " fps")
        val s = UvcBulkStreamer(
            usb, dev, 2, 1,
            if (fps60) 166666 else 333333,
            W * H * 2,
            { f, n, pts -> offerFrame(f, n, pts) },
            { LogStore.log(it) }
        )
        streamer = s
        startRender()
        s.start()
        return null
    }

    fun stop() {
        stopVideo()
        val s = streamer
        if (s != null) {
            s.running = false
            try { s.join(2500) } catch (_: InterruptedException) {}
            streamer = null
        }
        stopRender()
    }

    /** Restart with the current frame-rate setting. */
    fun restart(): String? {
        stop()
        return start()
    }

    /** Call periodically from the UI. Returns true if the stream died and was cleaned up. */
    fun reapIfDead(): Boolean {
        val s = streamer ?: return false
        if (s.isAlive) return false
        LogStore.log("stream ended")
        stop()
        listener.onEngineChanged()
        return true
    }

    fun release() {
        stop()
        closeSerial()
        serialExec.shutdown()
    }

    // USB thread: copy only.
    private fun offerFrame(f: ByteArray, usbFrame: Long, pts: Long) {
        val rr = rawRec
        if (rr != null) rr.offer(f, usbFrame, SystemClock.elapsedRealtimeNanos() / 1000, pts)
        val sum = calSum
        if (sum != null && calRemaining > 0) {
            var j = 0
            for (p in 0 until W * H) {
                sum[p] += (f[j].toInt() and 0xFF) or ((f[j + 1].toInt() and 0xFF) shl 8)
                j += 2
            }
            calRemaining -= 1
            if (calRemaining == 0) finishCalibration(sum)
        }
        hub.publish(f)
    }

    // ------------------------------------------------------------------ display + snapshot

    private fun startRender() {
        if (renderThread != null) return
        renderRunning = true
        val t = Thread {
            val local = ByteArray(W * H * 2)
            var lastSeq = 0L
            while (renderRunning) {
                val s = hub.await(lastSeq, local) { renderRunning }
                if (s < 0) continue
                lastSeq = s
                mapper.map(local, if (fpnEnabled) fpnOffset else null, pixels)
                bmp.setPixels(pixels, 0, W, 0, 0, W, H)
                preview.draw(bmp)
                renderedFrames++
                val rq = snapReq
                if (rq != null) {
                    snapReq = null
                    doSnapshot(local, rq)
                }
            }
        }
        t.name = "render"
        renderThread = t
        t.start()
    }

    private fun stopRender() {
        val t = renderThread ?: return
        renderRunning = false
        hub.wake()
        try { t.join(2000) } catch (_: InterruptedException) {}
        renderThread = null
    }

    /** Takes the next displayed frame. Returns false if not streaming. */
    fun snapshot(normal: Boolean, raw: Boolean): Boolean {
        if (streamer == null || !normal && !raw) return false
        snapReq = SnapReq(normal, raw)
        return true
    }

    private fun doSnapshot(rawFrame: ByteArray, rq: SnapReq) {
        val rawCopy = rawFrame.copyOf()
        val bmpCopy = if (rq.normal) bmp.copy(Bitmap.Config.ARGB_8888, false) else null
        val appCtx = ctx.applicationContext
        Thread {
            try {
                val name = Capture.saveSnapshot(appCtx, rawCopy, W, H, bmpCopy, rq.normal, rq.raw)
                val what = (if (rq.normal) ".png " else "") + (if (rq.raw) "_raw16.tif" else "")
                LogStore.log("saved Pictures/BosonThermal/$name ($what)")
                listener.onMessage("Saved $name")
            } catch (e: Exception) {
                LogStore.log("snapshot failed: $e")
                listener.onMessage("Photo failed: ${e.message}")
            }
        }.start()
    }

    // ------------------------------------------------------------------ video

    fun startVideo(normal: Boolean, raw: Boolean): Boolean {
        if (isRecording) return true
        if (streamer == null) {
            listener.onMessage("Camera not streaming")
            return false
        }
        var r: RawRecorder? = null
        if (raw) {
            val dev = findDevice()
            val serialNo: String? = try { dev?.serialNumber } catch (_: Exception) { null }
            r = RawRecorder(ctx.applicationContext, W, H, if (streamFps60) 60 else 30, serialNo, ctx.filesDir)
            try {
                r.start()
            } catch (e: Exception) {
                LogStore.log("raw start failed: $e")
                r.abort()
                listener.onMessage("Raw recording failed: ${e.message}")
                return false
            }
        }
        var rec: Mp4Recorder? = null
        if (normal) {
            rec = Mp4Recorder(ctx.applicationContext, 640, 512, if (streamFps60) 60 else 30)
            try {
                rec.start()
            } catch (e: Exception) {
                LogStore.log("record start failed: $e")
                rec.abort()
                r?.abort()
                listener.onMessage("Video failed: ${e.message}")
                return false
            }
        }
        recStartMs = SystemClock.elapsedRealtime()
        if (r != null) {
            rawRec = r
            recordingRaw = true
            LogStore.log("raw recording -> Download/BosonThermal/${r.rawName} (+ .csv)")
        }
        if (rec != null) startMp4Thread(rec)
        listener.onEngineChanged()
        return true
    }

    private fun startMp4Thread(rec: Mp4Recorder) {
        recorder = rec
        recFrames = 0L
        recRunning = true
        recordingNormal = true
        LogStore.log("recording -> Movies/BosonThermal/${rec.name}")
        val t = Thread {
            val local = ByteArray(W * H * 2)
            val px = IntArray(W * H)
            val rb = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val recMapper = FrameMapper(W, H)
            var lastSeq = 0L
            try {
                while (recRunning) {
                    val s = hub.await(lastSeq, local) { recRunning }
                    if (s < 0) continue
                    lastSeq = s
                    recMapper.map(local, if (fpnEnabled) fpnOffset else null, px)
                    rb.setPixels(px, 0, W, 0, 0, W, H)
                    rec.addFrame(rb)
                    recFrames++
                }
            } catch (e: Exception) {
                LogStore.log("recording error: $e")
            }
            val kept = rec.finish()
            LogStore.log(
                if (kept) "recording saved: ${rec.name} (${rec.framesWritten} frames, " +
                        String.format(Locale.US, "%.1f MB)", rec.bytesWritten / 1e6)
                else "recording discarded (no frames written)"
            )
            if (kept) listener.onMessage("Saved ${rec.name}")
            recorder = null
        }
        t.name = "record"
        recThread = t
        t.start()
    }

    fun stopVideo() {
        val t = recThread
        if (t != null) {
            recRunning = false
            hub.wake()
            try { t.join(8000) } catch (_: InterruptedException) {}
            recThread = null
        }
        recordingNormal = false
        val r = rawRec
        if (r != null) {
            rawRec = null
            Thread {
                val kept = r.finish()
                LogStore.log(
                    if (kept) String.format(
                        Locale.US, "raw saved: %s, %d frames, %.0f MB, %d dropped in app",
                        r.rawName, r.framesWritten, r.bytesWritten / 1e6, r.framesDropped
                    ) else "raw recording discarded (no frames)"
                )
                if (kept) listener.onMessage("Saved ${r.rawName}")
                listener.onEngineChanged()
            }.start()
        }
        recordingRaw = false
        listener.onEngineChanged()
    }

    /** UI tick: stops recording on low space / write errors / dead encoder thread. */
    fun checkRecording() {
        val rr = rawRec
        if (rr != null && (rr.lowSpace || rr.writeError != null)) {
            val why = if (rr.lowSpace) "storage nearly full" else "write error ${rr.writeError}"
            LogStore.log("raw recording stopped: $why")
            listener.onMessage("Recording stopped: $why")
            stopVideo()
            return
        }
        val t = recThread
        if (t != null && !t.isAlive) {
            recThread = null
            recordingNormal = false
            if (rawRec == null) listener.onEngineChanged()
        }
    }

    // ------------------------------------------------------------------ tone panel data

    /** Copies the live histogram; returns (base, span) of the display range. */
    fun copyHistogram(dst: IntArray): Pair<Float, Float> {
        mapper.tone.copyHistogram(dst)
        return Pair(mapper.rangeBase, mapper.rangeSpan)
    }

    // ------------------------------------------------------------------ FPN calibration (diagnostics)

    fun startCalibration(): Boolean {
        if (streamer == null) return false
        calSum = IntArray(W * H)
        calRemaining = CAL_FRAMES
        LogStore.log("FPN calibration: averaging $CAL_FRAMES frames...")
        return true
    }

    fun clearFpn() {
        fpnOffset = null
    }

    // Runs on the USB thread.
    private fun finishCalibration(sum: IntArray) {
        val n = CAL_FRAMES
        var tot = 0L
        for (v in sum) tot += v
        val mean = tot.toDouble() / (n.toDouble() * sum.size)
        val off = IntArray(sum.size)
        var mn = Int.MAX_VALUE
        var mx = Int.MIN_VALUE
        var sq = 0.0
        for (p in sum.indices) {
            val d = Math.round(sum[p].toDouble() / n - mean).toInt()
            off[p] = d
            if (d < mn) mn = d
            if (d > mx) mx = d
            sq += d.toDouble() * d
        }
        val rms = Math.sqrt(sq / sum.size)
        fpnOffset = off
        fpnEnabled = true
        calSum = null
        LogStore.log(
            String.format(Locale.US, "FPN cal done: offsets %d..%d counts, rms %.1f, mean level %.0f", mn, mx, rms, mean)
        )
        if (rms > 150.0) LogStore.log("WARNING: large offsets, lens probably not fully covered or target not uniform")
        listener.onMessage("FPN calibration done")
        listener.onEngineChanged()
    }

    // ------------------------------------------------------------------ serial (FSLP)

    fun runCommand(label: String, fn: Int, data: ByteArray, quiet: Boolean = false) {
        val dev = findDevice()
        if (dev == null) { LogStore.log("No device attached"); listener.onMessage("No camera attached"); return }
        if (!usb.hasPermission(dev)) { LogStore.log("No USB permission"); return }
        var s = serial
        if (s == null) {
            s = BosonSerial(usb, dev)
            serial = s
        }
        val ser = s
        try {
            serialExec.execute {
                val r = ser.transact(fn, data)
                val d = BosonSerial.describe(r)
                LogStore.log("$label: $d")
                if (!quiet || !r.ok) listener.onMessage("$label: $d")
                if (r.ok && fn == BosonSerial.FN_RUN_FFC && fpnOffset != null) {
                    fpnOffset = null
                    LogStore.log("FFC changed the camera's offsets: software FPN correction cleared, recalibrate")
                    listener.onEngineChanged()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun ffc() = runCommand("FFC", BosonSerial.FN_RUN_FFC, ByteArray(0), quiet = true)

    fun closeSerial() {
        val s = serial ?: return
        serial = null
        try { serialExec.execute { s.close() } } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ stats

    /** Call about once per second-ish; updates rates. */
    fun tickStats() {
        val s = streamer ?: run { usbFps = 0.0; mbPerSec = 0.0; displayFps = 0.0; prevT = 0L; return }
        val now = SystemClock.elapsedRealtime()
        val ok = s.framesOk
        val bytes = s.bytesTotal
        val rendered = renderedFrames
        if (prevT != 0L && now > prevT) {
            val dt = (now - prevT) / 1000.0
            usbFps = (ok - prevOk) / dt
            mbPerSec = (bytes - prevBytes) / dt / 1e6
            displayFps = (rendered - prevRendered) / dt
        }
        prevT = now; prevOk = ok; prevBytes = bytes; prevRendered = rendered
    }

    fun recordingElapsedSec(): Long =
        if (isRecording) (SystemClock.elapsedRealtime() - recStartMs) / 1000 else 0L

    fun statsText(): String {
        val s = streamer
        val sb = StringBuilder()
        if (s == null) {
            sb.appendLine("stream: not running")
        } else {
            sb.appendLine(
                String.format(
                    Locale.US, "stream: %s  USB %.1f fps  %.2f MB/s  display %.1f fps",
                    if (s.streaming) "STREAMING" else "starting", usbFps, mbPerSec, displayFps
                )
            )
            sb.appendLine("frames ok=${s.framesOk} bad=${s.framesBad}  bad hdrs=${s.badHeaders}  read errs=${s.readErrors}")
            sb.appendLine(
                String.format(Locale.US, "tone: %s  range lo=%.0f hi=%.0f  span=%.0f",
                    AppSettings.toneMode.label, mapper.lo, mapper.hi, mapper.rangeSpan)
            )
            if (AppSettings.toneMode == ToneMode.AUTO) {
                val a = mapper.auto
                sb.appendLine(
                    String.format(Locale.US, "AUTO: bulk=%.2f  compression=%.0f%%  noise sigma=%.1f counts",
                        a.bulk, a.strength / AutoMapper.S_MAX * 100, a.sigma)
                )
            }
            if (calRemaining > 0) sb.appendLine("calibrating... $calRemaining frames left")
            sb.appendLine("FPN correction: " + (if (fpnOffset == null) "none" else if (fpnEnabled) "on" else "off"))
        }
        if (recordingNormal) {
            val rec = recorder
            if (rec != null) sb.appendLine(
                String.format(Locale.US, "MP4 %s  fed=%d  encoded=%d  %.1f MB",
                    mmss(recordingElapsedSec()), recFrames, rec.framesWritten, rec.bytesWritten / 1e6)
            )
        }
        val rr = rawRec
        if (rr != null) {
            val bytesPerSec = W * H * 2.0 * (if (streamFps60) 60 else 30)
            val freeBytes = ctx.filesDir.usableSpace.toDouble()
            sb.appendLine(
                String.format(Locale.US,
                    "RAW %s  written=%d (%.0f MB)  dropped=%d  queued=%d  free %.1f GB (~%.0f min)",
                    mmss(recordingElapsedSec()), rr.framesWritten, rr.bytesWritten / 1e6,
                    rr.framesDropped, rr.queued(), freeBytes / 1e9, freeBytes / bytesPerSec / 60.0)
            )
        }
        return sb.toString().trimEnd()
    }

    /** Compact one-line stats for the log file. */
    fun statsLine(): String {
        val s = streamer ?: return "STATS stream not running"
        return String.format(
            Locale.US, "STATS usb=%.1ffps disp=%.1ffps ok=%d bad=%d errs=%d tone=%s%s%s",
            usbFps, displayFps, s.framesOk, s.framesBad, s.readErrors, AppSettings.toneMode.label,
            if (AppSettings.toneMode == ToneMode.AUTO)
                String.format(Locale.US, " bulk=%.2f comp=%.2f sigma=%.1f", mapper.auto.bulk, mapper.auto.strength, mapper.auto.sigma) else "",
            rawRec?.let { " rawDropped=${it.framesDropped} queued=${it.queued()}" } ?: ""
        )
    }

    companion object {
        const val W = 320
        const val H = 256
        private const val CAL_FRAMES = 64
        fun mmss(secs: Long): String = String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60)
    }
}
