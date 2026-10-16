package dev.gaimoy.betterspeedtest.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Speedtest.net (Ookla) 源:config-sdk 拉就近服务器列表,预构建 /download /upload URL。 */
object Ookla {
    private const val CONFIG_URL =
        "https://www.speedtest.net/api/js/config-sdk?engine=js&limit=1000&https_functional=true"
    private const val DOWNLOAD_SIZE = 100_000_000

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = TimedCache<List<Node>>(120_000_000_000L)

    fun invalidateCache() = cache.invalidate()

    suspend fun nodes(limit: Int = 10): List<Node> = cache.get(cacheIf = { it.isNotEmpty() }) {
        val body = HttpClients.plain(15).getText(CONFIG_URL, HttpClients.CHROME_UA)
        val servers = json.parseToJsonElement(body).jsonObject["servers"]?.jsonArray
            ?: return@get emptyList()
        servers
            .map { it.jsonObject }
            .filter { it.str("host").isNotEmpty() }
            .sortedBy { (it["distance"]?.jsonPrimitive?.content?.toDoubleOrNull()) ?: Double.MAX_VALUE }
            .take(100)
            .mapNotNull { s ->
                val host = s.str("host")
                val endpoint = parseEndpoint(host, https = true) ?: return@mapNotNull null
                val base = "https://${if (':' in endpoint.first) "[${endpoint.first}]" else endpoint.first}:${endpoint.second}"
                val nocache = List(8) { "0123456789abcdef".random() }.joinToString("")
                var name = s.str("sponsor")
                val place = s.str("name")
                if (place.isNotEmpty()) name += " $place"
                Node(
                    name = "$name (Ookla)",
                    source = "ookla",
                    region = "global",
                    carrier = s.str("sponsor"),
                    province = s.str("country"),
                    city = place,
                    hostIp = endpoint.first,
                    port = endpoint.second.toString(),
                    dlUrl = "$base/download?nocache=$nocache&size=$DOWNLOAD_SIZE",
                    ulUrl = "$base/upload?nocache=$nocache",
                )
            }
    }.take(limit.coerceAtLeast(0))

    /** 从 URL 推出 ping 目标(host, port);失败返回 null。 */
    fun pingTarget(url: String): Pair<String, Int>? {
        val u = url.toHttpUrlOrNull() ?: return null
        val port = u.port.takeIf { it != 0 } ?: if (u.isHttps) 443 else 80
        return u.host to port
    }
}

private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
