package dev.gaimoy.betterspeedtest.data

import dev.gaimoy.betterspeedtest.core.SpeedtestResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON 历史记录。文件操作与内存发布在同一把锁内完成，写入失败不会伪装成功。 */
class HistoryStore(file: File) {
    private val file = file.absoluteFile
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(SpeedtestResult.serializer())
    private val mutex = Mutex()
    private var loaded = false

    private val mutableRecords = MutableStateFlow<List<SpeedtestResult>>(emptyList())
    val records: StateFlow<List<SpeedtestResult>> = mutableRecords.asStateFlow()

    private val mutableLoading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    /** 兼容旧调用方的内存快照；不会触发磁盘读取。新界面应收集 [records]。 */
    val all: List<SpeedtestResult>
        get() = records.value.toList()

    suspend fun load() = operation("历史记录读取失败，原文件已保留", showLoading = true) {
        loadLocked(force = true)
    }

    suspend fun add(result: SpeedtestResult) = operation("历史记录保存失败") {
        // 读取失败时不能把原文件覆盖成只有一条记录的新文件。
        loadLocked()
        val next = (listOf(result) + mutableRecords.value).take(MAX_RECORDS)
        persistLocked(next)
        mutableRecords.value = next
    }

    /** 用户确认清空后调用；即使旧文件损坏也能清空，失败时保留当前内存快照。 */
    suspend fun clear() = operation("历史记录清空失败") {
        persistLocked(emptyList())
        mutableRecords.value = emptyList()
        loaded = true
    }

    private suspend fun operation(message: String, showLoading: Boolean = false, action: () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded || showLoading) mutableLoading.value = true
            try {
                action()
                mutableError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // 解析异常可能包含原始 JSON、IP 或文件路径；界面只展示可操作的简短说明。
                mutableError.value = when (failure) {
                    is SerializationException -> "历史记录格式异常，原文件已保留。可重试读取，或确认后清空历史记录。"
                    is IOException -> "$message。请检查设备存储空间后重试。"
                    else -> "$message。请重试。"
                }
                throw failure
            } finally {
                mutableLoading.value = false
            }
        }
    }

    private fun loadLocked(force: Boolean = false) {
        if (loaded && !force) return
        // 重读失败仍展示上一份有效快照，但下一次追加必须重新读取，不能覆盖损坏文件。
        loaded = false
        val contents = try {
            Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            null
        }
        val stored = contents?.let { json.decodeFromString(serializer, it).take(MAX_RECORDS) }.orEmpty()
        mutableRecords.value = stored
        loaded = true
    }

    private fun persistLocked(next: List<SpeedtestResult>) {
        val parent = file.parentFile ?: throw IOException("历史记录目录无效")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建历史记录目录")
        // 同目录临时文件保证 rename 不跨文件系统，fsync 后才替换已有记录。
        val pending = File.createTempFile(".history-", ".tmp", parent)
        try {
            FileOutputStream(pending).use { output ->
                output.write(json.encodeToString(serializer, next).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                pending.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            pending.delete()
        }
    }

    private companion object {
        const val MAX_RECORDS = 200
    }
}
