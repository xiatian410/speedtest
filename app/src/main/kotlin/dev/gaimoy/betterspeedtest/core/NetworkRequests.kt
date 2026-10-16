package dev.gaimoy.betterspeedtest.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Response acquisition never holds a transfer worker hostage to synchronous DNS/connect work. */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    val delivered = AtomicReference<Response?>()
    continuation.invokeOnCancellation { cancel(); delivered.get()?.close() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            delivered.set(response)
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

/** Cancellation closes the call while connecting, reading headers, or consuming the response. */
internal suspend fun OkHttpClient.getText(url: String, ua: String, maxBytes: Int = 8 * 1024 * 1024): String =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(Request.Builder().url(url).header("User-Agent", ua).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        val body = it.body
                        if (body.contentLength() > maxBytes) throw IOException("响应过大")
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (output.size() + n > maxBytes) throw IOException("响应过大")
                                output.write(buffer, 0, n)
                            }
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

/** A short monotonic TTL and mutex coalesce simultaneous prefetch/list/run requests. */
internal class TimedCache<T>(private val ttlNanos: Long) {
    private val mutex = Mutex()
    @Volatile private var value: Pair<Long, T>? = null
    @Volatile private var generation = 0L

    suspend fun get(cacheIf: (T) -> Boolean = { true }, loader: suspend () -> T): T = mutex.withLock {
        value?.takeIf { System.nanoTime() - it.first < ttlNanos }?.let { return@withLock it.second }
        val currentGeneration = generation
        loader().also {
            synchronized(this) {
                if (currentGeneration == generation && cacheIf(it)) value = System.nanoTime() to it
            }
        }
    }

    @Synchronized fun invalidate() { generation++; value = null }
}
