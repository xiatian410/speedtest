package dev.gaimoy.betterspeedtest.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkerCancellationTest {
    @Test fun rollbackCancelsWorkerScopeAndItsBlockingIoGuard() = runBlocking {
        val root = SupervisorJob()
        val workerScope = CoroutineScope(root + Dispatchers.IO)
        val started = CountDownLatch(1)
        val unblocked = CountDownLatch(1)
        val completed = CompletableDeferred<Unit>()
        val factory = WorkerFactory {
            { scope, _ ->
                val guard = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { unblocked.countDown() }
                }
                started.countDown()
                try { unblocked.await(3, TimeUnit.SECONDS) }
                finally { guard.cancel(); completed.complete(Unit) }
            }
        }
        val pool = ConnPool(workerScope, listOf(factory), AtomicLong())
        try {
            pool.add(1)
            assertTrue(withContext(Dispatchers.IO) { started.await(1, TimeUnit.SECONDS) })
            assertEquals(1, pool.count())
            pool.removeLast(1)
            withTimeout(1000) { completed.await() }
            assertEquals(0, pool.count())
        } finally { root.cancelAndJoin() }
    }

    @Test fun naturallyCompletedWorkersAreNotReportedAsActive() = runBlocking {
        val root = SupervisorJob()
        val scope = CoroutineScope(root + Dispatchers.IO)
        val pool = ConnPool(scope, listOf(WorkerFactory { { _, _ -> } }), AtomicLong())
        try {
            pool.add(2)
            withTimeout(1000) { while (pool.count() != 0) delay(10) }
            assertEquals(0, pool.count())
        } finally { root.cancelAndJoin() }
    }

    @Test fun cancellingDirectionWaitsForEveryWorkerCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val factory = WorkerFactory { { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cleaned.complete(Unit) }
        } }
        val job = launch {
            Engine.runDirectionPool(false, listOf(factory), 1, 1, 30.0, 2.0, 100) { _, _, _ -> }
        }
        withTimeout(1000) { started.await() }
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(cleaned.isCompleted)
    }
}
