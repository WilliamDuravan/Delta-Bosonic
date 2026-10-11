package com.example.bosonthermal

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves snapshots into Pictures/BosonThermal via MediaStore (no storage permission needed). */
object Capture {

    private const val DIR = "Pictures/BosonThermal"

    /** Returns the base name used. Writes a lossless 16-bit TIFF and an 8-bit PNG of the preview. */
    fun saveSnapshot(
        ctx: Context, raw16: ByteArray, w: Int, h: Int, preview: Bitmap?,
        wantNormal: Boolean = true, wantRaw: Boolean = true
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val base = "BOSON_$stamp"
        if (wantRaw) {
            val tiff = tiff16(raw16, w, h)
            insert(ctx, base + "_raw16.tif", "image/tiff") { it.write(tiff) }
        }
        if (wantNormal && preview != null) {
            insert(ctx, "$base.png", "image/png") { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return base
    }

    private fun insert(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed for $name")
        try {
            val os = resolver.openOutputStream(uri) ?: throw IllegalStateException("no output stream")
            os.use { write(it) }
            val done = ContentValues()
            done.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Minimal uncompressed little-endian grayscale 16-bit TIFF. [raw] is little-endian 16-bit samples. */
    fun tiff16(raw: ByteArray, w: Int, h: Int): ByteArray {
        val entries = 9
        val dataOffset = 8 + 2 + entries * 12 + 4
        val bb = ByteBuffer.allocate(dataOffset + raw.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put('I'.code.toByte()).put('I'.code.toByte())
        bb.putShort(42)
        bb.putInt(8)
        bb.putShort(entries.toShort())
        fun shortEntry(tag: Int, v: Int) {
            bb.putShort(tag.toShort()); bb.putShort(3); bb.putInt(1)
            bb.putShort(v.toShort()); bb.putShort(0)
        }
        fun longEntry(tag: Int, v: Int) {
            bb.putShort(tag.toShort()); bb.putShort(4); bb.putInt(1); bb.putInt(v)
        }
        shortEntry(256, w)            // ImageWidth
        shortEntry(257, h)            // ImageLength
        shortEntry(258, 16)           // BitsPerSample
        shortEntry(259, 1)            // Compression: none
        shortEntry(262, 1)            // Photometric: BlackIsZero
        longEntry(273, dataOffset)    // StripOffsets
        shortEntry(277, 1)            // SamplesPerPixel
        shortEntry(278, h)            // RowsPerStrip
        longEntry(279, raw.size)      // StripByteCounts
        bb.putInt(0)                  // next IFD
        bb.put(raw)
        return bb.array()
    }
}
