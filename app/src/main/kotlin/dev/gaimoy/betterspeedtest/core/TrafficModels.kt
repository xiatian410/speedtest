package dev.gaimoy.betterspeedtest.core

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class TrafficMode(val label: String) { URL("自定义 URL"), NODES("测速节点循环") }

@Serializable
enum class TrafficDirection(val label: String) {
    DOWNLOAD("持续下载"), UPLOAD("持续上传"), BOTH("双向同时");
    val download get() = this != UPLOAD
    val upload get() = this != DOWNLOAD
}

@Serializable
data class TrafficOptions(
    val mode: TrafficMode = TrafficMode.URL,
    val direction: TrafficDirection = TrafficDirection.DOWNLOAD,
    val downloadUrl: String = "",
    val uploadUrl: String = "",
    val source: TestSource = TestSource.AUTO,
    val node: Node? = null,
    val connections: Int = 2,
    val durationSec: Int = 60,
    val limitMiB: Int = 256,
    val rateMbps: Double = 0.0,
    val cycleSec: Int = 30,
) {
    fun validate(): String? {
        if (connections !in 1..16) return "每方向连接数须为 1–16"
        if (durationSec !in 1..21600) return "持续时间须为 1 秒至 6 小时"
        if (limitMiB !in 1..1_048_576) return "总流量上限须为 1–1048576 MiB"
        if (!rateMbps.isFinite() || (rateMbps != 0.0 && rateMbps !in 0.1..100000.0)) return "总速率限制须为 0（不限）或 0.1–100000 Mbps"
        if (cycleSec !in 5..120) return "每轮时长须为 5–120 秒"
        if (mode == TrafficMode.URL) {
            fun valid(url: String): Boolean {
                val parsed = url.trim().toHttpUrlOrNull() ?: return false
                return parsed.username.isEmpty() && parsed.password.isEmpty()
            }
            if (direction.download && !valid(downloadUrl)) return "请输入有效的 HTTP/HTTPS 下载 URL（不含用户名密码）"
            if (direction.upload && !valid(uploadUrl)) return "请输入有效的 HTTP/HTTPS 上传 URL（不含用户名密码）"
        }
        return null
    }
}

enum class TrafficStatus { IDLE, RUNNING, STOPPING, DONE, FAILED }

data class TrafficState(
    val status: TrafficStatus = TrafficStatus.IDLE,
    val session: Long = 0,
    val options: TrafficOptions? = null,
    val message: String = "设置参数后开始",
    val target: String = "",
    val elapsedSec: Double = 0.0,
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val failures: Int = 0,
    val uploadResponses: Int = 0,
    val cycle: Int = 0,
) {
    val busy get() = status == TrafficStatus.RUNNING || status == TrafficStatus.STOPPING
    val totalBytes get() = downloadBytes + uploadBytes
}
