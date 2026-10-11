package com.example.bosonthermal

/**
 * Y16 -> palette-colored ARGB.
 * Optional per-pixel offset (software fixed-pattern correction) is subtracted first, then a
 * smoothed 0.1%-99.95% percentile range is found (never narrower than [minRange] counts), then either a
 * linear stretch or the soft-tail [ToneCurve] LUT is applied, then the palette.
 */
class ToneMapper(private val w: Int, private val h: Int, private val wantHist: Boolean = false) {
    private val total = w * h
    private val vals = IntArray(total)
    private val hist = IntArray(16384)
    private var haveRange = false
    private var frameCount = 0
    private val bins = IntArray(HBINS)

    @Volatile var lo = 0f
    @Volatile var hi = 0f
    /** The range actually used for display (after the min-range rule). */
    @Volatile var rangeBase = 0f
    @Volatile var rangeSpan = 1f

    fun reset() {
        haveRange = false
    }

    fun copyHistogram(dst: IntArray) {
        System.arraycopy(bins, 0, dst, 0, HBINS)
    }

    private fun updateHistogram(base: Float, range: Float) {
        java.util.Arrays.fill(bins, 0)
        val inv = 1f / range
        for (b in hist.indices) {
            val c = hist[b]
            if (c == 0) continue
            val u = ((b * 4 + 2) - base) * inv
            if (u < 0f || u >= 1f) continue
            bins[(u * HBINS).toInt()] += c
        }
    }

    fun map(
        f: ByteArray, lut: IntArray, offset: IntArray?, minRange: Float,
        curve: IntArray?, out: IntArray
    ) {
        java.util.Arrays.fill(hist, 0)
        var j = 0
        for (p in 0 until total) {
            var v = (f[j].toInt() and 0xFF) or ((f[j + 1].toInt() and 0xFF) shl 8)
            j += 2
            if (offset != null) {
                v -= offset[p]
                if (v < 0) v = 0 else if (v > 65535) v = 65535
            }
            vals[p] = v
            hist[v shr 2]++
        }
        // Clip only a sliver at each end (stray hot/cold pixels). The old 1%/99% clip whited-out any
        // hot subject smaller than 1% of the frame, e.g. most of a squirrel.
        val lowTarget = maxOf(1, total / 1000)
        val highTarget = total - maxOf(1, total / 2000)
        var acc = 0
        var loBin = 0
        var hiBin = hist.size - 1
        var gotLo = false
        for (b in hist.indices) {
            acc += hist[b]
            if (!gotLo && acc >= lowTarget) {
                loBin = b
                gotLo = true
            }
            if (acc >= highTarget) {
                hiBin = b
                break
            }
        }
        val loV = loBin * 4f
        val hiV = hiBin * 4f + 3f
        if (!haveRange) {
            lo = loV
            hi = hiV
            haveRange = true
        } else {
            lo += (loV - lo) * 0.1f
            hi += (hiV - hi) * 0.1f
        }
        var base = lo
        var range = hi - lo
        if (range < minRange) {
            base = (lo + hi) / 2f - minRange / 2f
            range = minRange
        }
        rangeBase = base
        rangeSpan = range
        if (wantHist && (frameCount++ % 3 == 0)) updateHistogram(base, range)

        if (curve == null) {
            val scale = 255f / range
            for (p in 0 until total) {
                var g = ((vals[p] - base) * scale).toInt()
                if (g < 0) g = 0 else if (g > 255) g = 255
                out[p] = lut[g]
            }
        } else {
            val invRange = 1f / range
            val k = ToneCurve.K
            val u0 = ToneCurve.U0
            val last = ToneCurve.N - 1
            for (p in 0 until total) {
                val u = (vals[p] - base) * invRange
                var idx = ((u - u0) * k).toInt()
                if (idx < 0) idx = 0 else if (idx > last) idx = last
                out[p] = lut[curve[idx]]
            }
        }
    }

    companion object {
        const val HBINS = 64
    }
}
