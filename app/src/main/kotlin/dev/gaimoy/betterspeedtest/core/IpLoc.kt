package dev.gaimoy.betterspeedtest.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * IP 定位:仅供自动选点(大陆判定 + cnspeed 就近节点)与信息展示。
 * 原版的 AT 串口查运营商、MCC-MNC 表、手动定位等 UFI 设备逻辑已移除。
 */
object IpLoc {
    private const val CNSPEED_URL = "https://dlcv2.cnspeedtest.cn:8443/dataServer/getIpLocSP.php"
    private const val IPIP_URL = "https://myip.ipip.net/json"

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = TimedCache<IpLocation>(60_000_000_000L)

    fun invalidateCache() = cache.invalidate()

    /** Cnspeed 优先，信息不完整时查询 IPIP；短缓存合并同时到达的请求。 */
    suspend fun resolve(): IpLocation = cache.get(cacheIf = { it.ip.isNotBlank() || it.province.isNotBlank() }) {
        var out = IpLocation()
        try {
            val loc = cnspeed()
            if (loc.ip.isNotEmpty() || loc.carrier.isNotEmpty() || loc.province.isNotEmpty()) out = loc
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        if (out.carrier.isEmpty() || out.province.isEmpty() || out.city.isEmpty()) {
            try {
                val ipip = ipip()
                out = merge(out, ipip)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        out
    }

    private fun merge(base: IpLocation, extra: IpLocation): IpLocation = IpLocation(
        ip = base.ip.ifEmpty { extra.ip },
        carrier = base.carrier.ifEmpty { extra.carrier },
        province = base.province.ifEmpty { extra.province },
        city = base.city.ifEmpty { extra.city },
    )

    /** cnspeed 管道格式: ip|["中国","广东","广州",...,"电信",...]|...|电信|... */
    private suspend fun cnspeed(): IpLocation = withContext(Dispatchers.IO) {
        val body = httpGet(HttpClients.insecure(12), CNSPEED_URL, HttpClients.CHROME_UA)
        val parts = body.trim().split("|")
        var loc = IpLocation(ip = parts.getOrNull(0)?.trim() ?: "")
        parts.getOrNull(1)?.let { arrJson ->
            runCatching {
                val arr = json.parseToJsonElement(arrJson).jsonArray.map { it.jsonPrimitive.content }
                loc = loc.copy(
                    province = cleanPlace(arr.getOrNull(1) ?: ""),
                    city = cleanPlace(arr.getOrNull(2) ?: ""),
                    carrier = normalizeCarrier(arr.getOrNull(4) ?: ""),
                )
            }
        }
        parts.getOrNull(3)?.takeIf { it.isNotBlank() }?.let {
            loc = loc.copy(carrier = normalizeCarrier(it))
        }
        loc
    }

    /** ipip JSON: {ret, data:{ip, location:["中国","广东","广州",...,"电信"]}} */
    private suspend fun ipip(): IpLocation = withContext(Dispatchers.IO) {
        val body = httpGet(HttpClients.plain(12), IPIP_URL, HttpClients.CHROME_UA)
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return@withContext IpLocation()
        val arr = data["location"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        IpLocation(
            ip = data.str("ip"),
            province = cleanPlace(arr.getOrNull(1) ?: ""),
            city = cleanPlace(arr.getOrNull(2) ?: ""),
            carrier = normalizeCarrier(arr.getOrNull(4) ?: ""),
        )
    }

    private suspend fun httpGet(client: OkHttpClient, url: String, ua: String): String = client.getText(url, ua, 256 * 1024)

    private val carrierAliases = listOf(
        "移动" to "移动", "china mobile" to "移动", "cmcc" to "移动",
        "联通" to "联通", "china unicom" to "联通",
        "电信" to "电信", "china telecom" to "电信", "chinanet" to "电信",
        "广电" to "广电", "china broadnet" to "广电", "china broadcast" to "广电",
    )

    fun normalizeCarrier(s: String): String {
        val low = s.trim().lowercase()
        for ((needle, name) in carrierAliases) {
            if (low.contains(needle) || s.contains(needle)) return name
        }
        return s.trim()
    }

    fun cleanPlace(s: String): String {
        var v = s.trim()
        for (suf in listOf("特别行政区", "自治区", "省", "市", "地区")) {
            if (v.endsWith(suf) && v.length > suf.length) {
                v = v.removeSuffix(suf)
                break
            }
        }
        return v
    }

    // ---- 大陆判定与节点排序(与原版 selector/pick.go 一致)----

    private val mainlandProvinces = setOf(
        "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海",
        "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南",
        "广东", "广西", "海南", "重庆", "四川", "贵州", "云南", "西藏", "陕西",
        "甘肃", "青海", "宁夏", "新疆",
    )

    /** 能否使用全球网测分运营商节点；港澳台/海外优先 Speedtest.net。 */
    fun isMainlandChina(loc: IpLocation): Boolean {
        val p = cleanPlace(loc.province)
        if (p == "香港" || p == "澳门" || p == "台湾") return false
        if (mainlandProvinces.any { p.startsWith(it) }) return true
        return loc.carrier in setOf("移动", "联通", "电信", "广电")
    }

    /** 同运营商优先,同城 > 同省 > 其他。 */
    fun orderCnspeed(list: List<Node>, carrier: String, province: String, city: String): List<Node> {
        var same = list.filter { carrier.isEmpty() || it.carrier.isEmpty() || it.carrier.contains(carrier) || carrier.contains(it.carrier) }
        if (same.isEmpty()) same = list
        fun tier(n: Node): Int {
            val loc = n.province + n.city + n.name
            return when {
                city.isNotEmpty() && loc.contains(city) -> 0
                province.isNotEmpty() && loc.contains(province) -> 1
                else -> 2
            }
        }
        return same.sortedBy { tier(it) }
    }

    /** 关键字匹配节点(IP/城市/名称/省份)。 */
    fun matchNode(list: List<Node>, kw: String): List<Node> =
        list.filter {
            it.hostIp.contains(kw) || it.city.contains(kw) || it.name.contains(kw) || it.province.contains(kw)
        }
}

private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
