package com.example.bosonthermal

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/**
 * The camera screen (launcher). Live view first; photo/video modes, output format and tone mode
 * on the main screen; everything else lives in [SettingsUi].
 */
class LiveActivity : Activity(), CameraEngine.Listener, SettingsUi.Host {

    private enum class Status { OK, NEED_CAMERA, NEED_USB, NO_DEVICE, ERROR }

    private lateinit var usb: UsbManager
    private lateinit var engine: CameraEngine
    private lateinit var preview: PreviewSurface
    private lateinit var settingsUi: SettingsUi

    private lateinit var root: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var toneView: ToneCurveView
    private lateinit var topBar: LinearLayout
    private lateinit var centerInfo: TextView
    private lateinit var controls: LinearLayout
    private lateinit var modeStrip: LinearLayout
    private lateinit var shutterRow: LinearLayout
    private lateinit var photoLabel: TextView
    private lateinit var videoLabel: TextView
    private lateinit var shutter: ShutterView
    private lateinit var formatBtn: TextView
    private lateinit var toneBtn: TextView
    private lateinit var paletteBtn: TextView
    private lateinit var ffcBtn: TextView
    private lateinit var flash: View

    private var status = Status.NO_DEVICE
    private var errorText = ""
    private var askedCamera = false
    private val askedUsb = HashSet<Int>()
    private var lastConnectAttempt = 0L
    private var inset = intArrayOf(0, 0, 0, 0)
    private var tickCount = 0
    private val toneHist = IntArray(ToneMapper.HBINS)
    private var backCb: OnBackInvokedCallback? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var gestures: GestureDetector

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    LogStore.log("USB device detached")
                    engine.stop()
                    engine.closeSerial()
                    askedUsb.clear()
                    status = Status.NO_DEVICE
                    updateUi()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    LogStore.log("USB device attached")
                    askedUsb.clear()
                    connect()
                }
                ACTION_PERM -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    LogStore.log("USB permission " + if (granted) "granted" else "denied")
                    connect()
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickCount++
            engine.tickStats()
            engine.checkRecording()
            if (engine.reapIfDead()) {
                status = Status.OK
                lastConnectAttempt = System.currentTimeMillis() - 1500 // retry soon
            }
            val now = System.currentTimeMillis()
            if (!engine.hasStreamer && now - lastConnectAttempt > 3000) {
                lastConnectAttempt = now
                connect()
            }
            if (LogStore.fileActive && tickCount % 2 == 0) LogStore.log(engine.statsLine())
            if (settingsUi.isOpen) settingsUi.tick()
            updateUi()
            ui.postDelayed(this, 500)
        }
    }

    private val toneTicker = object : Runnable {
        override fun run() {
            if (AppSettings.toneMode == ToneMode.CURVE && engine.hasStreamer) {
                val (b, s) = engine.copyHistogram(toneHist)
                toneView.setData(toneHist, b, s)
            }
            ui.postDelayed(this, 150)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        AppSettings.load(this)
        buildUi()
        engine = CameraEngine(this, usb, preview, this)
        settingsUi = SettingsUi(this, engine, this)
        root.addView(settingsUi.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wireInsets()
        applyOrientation()
        updateUi()
        LogStore.log("app started")
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        connect()
        ui.post(ticker)
        ui.post(toneTicker)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(toneTicker)
        engine.stop()
        engine.closeSerial()
        unregisterReceiver(receiver)
        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        askedUsb.clear()
        connect()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    // ------------------------------------------------------------------ connection flow

    private fun connect() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = Status.NEED_CAMERA
            if (!askedCamera) {
                askedCamera = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            }
            updateUi()
            return
        }
        val dev = engine.findDevice()
        if (dev == null) {
            status = Status.NO_DEVICE
            updateUi()
            return
        }
        if (!usb.hasPermission(dev)) {
            status = Status.NEED_USB
            if (askedUsb.add(dev.deviceId)) requestUsb(dev)
            updateUi()
            return
        }
        if (!engine.hasStreamer) {
            val err = engine.start()
            if (err != null) {
                status = Status.ERROR
                errorText = err
            } else status = Status.OK
        } else status = Status.OK
        updateUi()
    }

    private fun requestUsb(d: UsbDevice) {
        val pi = PendingIntent.getBroadcast(
            this, d.deviceId, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE
        )
        try {
            usb.requestPermission(d, pi)
        } catch (e: Exception) {
            LogStore.log("requestPermission failed: $e")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        connect()
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val sv = SurfaceView(this)
        preview = PreviewSurface(sv)
        root.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setOnClickListener {
                askedCamera = false
                askedUsb.clear()
                connect()
            }
        }
        root.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        toneView = ToneCurveView(this).apply {
            t = AppSettings.curveT
            m = AppSettings.curveM
            s = AppSettings.curveS
            visibility = View.GONE
            onChange = {
                AppSettings.curveT = t
                AppSettings.curveM = m
                AppSettings.curveS = s
                AppSettings.rebuildCurve()
                AppSettings.save()
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateReserves() }
        }
        root.addView(toneView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104), Gravity.BOTTOM))

        // Top bar: settings, status/rec info, FFC, palette.
        val gear = GearView(this).apply { setOnClickListener { settingsUi.open() } }
        centerInfo = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        ffcBtn = Ui.pill(this, "FFC") {
            Toast.makeText(this, "FFC…", Toast.LENGTH_SHORT).show()
            engine.ffc()
        }
        paletteBtn = Ui.pill(this, "") {
            AppSettings.paletteIdx = (AppSettings.paletteIdx + 1) % Palettes.names.size
            AppSettings.save()
            updateUi()
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(centerInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ffcBtn)
            addView(paletteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateReserves() }
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Mode strip.
        photoLabel = modeLabel { setVideoMode(false) }
        videoLabel = modeLabel { setVideoMode(true) }
        modeStrip = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(photoLabel)
            addView(videoLabel)
        }

        // Shutter row: output format | shutter | tone mode.
        formatBtn = twoLine { cycleFormat() }
        toneBtn = twoLine { cycleTone() }
        shutter = ShutterView(this).apply { setOnClickListener { onShutter() } }
        shutterRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(formatBtn, LinearLayout.LayoutParams(dp(88), dp(52)).apply { setMargins(dp(14), dp(10), dp(14), dp(10)) })
            addView(shutter, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(14), dp(10), dp(14), dp(10)) })
            addView(toneBtn, LinearLayout.LayoutParams(dp(88), dp(52)).apply { setMargins(dp(14), dp(10), dp(14), dp(10)) })
        }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(shutterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionOverlays() }
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        flash = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            isClickable = false
        }
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || settingsUi.isOpen || engine.isRecording) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < dp(80) || abs(dx) < abs(dy) * 1.5f) return false
                if (toneView.visibility == View.VISIBLE &&
                    e1.y >= toneView.top && e1.y <= toneView.bottom
                ) return false
                setVideoMode(dx < 0)  // swipe left -> video, right -> photo
                return true
            }
        })
    }

    private fun modeLabel(onClick: () -> Unit) = TextView(this).apply {
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun twoLine(onClick: () -> Unit) = TextView(this).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = Ui.roundBg(this@LiveActivity, 0x99000000.toInt(), 14f)
        setOnClickListener { onClick() }
    }

    private fun wireInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            inset = intArrayOf(b.left, b.top, b.right, b.bottom)
            topBar.setPadding(b.left + dp(8), b.top + dp(8), b.right + dp(8), dp(8))
            controls.setPadding(b.left + dp(8), dp(4), b.right + dp(8), b.bottom + dp(8))
            settingsUi.setInsets(b.left, b.top, b.right, b.bottom)
            positionOverlays()
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Portrait: controls along the bottom. Landscape: controls in a column on the right. */
    private fun applyOrientation() {
        val land = isLandscape()
        controls.layoutParams = FrameLayout.LayoutParams(
            if (land) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            if (land) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (land) Gravity.END else Gravity.BOTTOM
        )
        controls.gravity = if (land) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
        modeStrip.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        shutterRow.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        // Landscape has little height: tighten margins.
        for (i in 0 until shutterRow.childCount) {
            val lp = shutterRow.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            if (land) lp.setMargins(dp(10), dp(4), dp(10), dp(4)) else lp.setMargins(dp(14), dp(10), dp(14), dp(10))
        }
        controls.requestLayout()
        positionOverlays()
    }

    /** Places the tone panel next to the controls and keeps the top bar clear of a right-hand control column. */
    private fun positionOverlays() {
        val land = isLandscape()
        val tlp = toneView.layoutParams as FrameLayout.LayoutParams
        val wantBottom: Int
        val wantRight: Int
        if (land) {
            wantBottom = inset[3] + dp(8)
            wantRight = controls.width + dp(8)
            tlp.gravity = Gravity.BOTTOM or Gravity.START
        } else {
            wantBottom = controls.height + dp(4)
            wantRight = 0
            tlp.gravity = Gravity.BOTTOM
        }
        val wantLeft = inset[0] + dp(10)
        val wantRightTotal = (if (land) wantRight else inset[2] + dp(10))
        if (tlp.bottomMargin != wantBottom || tlp.rightMargin != wantRightTotal || tlp.leftMargin != wantLeft) {
            tlp.bottomMargin = wantBottom
            tlp.rightMargin = wantRightTotal
            tlp.leftMargin = wantLeft
            toneView.layoutParams = tlp
        }
        val blp = topBar.layoutParams as FrameLayout.LayoutParams
        val wantTopRight = if (land) controls.width else 0
        if (blp.rightMargin != wantTopRight) {
            blp.rightMargin = wantTopRight
            topBar.layoutParams = blp
        }
        updateReserves()
    }

    /** Tells the preview which part of the screen the controls leave free. */
    private fun updateReserves() {
        val land = isLandscape()
        val toneShown = toneView.visibility == View.VISIBLE
        preview.reserveTop = topBar.bottom.coerceAtLeast(0)
        if (land) {
            preview.reserveRight = if (controls.width > 0) root.width - controls.left else 0
            preview.reserveBottom = if (toneShown && toneView.top > 0) root.height - toneView.top else 0
        } else {
            val limit = if (toneShown && toneView.top > 0) toneView.top else controls.top
            preview.reserveBottom = if (limit > 0) root.height - limit else 0
            preview.reserveRight = 0
        }
    }

    // ------------------------------------------------------------------ actions

    private fun setVideoMode(video: Boolean) {
        if (engine.isRecording || AppSettings.videoMode == video) return
        AppSettings.videoMode = video
        AppSettings.save()
        updateUi()
    }

    private fun cycleFormat() {
        if (engine.isRecording) return
        val all = OutFormat.values()
        if (AppSettings.videoMode) AppSettings.videoFormat = all[(AppSettings.videoFormat.ordinal + 1) % all.size]
        else AppSettings.photoFormat = all[(AppSettings.photoFormat.ordinal + 1) % all.size]
        AppSettings.save()
        updateUi()
    }

    private fun cycleTone() {
        val all = ToneMode.values()
        AppSettings.toneMode = all[(AppSettings.toneMode.ordinal + 1) % all.size]
        AppSettings.save()
        updateUi()
    }

    private fun onShutter() {
        if (!engine.isStreaming) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AppSettings.videoMode) {
            val f = AppSettings.photoFormat
            if (engine.snapshot(f.normal, f.raw)) {
                flash.animate().cancel()
                flash.alpha = 0.7f
                flash.animate().alpha(0f).setDuration(180).start()
            }
        } else if (engine.isRecording) {
            engine.stopVideo()
        } else {
            val f = AppSettings.videoFormat
            engine.startVideo(f.normal, f.raw)
        }
        updateUi()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!settingsUi.isOpen) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && !settingsUi.isOpen) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ UI state

    private fun fmtName(base: String, f: OutFormat) = when (f) {
        OutFormat.NORMAL -> base
        OutFormat.BOTH -> "$base+RAW"
        OutFormat.RAW -> "RAW $base"
    }

    private fun updateUi() {
        val video = AppSettings.videoMode
        val rec = engine.isRecording
        photoLabel.text = fmtName("PHOTO", AppSettings.photoFormat)
        videoLabel.text = fmtName("VIDEO", AppSettings.videoFormat)
        photoLabel.setTextColor(if (!video) Ui.AMBER else 0xB3FFFFFF.toInt())
        videoLabel.setTextColor(if (video) Ui.AMBER else 0xB3FFFFFF.toInt())
        modeStrip.alpha = if (rec) 0.4f else 1f
        shutter.video = video
        shutter.recording = rec
        shutter.alpha = if (engine.isStreaming) 1f else 0.4f

        val fmt = if (video) AppSettings.videoFormat else AppSettings.photoFormat
        formatBtn.text = "SAVE\n" + fmt.label
        formatBtn.alpha = if (rec) 0.4f else 1f
        toneBtn.text = "TONE\n" + AppSettings.toneMode.label
        paletteBtn.text = Palettes.names[AppSettings.paletteIdx]

        val curve = AppSettings.toneMode == ToneMode.CURVE
        val newVis = if (curve) View.VISIBLE else View.GONE
        if (toneView.visibility != newVis) {
            toneView.visibility = newVis
            toneView.t = AppSettings.curveT
            toneView.m = AppSettings.curveM
            toneView.s = AppSettings.curveS
            root.post { updateReserves() }
        }

        if (rec) {
            centerInfo.setTextColor(Ui.RED)
            val parts = (if (engine.recordingNormal) "MP4" else "") +
                    (if (engine.recordingNormal && engine.recordingRaw) "+" else "") +
                    (if (engine.recordingRaw) "RAW" else "")
            centerInfo.text = "● " + CameraEngine.mmss(engine.recordingElapsedSec()) + "  " + parts
        } else {
            centerInfo.setTextColor(0xCCFFFFFF.toInt())
            centerInfo.text = if (engine.isStreaming) (if (AppSettings.fps60) "60 fps" else "30 fps") else ""
        }

        val msg: String? = when {
            status == Status.NEED_CAMERA -> "Allow Camera access\n\nAndroid requires it before an app can open a USB camera.\n\nTap to continue"
            status == Status.NO_DEVICE -> "No camera connected\n\nPlug in the Boson with the USB adapter"
            status == Status.NEED_USB -> "Allow USB access to the camera\n\nTap to ask again"
            status == Status.ERROR -> "Could not start the camera\n\n$errorText\n\nTap to retry"
            !engine.isStreaming -> "Starting camera…"
            else -> null
        }
        statusView.text = msg ?: ""
        statusView.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ CameraEngine.Listener

    override fun onEngineChanged() {
        runOnUiThread { updateUi() }
    }

    override fun onMessage(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------------ SettingsUi.Host

    override fun settingsVisibilityChanged(visible: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            val d = onBackInvokedDispatcher
            if (visible && backCb == null) {
                val cb = OnBackInvokedCallback { settingsUi.back() }
                backCb = cb
                d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            } else if (!visible) {
                backCb?.let { d.unregisterOnBackInvokedCallback(it) }
                backCb = null
            }
        }
        updateUi()
    }

    override fun settingsChanged() {
        toneView.t = AppSettings.curveT
        toneView.m = AppSettings.curveM
        toneView.s = AppSettings.curveS
        toneView.invalidate()
        updateUi()
    }

    override fun restartStream() {
        val err = engine.restart()
        if (err != null) {
            status = Status.ERROR
            errorText = err
        }
        updateUi()
    }

    override fun openUsbReport() {
        startActivity(Intent(this, UsbReportActivity::class.java))
    }

    @Deprecated("Used on API < 33; newer versions use OnBackInvokedCallback")
    override fun onBackPressed() {
        if (!settingsUi.back()) super.onBackPressed()
    }

    companion object {
        const val ACTION_PERM = "com.example.bosonthermal.USB_PERMISSION"
        private const val REQ_CAMERA = 1
    }
}
