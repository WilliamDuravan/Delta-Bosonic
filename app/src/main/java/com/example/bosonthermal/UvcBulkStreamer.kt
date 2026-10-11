package com.example.bosonthermal

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.util.Locale

private fun ByteArray.putU16(i: Int, v: Int) {
    this[i] = v.toByte()
    this[i + 1] = (v shr 8).toByte()
}

private fun ByteArray.putU32(i: Int, v: Int) {
    putU16(i, v and 0xFFFF)
    putU16(i + 2, (v ushr 16) and 0xFFFF)
}

private fun ByteArray.u32(i: Int): Long {
    if (i + 3 >= size) return 0
    return (this[i].toLong() and 0xFF) or
        ((this[i + 1].toLong() and 0xFF) shl 8) or
        ((this[i + 2].toLong() and 0xFF) shl 16) or
        ((this[i + 3].toLong() and 0xFF) shl 24)
}

/**
 * Minimal UVC 1.0 bulk-streaming client.
 * Negotiates format/frame/interval via VS probe/commit, then reads bulk payloads,
 * strips UVC payload headers and reassembles whole frames of [frameBytes] bytes.
 */
class UvcBulkStreamer(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val formatIndex: Int,
    private val frameIndex: Int,
    private val frameInterval100ns: Int,
    private val frameBytes: Int,
    private val onFrame: (ByteArray, Long, Long) -> Unit,
    private val onLog: (String) -> Unit
) : Thread("UvcBulkStreamer") {

    @Volatile var running = true
    @Volatile var streaming = false
    @Volatile var framesOk = 0L
    @Volatile var framesBad = 0L
    @Volatile var badHeaders = 0L
    @Volatile var readErrors = 0L
    @Volatile var bytesTotal = 0L

    private fun hex(b: ByteArray, n: Int): String =
        (0 until minOf(n, b.size)).joinToString(" ") {
            String.format(Locale.US, "%02X", b[it].toInt() and 0xFF)
        }

    override fun run() {
        val conn: UsbDeviceConnection? = try { usb.openDevice(device) } catch (e: Exception) { null }
        if (conn == null) {
            onLog("openDevice failed")
            return
        }
        var claimed: UsbInterface? = null
        try {
            var vs: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_VIDEO &&
                    itf.interfaceSubclass == 2 && itf.alternateSetting == 0
                ) {
                    vs = itf
                    break
                }
            }
            if (vs == null) {
                onLog("no UVC streaming interface found")
                return
            }
            var ep: UsbEndpoint? = null
            for (e in 0 until vs.endpointCount) {
                val c = vs.getEndpoint(e)
                if (c.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    c.direction == UsbConstants.USB_DIR_IN
                ) {
                    ep = c
                    break
                }
            }
            if (ep == null) {
                onLog("no bulk IN endpoint (isochronous device? not supported yet)")
                return
            }
            if (!conn.claimInterface(vs, true)) {
                onLog("claimInterface failed")
                return
            }
            claimed = vs
            if (!negotiate(conn, vs.id)) return
            readLoop(conn, ep)
        } catch (e: Exception) {
            onLog("exception: $e")
        } finally {
            val c = claimed
            if (c != null) {
                try { conn.releaseInterface(c) } catch (_: Exception) {}
            }
            conn.close()
            streaming = false
            onLog("stream stopped")
        }
    }

    private fun negotiate(conn: UsbDeviceConnection, ifNum: Int): Boolean {
        val probeValue = SEL_PROBE shl 8
        val commitValue = SEL_COMMIT shl 8
        val buf = ByteArray(48)
        var plen = 0
        for (l in intArrayOf(26, 34, 48)) {
            val r = conn.controlTransfer(0xA1, 0x81, probeValue, ifNum, buf, l, 1000)
            onLog("GET_CUR probe (wLength=$l) -> $r")
            if (r > 0) {
                plen = r
                break
            }
        }
        if (plen == 0) {
            onLog("cannot read probe control")
            return false
        }
        val req = buf.copyOf(plen)
        req.putU16(0, 1) // bmHint: dwFrameInterval fixed
        req[2] = formatIndex.toByte()
        req[3] = frameIndex.toByte()
        req.putU32(4, frameInterval100ns)
        val r1 = conn.controlTransfer(0x21, 0x01, probeValue, ifNum, req, plen, 1000)
        val resp = ByteArray(48)
        val r2 = conn.controlTransfer(0xA1, 0x81, probeValue, ifNum, resp, plen, 1000)
        onLog("SET probe=$r1, GET probe=$r2")
        onLog("probe: " + hex(resp, plen))
        if (r1 < 0 || r2 < 0) return false
        onLog(
            "device: format=${resp[2].toInt() and 0xFF} frame=${resp[3].toInt() and 0xFF} " +
                "interval=${resp.u32(4)} maxFrame=${resp.u32(18)} maxPayload=${resp.u32(22)}"
        )
        val commit = resp.copyOf(plen)
        val r3 = conn.controlTransfer(0x21, 0x01, commitValue, ifNum, commit, plen, 1000)
        onLog("SET commit=$r3")
        return r3 >= 0
    }

    private fun readLoop(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        val readSize = 16384
        val buf = ByteArray(readSize)
        val frame = ByteArray(frameBytes)
        var fill = 0
        var lastFid = -1
        var inPayload = false
        var payloadValid = false
        var eofPending = false
        var frameErr = false
        var curPts = -1L
        var consecutiveErr = 0
        var loggedHeaders = 0

        streaming = true
        onLog("streaming...")

        fun endPayload() {
            inPayload = false
            if (payloadValid && eofPending && fill > 0) {
                framesBad++
                fill = 0
                frameErr = false
            }
            eofPending = false
            payloadValid = false
        }

        while (running) {
            val n = conn.bulkTransfer(ep, buf, readSize, 500)
            if (n < 0) {
                readErrors++
                consecutiveErr++
                endPayload()
                if (consecutiveErr >= 20) {
                    onLog("20 consecutive read failures; stopping")
                    break
                }
                continue
            }
            consecutiveErr = 0
            if (n == 0) {
                endPayload()
                continue
            }
            bytesTotal += n

            var off = 0
            if (!inPayload) {
                inPayload = true
                val hl = buf[0].toInt() and 0xFF
                val info = if (n > 1) buf[1].toInt() and 0xFF else 0
                if (loggedHeaders < 4) {
                    loggedHeaders++
                    onLog(
                        "payload hdr: len=$hl info=" +
                            String.format(Locale.US, "0x%02X", info) + " n=$n"
                    )
                }
                if (n < 2 || hl < 2 || hl > 12 || hl > n) {
                    badHeaders++
                    payloadValid = false
                } else {
                    payloadValid = true
                    off = hl
                    val fid = info and 1
                    if (lastFid != -1 && fid != lastFid && fill > 0) {
                        framesBad++
                        fill = 0
                        frameErr = false
                    }
                    lastFid = fid
                    curPts = if ((info and 0x04) != 0 && hl >= 6) buf.u32(2) else -1L
                    if ((info and 0x40) != 0) frameErr = true
                    if ((info and 0x02) != 0) eofPending = true
                }
            }

            if (payloadValid && n > off) {
                val cnt = minOf(n - off, frameBytes - fill)
                System.arraycopy(buf, off, frame, fill, cnt)
                fill += cnt
                if (fill == frameBytes) {
                    if (!frameErr) {
                        framesOk++
                        onFrame(frame, framesOk, curPts)
                    } else {
                        framesBad++
                    }
                    fill = 0
                    frameErr = false
                }
            }

            if (n < readSize) endPayload()
        }
        streaming = false
    }

    companion object {
        private const val SEL_PROBE = 0x01
        private const val SEL_COMMIT = 0x02
    }
}
