package com.example.bosonthermal

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** USB descriptor report and claim test. Reached from Settings > Diagnostics. */
class UsbReportActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var out: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PERM) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Toast.makeText(
                    context,
                    if (granted) "USB permission granted" else "USB permission DENIED",
                    Toast.LENGTH_LONG
                ).show()
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        refresh()
        requestAll()
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val act = this

        fun btn(label: String, onClick: () -> Unit): Button {
            val b = Button(act)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { onClick() }
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            return b
        }

        val row1 = LinearLayout(act)
        row1.addView(btn("Refresh") { refresh() })
        row1.addView(btn("Grant USB permission") { requestAll() })

        val row2 = LinearLayout(act)
        row2.addView(btn("Claim test") { runClaimTest() })
        row2.addView(btn("Copy") { copyReport() })
        row2.addView(btn("Share") { shareReport() })

        val row3 = LinearLayout(act)
        row3.addView(btn("Back to camera") { finish() })

        out = TextView(act)
        out.typeface = Typeface.MONOSPACE
        out.textSize = 11f
        out.setTextIsSelectable(true)

        val scroll = ScrollView(act)
        scroll.addView(out)

        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(row1)
        root.addView(row2)
        root.addView(row3)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left + dp(8), b.top + dp(8), b.right + dp(8), b.bottom + dp(8))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun refresh() {
        out.text = UsbReport.build(this, usb)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestAll()
        } else {
            Toast.makeText(this, "CAMERA permission denied; USB video access will not work", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAll() {
        // Android requires CAMERA permission before it will grant USB access to a UVC device.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        val missing = usb.deviceList.values.filter { !usb.hasPermission(it) }
        if (missing.isEmpty()) {
            Toast.makeText(this, "Nothing to grant (no devices, or all already granted)", Toast.LENGTH_SHORT).show()
            return
        }
        for (d in missing) {
            val pi = PendingIntent.getBroadcast(
                this,
                d.deviceId,
                Intent(ACTION_PERM).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE
            )
            try {
                usb.requestPermission(d, pi)
            } catch (e: Exception) {
                Toast.makeText(this, "requestPermission failed: $e", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runClaimTest() {
        out.text = UsbReport.build(this, usb) + UsbReport.claimTest(usb)
    }

    private fun copyReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Boson USB report", out.text.toString()))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, out.text.toString())
        startActivity(Intent.createChooser(send, "Share report"))
    }

    companion object {
        private const val ACTION_PERM = LiveActivity.ACTION_PERM
        private const val REQ_CAMERA = 1
    }
}
