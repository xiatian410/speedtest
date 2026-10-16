package dev.gaimoy.betterspeedtest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gaimoy.betterspeedtest.core.stableId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import dev.gaimoy.betterspeedtest.core.Node
import dev.gaimoy.betterspeedtest.core.NodeCatalog
import dev.gaimoy.betterspeedtest.core.TestSource
import dev.gaimoy.betterspeedtest.core.quickPing
import io.github.lingqiqi5211.meowui.component.MeowCard
import io.github.lingqiqi5211.meowui.component.MeowScaffold
import io.github.lingqiqi5211.meowui.component.MeowSearchBar
import io.github.lingqiqi5211.meowui.component.MeowTip
import io.github.lingqiqi5211.meowui.component.MeowTipStyle
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import io.github.lingqiqi5211.meowui.component.meowScaffoldScroll
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun nodeKey(n: Node): String =
    n.stableId()

private fun sourceTitle(source: String): String = when (source) {
    "cnspeed" -> "全球网测节点"
    "ookla" -> "Speedtest.net 节点"
    else -> "选择节点"
}

private fun testSource(source: String): TestSource = when (source) {
    "cnspeed" -> TestSource.CNSPEED
    "ookla" -> TestSource.OOKLA
    else -> TestSource.AUTO
}

/**
 * 节点选择页:只列主页当前「源」对应的节点(取数走 NodeCatalog,与引擎同一份逻辑),
 * 点选即返回。自动模式显示说明卡,不提供节点列表。
 */
@Composable
fun NodePickerScreen(
    source: String,
    onBack: () -> Unit,
    pickedNode: Node?,
    onPick: (Node) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<Node>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var pings by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var pinging by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var pingJob by remember { mutableStateOf<Job?>(null) }
    val isAuto = source == "auto"

    fun load() {
        loadJob?.cancel()
        pingJob?.cancel()
        pings = emptyMap()
        pinging = false
        loading = true
        error = null
        loadJob = scope.launch {
            try {
                nodes = withContext(Dispatchers.IO) { NodeCatalog.nodes(testSource(source)).distinctBy(::nodeKey) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nodes = emptyList()
                error = e.message ?: "加载失败"
            } finally {
                if (isActive) loading = false
            }
        }
    }

    fun pingVisible(list: List<Node>) {
        pingJob?.cancel()
        val targets = list.take(12)
        if (targets.isEmpty()) return
        pinging = true
        pingJob = scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    targets.map { n ->
                        async {
                            val (ms, _, ok) = quickPing(n)
                            nodeKey(n) to (if (ok) ms else -1.0)
                        }
                    }.awaitAll()
                }
                pings = pings + results.toMap()
            } finally {
                if (isActive) pinging = false
            }
        }
    }

    LaunchedEffect(source) { load() }

    val match: (Node) -> Boolean = { n ->
        query.isBlank() || n.name.contains(query, true) ||
            n.city.contains(query) || n.province.contains(query) || n.hostIp.contains(query)
    }
    val filtered = nodes.filter(match)

    MeowScaffold(
        title = sourceTitle(source),
        onBackClick = onBack,
        actionItems = if (!isAuto) {
            listOf(
                MeowTopBarAction.Icon(
                    icon = Icons.Rounded.NetworkCheck,
                    contentDescription = "测延迟",
                    enabled = !loading && !pinging && filtered.isNotEmpty(),
                    onClick = { pingVisible(filtered) },
                ),
                MeowTopBarAction.Icon(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "刷新",
                    enabled = !loading,
                    onClick = { load() },
                ),
            )
        } else {
            emptyList()
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            if (isAuto) {
                MeowCard {
                    BasicText(
                        text = "当前为自动源",
                        style = MeowTheme.typography.title.copy(color = MeowTheme.colors.onSurface),
                    )
                    BasicText(
                        text = "自动模式由测速引擎择优,不指定单节点:中国大陆走全球网测就近节点,其他地区走 Speedtest.net。",
                        style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
                    )
                }
            } else {
                MeowSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    placeholder = "搜索名称 / 城市 / IP",
                    cancelText = "取消",
                ) {
                    if (query.isNotBlank()) {
                        NodeList(
                            nodes = filtered,
                            pickedNode = pickedNode,
                            pings = pings,
                            showEmpty = true,
                            onPick = {
                                onPick(it)
                                expanded = false
                            },
                        )
                    }
                }

                if (loading && nodes.isEmpty()) {
                    MeowTip(title = "加载中", message = "正在获取节点列表…")
                }
                if (pinging) {
                    MeowTip(title = "测延迟中", message = "对前 ${minOf(filtered.size, 12)} 个节点做 TCP 连接时延…")
                }
                error?.let {
                    MeowTip(title = "加载失败", message = it, style = MeowTipStyle.Error)
                }

                NodeList(
                    nodes = filtered,
                    pickedNode = pickedNode,
                    pings = pings,
                    showEmpty = !loading && error == null,
                    modifier = Modifier.weight(1f),
                    onPick = onPick,
                )

                if (nodes.isNotEmpty()) {
                    BasicText(
                        text = "共 ${nodes.size} 个节点" + if (pings.isNotEmpty()) " · 已测 ${pings.size} 个延迟" else "",
                        style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeList(
    nodes: List<Node>,
    pickedNode: Node?,
    pings: Map<String, Double>,
    showEmpty: Boolean,
    modifier: Modifier = Modifier,
    onPick: (Node) -> Unit,
) {
    if (nodes.isEmpty()) {
        if (showEmpty) {
            BasicText(
                text = "暂无节点",
                style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .meowScaffoldScroll(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pickedNode != null) {
            item(key = "clear") {
                NodeCard(
                    title = "恢复自动选择",
                    subtitle = "由测速引擎按就近/实测吞吐自动挑节点",
                    pingMs = null,
                    selected = false,
                    onClick = { onPick(Node(name = "", source = "")) },
                )
            }
        }
        items(nodes, key = { nodeKey(it) }) { n ->
            val pingMs = pings[nodeKey(n)]
            val isPicked = pickedNode != null && nodeKey(pickedNode) == nodeKey(n)
            val sub = listOfNotNull(
                n.province.takeIf { it.isNotEmpty() },
                n.city.takeIf { it.isNotEmpty() },
                n.carrier.takeIf { it.isNotEmpty() },
                n.hostIp.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            NodeCard(
                title = n.name,
                subtitle = sub,
                pingMs = pingMs,
                selected = isPicked,
                onClick = { onPick(n) },
            )
        }
    }
}

@Composable
private fun NodeCard(
    title: String,
    subtitle: String,
    pingMs: Double?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MeowTheme.colors.primary
    MeowCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = MeowTheme.typography.title.copy(
                        color = if (selected) accent else MeowTheme.colors.onSurface,
                    ),
                )
                if (subtitle.isNotEmpty()) {
                    BasicText(
                        text = subtitle,
                        style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
                    )
                }
            }
            if (pingMs != null) {
                val label = if (pingMs < 0) "超时" else "%.0f ms".format(pingMs)
                BasicText(
                    text = label,
                    style = MeowTheme.typography.value.copy(
                        color = if (pingMs < 0) MeowTheme.colors.error else accent,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (selected) {
                BasicText(
                    text = "✓",
                    style = MeowTheme.typography.value.copy(color = accent),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
