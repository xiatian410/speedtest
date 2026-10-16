package dev.gaimoy.betterspeedtest.core

import androidx.compose.runtime.Immutable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

const val DL_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.60 Safari/537.36"
const val UL_UA = "Dalvik/1.6.0 (Linux; U; Android 4.2.2; GT-I9505 Build/JDQ39)"
private const val UL_BOUNDARY = "00content0boundary00"
private val OCTET_STREAM = "application/octet-stream".toMediaType()

class TransferResult(val avgBps: Double, val peakBps: Double, val appBytes: Long, val conns: Int)

@Immutable
data class Sample(val t: Double, val bps: Double)

fun interface WorkerFactory {
    fun create(): suspend (CoroutineScope, AtomicLong) -> Unit
}

object Engine {
    /** One scope per actual worker; cancelling or rolling back a worker also closes its I/O. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun runDirectionPool(
        upload: Boolean,
        factories: List<WorkerFactory>,
        minThreads: Int,
        maxThreads: Int,
        durationSec: Double,
        warmupSec: Double,
        everyMs: Long,
        onAverage: (Double) -> Unit = {},
        onTick: (tSec: Double, mbps: Double, peakMbps: Double) -> Unit,
    ): TransferResult {
        require(durationSec > 0 && durationSec.isFinite() && everyMs > 0)
        if (factories.isEmpty()) return TransferResult(0.0, 0.0, 0, 0)
        val min = minThreads.coerceIn(1, 128)
        val max = maxThreads.coerceIn(min, 128)
        val bytes = AtomicLong()
        val root = SupervisorJob(currentCoroutineContext()[Job])
        val workers = CoroutineScope(root + Dispatchers.IO)
        val pool = ConnPool(workers, factories, bytes)
        val warmup = warmupSec.coerceIn(0.0, (durationSec - 0.2).coerceAtLeast(0.0))
        val meter = TransferMeter(warmup)
        val start = System.nanoTime()
        val durationNanos = (durationSec * 1e9).toLong()
        val intervalNanos = everyMs * 1_000_000L
        var nextTick = intervalNanos.coerceAtMost(durationNanos)
        var elapsed = 0.0
        var sampledBytes = 0L
        var activeConnections = 0
        var bestBps = 0.0
        var bestCount = min
        var ramping = min < max
        var rampAt = 0.0
        var rampBytes = 0L
        try {
            pool.add(min)
            while (true) {
                val waitNanos = start + nextTick - System.nanoTime()
                if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
                currentCoroutineContext().ensureActive()
                val now = System.nanoTime()
                elapsed = (now - start) / 1e9
                sampledBytes = bytes.get()
                val bps = meter.record(elapsed, sampledBytes)
                onAverage(meter.averageBps / 1e6)
                onTick(elapsed, bps / 1e6, meter.peakBps / 1e6)
                activeConnections = pool.count()
                if (elapsed >= durationSec || activeConnections == 0) break
                // Give new connections one second to establish and settle before evaluating gain.
                if (ramping && elapsed - rampAt >= 1.0) {
                    val raw = (sampledBytes - rampBytes) * 8.0 / (elapsed - rampAt)
                    if (raw > 0) {
                        val ratio = if (bestBps > 0) raw / bestBps else 2.0
                        if (ratio <= 1.05) {
                            pool.removeLast((activeConnections - bestCount).coerceAtLeast(0))
                            ramping = false
                        } else {
                            bestBps = raw
                            bestCount = activeConnections
                            val step = (if (ratio > 1.30) activeConnections else maxOf(1, activeConnections / 4))
                                .coerceAtMost(max - activeConnections)
                            if (step > 0) pool.add(step) else ramping = false
                        }
                    }
                    rampAt = elapsed
                    rampBytes = sampledBytes
                }
                // Absolute deadlines avoid cumulative drift; skip missed ticks instead of busy catch-up.
                val elapsedNanos = now - start
                nextTick = ((elapsedNanos / intervalNanos + 1) * intervalNanos).coerceAtMost(durationNanos)
            }
        } finally {
            // Always await cancellation, including when the caller itself was cancelled.
            withContext(NonCancellable) { root.cancelAndJoin() }
        }
        val (average, peak) = meter.finish(elapsed, sampledBytes)
        onAverage(average / 1e6)
        // Include final in-flight writes in traffic/accounting, even though timed speed samples have ended.
        return TransferResult(average, peak, bytes.get(), activeConnections)
    }

    /** TCP connection latency, not ICMP. DNS work is done before timing each connection. */
    suspend fun tcpPing(host: String, port: Int, count: Int = 4, timeoutMs: Int = 3000): Triple<Double, Double, Boolean> =
        withContext(Dispatchers.IO) {
            val scope = CoroutineScope(currentCoroutineContext())
            val target = try { InetSocketAddress(BoundedDnsResolver.shared.resolve(host), port) }
            catch (e: CancellationException) { throw e }
            catch (_: IOException) { return@withContext Triple(0.0, 0.0, false) }
            val values = mutableListOf<Double>()
            repeat(count) {
                currentCoroutineContext().ensureActive()
                try {
                    withSocketGuard(scope, Socket()) { socket ->
                        val start = System.nanoTime()
                        socket.connect(target, timeoutMs)
                        values += (System.nanoTime() - start) / 1e6
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { /* Failed samples are not successful latency measurements. */ }
                delay(30)
            }
            if (values.isEmpty()) Triple(0.0, 0.0, false) else Triple(values.average(), jitter(values), true)
        }

    fun jitter(xs: List<Double>): Double = if (xs.size < 2) 0.0 else
        xs.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()

    /** Legacy sample utility; rates cover the interval ending at each sample timestamp. */
    fun summarize(series: List<Sample>, warmupSec: Double): Pair<Double, Double> {
        if (series.isEmpty()) return 0.0 to 0.0
        val cutoff = if (series.last().t > warmupSec) warmupSec.coerceAtLeast(0.0) else 0.0
        var previous = 0.0
        var weighted = 0.0
        var seconds = 0.0
        var peak = 0.0
        for (sample in series) {
            val interval = (sample.t - maxOf(previous, cutoff)).coerceAtLeast(0.0)
            weighted += interval * sample.bps
            seconds += interval
            if (interval > 0) peak = maxOf(peak, sample.bps)
            previous = sample.t
        }
        return (if (seconds > 0) weighted / seconds else 0.0) to peak
    }

    fun cnspeedDownload(ip: String, port: String, dlPath: String, key: String): WorkerFactory = WorkerFactory {
        { scope, bytes ->
            var failures = 0
            while (scope.isActive && failures < 3) {
                var rejected = false
                try {
                    withSocketGuard(scope, Socket()) { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(BoundedDnsResolver.shared.resolve(ip), port.toIntOrNull() ?: 80), 10_000)
                        socket.soTimeout = 10_000
                        val request = "GET $dlPath?r=${System.currentTimeMillis() / 1000}&key=$key HTTP/1.1\r\n" +
                            "Accept: */*\r\nConnection: close\r\nUser-Agent: $DL_UA\r\nHost: ${httpAuthority(ip, port)}\r\n\r\n"
                        socket.getOutputStream().apply { write(request.toByteArray()); flush() }
                        val input = socket.getInputStream().buffered()
                        val head = readHttpResponseHead(input)
                        if (head.status !in 200..299) { rejected = true; return@withSocketGuard }
                        readHttpBody(input, head, { scope.isActive }) { bytes.addAndGet(it.toLong()) }
                    }
                    if (rejected) break
                    failures = 0
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { failures++; if (scope.isActive) delay(200) }
            }
        }
    }

    fun cnspeedUpload(ip: String, port: String, ulPath: String, key: String, gotOK: AtomicBoolean = AtomicBoolean()): WorkerFactory = WorkerFactory {
        { scope, bytes ->
            val chunk = ByteArray(64 * 1024).also { HttpClients.random.nextBytes(it) }
            var failures = 0
            while (scope.isActive && failures < 3) {
                val rejected = AtomicBoolean(false)
                try {
                    withSocketGuard(scope, Socket()) { socket ->
                        socket.tcpNoDelay = true
                        socket.sendBufferSize = 128 * 1024
                        socket.connect(InetSocketAddress(BoundedDnsResolver.shared.resolve(ip), port.toIntOrNull() ?: 80), 10_000)
                        socket.soTimeout = 30_000
                        val prefix = "--$UL_BOUNDARY\r\nContent-Disposition: form-data; name=\"upload\";" +
                            "filename=\"SPEED_${System.currentTimeMillis() / 1000}\"\r\n\r\n"
                        val suffix = "\r\n--$UL_BOUNDARY--\r\n"
                        val total = 900_000_000L // Preserve the server's private protocol contract.
                        val head = "POST $ulPath HTTP/1.1\r\nConnection: close\r\nCache-Control: no-cache\r\n" +
                            "Charset: UTF-8\r\nKey: 1-$key\r\nContent-Type: multipart/form-data;boundary=$UL_BOUNDARY\r\n" +
                            "User-Agent: $UL_UA\r\nHost: ${httpAuthority(ip, port)}\r\nContent-Length: $total\r\n\r\n"
                        val output = socket.getOutputStream()
                        output.write((head + prefix).toByteArray())
                        output.flush()
                        // Read early server rejection concurrently instead of repeatedly filling a rejected socket.
                        val responseJob = scope.launch(Dispatchers.IO) {
                            try {
                                val response = readHttpResponseHead(socket.getInputStream().buffered())
                                if (response.status in 200..299) gotOK.set(true) else rejected.set(true)
                            } catch (e: CancellationException) { throw e }
                            catch (_: IOException) { /* The direction may have closed the streaming request. */ }
                            finally { runCatching { socket.close() } }
                        }
                        try {
                            var remaining = total - prefix.toByteArray().size - suffix.toByteArray().size
                            while (scope.isActive && remaining > 0 && !rejected.get()) {
                                val size = minOf(chunk.size.toLong(), remaining).toInt()
                                output.write(chunk, 0, size)
                                output.flush()
                                bytes.addAndGet(size.toLong())
                                remaining -= size
                            }
                            if (scope.isActive && !rejected.get() && remaining == 0L) {
                                output.write(suffix.toByteArray()); output.flush()
                                responseJob.join()
                            }
                        } finally {
                            runCatching { socket.close() }
                            responseJob.cancel()
                        }
                    }
                    failures = 0
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { failures++; if (scope.isActive && !rejected.get()) delay(200) }
                if (rejected.get()) break
            }
        }
    }

    fun cdnDownload(client: OkHttpClient, url: String, ua: String): WorkerFactory = WorkerFactory {
        worker@{ scope, bytes ->
            val buffer = ByteArray(64 * 1024)
            var failures = 0
            while (scope.isActive && failures < 3) {
                val call = client.newCall(Request.Builder().url(url).header("User-Agent", ua.ifBlank { DL_UA }).build())
                val guard = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitResponse().use { response ->
                        if (!response.isSuccessful) return@worker
                        val input = response.body.byteStream()
                        while (scope.isActive) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n > 0) bytes.addAndGet(n.toLong())
                        }
                    }
                    failures = 0
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { failures++; if (scope.isActive) delay(200) }
                finally { guard.cancel(); call.cancel() }
            }
        }
    }

    fun cdnUpload(client: OkHttpClient, url: String, ua: String, gotOK: AtomicBoolean): WorkerFactory = WorkerFactory {
        worker@{ scope, bytes ->
            val chunk = ByteArray(64 * 1024).also { HttpClients.random.nextBytes(it) }
            var failures = 0
            while (scope.isActive && failures < 3) {
                val body = object : RequestBody() {
                    override fun contentType() = OCTET_STREAM
                    override fun writeTo(sink: BufferedSink) {
                        // Finite streaming requests obtain a response instead of relying on buffered writes as proof of acceptance.
                        repeat(64) {
                            if (!scope.isActive) throw IOException("上传已取消")
                            sink.write(chunk)
                            sink.flush()
                            bytes.addAndGet(chunk.size.toLong())
                        }
                    }
                }
                val call = client.newCall(Request.Builder().url(url).header("User-Agent", ua.ifBlank { UL_UA }).post(body).build())
                val guard = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitResponse().use { response ->
                        if (!response.isSuccessful) return@worker
                        gotOK.set(true)
                    }
                    failures = 0
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { failures++; if (scope.isActive) delay(200) }
                finally { guard.cancel(); call.cancel() }
            }
        }
    }
}

internal class ConnPool(private val scope: CoroutineScope, private val factories: List<WorkerFactory>, private val bytes: AtomicLong) {
    private val jobs = mutableListOf<Job>()
    private var next = 0

    @Synchronized fun add(count: Int) {
        jobs.removeAll { it.isCompleted }
        repeat(count) {
            val worker = factories[next++ % factories.size].create()
            // Pass the launched coroutine itself: its guard children and loop use the exact Job we cancel.
            jobs += scope.launch {
                try { worker(this, bytes) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* This worker is unavailable; other sources remain supervised. */ }
            }
        }
    }

    @Synchronized fun removeLast(count: Int) {
        jobs.filter { it.isActive }.takeLast(count).forEach { it.cancel() }
    }

    @Synchronized fun count(): Int = jobs.count { it.isActive }
}

private suspend fun withSocketGuard(scope: CoroutineScope, socket: Socket, block: suspend (Socket) -> Unit) {
    val guard = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { runCatching { socket.close() } }
    }
    try { block(socket) } finally { guard.cancel(); runCatching { socket.close() } }
}

private fun httpAuthority(host: String, port: String): String =
    "${if (':' in host && !host.startsWith('[')) "[$host]" else host}:${port.toIntOrNull() ?: 80}"
