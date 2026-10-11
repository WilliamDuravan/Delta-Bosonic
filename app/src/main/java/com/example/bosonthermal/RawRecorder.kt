package com.example.bosonthermal

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Lossless raw Y16 recorder.
 *   <base>.y16 : concatenated frames, gray16le, w*h*2 bytes each, no headers
 *   <base>.csv : '#' metadata lines, then file_index,usb_frame,host_us,pts_raw per frame
 * The USB thread only calls [offer] (a memcpy into a pooled buffer). A writer thread does all disk I/O.
 * Both files are saved to Download/BosonThermal/.
 */
class RawRecorder(
    private val ctx: Context,
    private val w: Int,
    private val h: Int,
    private val nominalFps: Int,
    private val cameraSerial: String?,
    private val spaceDir: File
) {
    private class Slot(size: Int) {
        val data = ByteArray(size)
        var usbFrame = 0L
        var hostUs = 0L
        var pts = -1L
    }

    private val frameBytes = w * h * 2
    private val free = ArrayBlockingQueue<Slot>(POOL)
    private val ready = ArrayBlockingQueue<Slot>(POOL)

    @Volatile private var accepting = false
    @Volatile private var stopping = false
    private var thread: Thread? = null

    private var rawUri: Uri? = null
    private var csvUri: Uri? = null
    private var rawPfd: ParcelFileDescriptor? = null
    private var csvPfd: ParcelFileDescriptor? = null
    private var rawOut: FileOutputStream? = null
    private var csv: BufferedWriter? = null

    var rawName: String = ""
        private set
    @Volatile var framesWritten = 0L
    @Volatile var bytesWritten = 0L
    @Volatile var framesDropped = 0L
    @Volatile var lowSpace = false
    @Volatile var writeError: String? = null

    fun queued(): Int = ready.size

    private fun insertDownload(name: String, mime: String?): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/BosonThermal")
            if (mime != null) put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed for $name")
    }

    private fun actualName(uri: Uri, fallback: String): String {
        try {
            ctx.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    fun start() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "BOSON_${stamp}_${w}x${h}_gray16le_${nominalFps}fps"
        val rUri = insertDownload("$base.y16", null)
        rawUri = rUri
        val cUri = insertDownload("$base.csv", "text/csv")
        csvUri = cUri
        rawName = actualName(rUri, "$base.y16")

        val rp = ctx.contentResolver.openFileDescriptor(rUri, "w")
            ?: throw IllegalStateException("cannot open raw file")
        rawPfd = rp
        val cp = ctx.contentResolver.openFileDescriptor(cUri, "w")
            ?: throw IllegalStateException("cannot open csv file")
        csvPfd = cp
        rawOut = FileOutputStream(rp.fileDescriptor)
        val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(cp.fileDescriptor), Charsets.UTF_8))
        csv = bw

        bw.write("# Boson raw Y16 recording\n")
        bw.write("# format=gray16le (little-endian), width=$w, height=$h, frame_bytes=$frameBytes\n")
        bw.write("# nominal_fps=$nominalFps\n")
        bw.write("# camera_serial=${cameraSerial ?: "unknown"}\n")
        bw.write("# started=" + SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()) + "\n")
        bw.write("# columns: file_index (position in .y16), usb_frame (count of frames received since stream start), ")
        bw.write("host_us (microseconds since first recorded frame), pts_raw (camera UVC timestamp, -1 if absent)\n")
        bw.write("file_index,usb_frame,host_us,pts_raw\n")
        bw.flush()

        for (i in 0 until POOL) free.offer(Slot(frameBytes))
        accepting = true
        val t = Thread { writerLoop() }
        t.name = "raw-writer"
        thread = t
        t.start()
    }

    /** USB thread. Never blocks. */
    fun offer(f: ByteArray, usbFrame: Long, hostUs: Long, pts: Long) {
        if (!accepting) return
        val s = free.poll()
        if (s == null) {
            framesDropped++
            return
        }
        System.arraycopy(f, 0, s.data, 0, frameBytes)
        s.usbFrame = usbFrame
        s.hostUs = hostUs
        s.pts = pts
        ready.offer(s)
    }

    private fun writerLoop() {
        val out = rawOut ?: return
        val bw = csv ?: return
        var idx = 0L
        var baseUs = -1L
        var sinceCheck = 0
        try {
            while (true) {
                val s = ready.poll(100, TimeUnit.MILLISECONDS)
                if (s == null) {
                    if (stopping) break else continue
                }
                if (baseUs < 0) baseUs = s.hostUs
                out.write(s.data, 0, frameBytes)
                bw.write("$idx,${s.usbFrame},${s.hostUs - baseUs},${s.pts}\n")
                idx++
                framesWritten = idx
                bytesWritten = idx * frameBytes
                free.offer(s)
                sinceCheck++
                if (sinceCheck >= 120) {
                    sinceCheck = 0
                    bw.flush()
                    if (spaceDir.usableSpace < MIN_FREE_BYTES) {
                        lowSpace = true
                        accepting = false
                    }
                }
            }
        } catch (e: Exception) {
            writeError = e.toString()
            accepting = false
        }
    }

    /** Drains the queue and closes the files. Returns true if any frames were kept. */
    fun finish(): Boolean {
        accepting = false
        stopping = true
        try { thread?.join(20000) } catch (_: InterruptedException) {}
        try {
            csv?.write("# end frames=$framesWritten dropped_in_app=$framesDropped")
            if (lowSpace) csv?.write(" stopped=low_storage")
            if (writeError != null) csv?.write(" stopped=write_error")
            csv?.write("\n")
            csv?.flush()
        } catch (_: Exception) {
        }
        try { rawOut?.flush() } catch (_: Exception) {}
        try { rawOut?.fd?.sync() } catch (_: Exception) {}
        closeAll()
        if (framesWritten == 0L) {
            deleteBoth()
            return false
        }
        return true
    }

    fun abort() {
        accepting = false
        stopping = true
        closeAll()
        deleteBoth()
    }

    private fun closeAll() {
        try { csv?.close() } catch (_: Exception) {}
        try { rawOut?.close() } catch (_: Exception) {}
        try { csvPfd?.close() } catch (_: Exception) {}
        try { rawPfd?.close() } catch (_: Exception) {}
        csv = null
        rawOut = null
        csvPfd = null
        rawPfd = null
    }

    private fun deleteBoth() {
        try { rawUri?.let { ctx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
        try { csvUri?.let { ctx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
    }

    companion object {
        private const val POOL = 120
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
    }
}
