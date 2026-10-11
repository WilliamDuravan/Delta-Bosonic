package com.example.bosonthermal

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * H.264 MP4 recorder. Frames are drawn onto the encoder's input Surface;
 * timestamps come from the surface (arrival time), so dropped frames stay time-correct.
 * All methods must be called from a single thread.
 */
class Mp4Recorder(
    private val ctx: Context,
    private val outW: Int,
    private val outH: Int,
    private val fps: Int
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var surface: Surface? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var track = -1
    private var muxerStarted = false
    private var basePtsUs = -1L
    private val info = MediaCodec.BufferInfo()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect(0, 0, outW, outH)

    var name: String = ""
        private set
    @Volatile var framesWritten = 0L
    @Volatile var bytesWritten = 0L

    fun start() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        name = "BOSON_$stamp.mp4"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BosonThermal")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        uri = u
        pfd = resolver.openFileDescriptor(u, "rw") ?: throw IllegalStateException("cannot open file")

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = c.createInputSurface()
        c.start()
        codec = c
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun addFrame(bmp: Bitmap) {
        val s = surface ?: return
        val canvas = s.lockCanvas(null)
        try {
            canvas.drawBitmap(bmp, null, dst, paint)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
        drain(false)
    }

    private fun drain(endOfStream: Boolean) {
        val cd = codec ?: return
        val mx = muxer ?: return
        var idleRounds = 0
        while (true) {
            val idx = cd.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                idleRounds++
                if (idleRounds > 300) return
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    track = mx.addTrack(cd.outputFormat)
                    mx.start()
                    muxerStarted = true
                }
            } else if (idx >= 0) {
                val buf = cd.getOutputBuffer(idx)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (info.size > 0 && muxerStarted && buf != null) {
                    if (basePtsUs < 0) basePtsUs = info.presentationTimeUs
                    info.presentationTimeUs -= basePtsUs
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    mx.writeSampleData(track, buf, info)
                    framesWritten++
                    bytesWritten += info.size
                }
                cd.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    /** Finishes the file. Returns true if a playable file was kept. */
    fun finish(): Boolean {
        try {
            codec?.signalEndOfInputStream()
            drain(true)
        } catch (_: Exception) {
        }
        release()
        val u = uri
        if (u != null) {
            val resolver = ctx.contentResolver
            if (framesWritten > 0) {
                val done = ContentValues()
                done.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(u, done, null, null)
                return true
            } else {
                resolver.delete(u, null, null)
            }
        }
        return false
    }

    fun abort() {
        release()
        uri?.let { ctx.contentResolver.delete(it, null, null) }
    }

    private fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }
}
