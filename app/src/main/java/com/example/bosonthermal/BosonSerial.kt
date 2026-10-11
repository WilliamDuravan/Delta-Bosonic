package com.example.bosonthermal

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.Locale

class FslpResult(val ok: Boolean, val status: Long, val data: ByteArray, val message: String)

/**
 * FLIR FSLP over the Boson's CDC-ACM interface (framing per FLIR/rawBoson):
 *  0x8E | channel(0) | seq(4, BE) | function(4, BE) | status(4, 0xFFFFFFFF on send) | data | CRC16(BE) | 0xAE
 *  CRC-16/AUG-CCITT (init 0x1D0F, poly 0x1021) over channel..last data byte.
 *  Byte stuffing between start/end: 8E->9E 81, 9E->9E 91, AE->9E A1.
 */
class BosonSerial(private val usb: UsbManager, private val device: UsbDevice) {

    private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var ctrlIf: UsbInterface? = null
    private var dataIf: UsbInterface? = null
    private var sequence = 0

    @Synchronized
    fun open(): String? {
        if (conn != null) return null
        var ctrl: UsbInterface? = null
        var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass == 2 && itf.interfaceSubclass == 2) ctrl = itf
            if (itf.interfaceClass == 0x0A) data = itf
        }
        if (ctrl == null || data == null) return "no CDC-ACM interfaces found"
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (e in 0 until data.endpointCount) {
            val ep = data.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
        }
        if (inEp == null || outEp == null) return "CDC data endpoints missing"
        val c = try { usb.openDevice(device) } catch (e: Exception) { null }
            ?: return "openDevice failed (second connection refused?)"
        if (!c.claimInterface(ctrl, true)) {
            c.close()
            return "claimInterface(control) failed"
        }
        if (!c.claimInterface(data, true)) {
            c.releaseInterface(ctrl)
            c.close()
            return "claimInterface(data) failed"
        }
        // 921600 8N1 line coding, DTR+RTS asserted. Failures here are non-fatal.
        val lc = byteArrayOf(0x00, 0x10, 0x0E, 0x00, 0, 0, 8)
        c.controlTransfer(0x21, 0x20, 0, ctrl.id, lc, lc.size, 500)
        c.controlTransfer(0x21, 0x22, 0x03, ctrl.id, null, 0, 500)
        conn = c
        epIn = inEp
        epOut = outEp
        ctrlIf = ctrl
        dataIf = data
        return null
    }

    @Synchronized
    fun close() {
        val c = conn ?: return
        try { ctrlIf?.let { c.releaseInterface(it) } } catch (_: Exception) {}
        try { dataIf?.let { c.releaseInterface(it) } } catch (_: Exception) {}
        c.close()
        conn = null
        epIn = null
        epOut = null
    }

    private fun fail(msg: String) = FslpResult(false, -1, ByteArray(0), msg)

    @Synchronized
    fun transact(function: Int, payload: ByteArray = ByteArray(0)): FslpResult {
        val err = open()
        if (err != null) return fail("open: $err")
        val c = conn ?: return fail("not open")
        val ein = epIn ?: return fail("not open")
        val eout = epOut ?: return fail("not open")

        sequence = (sequence + 1) % 10
        val frame = buildFrame(function, payload)

        // Discard any stale bytes.
        val tmp = ByteArray(512)
        for (k in 0 until 4) {
            val n = c.bulkTransfer(ein, tmp, tmp.size, 5)
            if (n <= 0) break
        }

        val w = c.bulkTransfer(eout, frame, frame.size, 1000)
        if (w != frame.size) return fail("write returned $w (expected ${frame.size})")

        val buf = ByteArrayOutputStream()
        val deadline = SystemClock.elapsedRealtime() + 1500
        var gotStart = false
        var done = false
        while (!done && SystemClock.elapsedRealtime() < deadline) {
            val n = c.bulkTransfer(ein, tmp, tmp.size, 100)
            if (n > 0) {
                for (i in 0 until n) {
                    val b = tmp[i].toInt() and 0xFF
                    if (!gotStart) {
                        if (b == 0x8E) {
                            gotStart = true
                            buf.write(b)
                        }
                    } else {
                        buf.write(b)
                        if (b == 0xAE) {
                            done = true
                            break
                        }
                    }
                }
            }
        }
        if (!done) return fail("timeout waiting for response (${buf.size()} bytes received)")
        return parse(buf.toByteArray())
    }

    private fun buildFrame(function: Int, payload: ByteArray): ByteArray {
        val n = payload.size
        val raw = ByteArray(14 + n + 3)
        raw[0] = 0x8E.toByte()
        raw[1] = 0
        raw[5] = sequence.toByte()
        raw[6] = (function ushr 24).toByte()
        raw[7] = (function ushr 16).toByte()
        raw[8] = (function ushr 8).toByte()
        raw[9] = function.toByte()
        for (i in 10..13) raw[i] = 0xFF.toByte()
        System.arraycopy(payload, 0, raw, 14, n)
        val crc = crc16(raw, 1, 13 + n)
        raw[14 + n] = (crc shr 8).toByte()
        raw[15 + n] = crc.toByte()
        raw[16 + n] = 0xAE.toByte()

        val out = ByteArrayOutputStream()
        out.write(raw[0].toInt() and 0xFF)
        for (i in 1 until raw.size - 1) {
            when (raw[i].toInt() and 0xFF) {
                0x8E -> { out.write(0x9E); out.write(0x81) }
                0x9E -> { out.write(0x9E); out.write(0x91) }
                0xAE -> { out.write(0x9E); out.write(0xA1) }
                else -> out.write(raw[i].toInt() and 0xFF)
            }
        }
        out.write(raw[raw.size - 1].toInt() and 0xFF)
        return out.toByteArray()
    }

    private fun parse(stuffed: ByteArray): FslpResult {
        val o = ByteArrayOutputStream()
        o.write(stuffed[0].toInt() and 0xFF)
        var i = 1
        while (i < stuffed.size - 1) {
            val b = stuffed[i].toInt() and 0xFF
            if (b == 0x9E) {
                if (i + 1 >= stuffed.size - 1) return fail("malformed stuffing")
                o.write(((stuffed[i + 1].toInt() and 0xFF) + 0x0D) and 0xFF)
                i += 2
            } else {
                o.write(b)
                i++
            }
        }
        o.write(stuffed[stuffed.size - 1].toInt() and 0xFF)
        val d = o.toByteArray()

        if (d.size < 17) return fail("response too short (${d.size} bytes)")
        if (d[1].toInt() != 0) return fail("response not on channel 0")
        val seqRx = be32(d, 2)
        if (seqRx != sequence.toLong()) return fail("sequence mismatch ($seqRx vs $sequence)")
        val crc = crc16(d, 1, d.size - 4)
        val crcRx = ((d[d.size - 3].toInt() and 0xFF) shl 8) or (d[d.size - 2].toInt() and 0xFF)
        if (crc != crcRx) return fail("bad CRC")
        val status = be32(d, 10)
        val data = d.copyOfRange(14, d.size - 3)
        val msg = if (status == 0L) "OK" else String.format(Locale.US, "camera status 0x%08X", status)
        return FslpResult(status == 0L, status, data, msg)
    }

    companion object {
        const val FN_RUN_FFC = 0x00050007
        const val FN_GET_CAMERA_SN = 0x00050002

        private fun be32(d: ByteArray, off: Int): Long =
            ((d[off].toLong() and 0xFF) shl 24) or
                ((d[off + 1].toLong() and 0xFF) shl 16) or
                ((d[off + 2].toLong() and 0xFF) shl 8) or
                (d[off + 3].toLong() and 0xFF)

        fun crc16(buf: ByteArray, start: Int, count: Int): Int {
            var crc = 0x1D0F
            for (k in start until start + count) {
                crc = crc xor ((buf[k].toInt() and 0xFF) shl 8)
                for (bit in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                    crc = crc and 0xFFFF
                }
            }
            return crc
        }

        fun describe(r: FslpResult): String {
            val sb = StringBuilder(r.message)
            if (r.data.isNotEmpty()) {
                sb.append("  data=")
                sb.append(r.data.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) })
                if (r.data.size == 4) sb.append("  (u32 BE=" + be32(r.data, 0) + ")")
                if (r.data.all { (it.toInt() and 0xFF) in 32..126 }) {
                    sb.append("  ascii='" + String(r.data, Charsets.US_ASCII) + "'")
                }
            }
            return sb.toString()
        }
    }
}
