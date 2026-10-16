package dev.gaimoy.betterspeedtest.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * 全球网测(cnspeedtest.cn)源:官方 App 的私有协议 —— mobilematch 就近节点、
 * 加密 serverlist 全量表、dovalid 换 key、原始 socket 下载/上传。
 */
object Cnspeed {
    const val CONTROL_BASE = "https://dlcv2.cnspeedtest.cn:8443"
    const val DOWNLOAD_BASE = "https://down.cnspeedtest.cn:8043"
    const val PKG = "com.cnspeedtest.globalspeed"
    private const val DES_KEY = "dw!@#$%^"

    private const val PATH_SERVERLIST = "/TaierAndroid/Config/serverlist_encrypt.json"
    private const val PATH_MOBILEMATCH = "/dataServer/mobilematch_many.php"
    private const val PATH_DOVALID = "/speed/dovalid"
    const val PATH_DOWNLOAD = "/speed/File(1G).dl"
    const val PATH_UPLOAD = "/speed/doAnalsLoad.do"

    private const val TOKEN_PART1 = "model=Android&imei={imei}"
    private const val TOKEN_PART2 = "stime={t}&band=200&rand=12345555"

    private val RE_KEY = Regex("1-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val json = Json { ignoreUnknownKeys = true }
    private val serverCache = TimedCache<List<Node>>(300_000_000_000L)

    private fun md5hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun genImei(): String {
        val b = ByteArray(8).also { HttpClients.random.nextBytes(it) }
        val hex = b.joinToString("") { "%02x".format(it) }
        return "TS" + md5hex(hex).substring(8, 24).uppercase()
    }

    fun token(imei: String, t: Long): String =
        md5hex(md5hex(TOKEN_PART1.replace("{imei}", imei)) + md5hex(TOKEN_PART2.replace("{t}", t.toString())))

    /** 就近节点表(按省/市/运营商请求,移动网络下的官方匹配逻辑)。 */
    suspend fun fetchNearby(province: String, city: String, oper: String): List<Node> =
        withContext(Dispatchers.IO) {
            val url = (CONTROL_BASE + PATH_MOBILEMATCH).toHttpUrl().newBuilder().apply {
                addQueryParameter("ip", "")
                addQueryParameter("network", "4")
                addQueryParameter("province", province)
                addQueryParameter("city", city)
                addQueryParameter("wifioper", oper)
                addQueryParameter("mobileoperid", "0")
                addQueryParameter("ipv6", "0")
                addQueryParameter("model", "Android")
                addQueryParameter("pkg", PKG)
            }.build().toString()
            val body = httpGet(HttpClients.insecure(12), url, HttpClients.CHROME_UA)
            json.parseToJsonElement(body).jsonArray.map { el ->
                val o = el.jsonObject
                Node(
                    name = o.str("hostname"),
                    source = "cnspeed",
                    carrier = oper,
                    province = o.str("pname"),
                    city = o.str("city"),
                    hostIp = o.str("hostip"),
                    port = o.str("port"),
                )
            }
        }

    /** 全量节点表(DES-ECB 加密的 JSON)。 */
    suspend fun fetchServerlist(): List<Node> = serverCache.get(cacheIf = { it.isNotEmpty() }) { withContext(Dispatchers.IO) {
        val url = DOWNLOAD_BASE + PATH_SERVERLIST
        val hexBody = httpGet(HttpClients.plain(30), url, "okhttp/3").trim()
        val plain = desDecrypt(hexBody, DES_KEY)
        json.parseToJsonElement(plain).jsonArray.map { el ->
            val o = el.jsonObject
            Node(
                name = o.str("hostname"),
                source = "cnspeed",
                carrier = o.str("oper"),
                province = o.str("pname"),
                city = o.str("location"),
                hostIp = o.str("hostip"),
                port = (o["port"] as? JsonPrimitive)?.content ?: "",
            )
        }
    } }

    /** 向节点换取本次测速 key。 */
    suspend fun dovalid(ip: String, port: String): String = withContext(Dispatchers.IO) {
        val imei = genImei()
        val t = System.currentTimeMillis() / 1000
        val address = if (':' in ip && !ip.startsWith('[')) "[$ip]" else ip
        val url = "http://$address:${port.toIntOrNull() ?: 80}$PATH_DOVALID".toHttpUrl().newBuilder().apply {
            addQueryParameter("key", "")
            addQueryParameter("flag", "true")
            addQueryParameter("bandwidth", "200")
            addQueryParameter("model", "Android")
            addQueryParameter("imei", imei)
            addQueryParameter("time", t.toString())
            addQueryParameter("app", "globalspeed")
            addQueryParameter("token", token(imei, t))
            addQueryParameter("pkg", PKG)
        }.build().toString()
        val body = httpGet(
            HttpClients.plain(12),
            url,
            "Dalvik/2.1.0 (Linux; U; Android 12; RMX3031 Build/SP1A.210812.016)",
        )
        RE_KEY.find(body)?.value ?: throw IllegalStateException(
            if (body.contains("-1-")) "节点忙/排队中" else "dovalid 响应异常"
        )
    }

    private fun desDecrypt(hexData: String, key: String): String {
        val raw = ByteArray(hexData.length / 2) { hexData.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES"))
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    private suspend fun httpGet(client: OkHttpClient, url: String, ua: String): String = client.getText(url, ua)
}

private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: ""
