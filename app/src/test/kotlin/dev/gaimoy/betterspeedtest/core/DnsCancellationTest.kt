package dev.gaimoy.betterspeedtest.core

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class DnsCancellationTest {
    @Test fun directionCancellationDoesNotWaitForBlockedOperatingSystemDns() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val resolver = BoundedDnsResolver(timeoutMs = 30_000, threads = 1, queueCapacity = 1) {
            entered.complete(Unit)
            release.await()
            listOf(InetAddress.getLoopbackAddress())
        }
        val worker = WorkerFactory { { _, _ -> resolver.resolve("blocked.invalid") } }
        val job = launch { Engine.runDirectionPool(false, listOf(worker), 1, 1, 30.0, 0.0, 100) { _, _, _ -> } }
        try {
            withTimeout(1000) { entered.await() }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCompleted)
            assertEquals(1, release.count.toInt()) // Resolver remains blocked while cancellation has completed.
        } finally { release.countDown(); job.cancelAndJoin(); resolver.close() }
    }

    @Test fun blockedResolversHaveBoundedThreadsAndQueueAndCancelledWaitsFreeQueueSlots() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val resolver = BoundedDnsResolver(timeoutMs = 30_000, threads = 1, queueCapacity = 1) {
            calls.incrementAndGet(); entered.complete(Unit); release.await()
            listOf(InetAddress.getLoopbackAddress())
        }
        val first = launch { resolver.resolve("first.invalid") }
        try {
            withTimeout(1000) { entered.await() }
            val queued = launch(start = CoroutineStart.UNDISPATCHED) { resolver.resolve("queued.invalid") }
            var saturated = false
            try { resolver.resolve("excess.invalid") } catch (_: IOException) { saturated = true }
            assertTrue(saturated)
            assertEquals(1, calls.get())
            queued.cancelAndJoin()
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) { resolver.resolve("replacement.invalid") }
            assertTrue(replacement.isActive)
            replacement.cancelAndJoin()
        } finally { first.cancelAndJoin(); release.countDown(); resolver.close() }
    }

    @Test fun cancellingHttpResponseWaitDoesNotWaitForItsDnsHook() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val resolver = BoundedDnsResolver(timeoutMs = 30_000, threads = 1, queueCapacity = 1) {
            entered.complete(Unit); release.await(); listOf(InetAddress.getLoopbackAddress())
        }
        val client = OkHttpClient.Builder().dns(resolver).build()
        val job = launch { client.newCall(Request.Builder().url("http://blocked.invalid:9/").build()).awaitResponse().close() }
        try {
            withTimeout(1000) { entered.await() }
            withTimeout(1000) { job.cancelAndJoin() }
            assertEquals(1, release.count.toInt())
        } finally {
            job.cancelAndJoin(); release.countDown(); resolver.close()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
