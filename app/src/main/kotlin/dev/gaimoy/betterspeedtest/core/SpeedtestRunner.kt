package dev.gaimoy.betterspeedtest.core

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.gaimoy.betterspeedtest.data.HistoryStore
import dev.gaimoy.betterspeedtest.data.Settings
import java.util.concurrent.atomic.AtomicBoolean

enum class RunStatus { IDLE, RUNNING, DONE, FAILED, CANCELLED }

enum class Direction { DOWNLOAD, UPLOAD }

@Immutable
data class DirectionState(
    val progressSec: Double = 0.0,
    val mbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    val avgMbps: Double = 0.0,
    val samples: List<Sample> = emptyList(),
)

@Immutable
data class TestUiState(
    val status: RunStatus = RunStatus.IDLE,
    val direction: Direction = Direction.DOWNLOAD,
    val dl: DirectionState = DirectionState(),
    val ul: DirectionState = DirectionState(),
    val phaseText: String = "",
    val log: List<String> = emptyList(),
    val result: SpeedtestResult? = null,
    val error: String? = null,
    val ipInfo: IpLocation? = null,
    val livePingMs: Double = 0.0,
    val liveJitter: Double = 0.0,
    val activeOptions: TestOptions? = null,
)

/** 一次测速的全部参数(来自设置页)。 */
data class TestOptions(
    val source: TestSource = TestSource.AUTO,
    val node: Node? = null,
    val durationSec: Int = 15,
    val multi: Int = 1,
    val doDownload: Boolean = true,
    val doUpload: Boolean = true,
    val threadsDl: Int = 6,
    val threadsDlMax: Int = 16,
    val threadsUl: Int = 4,
    val threadsUlMax: Int = 8,
    val warmupSec: Int = 2,
    val network: String = "",
)

/**
 * 测速编排器:自动选源 → 节点校验/并发探测 → 下载 → 上传 → 结果,
 * 通过 StateFlow 驱动 UI;随时可取消。
 */
class SpeedtestRunner internal constructor(private val history: HistoryStore, private val backend: RunnerBackend) : AutoCloseable {
    constructor(history: HistoryStore) : this(history, DefaultRunnerBackend)

    private val _state = MutableStateFlow(TestUiState())
    val state: StateFlow<TestUiState> = _state.asStateFlow()

    private var runJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var generation = 0L
    private var closed = false
    private var hasPayload = false
    private var probeBytes = 0L
    private var activeLocation = IpLocation()

    @Synchronized fun start(opts: TestOptions) {
        if (closed || runJob?.isCompleted == false) return
        val options = opts.normalized()
        val runGeneration = ++generation
        hasPayload = false
        probeBytes = 0L
        _state.value = TestUiState(status = RunStatus.RUNNING, ipInfo = _state.value.ipInfo, activeOptions = options)
        runJob = scope.launch {
            try {
                runTest(options)
                _state.update {
                    if (generation != runGeneration) return@update it
                    val partial = it.result?.ulMsg?.isNotBlank() == true
                    it.copy(status = RunStatus.DONE, phaseText = if (partial) "完成（上传未完成）" else "完成")
                }
            } catch (e: CancellationException) {
                _state.update { if (generation == runGeneration) it.copy(status = RunStatus.CANCELLED, phaseText = "已停止") else it }
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (generation == runGeneration) it.copy(status = RunStatus.FAILED, phaseText = "失败", error = e.message ?: "测速失败") else it
                }
            }
        }
    }

    @Synchronized fun stop() {
        runJob?.cancel()
    }

    private var prefetchJob: Job? = null

    /** 进主页即预取 IP/位置,状态卡无需等开测;已有或进行中则跳过。 */
    @Synchronized fun ensureIpPrefetch() {
        if (closed || prefetchJob?.isActive == true || _state.value.ipInfo != null) return
        prefetchJob = scope.launch {
            val loc = try { backend.location() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (loc != null && (loc.ip.isNotEmpty() || loc.province.isNotEmpty())) {
                _state.update { it.copy(ipInfo = loc) }
            }
        }
    }

    @Synchronized fun reset() {
        generation++
        if (runJob?.isActive == true) runJob?.cancel()
        _state.value = TestUiState(ipInfo = _state.value.ipInfo)
    }

    @Synchronized fun invalidateIpInfo() {
        IpLoc.invalidateCache()
        Ookla.invalidateCache()
        prefetchJob?.cancel()
        prefetchJob = null
        _state.update { it.copy(ipInfo = null) }
        ensureIpPrefetch()
    }

    @Synchronized override fun close() {
        closed = true
        generation++
        scope.cancel()
    }

    private fun status(msg: String) = _state.update {
        if (it.status == RunStatus.RUNNING) it.copy(phaseText = msg, log = (it.log + msg).takeLast(200)) else it
    }

    private fun tick(
        dir: Direction,
        tSec: Double,
        mbps: Double,
        peakMbps: Double,
        samples: List<Sample>,
        avgMbps: Double,
    ) = _state.update { st ->
        if (st.status != RunStatus.RUNNING) return@update st
        val cur = st.dl.takeIf { dir == Direction.DOWNLOAD } ?: st.ul
        val next = cur.copy(
            progressSec = tSec,
            mbps = mbps,
            peakMbps = peakMbps,
            avgMbps = avgMbps,
            samples = samples,
        )
        if (dir == Direction.DOWNLOAD) st.copy(direction = dir, dl = next) else st.copy(direction = dir, ul = next)
    }

    private suspend fun runTest(opts: TestOptions) {
        if (!opts.doDownload && !opts.doUpload) throw IllegalStateException("下载和上传不能都关闭")
        status("准备测速…")

        val loc = backend.location()
        activeLocation = loc
        if (loc.ip.isNotEmpty() || loc.province.isNotEmpty()) {
            _state.update { it.copy(ipInfo = loc) }
            status("定位: ${loc.carrier}/${loc.province}/${loc.city} · IP ${loc.ip}")
        } else {
            status("定位失败,继续测速")
        }

        val mainland = IpLoc.isMainlandChina(loc)
        var src = when (opts.node?.source) {
            "cnspeed" -> TestSource.CNSPEED
            "ookla" -> TestSource.OOKLA
            else -> opts.source
        }
        if (src == TestSource.AUTO) {
            src = if (mainland) {
                TestSource.CNSPEED
            } else {
                status("${loc.carrier}/${loc.province} 非中国大陆 → Speedtest.net")
                TestSource.OOKLA
            }
        }

        suspend fun runSource(source: TestSource) {
            when (source) {
                TestSource.CNSPEED -> runCnspeed(opts, loc)
                else -> runCdn(opts, opts.node?.let { listOf(it) } ?: backend.nodes(source) { status(it) })
            }
        }
        try { runSource(src) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (opts.source != TestSource.AUTO || opts.node != null || hasPayload) throw e
            val fallback = if (src == TestSource.CNSPEED) TestSource.OOKLA else TestSource.CNSPEED
            status("${src.label} 未获得有效数据，尝试${fallback.label}: ${e.message}")
            runSource(fallback)
        }
    }

    // ---- cnspeed:就近节点 + dovalid + 原始 socket 传输 ----

    private suspend fun runCnspeed(opts: TestOptions, loc: IpLocation) {
        val cand: List<Node> = if (opts.node != null) {
            listOf(opts.node)
        } else {
            IpLoc.orderCnspeed(
                backend.nearby(loc),
                loc.carrier, loc.province, loc.city,
            )
        }
        if (cand.isEmpty()) throw IllegalStateException("无候选节点")
        status("全球网测 ${cand.size} 个候选节点")

        if (opts.multi > 1) {
            runCnspeedMulti(opts, cand, loc)
        } else {
            runCnspeedSingle(opts, cand, loc)
        }
    }

    private fun capMax(max: Int, min: Int) = if (max < min) min else max

    private suspend fun runCnspeedSingle(opts: TestOptions, cand: List<Node>, loc: IpLocation) {
        var lastErr = ""
        for ((i, n) in cand.withIndex()) {
            if (i >= 8) break
            val (ping, jit, ok) = pingNode(n)
            if (!ok) {
                status("${n.name} 不可达")
                continue
            }
            _state.update { it.copy(livePingMs = ping, liveJitter = jit) }
            val key = try {
                backend.key(n)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                status("${n.name} 校验失败: ${e.message}")
                continue
            }
            var dl = TransferResult(0.0, 0.0, 0L, 0)
            var ul = dl
            if (opts.doDownload) {
                status("${n.name} ping=" + "%.0f".format(ping) + "ms · 下载中")
                dl = runDirection(opts, Direction.DOWNLOAD) {
                    listOf(Engine.cnspeedDownload(n.hostIp, n.port, Cnspeed.PATH_DOWNLOAD, key))
                }
                if (dl.appBytes == 0L || dl.avgBps <= 0.0 || dl.conns == 0) {
                    lastErr = "${n.name} 下载失败(403/0字节)"
                    if (!hasPayload && opts.node == null) { status("$lastErr，尝试下一个节点"); continue }
                    throw IllegalStateException(lastErr)
                }
            }
            var ulMsg = ""
            if (opts.doUpload) {
                status("${n.name} 上传中")
                val gotOK = AtomicBoolean(false)
                ul = runDirection(opts, Direction.UPLOAD) {
                    listOf(Engine.cnspeedUpload(n.hostIp, n.port, Cnspeed.PATH_UPLOAD, key, gotOK))
                }
                if (ul.appBytes == 0L || ul.avgBps <= 0.0 || ul.conns == 0) {
                    lastErr = "${n.name} 上传失败(0字节)"
                    if (!hasPayload && opts.node == null) { status("$lastErr，尝试下一个节点"); continue }
                    if (!opts.doDownload) throw IllegalStateException(lastErr)
                    ulMsg = "上传失败，下载结果已保留"
                    status(ulMsg)
                    ul = TransferResult(0.0, 0.0, ul.appBytes, 0)
                } else if (!gotOK.get()) {
                    ulMsg = UPLOAD_UNCONFIRMED
                    status(ulMsg)
                }
            }
            finish(
                SpeedtestResult(
                    node = n.name, source = "cnspeed", carrier = loc.carrier, city = n.city,
                    mode = modeLabel(opts),
                    dlAvg = dl.avgBps / 1e6, dlPeak = dl.peakBps / 1e6,
                    ulAvg = ul.avgBps / 1e6, ulPeak = ul.peakBps / 1e6,
                    dlBytes = dl.appBytes, ulBytes = ul.appBytes,
                    pingMs = ping, jitter = jit, dlConns = dl.conns, ulConns = ul.conns,
                    ulMsg = ulMsg,
                ),
            )
            return
        }
        throw IllegalStateException(lastErr.ifEmpty { "所有候选节点测速失败" })
    }

    private class VNode(val n: Node, val key: String, val ping: Double, val jit: Double)

    /** Validate a bounded set of nearby nodes, then distribute workers across surviving sources. */
    private suspend fun runCnspeedMulti(opts: TestOptions, cand: List<Node>, loc: IpLocation) {
        val valid = mutableListOf<VNode>()
        var scanned = 0
        for ((i, n) in cand.withIndex()) {
            if (i >= 8 || valid.size >= opts.multi) break
            scanned = i + 1
            val (ping, jit, ok) = pingNode(n)
            if (!ok) {
                status("${n.name} 不可达")
                continue
            }
            _state.update { it.copy(livePingMs = ping, liveJitter = jit) }
            val key = try {
                backend.key(n)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                status("${n.name} 校验失败: ${e.message}")
                continue
            }
            valid += VNode(n, key, ping, jit)
        }
        if (valid.isEmpty()) throw IllegalStateException("所有候选节点不可用")
        try {
            runCnspeedGroup(opts, valid, loc)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val remaining = cand.take(8).drop(scanned)
            if (opts.node != null || hasPayload || remaining.isEmpty()) throw e
            status("当前节点组未传输有效数据，尝试下一组")
            runCnspeedMulti(opts, remaining, loc)
        }
    }

    private suspend fun runCnspeedGroup(opts: TestOptions, valid: List<VNode>, loc: IpLocation) {
        val name = if (valid.size > 1) "${valid[0].n.name} 等 ${valid.size} 源" else valid[0].n.name
        val ping = valid[0].ping
        val jit = valid[0].jit

        var dl = TransferResult(0.0, 0.0, 0L, 0)
        var ul = dl
        if (opts.doDownload) {
            status("$name ping=" + "%.0f".format(ping) + "ms · 下载中")
            dl = runDirection(opts, Direction.DOWNLOAD) {
                valid.map { Engine.cnspeedDownload(it.n.hostIp, it.n.port, Cnspeed.PATH_DOWNLOAD, it.key) }
            }
            if (dl.appBytes == 0L || dl.avgBps <= 0.0 || dl.conns == 0) throw IllegalStateException("$name 下载失败或连接全部中断")
        }
        var ulMsg = ""
        if (opts.doUpload) {
            status("$name 上传中")
            val gotOK = AtomicBoolean(false)
            ul = runDirection(opts, Direction.UPLOAD) {
                valid.map { Engine.cnspeedUpload(it.n.hostIp, it.n.port, Cnspeed.PATH_UPLOAD, it.key, gotOK) }
            }
            if (ul.appBytes == 0L || ul.avgBps <= 0.0 || ul.conns == 0) {
                if (!opts.doDownload) throw IllegalStateException("$name 上传失败")
                ulMsg = "上传失败，下载结果已保留"
                status(ulMsg)
                ul = TransferResult(0.0, 0.0, ul.appBytes, 0)
            } else if (!gotOK.get()) {
                ulMsg = UPLOAD_UNCONFIRMED
                status(ulMsg)
            }
        }
        finish(
            SpeedtestResult(
                node = name, source = "cnspeed", carrier = loc.carrier, city = valid[0].n.city,
                mode = modeLabel(opts),
                dlAvg = dl.avgBps / 1e6, dlPeak = dl.peakBps / 1e6,
                ulAvg = ul.avgBps / 1e6, ulPeak = ul.peakBps / 1e6,
                dlBytes = dl.appBytes, ulBytes = ul.appBytes,
                pingMs = ping, jitter = jit, dlConns = dl.conns, ulConns = ul.conns,
                ulMsg = ulMsg,
            ),
        )
    }

    // ---- CDN / Ookla:标准 HTTP 传输 ----

    private suspend fun runCdn(opts: TestOptions, pool: List<Node>) {
        var multi = opts.multi.coerceAtLeast(1)
        val ranked: List<Node> = if (opts.node != null) {
            multi = 1
            listOf(opts.node)
        } else if (pool.isNotEmpty()) {
            if (pool.size > 1) {
                status("检测 ${pool.size} 个节点的可达性和延迟…")
                rankCdn(pool, multi, opts.doDownload)
            } else {
                pool
            }
        } else {
            emptyList()
        }
        if (ranked.isEmpty()) throw IllegalStateException("无可用测速源")

        var lastFailure: Exception? = null
        for (group in ranked.chunked(multi)) {
            try {
                runCdnSelected(opts, group, multi)
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (opts.node != null || hasPayload) throw e
                lastFailure = e
                status("${aggName(group)} 未传输有效数据，继续尝试候选节点")
            }
        }
        throw lastFailure ?: IllegalStateException("所有候选节点均未传输有效数据")
    }

    private suspend fun runCdnSelected(opts: TestOptions, ranked: List<Node>, multi: Int) {
        val want = minOf(multi, ranked.size)
        val dlSrcs = ranked.take(want)

        // 上传源:下载冠军优先,其次 Ookla,再次任意支持上传的源
        val ulSrcs = mutableListOf<Node>()
        val seen = mutableSetOf<String>()
        fun tryUl(n: Node) {
            if (n.ulUrl.isNotEmpty() && n.stableId() !in seen && ulSrcs.size < want) {
                ulSrcs += n
                seen += n.stableId()
            }
        }
        ranked.firstOrNull()?.let { tryUl(it) }
        ranked.filter { it.source == "ookla" }.forEach { tryUl(it) }
        ranked.forEach { tryUl(it) }

        val pingTarget = when {
            opts.doDownload && dlSrcs.isNotEmpty() -> dlSrcs[0]
            ulSrcs.isNotEmpty() -> ulSrcs[0]
            else -> ranked[0]
        }
        val (ping, jit, pok) = pingNode(pingTarget)
        if (pok) {
            _state.update { it.copy(livePingMs = ping, liveJitter = jit) }
            status("${pingTarget.name} ping=" + "%.0f".format(ping) + "ms")
        }

        val dlName = aggName(dlSrcs)
        val client = HttpClients.cdnTransfer((opts.durationSec + 15).toLong())
        var dl = TransferResult(0.0, 0.0, 0L, 0)
        var ul = dl
        var ulMsg = ""

        if (opts.doDownload) {
            status("$dlName 下载中")
            dl = runDirection(opts, Direction.DOWNLOAD) {
                dlSrcs.map { Engine.cdnDownload(client, it.dlUrl, it.ua) }
            }
            if (dl.appBytes == 0L || dl.avgBps <= 0.0 || dl.conns == 0) throw IllegalStateException("$dlName 下载失败或连接全部中断")
        }
        if (opts.doUpload) {
            if (ulSrcs.isEmpty()) {
                ulMsg = "当前节点未提供上传接口"
                status(ulMsg)
                if (!opts.doDownload) throw IllegalStateException(ulMsg)
            } else {
                val ulName = aggName(ulSrcs)
                if (ulSrcs[0].name != dlSrcs[0].name) status("下载源不支持上传,上传改用 $ulName")
                status("$ulName 上传中")
                val gotOK = AtomicBoolean(false)
                ul = runDirection(opts, Direction.UPLOAD) {
                    ulSrcs.map { Engine.cdnUpload(client, it.ulUrl, it.ua, gotOK) }
                }
                if (ul.appBytes == 0L || ul.avgBps <= 0.0 || ul.conns == 0) {
                    ulMsg = "上传失败或节点拒绝请求"
                    if (!opts.doDownload) throw IllegalStateException(ulMsg)
                    ul = TransferResult(0.0, 0.0, ul.appBytes, 0)
                    status("$ulName $ulMsg，下载结果已保留")
                } else if (!gotOK.get()) {
                    ulMsg = UPLOAD_UNCONFIRMED
                    status(ulMsg)
                }
            }
        }

        finish(
            SpeedtestResult(
                node = dlName, source = ranked[0].source,
                mode = modeLabel(opts),
                dlAvg = dl.avgBps / 1e6, dlPeak = dl.peakBps / 1e6,
                ulAvg = ul.avgBps / 1e6, ulPeak = ul.peakBps / 1e6,
                dlBytes = dl.appBytes, ulBytes = ul.appBytes,
                ulMsg = ulMsg, dlConns = dl.conns, ulConns = ul.conns,
                pingMs = ping, jitter = jit,
            ),
        )
    }

    /** Preflight a bounded shortlist, then preserve throughput-based selection with an explicit traffic budget. */
    private suspend fun rankCdn(cands: List<Node>, multi: Int, probeDownload: Boolean): List<Node> = coroutineScope {
        val permits = Semaphore(4)
        val scored = cands.distinctBy { it.stableId() }.take(10).map { c ->
            async(Dispatchers.IO) {
                permits.withPermit { val (ping, _, ok) = backend.ping(c, quick = true); c to if (ok) ping else Double.POSITIVE_INFINITY }
            }
        }.awaitAll()
        val shortlist = scored.filter { it.second.isFinite() }.sortedBy { it.second }
            .take(maxOf(3, multi)).map { it.first }
        if (shortlist.isEmpty()) return@coroutineScope emptyList()
        if (!probeDownload) return@coroutineScope shortlist
        val probeSec = 1.25
        status("准备阶段：短测 ${shortlist.size} 个节点吞吐，每节点 1 连接、${probeSec} 秒")
        val client = HttpClients.cdnTransfer(8)
        val results = shortlist.map { node -> async(Dispatchers.IO) {
            node to backend.transfer(false, listOf(Engine.cdnDownload(client, node.dlUrl, node.ua)),
                1, 1, probeSec, 0.0, 250) { _, _, _ -> }
        } }.awaitAll()
        probeBytes += results.sumOf { it.second.appBytes }
        status("节点探测流量 ${fmtBytes(probeBytes)}（单独记录）")
        results.filter { it.second.appBytes > 0 && it.second.conns > 0 }
            .sortedByDescending { it.second.avgBps }.map { it.first }
    }

    private suspend fun pingNode(n: Node): Triple<Double, Double, Boolean> {
        return backend.ping(n)
    }

    private fun aggName(srcs: List<Node>): String = when (srcs.size) {
        0 -> ""
        1 -> srcs[0].name
        else -> "${srcs[0].name} 等 ${srcs.size} 源"
    }

    private fun modeLabel(opts: TestOptions): String = when {
        opts.doDownload && opts.doUpload -> "双向"
        opts.doDownload -> "仅下载"
        else -> "仅上传"
    }

    private suspend fun runDirection(
        opts: TestOptions,
        dir: Direction,
        factories: () -> List<WorkerFactory>,
    ): TransferResult {
        val upload = dir == Direction.UPLOAD
        // 阶段开始立即切方向:否则首个采样 tick(≤200ms)前表盘仍显示上一方向的
        // 残留速率(下载→上传时看起来像"上传串了下载速率")
        currentCoroutineContext().ensureActive()
        _state.update {
            if (it.status != RunStatus.RUNNING) it
            else if (dir == Direction.DOWNLOAD) it.copy(direction = dir, dl = DirectionState())
            else it.copy(direction = dir, ul = DirectionState())
        }
        val requestedMin = if (upload) opts.threadsUl else opts.threadsDl
        val max = if (upload) capMax(opts.threadsUlMax, opts.threadsUl) else capMax(opts.threadsDlMax, opts.threadsDl)
        val workers = factories().take(max)
        val min = maxOf(requestedMin, workers.size)
        val samples = mutableListOf<Sample>()
        var average = 0.0
        var measurementStart: Double? = if (opts.warmupSec == 0) 0.0 else null
        val result = backend.transfer(
            upload = upload,
            factories = workers,
            minThreads = min,
            maxThreads = max,
            durationSec = opts.durationSec.toDouble(),
            warmupSec = opts.warmupSec.toDouble(),
            everyMs = 200, // 5 点/秒,表盘与曲线的流畅度下限
            onAverage = { average = it },
        ) { t, mbps, peak ->
            if (mbps > 0) hasPayload = true
            val boundary = measurementStart
            if (boundary == null) {
                if (t >= opts.warmupSec) measurementStart = t
            } else if (t > boundary) {
                // The boundary tick is display-only. Later real zero-rate samples still belong to the curve.
                samples.add(Sample(t - boundary, mbps * 1e6))
            }
            // 全在预热段内(极短测试):退化为全样本均值
            tick(dir, t, mbps, peak, samples.toList(), average)
        }
        if (result.appBytes > 0) hasPayload = true
        _state.update { state ->
            if (state.status != RunStatus.RUNNING) return@update state
            val next = (if (dir == Direction.DOWNLOAD) state.dl else state.ul).copy(
                avgMbps = result.avgBps / 1e6, peakMbps = result.peakBps / 1e6,
            )
            if (dir == Direction.DOWNLOAD) state.copy(dl = next) else state.copy(ul = next)
        }
        return result
    }

    private suspend fun finish(result: SpeedtestResult) {
        val withIp = result.copy(
            ip = result.ip.ifEmpty { activeLocation.ip },
            network = _state.value.activeOptions?.network.orEmpty(),
            probeBytes = probeBytes,
        )
        try { history.add(withIp) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status("测速已完成，但历史保存失败: ${e.message ?: "存储不可用"}") }
        _state.update { if (it.status == RunStatus.RUNNING) it.copy(result = withIp) else it }
    }
}

internal const val UPLOAD_UNCONFIRMED = "已发送数据，但本次未收到服务器确认"

internal fun TestOptions.normalized(): TestOptions {
    val duration = durationSec.coerceIn(3, 120)
    val dl = threadsDl.coerceIn(1, 128)
    val ul = threadsUl.coerceIn(1, 128)
    val dlMax = threadsDlMax.coerceIn(dl, 128)
    val ulMax = threadsUlMax.coerceIn(ul, 128)
    val sourceLimit = minOf(if (doDownload) dlMax else 128, if (doUpload) ulMax else 128, 4)
    return copy(durationSec = duration, warmupSec = Settings.effectiveWarmup(duration, warmupSec),
        multi = multi.coerceIn(1, sourceLimit), threadsDl = dl, threadsDlMax = dlMax,
        threadsUl = ul, threadsUlMax = ulMax)
}
