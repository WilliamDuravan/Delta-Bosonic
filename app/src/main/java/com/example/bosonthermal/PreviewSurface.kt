package com.example.bosonthermal

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Draws the thermal bitmap, letterboxed, inside the free area left by the on-screen controls. */
class PreviewSurface(val view: SurfaceView) : SurfaceHolder.Callback {
    private val lock = Any()
    private var ready = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    @Volatile var reserveTop = 0
    @Volatile var reserveBottom = 0
    @Volatile var reserveLeft = 0
    @Volatile var reserveRight = 0

    init {
        view.holder.addCallback(this)
    }

    fun draw(bmp: Bitmap) {
        synchronized(lock) {
            if (!ready) return
            val holder = view.holder
            val c = holder.lockCanvas() ?: return
            try {
                c.drawColor(Color.BLACK)
                val l = reserveLeft.toFloat()
                val t = reserveTop.toFloat()
                val aw = (c.width - reserveLeft - reserveRight).coerceAtLeast(1).toFloat()
                val ah = (c.height - reserveTop - reserveBottom).coerceAtLeast(1).toFloat()
                val s = minOf(aw / bmp.width, ah / bmp.height)
                val dw = bmp.width * s
                val dh = bmp.height * s
                val left = l + (aw - dw) / 2f
                val top = t + (ah - dh) / 2f
                c.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { synchronized(lock) { ready = true } }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { synchronized(lock) { ready = false } }
}
