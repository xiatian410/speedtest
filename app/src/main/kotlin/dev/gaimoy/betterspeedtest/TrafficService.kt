package dev.gaimoy.betterspeedtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.gaimoy.betterspeedtest.core.TrafficOptions
import dev.gaimoy.betterspeedtest.core.TrafficRunner
import dev.gaimoy.betterspeedtest.core.TrafficState
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

object TrafficRuntime {
    val runner = TrafficRunner()
    private val json = Json { ignoreUnknownKeys = true }
    fun readOptions(context: Context): TrafficOptions = runCatching {
        json.decodeFromString<TrafficOptions>(context.getSharedPreferences("traffic", Context.MODE_PRIVATE).getString("options", "")!!)
    }.getOrDefault(TrafficOptions())

    fun start(context: Context, options: TrafficOptions): String? {
        options.validate()?.let { return it }
        val id = runner.reserve(options) ?: return "打流任务正在运行或停止中"
        return try {
            ContextCompat.startForegroundService(context, Intent(context, TrafficService::class.java).putExtra("session", id))
            context.getSharedPreferences("traffic", Context.MODE_PRIVATE).edit().putString("options", json.encodeToString(options)).apply()
            null
        } catch (_: RuntimeException) {
            runner.failStart(id, "后台服务启动失败，请返回前台重试")
            "后台服务启动失败，请返回前台重试"
        }
    }
}

class TrafficService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var session = 0L
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val runner get() = TrafficRuntime.runner

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "打流运行状态", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val id = intent?.getLongExtra("session", 0) ?: 0
        if (intent?.action == STOP) {
            runner.stop(id)
            if (!runner.state.value.busy) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        session = id
        try {
            val notification = notification(runner.state.value)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
            if (id != runner.state.value.session || !runner.state.value.busy) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId)
                return START_NOT_STICKY
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:traffic").apply {
                acquire(((runner.state.value.options?.durationSec ?: 60) + 30) * 1000L)
            }
            runner.launchReserved(id)
            observer?.cancel()
            observer = scope.launch {
                var lastNotification = 0L
                runner.state.collect { state ->
                    if (state.session != id) return@collect
                    if (!state.busy) {
                        wakeLock?.let { if (it.isHeld) it.release() }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Notification actions are additional start commands, so the original startId is stale.
                        stopSelfResult(latestStartId)
                    } else if (android.os.SystemClock.elapsedRealtime() - lastNotification >= 1000) {
                        // Statistics render at 4 Hz in-app, while notifications update at most once a second.
                        lastNotification = android.os.SystemClock.elapsedRealtime()
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
                    }
                }
            }
        } catch (_: RuntimeException) {
            runner.failStart(id, "后台服务不可用，请返回前台重试")
            runner.stop(id, "后台服务不可用，已停止")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runner.stop(session, "已达到系统后台运行时限")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runner.stop(session, "后台服务已停止")
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: TrafficState): Notification {
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_traffic", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 21, Intent(this, TrafficService::class.java).setAction(STOP)
            .putExtra("session", state.session), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("打流 · ${state.message}")
            .setContentText("↓ %.1f  ↑ %.1f Mbps · %.1f MiB · %d 秒".format(state.downloadMbps, state.uploadMbps, state.totalBytes / 1048576.0, state.elapsedSec.toInt()))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }

    companion object {
        private const val CHANNEL = "traffic"
        private const val NOTIFICATION = 20
        private const val STOP = "dev.gaimoy.betterspeedtest.STOP_TRAFFIC"
    }
}
