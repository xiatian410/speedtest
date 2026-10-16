package dev.gaimoy.betterspeedtest.ui

import android.content.Context
import android.widget.Toast
import dev.gaimoy.betterspeedtest.core.SpeedtestResult
import dev.gaimoy.betterspeedtest.core.SpeedFormat
import dev.gaimoy.betterspeedtest.core.fmtBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** 把测速结果排成纯文本卡片。 */
fun formatResultShare(r: SpeedtestResult, unit: String = "auto"): String {
    val lines = mutableListOf("BetterSpeedtest 测速结果")
    lines += "时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(r.time))
    lines += "节点: " + r.node.ifEmpty { "未知节点" }
    lines += "模式: ${resultMode(r)}"
    lines += "来源: " + when (r.source) { "cnspeed" -> "全球网测"; "ookla" -> "Speedtest.net"; else -> r.source }
    if (r.ip.isNotBlank()) lines += "公网 IP: ${r.ip}"
    if (r.network.isNotBlank()) lines += "网络: ${r.network}"
    val loc = listOfNotNull(
        r.carrier.takeIf { it.isNotEmpty() },
        r.city.takeIf { it.isNotEmpty() },
    ).joinToString(" ")
    if (loc.isNotEmpty()) lines += "位置: $loc"
    if (r.dlAvg > 0 || r.dlPeak > 0) lines += "下载: ${SpeedFormat.format(r.dlAvg, unit)} (峰值 ${SpeedFormat.format(r.dlPeak, unit)})"
    if (r.ulAvg > 0 || r.ulPeak > 0) lines += "上传: ${SpeedFormat.format(r.ulAvg, unit)} (峰值 ${SpeedFormat.format(r.ulPeak, unit)})"
    if (r.pingMs > 0) lines += "延迟: %.0f ms".format(r.pingMs)
    if (r.pingMs > 0) lines += "抖动: %.1f ms".format(Locale.US, r.jitter)
    if (r.dlConns > 0) lines += "下载连接数: ${r.dlConns}"
    if (r.ulConns > 0) lines += "上传连接数: ${r.ulConns}"
    if (r.ulMsg.isNotBlank()) lines += "上传说明: ${r.ulMsg}"
    if (r.probeBytes > 0) lines += "节点探测流量: ${fmtBytes(r.probeBytes)}"
    lines += "总流量: ${fmtBytes(r.dlBytes + r.ulBytes + r.probeBytes)}"
    return lines.joinToString("\n")
}

/** 复制结果到剪贴板。 */
fun copyResultToClipboard(context: Context, r: SpeedtestResult, unit: String = "auto") {
    val text = formatResultShare(r, unit)
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("BetterSpeedtest", text))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

/** 拉起系统分享面板。 */
fun shareResult(context: Context, r: SpeedtestResult, unit: String = "auto") {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, formatResultShare(r, unit))
    }
    runCatching {
        context.startActivity(android.content.Intent.createChooser(intent, "分享测速结果"))
    }.onFailure {
        Toast.makeText(context, "无法打开分享", Toast.LENGTH_SHORT).show()
    }
}

internal fun resultMode(r: SpeedtestResult): String = r.mode.ifBlank {
    val dl = r.dlAvg > 0 || r.dlPeak > 0 || r.dlBytes > 0
    val ul = r.ulAvg > 0 || r.ulPeak > 0 || r.ulBytes > 0
    when { dl && ul -> "双向"; dl -> "仅下载"; ul -> "仅上传"; else -> "—" }
}
