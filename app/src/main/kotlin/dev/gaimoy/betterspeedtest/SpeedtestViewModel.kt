package dev.gaimoy.betterspeedtest

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gaimoy.betterspeedtest.core.Node
import dev.gaimoy.betterspeedtest.core.SpeedtestRunner
import dev.gaimoy.betterspeedtest.data.HistoryStore
import dev.gaimoy.betterspeedtest.data.SharedPreferenceStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 测速与已选节点跨 Activity 重建保留，仅持有 Application context。 */
class SpeedtestViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    val store = SharedPreferenceStore(appContext.getSharedPreferences("settings", Context.MODE_PRIVATE))
    val history = HistoryStore(File(appContext.filesDir, "history.json"))
    val runner = SpeedtestRunner(history)
    private val networkManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val cleared = AtomicBoolean(false)
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshLocation()
        override fun onLost(network: Network) = refreshLocation()

        private fun refreshLocation() {
            // 只更新预取缓存；正在传输的测速由用户控制，不因换网主动取消。
            if (!cleared.get()) runner.invalidateIpInfo()
        }
    }

    var pickedNode by mutableStateOf<Node?>(null)
    var trafficNode by mutableStateOf(TrafficRuntime.readOptions(appContext).node)
    var trafficSource = dev.gaimoy.betterspeedtest.core.TestSource.AUTO
    var openTrafficRequested by mutableStateOf(false)

    init {
        try {
            networkManager?.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = networkManager != null
        } catch (failure: RuntimeException) {
            // 注册失败时仍可正常测速，避免系统回调配额或权限差异阻止应用启动。
            Log.w("SpeedtestViewModel", "Network changes cannot be monitored", failure)
        }
        viewModelScope.launch {
            try {
                history.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // HistoryStore.error 保留可见错误，由界面提示；不得覆盖损坏的旧记录。
            }
        }
    }

    override fun onCleared() {
        cleared.set(true)
        if (networkCallbackRegistered) {
            try {
                networkManager?.unregisterNetworkCallback(networkCallback)
            } catch (failure: RuntimeException) {
                Log.w("SpeedtestViewModel", "Network callback cleanup failed", failure)
            }
        }
        runner.close()
        super.onCleared()
    }
}
