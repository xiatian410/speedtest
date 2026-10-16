package dev.gaimoy.betterspeedtest.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TrafficRunner internal constructor(
    private val targets: TrafficTargetProvider = DefaultTrafficTargets,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(TrafficState())
    val state = mutableState.asStateFlow()
    private val sequence = AtomicLong()
    private var job: Job? = null

    /** Reserve synchronously before requesting a foreground service, preventing duplicate starts. */
    @Synchronized fun reserve(options: TrafficOptions): Long? {
        require(options.validate() == null) { options.validate().orEmpty() }
        if (state.value.busy || job?.isCompleted == false) return null
        val id = sequence.incrementAndGet()
        job = null
        mutableState.value = TrafficState(status = TrafficStatus.RUNNING, session = id, options = options, message = "准备打流")
        return id
    }

    @Synchronized fun launchReserved(id: Long) {
        val initial = state.value
        if (initial.session != id || !initial.busy || job != null) return
        val options = initial.options ?: return
        job = scope.launch { run(options) }
    }

    @Synchronized fun stop(id: Long = state.value.session, message: String = "已停止") {
        if (state.value.session != id || !state.value.busy) return
        if (job == null) mutableState.update { it.copy(status = TrafficStatus.DONE, message = message) }
        else {
            mutableState.update { it.copy(status = TrafficStatus.STOPPING, message = message) }
            job?.cancel()
        }
    }

    @Synchronized fun failStart(id: Long, message: String) {
        if (state.value.session == id && job == null) mutableState.update { it.copy(status = TrafficStatus.FAILED, message = message) }
    }

    fun close() { stop(); scope.cancel() }

    private suspend fun run(options: TrafficOptions) = coroutineScope {
        val budget = TrafficBudget(options.limitMiB * 1024L * 1024, options.rateMbps)
        val transport = TrafficTransport()
        val start = System.nanoTime()
        var previousTime = start
        var previousDl = 0L
        var previousUl = 0L
        fun snapshot(final: Boolean = false) {
            val now = System.nanoTime()
            val (dl, ul) = budget.totals()
            val interval = ((now - previousTime) / 1e9).coerceAtLeast(0.001)
            mutableState.update { it.copy(elapsedSec = (now - start) / 1e9,
                downloadBytes = dl, uploadBytes = ul,
                downloadMbps = if (final) 0.0 else (dl - previousDl) * 8 / interval / 1e6,
                uploadMbps = if (final) 0.0 else (ul - previousUl) * 8 / interval / 1e6) }
            previousTime = now; previousDl = dl; previousUl = ul
        }
        val sampler = launch { while (isActive) { delay(250); snapshot() } }
        var status = TrafficStatus.DONE
        var message = "已达到持续时间"
        try {
            withTimeoutOrNull(options.durationSec * 1000L) {
                coroutineScope {
                    val transfers = launch {
                        val rejected = mutableSetOf<String>()
                        do {
                            mutableState.update { it.copy(message = "正在连接目标", cycle = it.cycle + 1) }
                            val target = try { targets.resolve(options, rejected) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { if (rejected.isNotEmpty()) throw TrafficNodesRejected() else throw e }
                            if (target.identity in rejected) throw TrafficNodesRejected()
                            mutableState.update { it.copy(target = target.name, message = "打流中") }
                            try {
                                if (options.mode == TrafficMode.NODES) {
                                    withTimeoutOrNull(options.cycleSec * 1000L) { runWorkers(options, target, budget, transport) }
                                } else runWorkers(options, target, budget, transport)
                            } catch (e: TrafficHttpFailure) {
                                // Only automatic node selection may change servers. Custom URLs and manually
                                // selected nodes must never silently send traffic to another destination.
                                if (e.status != 403 || e.upload || options.mode != TrafficMode.NODES || options.node != null) throw e
                                rejected += target.identity
                                if (rejected.size >= 8) throw TrafficNodesRejected()
                                mutableState.update { it.copy(message = "节点拒绝下载（403），正在切换其他节点") }
                                delay(250)
                            }
                        } while (isActive && !budget.exhausted() && options.mode == TrafficMode.NODES)
                    }
                    while (transfers.isActive && !budget.exhausted()) delay(25)
                    if (budget.exhausted()) transfers.cancelAndJoin() else transfers.join()
                }
            }
            if (budget.exhausted()) message = "已达到总流量上限"
        } catch (e: CancellationException) {
            message = state.value.message.takeIf { state.value.status == TrafficStatus.STOPPING } ?: "已停止"
        } catch (e: Exception) {
            status = TrafficStatus.FAILED
            // Do not expose URLs, query credentials, or provider response bodies in UI/notifications.
            message = when {
                e is TrafficNodesRejected -> e.message.orEmpty()
                e is TrafficHttpFailure && e.status == 403 && options.mode == TrafficMode.NODES ->
                    "${if (e.upload) "上传" else "下载"}被节点拒绝（HTTP 403），请更换节点或恢复自动选点"
                e is TrafficHttpFailure -> e.message.orEmpty()
                else -> "连接失败或传输中断，重试后仍不可用"
            }
        } finally {
            withContext(NonCancellable) { sampler.cancelAndJoin() }
            snapshot(final = true)
            mutableState.update { it.copy(status = status, message = message) }
        }
    }

    private suspend fun runWorkers(options: TrafficOptions, target: TrafficTarget, budget: TrafficBudget, transport: TrafficTransport) = coroutineScope {
        val directions = buildList { if (options.direction.download) add(false); if (options.direction.upload) add(true) }
        for (upload in directions) repeat(options.connections) {
            launch(Dispatchers.IO) {
                var failures = 0
                while (isActive && !budget.exhausted()) {
                    try {
                        if (transport.transfer(target, upload, budget)) mutableState.update { it.copy(uploadResponses = it.uploadResponses + 1) }
                        failures = 0
                        // Avoid a tight request loop for tiny objects or immediate responses.
                        delay(25)
                    } catch (e: CancellationException) { throw e }
                    catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        if (budget.exhausted()) return@launch
                        mutableState.update { it.copy(failures = it.failures + 1) }
                        if (e is TrafficHttpFailure && e.status in 400..499 && e.status != 408 && e.status != 429) throw e
                        failures++
                        if (failures >= 4) throw e
                        delay(1000L shl (failures - 1))
                    }
                }
            }
        }
    }
}
