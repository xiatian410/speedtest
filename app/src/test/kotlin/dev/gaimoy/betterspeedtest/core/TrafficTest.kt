package dev.gaimoy.betterspeedtest.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class TrafficTest {
    @Test fun sharedBudgetCannotBeExceededByConcurrentDirections() = runBlocking {
        val budget = TrafficBudget(1234567, 0.0)
        coroutineScope {
            repeat(16) { worker -> launch(Dispatchers.IO) {
                while (true) {
                    val size = budget.acquire(16384, currentCoroutineContext().job)
                    if (size == 0) break
                    budget.finish(size, size, worker % 2 == 0)
                }
            } }
        }
        val (dl, ul) = budget.totals()
        assertEquals(1234567, dl + ul)
        assertTrue(budget.exhausted())
    }

    @Test fun cancelledRateWaitReturnsItsReservation() = runBlocking {
        val budget = TrafficBudget(16384, 0.1)
        val waiting = launch(Dispatchers.IO) {
            try { budget.acquire(16384, currentCoroutineContext().job) }
            catch (e: java.io.IOException) { currentCoroutineContext().ensureActive(); throw e }
        }
        delay(50)
        withTimeout(1000) { waiting.cancelAndJoin() }
        // A leaked reservation would leave this call waiting forever.
        val count = withContext(Dispatchers.IO) { budget.acquire(1, currentCoroutineContext().job) }
        budget.finish(count, 0, false)
        assertEquals(0, budget.totals().first)
    }

    @Test fun rateCapIsSharedRatherThanMultipliedByWorkers() = runBlocking {
        val budget = TrafficBudget(100000, 1.0)
        val start = System.nanoTime()
        coroutineScope {
            repeat(4) { launch(Dispatchers.IO) {
                while (true) {
                    val n = budget.acquire(5000, currentCoroutineContext().job)
                    if (n == 0) break
                    budget.finish(n, n, true)
                }
            } }
        }
        assertTrue((System.nanoTime() - start) / 1e9 >= 0.78)
        assertEquals(100000, budget.totals().second)
    }

    @Test fun validatesBoundsAndOnlyRequiresUrlsForEnabledDirections() {
        val options = TrafficOptions(downloadUrl = "https://example.test/data")
        assertNull(options.validate())
        assertNotNull(options.copy(direction = TrafficDirection.BOTH).validate())
        assertNotNull(options.copy(downloadUrl = "file:///data/test").validate())
        assertNotNull(options.copy(downloadUrl = "https://name:pass@example.test").validate())
        assertNotNull(options.copy(durationSec = 0).validate())
        assertNotNull(options.copy(limitMiB = 0).validate())
        assertNotNull(options.copy(rateMbps = Double.NaN).validate())
        assertNotNull(options.copy(connections = 17).validate())
        assertNull(options.copy(mode = TrafficMode.NODES, downloadUrl = "").validate())
    }

    @Test fun duplexUsesBothDirectionsAndStopsAtSharedBudget() = runBlocking {
        Fixture().use { server ->
            val runner = TrafficRunner()
            try {
                val options = server.options().copy(direction = TrafficDirection.BOTH, limitMiB = 1, rateMbps = 8.0)
                runner.launchReserved(runner.reserve(options)!!)
                val done = withTimeout(5000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.DONE, done.status)
                assertEquals(1048576L, done.totalBytes)
                assertTrue(done.downloadBytes > 0)
                assertTrue(done.uploadBytes > 0)
                assertEquals("已达到总流量上限", done.message)
                val bytes = done.totalBytes
                delay(200)
                assertEquals(bytes, runner.state.value.totalBytes)
            } finally { runner.close() }
        }
    }

    @Test fun successfulUploadIsOnlyConfirmedAfterServerResponse() = runBlocking {
        Fixture().use { server ->
            val runner = TrafficRunner()
            try {
                runner.launchReserved(runner.reserve(server.options().copy(direction = TrafficDirection.UPLOAD, connections = 1, limitMiB = 3, rateMbps = 16.0))!!)
                val done = withTimeout(5000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.DONE, done.status)
                assertTrue(done.uploadResponses >= 1)
                assertTrue(server.uploads.get() >= done.uploadResponses)
                assertEquals(0L, done.downloadBytes)
            } finally { runner.close() }
        }
    }

    @Test fun rejectionIsVisibleAndDoesNotRetryPermanentHttpError() = runBlocking {
        Fixture(status = 403).use { server ->
            val runner = TrafficRunner()
            try {
                runner.launchReserved(runner.reserve(server.options().copy(connections = 1))!!)
                val done = withTimeout(3000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.FAILED, done.status)
                assertTrue(done.message.contains("403"))
                assertEquals(1, server.requests.get())
                assertEquals(1, done.failures)
                assertEquals(0L, done.totalBytes)
            } finally { runner.close() }
        }
    }

    @Test fun transientServerFailureRetriesAndRecovers() = runBlocking {
        Fixture(failFirst = true).use { server ->
            val runner = TrafficRunner()
            try {
                runner.launchReserved(runner.reserve(server.options().copy(connections = 1, limitMiB = 1))!!)
                val done = withTimeout(5000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.DONE, done.status)
                assertEquals(1, done.failures)
                assertEquals(1048576L, done.downloadBytes)
            } finally { runner.close() }
        }
    }

    @Test fun manualStopCancelsBlockedHeaderReadAndPreventsDuplicateStarts() = runBlocking {
        Fixture(stall = true).use { server ->
            val runner = TrafficRunner()
            try {
                val options = server.options().copy(connections = 1)
                val id = runner.reserve(options)!!
                assertNull(runner.reserve(options))
                runner.launchReserved(id)
                withTimeout(2000) { while (server.requests.get() == 0) delay(10) }
                runner.stop(id)
                val done = withTimeout(1500) { runner.state.first { !it.busy } }
                assertEquals("已停止", done.message)
                assertEquals(0L, done.totalBytes)
            } finally { runner.close() }
        }
    }

    @Test fun nodeModeStartsNewCycleAndTimeLimitClosesWorkers() = runBlocking {
        Fixture().use { server ->
            val resolutions = AtomicInteger()
            val runner = TrafficRunner(TrafficTargetProvider {
                resolutions.incrementAndGet()
                TrafficTarget("本地测试节点", server.url, server.url)
            })
            try {
                runner.launchReserved(runner.reserve(server.options().copy(mode = TrafficMode.NODES, cycleSec = 5,
                    durationSec = 6, rateMbps = 1.0, limitMiB = 32))!!)
                val done = withTimeout(8500) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.DONE, done.status)
                assertEquals(2, resolutions.get())
                assertEquals(2, done.cycle)
                assertEquals("已达到持续时间", done.message)
                assertTrue(done.totalBytes > 0)
                assertTrue(done.totalBytes <= 800000)
            } finally { runner.close() }
        }
    }

    @Test fun stoppingBeforeServiceStartsDoesNotLaunchTraffic() {
        val runner = TrafficRunner()
        try {
            val id = runner.reserve(TrafficOptions(mode = TrafficMode.NODES))!!
            runner.stop(id)
            runner.launchReserved(id)
            assertFalse(runner.state.value.busy)
            assertEquals(0L, runner.state.value.totalBytes)
        } finally { runner.close() }
    }

    @Test fun nativeCnspeedNodeUsesValidationKeyAndMultipartContract() = runBlocking {
        Fixture().use { server ->
            val runner = TrafficRunner()
            try {
                val node = Node("本地全球网测协议", "cnspeed", hostIp = "127.0.0.1", port = server.port.toString())
                val options = server.options().copy(mode = TrafficMode.NODES, node = node,
                    direction = TrafficDirection.BOTH, rateMbps = 8.0, connections = 1, limitMiB = 1)
                runner.launchReserved(runner.reserve(options)!!)
                val done = withTimeout(6000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.DONE, done.status)
                assertTrue(done.downloadBytes > 0 && done.uploadBytes > 0)
                assertEquals("1-12345678-1234-1234-1234-123456789abc", server.uploadKey.get())
                assertTrue(server.downloadQuery.get().contains("key=12345678-1234-1234-1234-123456789abc"))
                assertEquals("900000000", server.uploadLength.get())
            } finally { runner.close() }
        }
    }

    @Test fun selectedOoklaNodeUsesItsTransferUrls() = runBlocking {
        Fixture().use { server ->
            val runner = TrafficRunner()
            try {
                val node = Node("本地 Ookla 协议", "ookla", dlUrl = server.url, ulUrl = server.url)
                runner.launchReserved(runner.reserve(server.options().copy(mode = TrafficMode.NODES, node = node, limitMiB = 1))!!)
                val done = withTimeout(4000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.DONE, done.status)
                assertEquals(node.name, done.target)
                assertEquals(1048576L, done.downloadBytes)
            } finally { runner.close() }
        }
    }

    @Test fun automaticNodesSkip403AndKeepSharedBudget() = runBlocking {
        Fixture(status = 403).use { rejected -> Fixture().use { good ->
            val first = TrafficTarget("拒绝节点", rejected.url, rejected.url)
            val second = TrafficTarget("可用节点", good.url, good.url)
            val runner = TrafficRunner(object : TrafficTargetProvider {
                override suspend fun resolve(options: TrafficOptions) = first
                override suspend fun resolve(options: TrafficOptions, excluded: Set<String>) =
                    if (first.identity in excluded) second else first
            })
            try {
                runner.launchReserved(runner.reserve(good.options().copy(mode = TrafficMode.NODES, connections = 1, limitMiB = 1))!!)
                val done = withTimeout(5000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.DONE, done.status)
                assertEquals("可用节点", done.target)
                assertEquals(1048576L, done.totalBytes)
                assertEquals(1, rejected.requests.get())
                assertEquals(1, done.failures)
            } finally { runner.close() }
        } }
    }

    @Test fun manuallySelected403NodeIsNotSilentlyReplaced() = runBlocking {
        Fixture(status = 403).use { server ->
            val resolves = AtomicInteger()
            val runner = TrafficRunner(TrafficTargetProvider {
                resolves.incrementAndGet()
                TrafficTarget("手选节点", server.url, server.url)
            })
            try {
                runner.launchReserved(runner.reserve(server.options().copy(mode = TrafficMode.NODES,
                    node = Node("手选节点", "cnspeed"), connections = 1))!!)
                val done = withTimeout(3000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.FAILED, done.status)
                assertTrue(done.message.contains("恢复自动选点"))
                assertEquals(1, resolves.get())
                assertEquals(1, server.requests.get())
            } finally { runner.close() }
        }
    }

    @Test fun rejectingNodeFallbackIsBounded() = runBlocking {
        Fixture(status = 403).use { server ->
            val resolves = AtomicInteger()
            val runner = TrafficRunner(TrafficTargetProvider {
                TrafficTarget("拒绝节点 ${resolves.incrementAndGet()}", server.url, server.url)
            })
            try {
                runner.launchReserved(runner.reserve(server.options().copy(mode = TrafficMode.NODES, connections = 1))!!)
                val done = withTimeout(6000) { runner.state.first { !it.busy } }
                assertEquals(TrafficStatus.FAILED, done.status)
                assertEquals(8, resolves.get())
                assertEquals(8, server.requests.get())
                assertTrue(done.message.contains("403"))
            } finally { runner.close() }
        }
    }

    private class Fixture(status: Int = 200, failFirst: Boolean = false, stall: Boolean = false) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val uploads = AtomicInteger()
        val uploadKey = java.util.concurrent.atomic.AtomicReference("")
        val uploadLength = java.util.concurrent.atomic.AtomicReference("")
        val downloadQuery = java.util.concurrent.atomic.AtomicReference("")
        val port get() = server.address.port
        val url get() = "http://127.0.0.1:${server.address.port}/data"
        init {
            server.executor = executor
            server.createContext("/speed/dovalid") { exchange ->
                val key = "1-12345678-1234-1234-1234-123456789abc".toByteArray()
                exchange.sendResponseHeaders(200, key.size.toLong())
                exchange.responseBody.use { it.write(key) }
                exchange.close()
            }
            server.createContext("/") { exchange ->
                try {
                    val request = requests.incrementAndGet()
                    if (stall) Thread.sleep(30000)
                    val code = if (failFirst && request == 1) 503 else status
                    if (code != 200) exchange.sendResponseHeaders(code, -1)
                    else if (exchange.requestMethod == "POST") {
                        uploadKey.set(exchange.requestHeaders.getFirst("Key").orEmpty())
                        uploadLength.set(exchange.requestHeaders.getFirst("Content-Length").orEmpty())
                        val bytes = exchange.requestBody.use { input ->
                            var total = 0L
                            val buffer = ByteArray(16384)
                            while (true) { val n = input.read(buffer); if (n < 0) break; total += n }
                            total
                        }
                        if (bytes == 1048576L) uploads.incrementAndGet()
                        exchange.sendResponseHeaders(200, -1)
                    } else {
                        downloadQuery.set(exchange.requestURI.rawQuery.orEmpty())
                        exchange.sendResponseHeaders(200, 8 * 1024 * 1024L)
                        exchange.responseBody.use { output -> repeat(512) { output.write(ByteArray(16384)) } }
                    }
                } catch (_: Exception) { } finally { exchange.close() }
            }
            server.start()
        }
        fun options() = TrafficOptions(downloadUrl = url, uploadUrl = url, durationSec = 15)
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
}
