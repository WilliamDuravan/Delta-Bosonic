package com.example.bosonthermal

import kotlin.math.exp

/**
 * Adaptive tone mapping: plain min/max for ordinary scenes, progressively histogram-aware
 * for scenes where a very hot (or cold) object squeezes everything else into a thin slice
 * of the range.
 *
 * Per frame:
 *  1. Robust range: smoothed 0.1%..99.95% percentiles, never narrower than minRange (same as MIN/MAX).
 *  2. "Bulk fraction" = span of the 5th..95th percentiles / range. Near 1 the scene fills its range;
 *     small means most pixels are crowded into a narrow band (e.g. ambient room + hot subject).
 *  3. Blend strength s rises smoothly from 0 to [S_MAX] as the bulk fraction falls through a window
 *     set by [sensitivity]. At s = 0 the curve is exactly linear, i.e. identical to MIN/MAX.
 *  4. Curve slope d(u) = s * (capped, smoothed histogram density) + (1 - s). The histogram part gives
 *     grey levels to where pixels actually are (the empty gap between ambient and a hot object gets
 *     few); the (1 - s) linear part guarantees rare hot/cold objects keep at least (1 - s) of
 *     their normal contrast. Two caps keep it honest:
 *       - plateau: no histogram bin counts for more than 2% of pixels (large flat areas such as a
 *         wall must not swallow every grey level), and
 *       - noise: the slope never exceeds what would make one sensor-noise sigma span more than
 *         about 1.2% of the output (3 grey levels), so noise and fixed pattern are not blown up.
 *  5. Strength, noise estimate and the curve itself are low-passed over time so there is no flicker.
 *
 * One instance per consumer thread (it holds state).
 */
class AutoMapper(private val w: Int, private val h: Int) {
    private val total = w * h
    private val vals = IntArray(total)
    private val hist = IntArray(65536)
    private val diffHist = IntArray(DIFF_BINS)

    private val dens = DoubleArray(NB)
    private val tmp = DoubleArray(NB)
    private val slope = DoubleArray(NB)
    private val y = FloatArray(NB)
    private val lutSm = FloatArray(NB)
    private val outIdx = IntArray(NB)
    private val kernel: DoubleArray

    private var haveState = false
    private var strengthSm = 0.0
    private var sigmaSm = 6.0

    @Volatile var lo = 0f
    @Volatile var hi = 0f
    @Volatile var rangeBase = 0f
    @Volatile var rangeSpan = 1f

    /** Diagnostics for the last frame. */
    @Volatile var bulk = 1f
    @Volatile var strength = 0f
    @Volatile var sigma = 0f

    init {
        val r = (4.0 * KERNEL_SIGMA + 0.5).toInt()
        kernel = DoubleArray(2 * r + 1)
        var sum = 0.0
        for (i in kernel.indices) {
            val x = (i - r).toDouble()
            kernel[i] = exp(-x * x / (2.0 * KERNEL_SIGMA * KERNEL_SIGMA))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum
    }

    fun reset() {
        haveState = false
    }

    /** @param sensitivity 0 = gentle, 1 = normal, 2 = strong: how readily compression engages. */
    fun map(
        f: ByteArray, lut: IntArray, fpn: IntArray?, minRange: Float, sensitivity: Int, out: IntArray
    ) {
        // ---- pass 1: decode + histogram
        java.util.Arrays.fill(hist, 0)
        var vmin = 65535
        var vmax = 0
        var j = 0
        for (p in 0 until total) {
            var v = (f[j].toInt() and 0xFF) or ((f[j + 1].toInt() and 0xFF) shl 8)
            j += 2
            if (fpn != null) {
                v -= fpn[p]
                if (v < 0) v = 0 else if (v > 65535) v = 65535
            }
            vals[p] = v
            hist[v]++
            if (v < vmin) vmin = v
            if (v > vmax) vmax = v
        }

        // ---- noise estimate: median |difference of horizontally adjacent pixels| on every other row
        java.util.Arrays.fill(diffHist, 0)
        var nd = 0
        var y0 = 0
        while (y0 < h) {
            val row = y0 * w
            for (x in 0 until w - 1) {
                var d = vals[row + x + 1] - vals[row + x]
                if (d < 0) d = -d
                if (d >= DIFF_BINS) d = DIFF_BINS - 1
                diffHist[d]++
                nd++
            }
            y0 += 2
        }
        var med = 0
        var acc = 0
        val half = nd / 2
        for (d in 0 until DIFF_BINS) {
            acc += diffHist[d]
            if (acc > half) { med = d; break }
        }
        val sigTarget = (med * 1.048).coerceIn(2.0, 30.0)

        // ---- percentiles in one scan
        val lowTarget = maxOf(1, total / 1000)
        val highTarget = total - maxOf(1, total / 2000)
        val p5Target = (total * 0.05).toInt()
        val p95Target = (total * 0.95).toInt()
        var loV = vmin
        var hiV = vmax
        var p5V = vmin
        var p95V = vmax
        var gotLo = false
        var got5 = false
        var got95 = false
        acc = 0
        for (v in vmin..vmax) {
            acc += hist[v]
            if (!gotLo && acc >= lowTarget) { loV = v; gotLo = true }
            if (!got5 && acc >= p5Target) { p5V = v; got5 = true }
            if (!got95 && acc >= p95Target) { p95V = v; got95 = true }
            if (acc >= highTarget) { hiV = v; break }
        }

        if (!haveState) {
            lo = loV.toFloat()
            hi = hiV.toFloat()
            sigmaSm = sigTarget
        } else {
            lo += (loV - lo) * 0.1f
            hi += (hiV - hi) * 0.1f
            sigmaSm += (sigTarget - sigmaSm) * 0.1
        }
        var base = lo
        var range = hi - lo
        if (range < minRange) {
            base = (lo + hi) / 2f - minRange / 2f
            range = minRange
        }
        rangeBase = base
        rangeSpan = range

        // ---- how crowded is the bulk of the scene?
        val bulkFrac = ((p95V - p5V).toDouble() / range).coerceIn(0.0, 1.0)
        val bHi: Double
        val bLo: Double
        when (sensitivity) {
            0 -> { bHi = 0.22; bLo = 0.08 }
            2 -> { bHi = 0.40; bLo = 0.15 }
            else -> { bHi = 0.30; bLo = 0.10 }
        }
        val t = ((bHi - bulkFrac) / (bHi - bLo)).coerceIn(0.0, 1.0)
        val sTarget = S_MAX * t * t * (3.0 - 2.0 * t)
        if (!haveState) strengthSm = sTarget else strengthSm += (sTarget - strengthSm) * 0.1
        val s = strengthSm
        bulk = bulkFrac.toFloat()
        strength = s.toFloat()
        sigma = sigmaSm.toFloat()

        // ---- histogram of the normalized value u in NB bins (clipped to [0,1])
        java.util.Arrays.fill(dens, 0.0)
        val invR = 1.0 / range
        for (v in vmin..vmax) {
            val c = hist[v]
            if (c == 0) continue
            var b = ((v - base) * invR * NB).toInt()
            if (v < base) b = 0
            if (b < 0) b = 0 else if (b >= NB) b = NB - 1
            dens[b] += c.toDouble()
        }
        // plateau cap, then smooth (reflect at the ends), then normalise to sum 1
        val plateau = PLATEAU * total
        for (i in 0 until NB) if (dens[i] > plateau) dens[i] = plateau
        val r = kernel.size / 2
        for (i in 0 until NB) {
            var a = 0.0
            for (k in kernel.indices) {
                var idx = i + k - r
                if (idx < 0) idx = -idx - 1
                if (idx >= NB) idx = 2 * NB - idx - 1
                a += kernel[k] * dens[idx]
            }
            tmp[i] = a
        }
        var psum = 0.0
        for (i in 0 until NB) psum += tmp[i]
        if (psum <= 0.0) psum = 1.0

        // ---- slope = s * density + (1 - s), capped for noise, deficit spread over unsaturated bins
        for (i in 0 until NB) slope[i] = s * tmp[i] / psum * NB + (1.0 - s)
        val cap = maxOf(NOISE_K * range / sigmaSm, 1.2)
        for (iter in 0 until 8) {
            var sum = 0.0
            for (i in 0 until NB) {
                if (slope[i] > cap) slope[i] = cap
                sum += slope[i]
            }
            val deficit = NB - sum
            if (deficit <= 1e-6 * NB) break
            var room = 0
            for (i in 0 until NB) if (slope[i] < cap - 1e-9) room++
            if (room == 0) break
            val add = deficit / room
            for (i in 0 until NB) if (slope[i] < cap - 1e-9) slope[i] += add
        }
        var cum = 0.0
        for (i in 0 until NB) {
            cum += slope[i]
            y[i] = ((cum - slope[i] / 2.0) / NB).toFloat()
        }

        // ---- temporal smoothing of the curve, then to 8-bit palette indices
        if (!haveState) {
            System.arraycopy(y, 0, lutSm, 0, NB)
        } else {
            for (i in 0 until NB) lutSm[i] += (y[i] - lutSm[i]) * 0.25f
        }
        for (i in 0 until NB) {
            var o = (lutSm[i] * 255f + 0.5f).toInt()
            if (o < 0) o = 0 else if (o > 255) o = 255
            outIdx[i] = o
        }
        haveState = true

        // ---- apply
        val scale = (NB / range.toDouble()).toFloat()
        val bs = base
        for (p in 0 until total) {
            var b = ((vals[p] - bs) * scale).toInt()
            if (vals[p] < bs) b = 0
            if (b < 0) b = 0 else if (b >= NB) b = NB - 1
            out[p] = lut[outIdx[b]]
        }
    }

    companion object {
        const val NB = 1024
        /** Largest share of the curve the histogram may take; the rest stays linear. */
        const val S_MAX = 0.7
        private const val PLATEAU = 0.02
        /** One noise sigma may span at most this fraction of the output range (~3 of 256 levels). */
        private const val NOISE_K = 0.012
        private const val KERNEL_SIGMA = 2.0
        private const val DIFF_BINS = 1024
    }
}
