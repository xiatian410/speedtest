package dev.gaimoy.betterspeedtest.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** 测速源类型(自定义源已移除,后期重新规划)。 */
enum class TestSource(val label: String) {
    AUTO("自动"),
    CNSPEED("全球网测"),
    OOKLA("Speedtest.net"),
}

/** 一个可用的测速节点（来自 Cnspeed 或 Ookla）。 */
@Immutable
@Serializable
data class Node(
    val name: String,
    val source: String,
    val carrier: String = "",
    val province: String = "",
    val city: String = "",
    val region: String = "",
    val hostIp: String = "",
    val port: String = "",
    val dlUrl: String = "",
    val ulUrl: String = "",
    val ua: String = "",
)

/** Query tokens and display labels do not affect a server's identity. */
fun Node.stableId(): String {
    val endpoint = resolvePingTarget(this)
    return if (endpoint != null) "$source|${endpoint.first.lowercase()}|${endpoint.second}"
    else "$source|${dlUrl.substringBefore('?')}|${ulUrl.substringBefore('?')}|$name"
}

/** IP 定位结果(仅用于自动选点与信息展示)。 */
@Immutable
@Serializable
data class IpLocation(
    val ip: String = "",
    val carrier: String = "",
    val province: String = "",
    val city: String = "",
)

/** 单次测速最终结果。 */
@Immutable
@Serializable
data class SpeedtestResult(
    val time: Long = System.currentTimeMillis(),
    val node: String,
    val source: String,
    val carrier: String = "",
    val city: String = "",
    val ip: String = "",
    val network: String = "",
    val mode: String = "",
    val dlAvg: Double = 0.0,
    val dlPeak: Double = 0.0,
    val ulAvg: Double = 0.0,
    val ulPeak: Double = 0.0,
    val dlBytes: Long = 0L,
    val ulBytes: Long = 0L,
    val pingMs: Double = 0.0,
    val jitter: Double = 0.0,
    val dlConns: Int = 0,
    val ulConns: Int = 0,
    val ulMsg: String = "",
    val probeBytes: Long = 0L,
)
