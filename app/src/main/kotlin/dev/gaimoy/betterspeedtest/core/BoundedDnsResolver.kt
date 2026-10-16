package dev.gaimoy.betterspeedtest.core

import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns

/** OS DNS cannot always be interrupted. Abandon waits, but never create unbounded resolver threads/tasks. */
internal class BoundedDnsResolver(
    private val timeoutMs: Long = 3000,
    threads: Int = 2,
    queueCapacity: Int = 32,
    private val lookupAddresses: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : Dns, AutoCloseable {
    private val threadNumber = AtomicInteger()
    private val executor = ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity), { task ->
            Thread(task, "speedtest-dns-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())

    /** Coroutine callers detach immediately on cancellation, even if an OS resolver remains blocked. */
    suspend fun resolve(host: String): InetAddress = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val task = FutureTask<Unit> {
                try {
                    val address = lookupAddresses(host).firstOrNull() ?: throw IOException("DNS 未返回地址")
                    if (continuation.isActive) continuation.resume(address)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
            continuation.invokeOnCancellation { task.cancel(false); executor.remove(task) }
            try { executor.execute(task) }
            catch (e: RejectedExecutionException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("DNS 查询队列已满", e))
            }
            // Cancellation can precede execute; remove a cancelled queued task in either ordering.
            if (!continuation.isActive) { task.cancel(false); executor.remove(task) }
        }
    } ?: throw SocketTimeoutException("DNS 查询超时")

    /** OkHttp's synchronous DNS hook is bounded too; its call coroutine uses cancellable awaitResponse. */
    override fun lookup(hostname: String): List<InetAddress> {
        val task = FutureTask { lookupAddresses(hostname) }
        try {
            executor.execute(task)
            return task.get(timeoutMs, TimeUnit.MILLISECONDS).ifEmpty { throw IOException("DNS 未返回地址") }
        } catch (e: TimeoutException) {
            throw SocketTimeoutException("DNS 查询超时")
        } catch (e: RejectedExecutionException) {
            throw IOException("DNS 查询队列已满", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("DNS 查询已取消", e)
        } catch (e: ExecutionException) {
            throw IOException("DNS 查询失败", e.cause)
        } finally {
            task.cancel(false)
            executor.remove(task)
        }
    }

    override fun close() { executor.shutdownNow() }

    companion object { val shared = BoundedDnsResolver() }
}
