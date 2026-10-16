package dev.gaimoy.betterspeedtest.core

import java.util.Locale

/** 速率单位换算与格式化(入参统一为 Mbps)。 */
object SpeedFormat {
    /** 自动只在比特单位间切换；所有完整输出均带单位。 */
    fun format(mbps: Double, unit: String = "auto"): String {
        if (!mbps.isFinite() || mbps < 0.0) return "—"
        val (value, label) = split(mbps, unit)
        return "$value $label"
    }

    fun split(mbps: Double, unit: String = "auto"): Pair<String, String> {
        val label = unitLabel(unit, mbps)
        if (!mbps.isFinite() || mbps < 0.0) return "—" to label
        val value = when (label) {
            "MB/s" -> "%.2f".format(Locale.US, mbps / 8.0)
            "Gbps" -> "%.2f".format(Locale.US, mbps / 1000.0)
            else -> num(mbps)
        }
        return value to label
    }

    fun unitLabel(unit: String, mbps: Double = 0.0): String = when (unit) {
        "MB/s", "Gbps", "Mbps" -> unit
        else -> if (mbps.isFinite() && mbps >= 1000.0) "Gbps" else "Mbps"
    }

    private fun num(mbps: Double): String = when {
        mbps >= 100.0 -> "%.0f".format(Locale.US, mbps)
        mbps >= 10.0 -> "%.1f".format(Locale.US, mbps)
        else -> "%.2f".format(Locale.US, mbps)
    }
}

/** 字节数格式化(流量)。 */
fun fmtBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val tb = gb * 1024
    val b = bytes.toDouble()
    return when {
        b >= tb -> "%.2f TB".format(Locale.US, b / tb)
        b >= gb -> "%.2f GB".format(Locale.US, b / gb)
        b >= mb -> "%.1f MB".format(Locale.US, b / mb)
        b >= kb -> "%.1f KB".format(Locale.US, b / kb)
        else -> "$bytes B"
    }
}
