package com.example.bosonthermal

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** In-memory ring log, optionally mirrored to Download/BosonThermal/BOSON_log_*.txt. Thread-safe. */
object LogStore {
    private const val MAX = 600
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "logfile").also { it.isDaemon = true } }
    private var writer: BufferedWriter? = null
    @Volatile var fileName: String? = null
        private set
    val fileActive: Boolean get() = writer != null

    fun log(msg: String) {
        val line = synchronized(fmt) { fmt.format(Date()) } + "  " + msg
        synchronized(lines) {
            lines.add(line)
            while (lines.size > MAX) lines.removeAt(0)
        }
        val w = writer
        if (w != null) exec.execute {
            try {
                w.write(line)
                w.newLine()
                w.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun recent(n: Int): List<String> = synchronized(lines) { lines.takeLast(n) }

    fun all(): String = synchronized(lines) { lines.joinToString("\n") }

    /** Starts mirroring to a new file. Returns null on success or an error message. */
    fun startFile(ctx: Context): String? {
        if (writer != null) return null
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "BOSON_log_$stamp.txt"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/BosonThermal")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "could not create log file"
            val os = ctx.contentResolver.openOutputStream(uri) ?: return "could not open log file"
            val w = BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
            // Dump what we already have so the file is self-contained.
            for (l in recent(MAX)) { w.write(l); w.newLine() }
            w.flush()
            writer = w
            fileName = name
            log("log file started: Download/BosonThermal/$name")
            null
        } catch (e: Exception) {
            e.toString()
        }
    }

    fun stopFile() {
        val w = writer ?: return
        log("log file stopped")
        writer = null
        exec.execute { try { w.close() } catch (_: Exception) {} }
    }
}
