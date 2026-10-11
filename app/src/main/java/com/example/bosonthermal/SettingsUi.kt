package com.example.bosonthermal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/** Full-screen settings overlay with a page stack. Diagnostics live in a submenu here. */
class SettingsUi(
    private val act: Activity,
    private val engine: CameraEngine,
    private val host: Host
) {
    interface Host {
        fun settingsVisibilityChanged(visible: Boolean)
        fun settingsChanged()
        fun restartStream()
        fun openUsbReport()
    }

    private enum class Page(val title: String) {
        ROOT("Settings"), IMAGE("Image"), CAMERA("Camera"), ABOUT("About & storage"),
        DIAG("Diagnostics"), LOG("Log")
    }

    val root = FrameLayout(act)
    private val back = TextView(act)
    private val titleView = TextView(act)
    private val content = LinearLayout(act)
    private val scroll = ScrollView(act)
    private val stack = ArrayList<Page>()
    private var statsView: TextView? = null
    private var logView: TextView? = null
    private var inset = intArrayOf(0, 0, 0, 0)
    private val pageColumn = LinearLayout(act)

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    private fun dp(v: Int) = Ui.dp(act, v)

    init {
        root.setBackgroundColor(0xF2101014.toInt())
        root.isClickable = true
        root.visibility = View.GONE

        pageColumn.orientation = LinearLayout.VERTICAL
        val header = LinearLayout(act)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        back.text = "‹"
        back.textSize = 30f
        back.setTextColor(Color.WHITE)
        back.gravity = Gravity.CENTER
        back.setOnClickListener { back() }
        titleView.textSize = 20f
        titleView.setTextColor(Color.WHITE)
        titleView.typeface = Typeface.DEFAULT_BOLD
        val close = TextView(act)
        close.text = "Done"
        close.textSize = 16f
        close.setTextColor(Ui.AMBER)
        close.setPadding(dp(16), dp(12), dp(16), dp(12))
        close.setOnClickListener { close() }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)

        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pageColumn.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pageColumn.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(pageColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        pageColumn.setPadding(l + dp(8), t + dp(4), r + dp(8), b + dp(4))
    }

    // ------------------------------------------------------------------ navigation

    fun open() {
        stack.clear()
        stack.add(Page.ROOT)
        root.visibility = View.VISIBLE
        render()
        host.settingsVisibilityChanged(true)
    }

    fun close() {
        if (!isOpen) return
        root.visibility = View.GONE
        stack.clear()
        statsView = null
        logView = null
        host.settingsVisibilityChanged(false)
    }

    /** Handles a Back press. Returns true if the overlay consumed it. */
    fun back(): Boolean {
        if (!isOpen) return false
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            render()
        } else close()
        return true
    }

    private fun go(p: Page) {
        stack.add(p)
        render()
    }

    /** Called by the activity ticker to refresh live pages. */
    fun tick() {
        statsView?.text = engine.statsText()
        logView?.let {
            it.text = LogStore.recent(120).joinToString("\n")
        }
    }

    // ------------------------------------------------------------------ row helpers

    private fun section(text: String) {
        val t = TextView(act)
        t.text = text.uppercase(Locale.US)
        t.textSize = 12f
        t.setTextColor(Ui.AMBER)
        t.setPadding(dp(16), dp(20), dp(16), dp(6))
        content.addView(t)
    }

    private fun row(title: String, value: String?, sub: String? = null, onClick: (() -> Unit)?) {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(16), dp(12), dp(16), dp(12))
        val left = LinearLayout(act)
        left.orientation = LinearLayout.VERTICAL
        val t = TextView(act)
        t.text = title
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        left.addView(t)
        if (sub != null) {
            val s = TextView(act)
            s.text = sub
            s.textSize = 12f
            s.setTextColor(0xFF9AA0A6.toInt())
            left.addView(s)
        }
        r.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            val v = TextView(act)
            v.text = value
            v.textSize = 15f
            v.setTextColor(0xFFB8BEC6.toInt())
            v.setPadding(dp(8), 0, 0, 0)
            r.addView(v)
        }
        if (onClick != null) {
            r.setOnClickListener { onClick() }
            val outValue = android.util.TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true))
                r.setBackgroundResource(outValue.resourceId)
        }
        content.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun nav(title: String, value: String? = null, page: Page) =
        row(title, (value ?: "") + "  ›", null) { go(page) }

    private fun toggle(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(16), dp(10), dp(16), dp(10))
        val left = LinearLayout(act)
        left.orientation = LinearLayout.VERTICAL
        val t = TextView(act)
        t.text = title
        t.textSize = 16f
        t.setTextColor(if (enabled) Color.WHITE else 0xFF777777.toInt())
        left.addView(t)
        if (sub != null) {
            val s = TextView(act)
            s.text = sub
            s.textSize = 12f
            s.setTextColor(0xFF9AA0A6.toInt())
            left.addView(s)
        }
        val sw = Switch(act)
        sw.isChecked = checked
        sw.isEnabled = enabled
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        r.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(sw)
        content.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun note(text: String) {
        val t = TextView(act)
        t.text = text
        t.textSize = 13f
        t.setTextColor(0xFF9AA0A6.toInt())
        t.setPadding(dp(16), dp(8), dp(16), dp(8))
        content.addView(t)
    }

    private fun mono(): TextView {
        val t = TextView(act)
        t.typeface = Typeface.MONOSPACE
        t.textSize = 10.5f
        t.setTextColor(0xFFD0D4D9.toInt())
        t.setPadding(dp(16), dp(8), dp(16), dp(8))
        t.setTextIsSelectable(true)
        content.addView(t)
        return t
    }

    private fun choose(title: String, items: List<String>, sel: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(act)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), sel) { d, which ->
                onPick(which)
                d.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun changed() {
        AppSettings.save()
        host.settingsChanged()
    }

    // ------------------------------------------------------------------ pages

    private fun render() {
        statsView = null
        logView = null
        content.removeAllViews()
        val page = stack.lastOrNull() ?: return
        titleView.text = page.title
        back.visibility = View.VISIBLE
        when (page) {
            Page.ROOT -> {
                nav("Image", AppSettings.toneMode.label, Page.IMAGE)
                nav("Camera", if (AppSettings.fps60) "60 fps" else "30 fps", Page.CAMERA)
                nav("About & storage", null, Page.ABOUT)
                nav("Diagnostics", null, Page.DIAG)
            }
            Page.IMAGE -> {
                section("Tone")
                row("Mode", AppSettings.toneMode.label) {
                    choose("Tone mode", ToneMode.values().map { it.label }, AppSettings.toneMode.ordinal) {
                        AppSettings.toneMode = ToneMode.values()[it]; changed()
                    }
                }
                row("Palette", Palettes.names[AppSettings.paletteIdx]) {
                    choose("Palette", Palettes.names.toList(), AppSettings.paletteIdx) {
                        AppSettings.paletteIdx = it; changed()
                    }
                }
                row(
                    "Minimum range", AppSettings.minRange.toInt().toString() + " counts",
                    "Narrowest span stretched to full contrast. Higher = low-contrast scenes stay honestly flat."
                ) {
                    val items = AppSettings.minRangeChoices.map { it.toInt().toString() }
                    choose("Minimum range (counts)", items, AppSettings.minRangeChoices.indexOf(AppSettings.minRange)) {
                        AppSettings.minRange = AppSettings.minRangeChoices[it]; changed()
                    }
                }
                row(
                    "Auto compression", AppSettings.autoSensLabels[AppSettings.autoSens],
                    "AUTO acts like MIN/MAX until a very hot or cold object crowds the rest of the scene into a thin band; then it spreads that band out. Strong engages sooner."
                ) {
                    choose("Auto compression", AppSettings.autoSensLabels, AppSettings.autoSens) {
                        AppSettings.autoSens = it; changed()
                    }
                }
                section("Tone curve")
                row("Reset curve handles", null) {
                    AppSettings.resetCurve(); AppSettings.rebuildCurve(); changed(); host.settingsChanged()
                }
                note("Switch to CURVE mode on the camera screen to drag the T / M / S handles.")
            }
            Page.CAMERA -> {
                row("Frame rate", if (AppSettings.fps60) "60 fps" else "30 fps") {
                    choose("Frame rate", listOf("60 fps", "30 fps"), if (AppSettings.fps60) 0 else 1) {
                        AppSettings.fps60 = it == 0; AppSettings.save(); host.restartStream()
                    }
                }
                row("Run FFC now", null, "Flat-field correction. Also available from the camera screen.") {
                    engine.ffc()
                }
            }
            Page.ABOUT -> {
                val ver = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (_: Exception) { "?" }
                row("Version", ver, null, null)
                val sn = try { engine.findDevice()?.serialNumber } catch (_: Exception) { null }
                row("USB serial", sn ?: "-", null, null)
                section("Where files go")
                note("Photos: Pictures/BosonThermal/\n  BOSON_<time>.png  (as displayed)\n  BOSON_<time>_raw16.tif  (16-bit raw)\n\n" +
                        "Videos: Movies/BosonThermal/BOSON_<time>.mp4\n\n" +
                        "Raw video: Download/BosonThermal/\n  *.y16 + *.csv  (process on a PC with tools/y16_gui.py)\n\n" +
                        "Logs: Download/BosonThermal/BOSON_log_*.txt")
            }
            Page.DIAG -> renderDiag()
            Page.LOG -> {
                row("Copy log", null) { copyLog() }
                row("Share log", null) { shareLog() }
                logView = mono()
                tick()
            }
        }
    }

    private fun renderDiag() {
        section("Live")
        statsView = mono()
        tick()
        section("Log")
        toggle(
            "Record log to file",
            LogStore.fileName?.let { if (LogStore.fileActive) "Writing Download/BosonThermal/$it" else "Download/BosonThermal/BOSON_log_*.txt" }
                ?: "Download/BosonThermal/BOSON_log_*.txt",
            LogStore.fileActive
        ) { on ->
            if (on) {
                val err = LogStore.startFile(act.applicationContext)
                if (err != null) LogStore.log("log file failed: $err")
            } else LogStore.stopFile()
            render()
        }
        row("View log", null) { go(Page.LOG) }
        row("Copy log", null) { copyLog() }
        row("Share log", null) { shareLog() }
        section("Tools")
        row("USB report & claim test", null) { host.openUsbReport() }
        row("Get camera serial number", null) {
            engine.runCommand("Get serial number", BosonSerial.FN_GET_CAMERA_SN, ByteArray(0))
        }
        row("Send FSLP command…", null) { showConsole() }
        section("Fixed-pattern noise")
        val has = engine.fpnOffset != null
        row("Calibrate FPN…", null, "Software one-point correction using a covered lens.") { showCalibration() }
        toggle("Apply FPN correction", if (has) null else "Run a calibration first", engine.fpnEnabled && has, has) {
            engine.fpnEnabled = it
        }
        row("Clear FPN correction", null) { engine.clearFpn(); render() }
    }

    // ------------------------------------------------------------------ dialogs / actions

    private fun copyLog() {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Boson log", LogStore.all()))
        android.widget.Toast.makeText(act, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, engine.statsText() + "\n--- log ---\n" + LogStore.all())
        act.startActivity(Intent.createChooser(send, "Share log"))
    }

    private fun showCalibration() {
        AlertDialog.Builder(act)
            .setTitle("Calibrate fixed-pattern noise")
            .setMessage(
                "1. Optional: run FFC first and let it finish.\n" +
                        "2. Cover the lens completely with a uniform surface.\n" +
                        "3. Hold still and tap Start (about 1 second).\n\n" +
                        "Recalibrate after any FFC, or when the pattern comes back."
            )
            .setPositiveButton("Start") { _, _ ->
                if (!engine.startCalibration()) android.widget.Toast.makeText(act, "Camera not streaming", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseHexBytes(text: String): ByteArray {
        val tokens = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val out = ByteArray(tokens.size)
        for ((i, tk) in tokens.withIndex()) {
            val t = tk.removePrefix("0x").removePrefix("0X").removePrefix("x").removePrefix("X")
            out[i] = t.toInt(16).toByte()
        }
        return out
    }

    private fun showConsole() {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), 0)
        val fn = EditText(act)
        fn.hint = "Function ID (hex), e.g. 00050007"
        fn.setSingleLine()
        val data = EditText(act)
        data.hint = "Data bytes (hex, optional), e.g. 00 00 00 01"
        data.setSingleLine()
        box.addView(fn)
        box.addView(data)
        AlertDialog.Builder(act)
            .setTitle("Send FSLP command")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                try {
                    val id = fn.text.toString().trim().removePrefix("0x").removePrefix("0X").toLong(16).toInt()
                    val bytes = parseHexBytes(data.text.toString())
                    engine.runCommand(String.format(Locale.US, "cmd 0x%08X", id), id, bytes)
                } catch (e: Exception) {
                    LogStore.log("bad console input: $e")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
