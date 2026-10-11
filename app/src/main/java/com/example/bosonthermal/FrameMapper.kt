package com.example.bosonthermal

/** Dispatches a Y16 frame to the active tone mode. One instance per consumer thread. */
class FrameMapper(w: Int, h: Int, wantHist: Boolean = false) {
    val tone = ToneMapper(w, h, wantHist)
    val auto = AutoMapper(w, h)
    private var lastMode: ToneMode? = null

    fun map(f: ByteArray, fpn: IntArray?, out: IntArray) {
        val s = AppSettings
        val mode = s.toneMode
        if (mode != lastMode) {
            // Start the newly selected mapper from a fresh range so it does not glide in from stale state.
            if (mode == ToneMode.AUTO) auto.reset() else tone.reset()
            lastMode = mode
        }
        val lut = Palettes.luts[s.paletteIdx]
        when (mode) {
            ToneMode.AUTO -> auto.map(f, lut, fpn, s.minRange, s.autoSens, out)
            ToneMode.CURVE -> tone.map(f, lut, fpn, s.minRange, s.curveLut, out)
            ToneMode.MINMAX -> tone.map(f, lut, fpn, s.minRange, null, out)
        }
    }

    fun reset() {
        tone.reset()
        auto.reset()
    }

    /** Range used for display (for the histogram panel and diagnostics). */
    val rangeBase: Float get() = if (lastMode == ToneMode.AUTO) auto.rangeBase else tone.rangeBase
    val rangeSpan: Float get() = if (lastMode == ToneMode.AUTO) auto.rangeSpan else tone.rangeSpan
    val lo: Float get() = if (lastMode == ToneMode.AUTO) auto.lo else tone.lo
    val hi: Float get() = if (lastMode == ToneMode.AUTO) auto.hi else tone.hi
}
