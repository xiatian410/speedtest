package dev.gaimoy.betterspeedtest.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NetworkCacheTest {
    @Test fun simultaneousReadersShareOneLoad() = runBlocking {
        val calls = AtomicInteger()
        val cache = TimedCache<Int>(10_000_000_000L)
        val values = List(10) { async { cache.get { delay(20); calls.incrementAndGet() } } }.awaitAll()
        assertEquals(List(10) { 1 }, values)
        assertEquals(1, calls.get())
    }

    @Test fun invalidationDuringLoadDoesNotCacheStaleNetworkData() = runBlocking {
        val cache = TimedCache<Int>(10_000_000_000L)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val pending = async { cache.get { started.complete(Unit); finish.await(); 1 } }
        started.await()
        cache.invalidate()
        finish.complete(Unit)
        assertEquals(1, pending.await())
        assertEquals(2, cache.get { 2 })
    }

    @Test fun cancelledLoadIsNotCachedAndReleasesTheMutex() = runBlocking {
        val cache = TimedCache<Int>(10_000_000_000L)
        val started = CompletableDeferred<Unit>()
        val job = launch { cache.get { started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancelAndJoin()
        assertEquals(2, withTimeout(1000) { cache.get { 2 } })
    }
}
