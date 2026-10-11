package com.example.bosonthermal

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UsbReport {

    fun className(c: Int): String = when (c) {
        0x00 -> "Per-interface"
        0x01 -> "Audio"
        0x02 -> "CDC-Comm"
        0x03 -> "HID"
        0x08 -> "Mass-Storage"
        0x09 -> "Hub"
        0x0A -> "CDC-Data"
        0x0E -> "Video(UVC)"
        0xEF -> "Misc/IAD"
        0xFE -> "App-Specific"
        0xFF -> "Vendor-Specific"
        else -> "Class"
    }

    private fun hex2(v: Int) = String.format(Locale.US, "0x%02X", v)

    private fun <T> safe(block: () -> T): T? = try { block() } catch (e: Exception) { null }

    fun build(ctx: Context, usb: UsbManager): String {
        val sb = StringBuilder()
        sb.appendLine("=== Boson USB Diagnostic ===")
        sb.appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        sb.appendLine(
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})"
        )
        sb.appendLine("USB host feature: " + ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
        val devices = usb.deviceList.values.sortedBy { it.deviceName }
        sb.appendLine(
            "CAMERA permission: " +
                (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        )
        sb.appendLine("Devices attached: ${devices.size}")
        if (devices.isEmpty()) {
            sb.appendLine()
            sb.appendLine("No USB devices visible. Check the adapter/cable, and that the Boson board is powered.")
            return sb.toString()
        }
        for (d in devices) sb.append(describeDevice(usb, d))
        return sb.toString()
    }

    private fun describeDevice(usb: UsbManager, d: UsbDevice): String {
        val sb = StringBuilder()
        val has = usb.hasPermission(d)
        sb.appendLine()
        sb.appendLine("--------------------------------------------")
        sb.appendLine("Device ${d.deviceName}")
        sb.appendLine("  VID:PID = " + String.format(Locale.US, "%04X:%04X", d.vendorId, d.productId))
        sb.appendLine(
            "  Class/Sub/Proto = ${hex2(d.deviceClass)}/${hex2(d.deviceSubclass)}/${hex2(d.deviceProtocol)}"
        )
        sb.appendLine("  Manufacturer = ${safe { d.manufacturerName }}, Product = ${safe { d.productName }}")
        sb.appendLine("  Permission granted: $has")
        sb.appendLine("  Interfaces reported by Android (incl. alt settings): ${d.interfaceCount}")
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            sb.appendLine(
                "    if ${itf.id} alt ${itf.alternateSetting}: ${className(itf.interfaceClass)} " +
                    "sub=${hex2(itf.interfaceSubclass)} endpoints=${itf.endpointCount}"
            )
        }
        if (!has) {
            sb.appendLine("  (Tap 'Grant USB permission' to read full descriptors.)")
            return sb.toString()
        }
        val conn = safe { usb.openDevice(d) }
        if (conn == null) {
            sb.appendLine("  !! openDevice() failed")
            return sb.toString()
        }
        try {
            sb.appendLine("  Serial = ${safe { conn.serial }}")
            val raw = conn.rawDescriptors
            if (raw == null) {
                sb.appendLine("  !! rawDescriptors was null")
            } else {
                sb.appendLine("  Raw descriptor bytes: ${raw.size}")
                sb.append(DescriptorParser.parse(raw))
                sb.appendLine()
                sb.appendLine("  Raw hex:")
                val hex = raw.joinToString("") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
                for (chunk in hex.chunked(64)) sb.appendLine("    $chunk")
            }
        } finally {
            conn.close()
        }
        return sb.toString()
    }

    /** Tries to claim every interface and select every alt setting. */
    fun claimTest(usb: UsbManager): String {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=== Claim / alt-setting test ===")
        val devices = usb.deviceList.values.sortedBy { it.deviceName }
        for (d in devices) {
            sb.appendLine("Device " + String.format(Locale.US, "%04X:%04X", d.vendorId, d.productId))
            if (!usb.hasPermission(d)) {
                sb.appendLine("  no permission, skipped")
                continue
            }
            val conn = safe { usb.openDevice(d) }
            if (conn == null) {
                sb.appendLine("  openDevice() failed")
                continue
            }
            try {
                val byId = (0 until d.interfaceCount).map { d.getInterface(it) }.groupBy { it.id }
                for ((id, alts) in byId) {
                    val sorted = alts.sortedBy { it.alternateSetting }
                    val first = sorted.first()
                    val ok = conn.claimInterface(first, true)
                    sb.appendLine("  if $id (${className(first.interfaceClass)}): claimInterface(force) = $ok")
                    if (!ok) continue
                    if (sorted.size > 1) {
                        for (alt in sorted) {
                            val eps = (0 until alt.endpointCount).joinToString(", ") {
                                val e = alt.getEndpoint(it)
                                hex2(e.address) + "/type" + e.type
                            }
                            val r = conn.setInterface(alt)
                            sb.appendLine("      setInterface(alt ${alt.alternateSetting}) = $r  endpoints: [$eps]")
                        }
                        conn.setInterface(first)
                    }
                    conn.releaseInterface(first)
                }
            } finally {
                conn.close()
            }
        }
        return sb.toString()
    }
}
