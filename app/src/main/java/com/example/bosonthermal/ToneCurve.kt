package com.example.bosonthermal

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Soft-tail tone curve in normalized input space u (u=0 is the live range's min, u=1 its max).
 *  - Between t and s the curve is linear (before the midtone adjustment).
 *  - Below t: exponential toe that approaches 0 but never goes flat; above s: matching shoulder toward 1.
 *    Slope is continuous at the knees.
 *  - A power function then moves the input value m to exactly 50% grey.
 */
object ToneCurve {
    const val N = 4096
    const val U0 = -2f
    const val U1 = 3f
    val K: Float = (N - 1) / (U1 - U0)

    const val DEF_T = 0.12f
    const val DEF_M = 0.5f
    const val DEF_S = 0.88f
    const val GAP = 0.05f
    const val T_MIN = 0.02f
    const val S_MAX = 0.98f

    fun eval(u: Float, t: Float, m: Float, s: Float): Float {
        val ud = u.toDouble()
        val td = t.toDouble()
        val sd = s.toDouble()
        val z: Double = when {
            ud < td -> td * exp((ud - td) / td)
            ud > sd -> sd + (1.0 - sd) * (1.0 - exp(-(ud - sd) / (1.0 - sd)))
            else -> ud
        }
        val gamma = ln(0.5) / ln(m.toDouble())
        return if (z <= 0.0) 0f else z.pow(gamma).toFloat()
    }

    /** LUT over u in [U0, U1] giving 0..255 grey. */
    fun buildLut(t: Float, m: Float, s: Float): IntArray {
        val lut = IntArray(N)
        for (i in 0 until N) {
            val u = U0 + (U1 - U0) * i / (N - 1)
            lut[i] = (eval(u, t, m, s) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return lut
    }
}
