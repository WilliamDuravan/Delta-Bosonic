package com.example.bosonthermal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

object Ui {
    const val AMBER = 0xFFFFC83C.toInt()
    const val RED = 0xFFE5382E.toInt()

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    fun roundBg(c: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radiusDp).toFloat()
        }

    /** Translucent rounded label/button used on top of the live view. */
    fun pill(c: Context, text: String, onClick: (() -> Unit)?): TextView = TextView(c).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        isAllCaps = false
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(c, 40)
        minimumWidth = dp(c, 64)
        setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        background = roundBg(c, 0x99000000.toInt(), 20f)
        if (onClick != null) setOnClickListener { onClick() }
    }
}

/** Phone-camera style shutter: ring + white disc (photo), red disc (video), red square while recording. */
class ShutterView(ctx: Context) : View(ctx) {
    var video = false
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        val d = resources.displayMetrics.density
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f * d
        p.color = Color.WHITE
        canvas.drawCircle(cx, cy, r - 2f * d, p)
        p.style = Paint.Style.FILL
        if (recording) {
            p.color = Ui.RED
            val s = r * 0.42f
            canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 6f * d, 6f * d, p)
        } else {
            p.color = if (video) Ui.RED else Color.WHITE
            canvas.drawCircle(cx, cy, r - 9f * d, p)
        }
    }
}

/** Simple gear icon drawn with paths so no icon font is needed. */
class GearView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 8f * d
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f * d
        canvas.drawCircle(cx, cy, r * 0.62f, p)
        p.strokeWidth = 4.5f * d
        for (i in 0 until 8) {
            val a = Math.PI * 2 * i / 8
            val x0 = cx + (r * 0.78f * cos(a)).toFloat()
            val y0 = cy + (r * 0.78f * sin(a)).toFloat()
            val x1 = cx + (r * 1.0f * cos(a)).toFloat()
            val y1 = cy + (r * 1.0f * sin(a)).toFloat()
            canvas.drawLine(x0, y0, x1, y1, p)
        }
    }
}
