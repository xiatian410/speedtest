package dev.gaimoy.betterspeedtest

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaimoy.betterspeedtest.core.RunStatus
import dev.gaimoy.betterspeedtest.core.TestOptions
import dev.gaimoy.betterspeedtest.core.TestSource
import dev.gaimoy.betterspeedtest.data.Settings
import dev.gaimoy.betterspeedtest.ui.AppearanceScreen
import dev.gaimoy.betterspeedtest.ui.copyResultToClipboard
import dev.gaimoy.betterspeedtest.ui.DisplaySettingsScreen
import dev.gaimoy.betterspeedtest.ui.EngineSettingsScreen
import dev.gaimoy.betterspeedtest.ui.HistoryScreen
import dev.gaimoy.betterspeedtest.ui.MainScreen
import dev.gaimoy.betterspeedtest.ui.NodePickerScreen
import dev.gaimoy.betterspeedtest.ui.SettingsScreen
import dev.gaimoy.betterspeedtest.ui.TestParamsScreen
import dev.gaimoy.betterspeedtest.ui.TrafficScreen
import dev.gaimoy.betterspeedtest.ui.shareResult
import io.github.lingqiqi5211.meowui.component.MeowAlertDialog
import io.github.lingqiqi5211.meowui.component.MeowNavHost
import io.github.lingqiqi5211.meowui.component.MeowNavigationBar
import io.github.lingqiqi5211.meowui.component.MeowNavigationBarStyle
import io.github.lingqiqi5211.meowui.component.MeowNavigationItem
import io.github.lingqiqi5211.meowui.component.MeowScaffold
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import io.github.lingqiqi5211.meowui.core.MeowUiStyle
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import io.github.lingqiqi5211.meowui.preference.MeowPreferenceProvider
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceWriter
import io.github.lingqiqi5211.meowui.setMeowContent
import io.github.lingqiqi5211.meowui.theme.MeowAppearance
import io.github.lingqiqi5211.meowui.theme.MeowColorSpec
import io.github.lingqiqi5211.meowui.theme.MeowPaletteStyle
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import io.github.lingqiqi5211.meowui.theme.MeowThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var model: SpeedtestViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[SpeedtestViewModel::class.java]
        handleTrafficIntent(intent)
        setMeowContent { SpeedtestApp(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTrafficIntent(intent)
    }

    private fun handleTrafficIntent(intent: Intent) {
        if (intent.getBooleanExtra("open_traffic", false)) {
            model.openTrafficRequested = true
            intent.removeExtra("open_traffic")
        }
    }
}

private enum class Route { Main, Nodes, Appearance, TestParams, Engine, Display, Traffic, TrafficNodes }

private val navigationItems = listOf(
    MeowNavigationItem(label = "测速", icon = Icons.Rounded.Speed),
    MeowNavigationItem(label = "打流", icon = Icons.Rounded.SwapVert),
    MeowNavigationItem(label = "历史", icon = Icons.Rounded.History),
    MeowNavigationItem(label = "设置", icon = Icons.Rounded.Settings),
)

@Composable
private fun SpeedtestApp(model: SpeedtestViewModel) {
    val context = LocalContext.current
    val store = model.store

    // ---- 外观状态(与 MeowUI sample 相同的接线方式)----
    val styleName by rememberMeowPreferenceValue(Settings.Style, store)
    val themeModeName by rememberMeowPreferenceValue(Settings.ThemeMode, store)
    val dynamicColor by rememberMeowPreferenceValue(Settings.DynamicColor, store)
    val paletteStyleName by rememberMeowPreferenceValue(Settings.PaletteStyle, store)
    val colorSpecName by rememberMeowPreferenceValue(Settings.ColorSpec, store)
    val seedColorValue by rememberMeowPreferenceValue(Settings.SeedColor, store)
    val miuixMonet by rememberMeowPreferenceValue(Settings.MiuixMonet, store)
    val amoledDark by rememberMeowPreferenceValue(Settings.AmoledDark, store)
    val predictiveBack by rememberMeowPreferenceValue(Settings.PredictiveBack, store)
    val interfaceScale by rememberMeowPreferenceValue(Settings.InterfaceScale, store)
    val blurEnabled by rememberMeowPreferenceValue(Settings.Blur, store)
    val appearance = MeowAppearance(
        style = if (styleName == "miuix") MeowUiStyle.Miuix else MeowUiStyle.MaterialExpressive,
        themeMode = MeowThemeMode.entries.firstOrNull { it.name == themeModeName } ?: MeowThemeMode.System,
        dynamicColor = dynamicColor,
        seedColor = Color(seedColorValue),
        paletteStyle = MeowPaletteStyle.entries.firstOrNull { it.name == paletteStyleName } ?: MeowPaletteStyle.TonalSpot,
        colorSpec = MeowColorSpec.entries.firstOrNull { it.name == colorSpecName } ?: MeowColorSpec.Spec2025,
        miuixMonetEnabled = miuixMonet,
        amoledDarkEnabled = amoledDark,
        blurEnabled = blurEnabled,
        predictiveBackEnabled = predictiveBack,
        interfaceScale = interfaceScale,
    )

    MeowPreferenceProvider(store, onWriteResult = { result ->
        if (result != PreferenceWriteResult.Success) {
            Toast.makeText(context, "设置保存失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }) {
        MeowTheme(appearance = appearance) {
            SpeedtestBody(model = model, appearance = appearance)
        }
    }
}

@Composable
private fun SpeedtestBody(
    model: SpeedtestViewModel,
    appearance: MeowAppearance,
) {
    val runner = model.runner
    val history = model.history
    val store = model.store
    val context = LocalContext.current
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }
    val pickedNode = model.pickedNode
    var backStack by rememberSaveable { mutableStateOf(listOf<Route>(Route.Main)) }
    val pageStateHolder = rememberSaveableStateHolder()
    val floatingNavigation by rememberMeowPreferenceValue(Settings.FloatingNavigation)
    val speedUnit by rememberMeowPreferenceValue(Settings.SpeedUnit)
    val historyList by history.records.collectAsStateWithLifecycle()
    val historyError by history.error.collectAsStateWithLifecycle()
    val historyLoading by history.loading.collectAsStateWithLifecycle()
    // 根导航仅订阅最终结果，200ms速率采样留在各自的显示组件中。
    val completedResults = remember(runner) {
        runner.state.map { if (it.status == RunStatus.DONE) it.result else null }.distinctUntilChanged()
    }
    val currentResult by completedResults.collectAsStateWithLifecycle(initialValue = null)

    val closeSub = { if (backStack.size > 1) backStack = backStack.dropLast(1) }
    val openNodes = { if (backStack.last() != Route.Nodes) backStack = backStack + Route.Nodes }
    val openAppearance = { if (backStack.last() != Route.Appearance) backStack = backStack + Route.Appearance }
    val openTestParams = { if (backStack.last() != Route.TestParams) backStack = backStack + Route.TestParams }
    val openEngine = { if (backStack.last() != Route.Engine) backStack = backStack + Route.Engine }
    val openDisplay = { if (backStack.last() != Route.Display) backStack = backStack + Route.Display }
    val openTraffic = {
        selectedPage = 1
        backStack = listOf(Route.Main)
    }
    LaunchedEffect(model.openTrafficRequested) {
        if (model.openTrafficRequested) { openTraffic(); model.openTrafficRequested = false }
    }

    val currentAppearance by rememberUpdatedState(appearance)
    val writeStyle = rememberMeowPreferenceWriter(Settings.Style)
    val writeThemeMode = rememberMeowPreferenceWriter(Settings.ThemeMode)
    val writeDynamicColor = rememberMeowPreferenceWriter(Settings.DynamicColor)
    val writeSeedColor = rememberMeowPreferenceWriter(Settings.SeedColor)
    val writePaletteStyle = rememberMeowPreferenceWriter(Settings.PaletteStyle)
    val writeColorSpec = rememberMeowPreferenceWriter(Settings.ColorSpec)
    val writeMiuixMonet = rememberMeowPreferenceWriter(Settings.MiuixMonet)
    val writeAmoledDark = rememberMeowPreferenceWriter(Settings.AmoledDark)
    val writePredictiveBack = rememberMeowPreferenceWriter(Settings.PredictiveBack)
    val writeInterfaceScale = rememberMeowPreferenceWriter(Settings.InterfaceScale)
    val writeBlur = rememberMeowPreferenceWriter(Settings.Blur)
    val updateAppearance: (MeowAppearance) -> Unit = remember {
        { updated ->
            val cur = currentAppearance
            if (updated.style != cur.style) writeStyle(if (updated.style == MeowUiStyle.Miuix) "miuix" else "material3_expressive")
            if (updated.themeMode != cur.themeMode) writeThemeMode(updated.themeMode.name)
            if (updated.dynamicColor != cur.dynamicColor) writeDynamicColor(updated.dynamicColor)
            if (updated.seedColor != cur.seedColor) writeSeedColor(updated.seedColor.toArgb())
            if (updated.paletteStyle != cur.paletteStyle) writePaletteStyle(updated.paletteStyle.name)
            if (updated.colorSpec != cur.colorSpec) writeColorSpec(updated.colorSpec.name)
            if (updated.miuixMonetEnabled != cur.miuixMonetEnabled) writeMiuixMonet(updated.miuixMonetEnabled)
            if (updated.amoledDarkEnabled != cur.amoledDarkEnabled) writeAmoledDark(updated.amoledDarkEnabled)
            if (updated.predictiveBackEnabled != cur.predictiveBackEnabled) writePredictiveBack(updated.predictiveBackEnabled)
            if (updated.interfaceScale != cur.interfaceScale) writeInterfaceScale(updated.interfaceScale)
            if (updated.blurEnabled != cur.blurEnabled) writeBlur(updated.blurEnabled)
        }
    }

    val startTest = start@{
        if (TrafficRuntime.runner.state.value.busy) {
            Toast.makeText(context, "请先停止正在进行的打流", Toast.LENGTH_SHORT).show()
            return@start
        }
        val (doDownload, doUpload) = store.readTestMode()
        val (durationSec, warmupSec) = store.readTestTiming()
        val source = when (store.read(Settings.Source)) {
            "cnspeed" -> TestSource.CNSPEED
            "ookla" -> TestSource.OOKLA
            else -> TestSource.AUTO
        }
        runner.start(
            TestOptions(
                source = source,
                node = pickedNode,
                durationSec = durationSec,
                multi = store.read(Settings.Multi),
                doDownload = doDownload,
                doUpload = doUpload,
                threadsDl = store.read(Settings.ThreadsDl),
                threadsDlMax = store.read(Settings.ThreadsDlMax),
                threadsUl = store.read(Settings.ThreadsUl),
                threadsUlMax = store.read(Settings.ThreadsUlMax),
                warmupSec = warmupSec,
                network = networkLabel(context.getSystemService(ConnectivityManager::class.java)),
            ),
        )
    }

    MeowNavHost(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = closeSub,
        predictiveBackEnabled = currentAppearance.predictiveBackEnabled,
    ) { route ->
        when (route) {
            Route.Main -> {
                var showClearHistory by remember { mutableStateOf(false) }
                var clearingHistory by remember { mutableStateOf(false) }
                val clearScope = androidx.compose.runtime.rememberCoroutineScope()
                MeowScaffold(
                    title = navigationItems[selectedPage].label,
                    actionItems = when {
                        // 历史分页且有记录:清空图标在顶栏
                        selectedPage == 2 && !clearingHistory && (historyList.isNotEmpty() || historyError != null) -> listOf(
                            MeowTopBarAction.Icon(
                                icon = Icons.Rounded.Delete,
                                contentDescription = "清空历史",
                                onClick = { showClearHistory = true },
                            ),
                        )
                        // 测速分页完成态:分享/复制图标
                        selectedPage == 0 && currentResult != null -> listOf(
                            MeowTopBarAction.Icon(
                                icon = Icons.Rounded.ContentCopy,
                                contentDescription = "复制结果",
                                onClick = {
                                    copyResultToClipboard(context, runner.state.value.result ?: return@Icon, speedUnit)
                                },
                            ),
                            MeowTopBarAction.Icon(
                                icon = Icons.Rounded.Share,
                                contentDescription = "分享结果",
                                onClick = {
                                    shareResult(context, runner.state.value.result ?: return@Icon, speedUnit)
                                },
                            ),
                        )
                        else -> emptyList()
                    },
                    bottomBar = {
                        MeowNavigationBar(
                            items = navigationItems,
                            selectedIndex = selectedPage,
                            onItemSelected = { selectedPage = it },
                            style = if (floatingNavigation) {
                                MeowNavigationBarStyle.Floating
                            } else {
                                MeowNavigationBarStyle.Standard
                            },
                        )
                    },
                ) { padding ->
                    AnimatedContent(
                        targetState = selectedPage,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val direction = if (targetState > initialState) 1 else -1
                            (slideInHorizontally { it * direction / 5 } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it * direction / 5 } + fadeOut())
                        },
                        label = "main-page",
                    ) { page ->
                        pageStateHolder.SaveableStateProvider(page) {
                        when (page) {
                            // 主页是自建滚动容器:自己消费 scaffold 内边距并接入顶栏折叠
                            0 -> MainScreen(
                                runner = runner,
                                pickedNode = pickedNode,
                                onPickNode = openNodes,
                                onClearPickedNode = { model.pickedNode = null },
                                onStart = startTest,
                                contentPadding = padding,
                            )
                            // 历史内容由持久化成功后发布的状态驱动。
                            1 -> TrafficScreen(
                                pickedNode = model.trafficNode,
                                onNodeChange = { model.trafficNode = it },
                                onPickNode = { source -> model.trafficSource = source; backStack = backStack + Route.TrafficNodes },
                                isSpeedtestRunning = { runner.state.value.status == RunStatus.RUNNING },
                                contentPadding = padding,
                            )
                            2 -> HistoryScreen(
                                history = historyList,
                                contentPadding = padding,
                                loading = historyLoading,
                                error = historyError,
                                onRetry = {
                                    clearScope.launch {
                                        try { history.load() } catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { /* history.error 在页面内显示原因 */ }
                                    }
                                },
                            )
                            else -> SettingsScreen(
                                onOpenAppearance = openAppearance,
                                onOpenTestParams = openTestParams,
                                onOpenEngine = openEngine,
                                onOpenDisplay = openDisplay,
                                contentPadding = padding,
                            )
                        }
                        }
                    }
                }

                MeowAlertDialog(
                    show = showClearHistory,
                    title = "清空历史记录",
                    message = "此操作会删除本地保存的全部测速结果。",
                    style = io.github.lingqiqi5211.meowui.component.MeowAlertStyle.Warning,
                    confirmText = "清空",
                    cancelText = "取消",
                    onConfirm = {
                        showClearHistory = false
                        clearScope.launch {
                            clearingHistory = true
                            try {
                                history.clear()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                Toast.makeText(context, "清空失败，请重试", Toast.LENGTH_SHORT).show()
                            } finally {
                                clearingHistory = false
                            }
                        }
                    },
                    onDismissRequest = { showClearHistory = false },
                )
            }

            Route.Nodes ->
                NodePickerScreen(
                    source = store.read(Settings.Source),
                    onBack = closeSub,
                    pickedNode = pickedNode,
                    onPick = { n ->
                        model.pickedNode = if (n.name.isEmpty()) null else n
                        closeSub()
                    },
                )

            Route.Appearance ->
                AppearanceScreen(
                    appearance = currentAppearance,
                    onChange = updateAppearance,
                    onBack = closeSub,
                )

            Route.TestParams -> TestParamsScreen(onBack = closeSub)
            // Restore older saved navigation stacks into the new top-level destination.
            Route.Traffic -> LaunchedEffect(Unit) { openTraffic() }
            Route.TrafficNodes -> NodePickerScreen(
                source = model.trafficSource.name.lowercase(),
                onBack = closeSub,
                pickedNode = model.trafficNode,
                onPick = { model.trafficNode = it.takeIf { node -> node.name.isNotEmpty() }; closeSub() },
            )

            Route.Engine -> EngineSettingsScreen(onBack = closeSub)

            Route.Display -> DisplaySettingsScreen(onBack = closeSub)
        }
    }
}

private fun networkLabel(manager: ConnectivityManager?): String {
    val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return ""
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
        else -> "其他网络"
    }
}
