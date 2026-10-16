package dev.gaimoy.betterspeedtest.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Parses DNS names, host:port and bracketed/bare IPv6 without resolving DNS. */
internal fun parseEndpoint(host: String, port: String = "", https: Boolean = false): Pair<String, Int>? {
    val raw = host.trim()
    if (raw.isEmpty()) return null
    val authority = if (raw.count { it == ':' } > 1 && !raw.startsWith("[")) "[$raw]" else raw
    val url = "${if (https) "https" else "http"}://$authority".toHttpUrlOrNull() ?: return null
    val explicitPort = if (port.isBlank()) null else {
        port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    }
    return url.host to (explicitPort ?: url.port)
}

/** 解析节点的 TCP ping 目标(host, port);无 host/port 时退回 URL 推导。 */
fun resolvePingTarget(n: Node): Pair<String, Int>? {
    if (n.source != "cnspeed") {
        Ookla.pingTarget(n.dlUrl.ifBlank { n.ulUrl })?.let { return it }
    }
    return parseEndpoint(n.hostIp, n.port, n.dlUrl.startsWith("https"))
        ?: Ookla.pingTarget(n.dlUrl.ifBlank { n.ulUrl })
}

/** 粗测节点延迟(count=2、短超时),用于节点列表预检。 */
suspend fun quickPing(n: Node): Triple<Double, Double, Boolean> {
    val target = resolvePingTarget(n) ?: return Triple(0.0, 0.0, false)
    return Engine.tcpPing(target.first, target.second, count = 2, timeoutMs = 1500)
}
