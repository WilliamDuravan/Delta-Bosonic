package com.example.bosonthermal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Track spanning the live range (left = min, right = max) with three draggable handles:
 * T = end of the dark tail, M = value shown as mid-grey, S = start of the bright tail.
 * Draws the live histogram and the resulting curve. Double-tap resets.
 */
class ToneCurveView(ctx: Context) : View(ctx) {

    var t = ToneCurve.DEF_T
    var m = ToneCurve.DEF_M
    var s = ToneCurve.DEF_S
    var onChange: (() -> Unit)? = null

    private val hist = IntArray(ToneMapper.HBINS)
    private var base = 0f
    private var span = 1f
    private val d = ctx.resources.displayMetrics.density
    private var active = -1

    private val bgPaint = Paint().apply { color = Color.rgb(22, 22, 28) }
    private val histPaint = Paint().apply { color = Color.rgb(70, 82, 105) }
    private val diagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 200, 60)
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 11f * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 200, 205)
        textSize = 10f * d
    }

    private val colors = intArrayOf(Color.rgb(90, 165, 255), Color.rgb(225, 225, 225), Color.rgb(255, 150, 60))
    private val letters = arrayOf("T", "M", "S")

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetHandles()
            return true
        }
    })

    private fun pl() = 10f * d
    private fun pr() = width - 10f * d
    private fun pt() = 16f * d
    private fun pb() = height - 20f * d

    fun resetHandles() {
        t = ToneCurve.DEF_T
        m = ToneCurve.DEF_M
        s = ToneCurve.DEF_S
        invalidate()
        onChange?.invoke()
    }

    fun setData(src: IntArray, b: Float, sp: Float) {
        System.arraycopy(src, 0, hist, 0, hist.size)
        base = b
        span = sp
        invalidate()
    }

    private fun handleX(i: Int): Float {
        val f = when (i) {
            0 -> t
            1 -> m
            else -> s
        }
        return pl() + f * (pr() - pl())
    }

    private fun setFromX(i: Int, x: Float) {
        val f = ((x - pl()) / (pr() - pl())).coerceIn(0f, 1f)
        when (i) {
            0 -> t = f.coerceIn(ToneCurve.T_MIN, m - ToneCurve.GAP)
            1 -> m = f.coerceIn(t + ToneCurve.GAP, s - ToneCurve.GAP)
            else -> s = f.coerceIn(m + ToneCurve.GAP, ToneCurve.S_MAX)
        }
        invalidate()
        onChange?.invoke()
    }

    private fun nearest(x: Float): Int {
        var best = 1
        var bestD = Float.MAX_VALUE
        for (i in 0..2) {
            val dd = abs(handleX(i) - x)
            if (dd < bestD) {
                bestD = dd
                best = i
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                active = nearest(e.x)
                setFromX(active, e.x)
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) setFromX(active, e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = -1
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val l = pl()
        val r = pr()
        val top = pt()
        val bot = pb()
        val pw = r - l
        val ph = bot - top

        canvas.drawRect(l, top, r, bot, bgPaint)

        // histogram (square-root scaled so small populations stay visible)
        var maxC = 1f
        for (c in hist) maxC = max(maxC, sqrt(c.toFloat()))
        val bw = pw / hist.size
        for (j in hist.indices) {
            val hgt = sqrt(hist[j].toFloat()) / maxC * ph
            if (hgt > 0f) canvas.drawRect(l + j * bw, bot - hgt, l + (j + 1) * bw, bot, histPaint)
        }

        // identity reference and the curve
        canvas.drawLine(l, bot, r, top, diagPaint)
        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val y = bot - ToneCurve.eval(u, t, m, s) * ph
            if (i == 0) path.moveTo(l + u * pw, y) else path.lineTo(l + u * pw, y)
        }
        canvas.drawPath(path, curvePaint)

        // handles
        for (i in 0..2) {
            val x = handleX(i)
            linePaint.color = colors[i]
            canvas.drawLine(x, top, x, bot, linePaint)
            dotPaint.color = colors[i]
            val cy = bot + 9f * d
            canvas.drawCircle(x, cy, 8f * d, dotPaint)
            canvas.drawText(letters[i], x, cy + 4f * d, letterPaint)
        }

        val txt = String.format(
            Locale.US, "T %.0f   M %.0f   S %.0f    (range %.0f to %.0f)",
            base + t * span, base + m * span, base + s * span, base, base + span
        )
        canvas.drawText(txt, l, 11f * d, textPaint)
    }
}
