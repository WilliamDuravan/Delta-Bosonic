package com.example.bosonthermal

import java.util.Locale

/**
 * Walks a raw USB configuration descriptor blob (UsbDeviceConnection.getRawDescriptors())
 * and prints interfaces, endpoints, and UVC video-class details (formats, frames, fps,
 * extension units), then a short summary of what was found.
 */
object DescriptorParser {

    private fun ByteArray.u8(i: Int): Int = if (i in indices) this[i].toInt() and 0xFF else 0
    private fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.u32(i: Int): Long = u16(i).toLong() or (u16(i + 2).toLong() shl 16)

    private fun hex2(v: Int) = String.format(Locale.US, "0x%02X", v)

    private fun guid(d: ByteArray, off: Int): String {
        val sb = StringBuilder()
        for (i in 0 until 16) {
            sb.append(String.format(Locale.US, "%02X", d.u8(off + i)))
            if (i == 3 || i == 5 || i == 7 || i == 9) sb.append('-')
        }
        return sb.toString()
    }

    private fun fourcc(d: ByteArray, off: Int): String {
        val sb = StringBuilder()
        for (i in 0 until 4) {
            val c = d.u8(off + i)
            sb.append(if (c in 32..126) c.toChar() else '?')
        }
        return sb.toString()
    }

    private fun fps(interval100ns: Long): String =
        if (interval100ns > 0) String.format(Locale.US, "%.1f", 1e7 / interval100ns) else "?"

    private fun intervals(d: ByteArray, start: Int, type: Int, end: Int): String {
        if (type == 0) {
            return "fps range ${fps(d.u32(start + 4))}..${fps(d.u32(start))} " +
                "(step ${d.u32(start + 8)} x100ns)"
        }
        val list = mutableListOf<String>()
        for (k in 0 until type) {
            if (start + 4 * k + 4 > end) break
            list += fps(d.u32(start + 4 * k))
        }
        return "fps " + list.joinToString(", ")
    }

    fun parse(d: ByteArray): String {
        val sb = StringBuilder()

        var pos = 0
        var curIf = -1
        var curAlt = 0
        var curClass = -1
        var curSub = -1

        var uvcInterfaces = 0
        var hasY16 = false
        var hasY8 = false
        var hasCdcAcm = false
        var hasVendorIf = false
        var extUnits = 0
        val formats = mutableListOf<String>()
        val seenUvcIf = mutableSetOf<Int>()

        sb.appendLine("  --- Parsed descriptors ---")
        while (pos + 2 <= d.size) {
            val len = d.u8(pos)
            if (len < 2 || pos + len > d.size) {
                sb.appendLine("  !! malformed descriptor at offset $pos (bLength=$len)")
                break
            }
            val type = d.u8(pos + 1)
            val end = pos + len

            when (type) {
                0x01 -> sb.appendLine(
                    "  DEVICE: bcdUSB=${hex2(d.u16(pos + 2))} maxPacket0=${d.u8(pos + 7)}"
                )

                0x02 -> sb.appendLine(
                    "  CONFIG: totalLength=${d.u16(pos + 2)} numInterfaces=${d.u8(pos + 4)} " +
                        "value=${d.u8(pos + 5)} maxPower=${d.u8(pos + 8) * 2}mA"
                )

                0x0B -> sb.appendLine(
                    "  IAD: firstIf=${d.u8(pos + 2)} count=${d.u8(pos + 3)} " +
                        "class=${hex2(d.u8(pos + 4))} sub=${hex2(d.u8(pos + 5))} proto=${hex2(d.u8(pos + 6))}"
                )

                0x04 -> {
                    curIf = d.u8(pos + 2)
                    curAlt = d.u8(pos + 3)
                    curClass = d.u8(pos + 5)
                    curSub = d.u8(pos + 6)
                    sb.appendLine(
                        "  INTERFACE ${curIf} alt ${curAlt}: ${UsbReport.className(curClass)} " +
                            "class=${hex2(curClass)} sub=${hex2(curSub)} proto=${hex2(d.u8(pos + 7))} " +
                            "endpoints=${d.u8(pos + 4)}"
                    )
                    if (curClass == 0x0E && curSub == 0x02 && seenUvcIf.add(curIf)) uvcInterfaces++
                    if (curClass == 0x02 && curSub == 0x02) hasCdcAcm = true
                    if (curClass == 0xFF) hasVendorIf = true
                }

                0x05 -> {
                    val addr = d.u8(pos + 2)
                    val attrs = d.u8(pos + 3)
                    val mp = d.u16(pos + 4)
                    val xfer = when (attrs and 3) {
                        0 -> "CTRL"; 1 -> "ISOC"; 2 -> "BULK"; else -> "INT"
                    }
                    val dir = if (addr and 0x80 != 0) "IN" else "OUT"
                    val effective = (mp and 0x7FF) * (1 + ((mp shr 11) and 3))
                    sb.appendLine(
                        "      EP ${hex2(addr)} $dir $xfer wMaxPacketSize=$mp " +
                            "(effective ${effective} B/interval) bInterval=${d.u8(pos + 6)}"
                    )
                }

                0x24 -> {
                    val sub = d.u8(pos + 2)
                    if (curClass == 0x0E && curSub == 0x01) {
                        // Video control
                        when (sub) {
                            0x01 -> sb.appendLine(
                                "      VC header: bcdUVC=${hex2(d.u16(pos + 3))}"
                            )
                            0x02 -> sb.appendLine(
                                "      VC input terminal id=${d.u8(pos + 3)} type=${hex2(d.u16(pos + 4))}"
                            )
                            0x03 -> sb.appendLine(
                                "      VC output terminal id=${d.u8(pos + 3)} type=${hex2(d.u16(pos + 4))}"
                            )
                            0x05 -> sb.appendLine("      VC processing unit id=${d.u8(pos + 3)}")
                            0x06 -> {
                                extUnits++
                                sb.appendLine(
                                    "      VC EXTENSION UNIT id=${d.u8(pos + 3)} " +
                                        "guid=${guid(d, pos + 4)} numControls=${d.u8(pos + 20)}"
                                )
                            }
                            else -> sb.appendLine("      VC subtype ${hex2(sub)}")
                        }
                    } else if (curClass == 0x0E && curSub == 0x02) {
                        // Video streaming
                        when (sub) {
                            0x01 -> sb.appendLine(
                                "      VS input header: numFormats=${d.u8(pos + 3)} " +
                                    "endpoint=${hex2(d.u8(pos + 6))}"
                            )
                            0x04, 0x10 -> {
                                val name = if (sub == 0x04) "UNCOMPRESSED" else "FRAME-BASED"
                                val fcc = fourcc(d, pos + 5)
                                val bpp = d.u8(pos + 21)
                                val note = when (fcc) {
                                    "Y16 " -> { hasY16 = true; "  <-- 16-bit RAW" }
                                    "Y800", "Y8  " -> { hasY8 = true; "  <-- 8-bit mono" }
                                    else -> ""
                                }
                                formats += "#${d.u8(pos + 3)} $name '$fcc' ${bpp}bpp"
                                sb.appendLine(
                                    "      VS FORMAT #${d.u8(pos + 3)} $name fourcc='$fcc' ${bpp}bpp " +
                                        "frames=${d.u8(pos + 4)} guid=${guid(d, pos + 5)}$note"
                                )
                            }
                            0x06 -> {
                                formats += "#${d.u8(pos + 3)} MJPEG"
                                sb.appendLine(
                                    "      VS FORMAT #${d.u8(pos + 3)} MJPEG frames=${d.u8(pos + 4)}"
                                )
                            }
                            0x05, 0x07 -> {
                                val t = d.u8(pos + 25)
                                sb.appendLine(
                                    "        frame #${d.u8(pos + 3)}: ${d.u16(pos + 5)}x${d.u16(pos + 7)} " +
                                        "default=${fps(d.u32(pos + 21))}fps " +
                                        intervals(d, pos + 26, t, end)
                                )
                            }
                            0x11 -> {
                                val t = d.u8(pos + 21)
                                sb.appendLine(
                                    "        frame #${d.u8(pos + 3)}: ${d.u16(pos + 5)}x${d.u16(pos + 7)} " +
                                        "default=${fps(d.u32(pos + 17))}fps " +
                                        intervals(d, pos + 26, t, end)
                                )
                            }
                            0x03 -> sb.appendLine("      VS still-image frame descriptor")
                            0x0D -> sb.appendLine("      VS color matching")
                            else -> sb.appendLine("      VS subtype ${hex2(sub)}")
                        }
                    } else {
                        sb.appendLine(
                            "      class-specific descriptor (interface class ${hex2(curClass)}) subtype ${hex2(sub)}"
                        )
                    }
                }

                else -> sb.appendLine("  descriptor type ${hex2(type)} len=$len")
            }
            pos = end
        }

        val summary = StringBuilder()
        summary.appendLine("  --- Summary ---")
        summary.appendLine("  UVC streaming interfaces: $uvcInterfaces")
        summary.appendLine("  Y16 (16-bit raw) advertised: $hasY16")
        summary.appendLine("  Y8/Y800 advertised: $hasY8")
        summary.appendLine("  Formats: ${if (formats.isEmpty()) "none" else formats.joinToString("; ")}")
        summary.appendLine("  UVC extension units: $extUnits")
        summary.appendLine("  CDC-ACM serial interface: $hasCdcAcm")
        summary.appendLine("  Vendor-specific interface present (possible FTDI/CP210x-style serial): $hasVendorIf")
        return summary.toString() + "\n" + sb.toString()
    }
}
