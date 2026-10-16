package dev.gaimoy.betterspeedtest.core

import dev.gaimoy.betterspeedtest.data.HistoryStore
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RunnerFallbackTest {
    private val first = Node("first", "cnspeed", hostIp = "127.0.0.1", port = "80")
    private val second = first.copy(name = "second", hostIp = "127.0.0.2")
    private val ookla = Node("fallback", "ookla", dlUrl = "http://127.0.0.1/download", ulUrl = "http://127.0.0.1/upload")
    private val zero get() = TransferResult(0.0, 0.0, 0, 0)
    private val success get() = TransferResult(8000.0, 8000.0, 1000, 1)

    private inner class Backend(val replies: ArrayDeque<TransferResult>, val nearbyNodes: List<Node>, val ticks: List<Sample> = emptyList()) : RunnerBackend {
        var transfers = 0
        val sourceRequests = mutableListOf<TestSource>()
        override suspend fun location() = IpLocation(ip = "127.0.0.1", province = "广东", carrier = "电信")
        override suspend fun nearby(location: IpLocation) = nearbyNodes
        override suspend fun nodes(source: TestSource, notice: (String) -> Unit): List<Node> {
            sourceRequests += source
            return listOf(ookla)
        }
        override suspend fun key(node: Node) = "local-test-key"
        override suspend fun ping(node: Node, quick: Boolean) = Triple(1.0, 0.0, true)
        override suspend fun transfer(upload: Boolean, factories: List<WorkerFactory>, minThreads: Int,
            maxThreads: Int, durationSec: Double, warmupSec: Double, everyMs: Long,
            onAverage: (Double) -> Unit, onTick: (Double, Double, Double) -> Unit): TransferResult {
            transfers++
            ticks.forEach { onTick(it.t, it.bps / 1e6, it.bps / 1e6) }
            return replies.removeFirst()
        }
    }

    private fun run(options: TestOptions, backend: Backend): TestUiState = runBlocking {
        val directory = Files.createTempDirectory("speedtest-runner-test").toFile()
        val runner = SpeedtestRunner(HistoryStore(directory.resolve("history.json")), backend)
        try {
            runner.start(options)
            withTimeout(3000) { runner.state.first { it.status in listOf(RunStatus.DONE, RunStatus.FAILED) } }
        } finally { runner.close(); directory.deleteRecursively() }
    }

    @Test fun zeroPayloadDownloadTriesNextUnpinnedNode() {
        val backend = Backend(ArrayDeque(listOf(zero, success)), listOf(first, second))
        val state = run(TestOptions(source = TestSource.CNSPEED, doUpload = false), backend)
        assertEquals(RunStatus.DONE, state.status)
        assertEquals("second", state.result?.node)
        assertEquals(2, backend.transfers)
    }

    @Test fun autoFallsBackAfterEveryPrimaryNodeReturnsZeroPayload() {
        val backend = Backend(ArrayDeque(listOf(zero, success)), listOf(first))
        val state = run(TestOptions(doUpload = false), backend)
        assertEquals(RunStatus.DONE, state.status)
        assertEquals("ookla", state.result?.source)
        assertEquals(listOf(TestSource.OOKLA), backend.sourceRequests)
    }

    @Test fun aPartiallyTransferredFailureNeverStartsAnotherBulkTest() {
        val backend = Backend(ArrayDeque(listOf(TransferResult(8000.0, 8000.0, 1000, 0), success)), listOf(first, second))
        val state = run(TestOptions(doUpload = false), backend)
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals(1, backend.transfers)
        assertTrue(backend.sourceRequests.isEmpty())
    }

    @Test fun manuallySelectedZeroPayloadNodeDoesNotChangeServers() {
        val backend = Backend(ArrayDeque(listOf(zero, success)), listOf(first, second))
        val state = run(TestOptions(node = first, doUpload = false), backend)
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals(1, backend.transfers)
        assertTrue(backend.sourceRequests.isEmpty())
    }

    @Test fun uploadOnlyZeroPayloadCanTryNextNodeAndMarksUnconfirmedData() {
        val backend = Backend(ArrayDeque(listOf(zero, success)), listOf(first, second))
        val state = run(TestOptions(source = TestSource.CNSPEED, doDownload = false), backend)
        assertEquals(RunStatus.DONE, state.status)
        assertEquals("second", state.result?.node)
        assertEquals(UPLOAD_UNCONFIRMED, state.result?.ulMsg)
        assertTrue(state.result!!.ulAvg > 0)
    }

    @Test fun uploadOnlyAutoFallsBackAfterZeroPayload() {
        val backend = Backend(ArrayDeque(listOf(zero, success)), listOf(first))
        val state = run(TestOptions(doDownload = false), backend)
        assertEquals(RunStatus.DONE, state.status)
        assertEquals("ookla", state.result?.source)
        assertEquals(listOf(TestSource.OOKLA), backend.sourceRequests)
        assertEquals(UPLOAD_UNCONFIRMED, state.result?.ulMsg)
    }

    @Test fun zeroPayloadMultiSourceGroupCanTryRemainingNodes() {
        val third = first.copy(name = "third", hostIp = "127.0.0.3")
        val fourth = first.copy(name = "fourth", hostIp = "127.0.0.4")
        val backend = Backend(ArrayDeque(listOf(zero, success)), listOf(first, second, third, fourth))
        val state = run(TestOptions(source = TestSource.CNSPEED, multi = 2, doUpload = false), backend)
        assertEquals(RunStatus.DONE, state.status)
        assertEquals("third 等 2 源", state.result?.node)
        assertEquals(2, backend.transfers)
    }

    @Test fun failedUploadPreservesDownloadWithoutRestartingIt() {
        val backend = Backend(ArrayDeque(listOf(success, zero, success)), listOf(first, second))
        val state = run(TestOptions(), backend)
        assertEquals(RunStatus.DONE, state.status)
        val result = requireNotNull(state.result)
        assertTrue(result.dlAvg > 0)
        assertTrue(result.ulMsg.contains("失败"))
        assertEquals(2, backend.transfers)
        assertTrue(backend.sourceRequests.isEmpty())
    }

    @Test fun curveExcludesWarmupBoundaryButKeepsRealZeroRateSamples() {
        val backend = Backend(ArrayDeque(listOf(success)), listOf(first), listOf(
            Sample(1.8, 8000.0), Sample(2.01, 8000.0), Sample(2.21, 0.0), Sample(2.41, 8000.0),
        ))
        val state = run(TestOptions(source = TestSource.CNSPEED, doUpload = false), backend)
        assertEquals(RunStatus.DONE, state.status)
        assertEquals(2, state.dl.samples.size)
        assertEquals(0.2, state.dl.samples[0].t, 0.001)
        assertEquals(0.0, state.dl.samples[0].bps, 0.001)
        assertEquals(8000.0, state.dl.samples[1].bps, 0.001)
    }
}
