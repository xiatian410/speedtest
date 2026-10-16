package dev.gaimoy.betterspeedtest.core

/** Small injectable boundary for offline orchestration regressions; production delegates to existing providers. */
internal interface RunnerBackend {
    suspend fun location(): IpLocation = IpLoc.resolve()
    suspend fun nodes(source: TestSource, notice: (String) -> Unit): List<Node> = NodeCatalog.pool(source, notice)
    suspend fun nearby(location: IpLocation): List<Node> = Cnspeed.fetchNearby(location.province, location.city, location.carrier)
    suspend fun key(node: Node): String = Cnspeed.dovalid(node.hostIp, node.port).removePrefix("1-")
    suspend fun ping(node: Node, quick: Boolean = false): Triple<Double, Double, Boolean> {
        val target = resolvePingTarget(node) ?: return Triple(0.0, 0.0, false)
        return Engine.tcpPing(target.first, target.second, if (quick) 2 else 4, if (quick) 1500 else 3000)
    }
    suspend fun transfer(
        upload: Boolean, factories: List<WorkerFactory>, minThreads: Int, maxThreads: Int,
        durationSec: Double, warmupSec: Double, everyMs: Long,
        onAverage: (Double) -> Unit = {}, onTick: (Double, Double, Double) -> Unit,
    ): TransferResult = Engine.runDirectionPool(upload, factories, minThreads, maxThreads,
        durationSec, warmupSec, everyMs, onAverage, onTick)
}

internal object DefaultRunnerBackend : RunnerBackend
