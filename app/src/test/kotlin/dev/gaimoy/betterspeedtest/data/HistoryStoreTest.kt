package dev.gaimoy.betterspeedtest.data

import dev.gaimoy.betterspeedtest.core.SpeedtestResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun addBeforeLoadPreservesExistingHistoryAndNewSchemaDefaults() = runBlocking {
        val file = historyFile()
        file.writeText("""[{"time":1,"node":"旧节点","source":"ookla","futureField":true}]""")
        val store = HistoryStore(file)
        store.add(result(2))

        assertEquals(listOf(2L, 1L), store.records.value.map { it.time })
        assertEquals(0.0, store.records.value.last().dlAvg, 0.0)
        val reopened = HistoryStore(file)
        reopened.load()
        assertEquals(store.records.value, reopened.records.value)
        assertFalse(store.loading.value)
        assertNull(store.error.value)
    }

    @Test fun onlyTheNewestTwoHundredRecordsRemain() = runBlocking {
        val file = historyFile()
        file.writeText((205 downTo 1).joinToString(prefix = "[", postfix = "]") {
            """{"time":$it,"node":"旧节点","source":"ookla"}"""
        })
        val store = HistoryStore(file)
        store.load()
        assertEquals(200, store.records.value.size)
        store.add(result(206))
        assertEquals((206L downTo 7L).toList(), store.records.value.map { it.time })
        val reopened = HistoryStore(file)
        reopened.load()
        assertEquals(store.records.value, reopened.records.value)
    }

    @Test fun corruptHistoryIsReportedAndCannotBeSilentlyOverwrittenByAdd() = runBlocking {
        val file = historyFile()
        val corrupt = """[{"time":1,"node":"private-node","source":"ookla","ip":"203.0.113.7","dlAvg":}]"""
        file.writeText(corrupt)
        val store = HistoryStore(file)
        assertTrue(runCatching { store.load() }.isFailure)
        assertFalse(store.loading.value)
        assertNotNull(store.error.value)
        val visibleError = store.error.value.orEmpty()
        assertFalse(visibleError.contains(corrupt))
        assertFalse(visibleError.contains("203.0.113.7"))
        assertFalse(visibleError.contains("private-node"))
        assertFalse(visibleError.contains("JSON"))
        assertTrue(runCatching { store.add(result(2)) }.isFailure)
        assertEquals(corrupt, file.readText())
        assertTrue(store.records.value.isEmpty())
        assertTrue(requireNotNull(file.parentFile).listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun loadCanRetryAfterTheFileIsRepaired() = runBlocking {
        val file = historyFile()
        file.writeText("broken")
        val store = HistoryStore(file)
        assertTrue(runCatching { store.load() }.isFailure)
        file.writeText("""[{"time":4,"node":"修复后","source":"ookla"}]""")
        store.load()
        assertEquals(listOf(4L), store.records.value.map { it.time })
        assertFalse(store.loading.value)
        assertNull(store.error.value)
    }

    @Test fun reloadReadsDiskAgainAndKeepsLastGoodSnapshotWhenItFails() = runBlocking {
        val file = historyFile()
        val store = HistoryStore(file)
        store.add(result(1))
        file.writeText("broken")
        assertTrue(runCatching { store.load() }.isFailure)
        assertEquals(listOf(1L), store.records.value.map { it.time })
        assertNotNull(store.error.value)
        assertTrue(runCatching { store.add(result(2)) }.isFailure)
        assertEquals("broken", file.readText())
        file.writeText("""[{"time":4,"node":"修复后","source":"ookla"}]""")
        store.load()
        assertEquals(listOf(4L), store.records.value.map { it.time })
        assertNull(store.error.value)
    }

    @Test fun failedReplacementDoesNotPublishAnUnsavedRecordOrClearMemory() = runBlocking {
        val file = historyFile()
        val store = HistoryStore(file)
        store.add(result(1))
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "keep.txt").writeText("keep")

        assertTrue(runCatching { store.add(result(2)) }.exceptionOrNull() is IOException)
        assertEquals(listOf(1L), store.records.value.map { it.time })
        assertTrue(runCatching { store.clear() }.isFailure)
        assertEquals(listOf(1L), store.records.value.map { it.time })
        assertNotNull(store.error.value)
        assertEquals("keep", File(file, "keep.txt").readText())
        assertTrue(requireNotNull(file.parentFile).listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun concurrentAddsPreserveEveryResultAndPersistThePublishedSnapshot() = runBlocking {
        val file = historyFile()
        val store = HistoryStore(file)
        (1L..40L).map { time -> async { store.add(result(time)) } }.awaitAll()
        assertEquals((1L..40L).toSet(), store.records.value.map { it.time }.toSet())
        val reopened = HistoryStore(file)
        reopened.load()
        assertEquals(store.records.value, reopened.records.value)
    }

    @Test fun explicitClearCanRecoverACorruptHistoryFile() = runBlocking {
        val file = historyFile()
        file.writeText("broken")
        val store = HistoryStore(file)
        assertTrue(runCatching { store.load() }.isFailure)
        store.clear()
        assertEquals("[]", file.readText())
        assertFalse(store.loading.value)
        assertNull(store.error.value)
        store.add(result(3))
        assertEquals(listOf(3L), store.records.value.map { it.time })
    }

    @Test fun constructingAndReadingTheSnapshotDoNotTouchTheFilesystem() = runBlocking {
        val file = File(temporary.root, "not-created/history.json")
        val store = HistoryStore(file)
        assertTrue(store.loading.value)
        assertTrue(store.all.isEmpty())
        assertFalse(requireNotNull(file.parentFile).exists())
        store.load()
        assertFalse(store.loading.value)
        assertFalse(requireNotNull(file.parentFile).exists())
    }

    private fun historyFile() = File(temporary.root, "history.json")
    private fun result(time: Long) = SpeedtestResult(time = time, node = "节点 $time", source = "ookla")
}
