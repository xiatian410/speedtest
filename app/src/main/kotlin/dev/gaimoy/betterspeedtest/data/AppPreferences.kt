package dev.gaimoy.betterspeedtest.data

import android.content.SharedPreferences
import io.github.lingqiqi5211.meowui.core.preference.PreferenceConnectionState
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.core.preference.PreferenceStore
import io.github.lingqiqi5211.meowui.core.preference.PreferenceType
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SharedPreferences 实现的 PreferenceStore,让 MeowUI 偏好组件直接持久化。 */
class SharedPreferenceStore(private val prefs: SharedPreferences) : PreferenceStore {
    private val timingMutex = Mutex()

    override val connectionState: StateFlow<PreferenceConnectionState> =
        MutableStateFlow<PreferenceConnectionState>(PreferenceConnectionState.Connected).asStateFlow()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> read(key: PreferenceKey<T>): T {
        return try {
            if (!prefs.contains(key.name)) return key.defaultValue
            val value: Any? = when (key.type) {
                PreferenceType.Boolean -> prefs.getBoolean(key.name, false)
                PreferenceType.Int -> prefs.getInt(key.name, 0)
                PreferenceType.Long -> prefs.getLong(key.name, 0L)
                PreferenceType.Float -> prefs.getFloat(key.name, 0f)
                PreferenceType.String -> prefs.getString(key.name, null)
                PreferenceType.StringSet -> prefs.getStringSet(key.name, null)?.let { stored ->
                    // SharedPreferences owns this set; never expose it to callers.
                    require((stored as Set<*>).all { it is String }) { "Invalid string set: ${key.name}" }
                    stored.toSet()
                }
            }
            (value as? T) ?: key.defaultValue
        } catch (_: ClassCastException) {
            key.defaultValue
        } catch (_: IllegalArgumentException) {
            key.defaultValue
        }
    }

    override fun <T : Any> observe(key: PreferenceKey<T>): Flow<T> = callbackFlow {
        val emissionLock = Any()
        fun publishCurrent() {
            synchronized(emissionLock) { trySend(read(key)) }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == key.name) publishCurrent()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            publishCurrent()
            awaitClose { }
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    override suspend fun <T : Any> write(key: PreferenceKey<T>, value: T): PreferenceWriteResult =
        commit {
            when (key.type) {
                PreferenceType.Boolean -> putBoolean(key.name, value as Boolean)
                PreferenceType.Int -> putInt(key.name, value as Int)
                PreferenceType.Long -> putLong(key.name, value as Long)
                PreferenceType.Float -> putFloat(key.name, value as Float)
                PreferenceType.String -> putString(key.name, value as String)
                PreferenceType.StringSet -> {
                    val strings = (value as Set<*>).map {
                        require(it is String) { "Preference '${key.name}' requires Set<String>." }
                        it
                    }.toSet()
                    putStringSet(key.name, strings)
                }
            }
        }

    override suspend fun delete(key: PreferenceKey<*>): PreferenceWriteResult = commit { remove(key.name) }

    override suspend fun clear(): PreferenceWriteResult = commit { clear() }

    /** 用一次快照读取同一事务中的两个方向，避免开始测速时混合新旧模式。 */
    fun readTestMode(): Pair<Boolean, Boolean> {
        val values = prefs.all
        return ((values[Settings.DoDownload.name] as? Boolean) ?: Settings.DoDownload.defaultValue) to
            ((values[Settings.DoUpload.name] as? Boolean) ?: Settings.DoUpload.defaultValue)
    }

    /** 两个方向属于同一选项，单次提交避免切换模式时写出双关闭状态。 */
    suspend fun writeTestMode(download: Boolean, upload: Boolean): PreferenceWriteResult {
        if (!download && !upload) {
            return PreferenceWriteResult.Failure(IllegalArgumentException("下载和上传不能都关闭"))
        }
        return commit {
            putBoolean(Settings.DoDownload.name, download)
            putBoolean(Settings.DoUpload.name, upload)
        }
    }

    /** 旧设置也按引擎和界面共同允许的范围读取，不为读取操作修改磁盘。 */
    fun readTestTiming(): Pair<Int, Int> {
        val values = prefs.all
        val duration = ((values[Settings.DurationSec.name] as? Int) ?: Settings.DurationSec.defaultValue).coerceIn(3, 120)
        val warmup = (values[Settings.WarmupSec.name] as? Int) ?: Settings.WarmupSec.defaultValue
        return duration to Settings.effectiveWarmup(duration, warmup)
    }

    /** 缩短测试时长时，同一次提交将预热调整到仍可选择的合法档位。 */
    suspend fun writeTestDuration(durationSec: Int): PreferenceWriteResult = timingMutex.withLock {
        val duration = durationSec.coerceIn(3, 120)
        val warmup = Settings.effectiveWarmup(duration, readTestTiming().second)
        commit {
            putInt(Settings.DurationSec.name, duration)
            putInt(Settings.WarmupSec.name, warmup)
        }
    }

    suspend fun writeTestWarmup(warmupSec: Int): PreferenceWriteResult = timingMutex.withLock {
        val duration = readTestTiming().first
        val warmup = Settings.effectiveWarmup(duration, warmupSec)
        commit {
            putInt(Settings.DurationSec.name, duration)
            putInt(Settings.WarmupSec.name, warmup)
        }
    }

    private suspend fun commit(edit: SharedPreferences.Editor.() -> Unit): PreferenceWriteResult =
        withContext(Dispatchers.IO) {
            try {
                if (prefs.edit().apply(edit).commit()) {
                    PreferenceWriteResult.Success
                } else {
                    PreferenceWriteResult.Failure(IOException("设置未能保存到设备存储"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                PreferenceWriteResult.Failure(failure)
            }
        }
}

/** 全部设置项(测速参数 + 外观)。 */
object Settings {
    /** UI 和引擎共用预热档位，避免界面显示 5 秒、引擎实际执行 4 秒。 */
    val WarmupOptions = listOf(0, 1, 2, 3, 5)

    fun effectiveWarmup(durationSec: Int, warmupSec: Int): Int {
        val duration = durationSec.coerceIn(3, 120)
        val desired = warmupSec.coerceAtLeast(0)
        return WarmupOptions.last { it < duration && it <= desired }
    }

    // 测速
    val Source = PreferenceKey("source", "auto")
    val DurationSec = PreferenceKey("duration_sec", 15)
    val Multi = PreferenceKey("multi_source", 1)
    val DoDownload = PreferenceKey("do_download", true)
    val DoUpload = PreferenceKey("do_upload", true)
    // 手机默认(_v2 换 key 让新默认对老安装也生效):过多连接在移动端只会抢占
    // UI 线程 CPU(传输本身是 CPU 密集:拷贝/TLS),不会更快
    val ThreadsDl = PreferenceKey("threads_dl_v2", 6)
    val ThreadsDlMax = PreferenceKey("threads_dl_max_v2", 16)
    val ThreadsUl = PreferenceKey("threads_ul_v2", 4)
    val ThreadsUlMax = PreferenceKey("threads_ul_max_v2", 8)
    // 换 key 且默认 2s:5s 预热对移动端太长(有效窗口只剩 10s),2s 已足够跳过 TCP 爬坡/缓冲灌入
    val WarmupSec = PreferenceKey("warmup_sec_v2", 2)

    // 仪表盘 / 单位
    /** 显示单位:auto=自适应,Mbps / MB/s / Gbps */
    val SpeedUnit = PreferenceKey("speed_unit", "auto")
    /** 量程模式:auto=按峰值放大,fixed=固定满量程 */
    val GaugeRangeMode = PreferenceKey("gauge_range_mode", "auto")
    /** 固定满量程(Mbps),仅 fixed 模式生效 */
    val GaugeRangeFixed = PreferenceKey("gauge_range_fixed", 1000)
    /** 数字显示:big=大数主显示,compact=数显更紧凑(峰值并排) */
    val GaugeDigits = PreferenceKey("gauge_digits", "big")
    /** 是否在表盘两端显示量程数字 */
    val GaugeShowScale = PreferenceKey("gauge_show_scale", true)
    val GaugeShowDot = PreferenceKey("gauge_show_dot", true)

    // 外观(与 MeowUI sample 相同的字段)
    val Style = PreferenceKey("ui_style", "material3_expressive")
    val ThemeMode = PreferenceKey("theme_mode", "System")
    val DynamicColor = PreferenceKey("dynamic_color", true)
    val PaletteStyle = PreferenceKey("palette_style", "TonalSpot")
    val ColorSpec = PreferenceKey("color_spec", "Spec2025")
    val SeedColor = PreferenceKey("seed_color", 0xFF0B57D0.toInt())
    val MiuixMonet = PreferenceKey("miuix_monet", true)
    val AmoledDark = PreferenceKey("amoled_dark", false)
    val PredictiveBack = PreferenceKey("predictive_back", true)
    val InterfaceScale = PreferenceKey("interface_scale", 1f)
    // 换 key 且默认关:磨砂(RuntimeShader)在模拟器/中低端机上是全局掉帧主因之一
    val Blur = PreferenceKey("bg_blur", false)
    val FloatingNavigation = PreferenceKey("floating_navigation", true)
}
