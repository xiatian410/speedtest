package dev.gaimoy.betterspeedtest.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

internal data class TrafficTarget(
    val name: String, val download: String, val upload: String,
    val ua: String = "", val cnspeedKey: String? = null,
    val nodeId: String = "",
) {
    val identity get() = nodeId.ifEmpty { "$name|${download.substringBefore('?')}|${upload.substringBefore('?')}" }
}

internal fun interface TrafficTargetProvider {
    suspend fun resolve(options: TrafficOptions): TrafficTarget
    suspend fun resolve(options: TrafficOptions, excluded: Set<String>): TrafficTarget = resolve(options)
}

internal object DefaultTrafficTargets : TrafficTargetProvider {
    override suspend fun resolve(options: TrafficOptions): TrafficTarget {
        return resolve(options, emptySet())
    }

    override suspend fun resolve(options: TrafficOptions, excluded: Set<String>): TrafficTarget {
        if (options.mode == TrafficMode.URL) return TrafficTarget("自定义 URL", options.downloadUrl.trim(), options.uploadUrl.trim())
        var source = options.source
        var candidates = options.node?.let { listOf(it) }
        if (candidates == null) {
            val location = IpLoc.resolve()
            if (source == TestSource.AUTO) source = if (IpLoc.isMainlandChina(location)) TestSource.CNSPEED else TestSource.OOKLA
            candidates = if (source == TestSource.CNSPEED) Cnspeed.fetchNearby(location.province, location.city, location.carrier)
                else NodeCatalog.pool(source)
        }
        for (node in candidates.filter { it.stableId() !in excluded }.take(8)) {
            currentCoroutineContext().ensureActive()
            try {
                val endpoint = resolvePingTarget(node) ?: continue
                if (!Engine.tcpPing(endpoint.first, endpoint.second, 1, 1500).third) continue
                if (node.source == "cnspeed") {
                    val key = Cnspeed.dovalid(node.hostIp, node.port).removePrefix("1-")
                    val host = node.hostIp.removeSurrounding("[", "]")
                    val base = okhttp3.HttpUrl.Builder().scheme("http").host(host).port(node.port.toIntOrNull() ?: 80).build()
                    val download = base.newBuilder().encodedPath(Cnspeed.PATH_DOWNLOAD)
                        .addQueryParameter("r", (System.currentTimeMillis() / 1000).toString()).addQueryParameter("key", key).build().toString()
                    return TrafficTarget(node.name, download, base.newBuilder().encodedPath(Cnspeed.PATH_UPLOAD).build().toString(), node.ua, key, node.stableId())
                }
                if (options.direction.download && node.dlUrl.isBlank()) continue
                if (options.direction.upload && node.ulUrl.isBlank()) continue
                return TrafficTarget(node.name, node.dlUrl, node.ulUrl, node.ua, nodeId = node.stableId())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Try another bounded candidate without producing probe payload. */ }
        }
        throw IOException("未找到可用节点，请更换测速源或手动选择节点")
    }
}

internal class TrafficHttpFailure(val status: Int, val upload: Boolean = false) :
    IOException("${if (upload) "上传" else "下载"}服务器返回 HTTP $status")
internal class TrafficNodesRejected : IOException("候选节点拒绝下载（HTTP 403）或不可用，请更换测速源或手动选择其他节点")
internal class TrafficEnd : IOException("已达到流量上限")

/** Uses existing provider contracts; custom upload is a finite, one-shot octet-stream POST. */
internal class TrafficTransport {
    private val downloadClient = HttpClients.cdnTransfer(30).newBuilder()
        .callTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val uploadClient = downloadClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun transfer(target: TrafficTarget, upload: Boolean, budget: TrafficBudget): Boolean = coroutineScope {
        val workerJob = currentCoroutineContext().job
        val request = Request.Builder().url(if (upload) target.upload else target.download)
            .header("User-Agent", target.ua.ifBlank { if (upload) UL_UA else DL_UA })
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache")
        if (target.cnspeedKey != null) {
            // Match the existing native speed-test contract; do not reuse a stale request timestamp.
            request.header("Connection", "close")
            if (!upload) {
                request.header("Accept", "*/*").removeHeader("Cache-Control")
                request.url(target.download.toHttpUrl().newBuilder()
                    .setQueryParameter("r", (System.currentTimeMillis() / 1000).toString()).build())
            }
        }
        if (upload) {
            val key = target.cnspeedKey
            val boundary = "00content0boundary00"
            val prefix = if (key == null) byteArrayOf() else
                "--$boundary\r\nContent-Disposition: form-data; name=\"upload\";filename=\"SPEED_${System.currentTimeMillis() / 1000}\"\r\n\r\n".toByteArray()
            val suffix = if (key == null) byteArrayOf() else "\r\n--$boundary--\r\n".toByteArray()
            val length = if (key == null) 1024 * 1024L else 900_000_000L
            if (key != null) request.header("Key", "1-$key").header("Charset", "UTF-8")
            request.post(object : RequestBody() {
                override fun contentType() = (if (key == null) "application/octet-stream" else "multipart/form-data;boundary=$boundary").toMediaType()
                override fun contentLength() = length
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    val chunk = ByteArray(budget.blockSize).also { HttpClients.random.nextBytes(it) }
                    fun write(data: ByteArray, offset: Int, max: Int): Int {
                        val size = budget.acquire(max, workerJob)
                        if (size == 0) throw TrafficEnd()
                        var sent = 0
                        try {
                            sink.write(data, offset, size)
                            sink.flush()
                            sent = size
                        } finally { budget.finish(size, sent, true) }
                        return size
                    }
                    fun writeAll(data: ByteArray) {
                        var at = 0
                        while (at < data.size) at += write(data, at, data.size - at)
                    }
                    writeAll(prefix)
                    var remaining = length - prefix.size - suffix.size
                    while (remaining > 0) remaining -= write(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                    writeAll(suffix)
                }
            })
        }
        val call = (if (upload) uploadClient else downloadClient).newCall(request.build())
        val requestFinished = CompletableDeferred<Unit>()
        val guard = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.awaitTrafficResponse(requestFinished).use { response ->
                if (!response.isSuccessful) throw TrafficHttpFailure(response.code, upload)
                if (!upload) {
                    val buffer = ByteArray(budget.blockSize)
                    val input = response.body.byteStream()
                    var received = 0L
                    while (isActive) {
                        val size = budget.acquire(buffer.size, workerJob)
                        if (size == 0) throw TrafficEnd()
                        var count = 0
                        var eof = false
                        try {
                            val n = input.read(buffer, 0, size)
                            eof = n < 0
                            count = n.coerceAtLeast(0)
                            received += count
                        } finally { budget.finish(size, count, false) }
                        if (eof) break
                    }
                    if (received == 0L) throw IOException("下载响应为空")
                }
            }
            upload
        } finally {
            guard.cancel(); call.cancel()
            // Wait for the asynchronous upload writer before taking the final counters or allowing a new session.
            withContext(NonCancellable) { requestFinished.await() }
        }
    }
}

private suspend fun Call.awaitTrafficResponse(finished: CompletableDeferred<Unit>): Response = suspendCancellableCoroutine { continuation ->
    val delivered = java.util.concurrent.atomic.AtomicReference<Response?>()
    continuation.invokeOnCancellation { cancel(); delivered.get()?.close() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            try { if (continuation.isActive) continuation.resumeWithException(e) }
            finally { finished.complete(Unit) }
        }
        override fun onResponse(call: Call, response: Response) {
            delivered.set(response)
            try { if (continuation.isActive) continuation.resume(response) else response.close() }
            finally { finished.complete(Unit) }
        }
    })
}
