package com.example.bosonthermal

/** 256-entry ARGB lookup tables applied after tone mapping. */
object Palettes {
    val names = arrayOf("White-hot", "Black-hot", "Ironbow")
    val luts: Array<IntArray> = arrayOf(gray(false), gray(true), ironbow())

    private fun gray(inverted: Boolean): IntArray = IntArray(256) { i ->
        val g = if (inverted) 255 - i else i
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    // Approximation of the classic "iron" look: black -> indigo -> magenta -> red -> orange -> yellow -> white.
    // (Not FLIR's exact table.)
    private fun ironbow(): IntArray {
        val stops = arrayOf(
            floatArrayOf(0.00f, 0f, 0f, 0f),
            floatArrayOf(0.14f, 24f, 0f, 86f),
            floatArrayOf(0.28f, 86f, 0f, 140f),
            floatArrayOf(0.42f, 150f, 10f, 130f),
            floatArrayOf(0.56f, 205f, 40f, 80f),
            floatArrayOf(0.68f, 236f, 90f, 25f),
            floatArrayOf(0.80f, 250f, 150f, 0f),
            floatArrayOf(0.90f, 255f, 210f, 50f),
            floatArrayOf(1.00f, 255f, 255f, 255f)
        )
        val lut = IntArray(256)
        for (i in 0..255) {
            val t = i / 255f
            var k = 0
            while (k < stops.size - 2 && t > stops[k + 1][0]) k++
            val a = stops[k]
            val b = stops[k + 1]
            val u = ((t - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f)
            val r = (a[1] + (b[1] - a[1]) * u).toInt().coerceIn(0, 255)
            val g = (a[2] + (b[2] - a[2]) * u).toInt().coerceIn(0, 255)
            val bl = (a[3] + (b[3] - a[3]) * u).toInt().coerceIn(0, 255)
            lut[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return lut
    }
}
