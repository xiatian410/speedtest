package dev.gaimoy.betterspeedtest.data

import android.content.SharedPreferences
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferenceStoreTest {
    @Test fun writesEverySupportedTypeUsingAnOffThreadCommit() = runBlocking {
        val prefs = FakePreferences()
        val store = SharedPreferenceStore(prefs)
        val callerThread = Thread.currentThread()
        verifyRoundTrip(store, PreferenceKey("boolean", false), true)
        verifyRoundTrip(store, PreferenceKey("int", 0), 7)
        verifyRoundTrip(store, PreferenceKey("long", 0L), 9L)
        verifyRoundTrip(store, PreferenceKey("float", 0f), 2.5f)
        verifyRoundTrip(store, PreferenceKey("string", ""), "test")
        verifyRoundTrip(store, PreferenceKey("set", emptySet<String>()), setOf("a", "b"))
        assertEquals(6, prefs.commitCount)
        assertNotEquals(callerThread, prefs.commitThread)
    }

    @Test fun commitFailuresAndExceptionsAreReportedForEveryMutation() = runBlocking {
        val prefs = FakePreferences()
        val store = SharedPreferenceStore(prefs)
        val key = PreferenceKey("value", 1)
        prefs.commitSucceeds = false
        assertTrue(store.write(key, 2) is PreferenceWriteResult.Failure)
        assertTrue(store.delete(key) is PreferenceWriteResult.Failure)
        assertTrue(store.clear() is PreferenceWriteResult.Failure)
        prefs.commitException = IOException("disk full")
        val failure = store.write(key, 3) as PreferenceWriteResult.Failure
        assertEquals("disk full", failure.cause.message)
    }

    @Test fun malformedStoredTypesFallBackToDefaults() {
        val prefs = FakePreferences(mutableMapOf("number" to "wrong", "bool" to 7, "set" to setOf(1)))
        val store = SharedPreferenceStore(prefs)
        assertEquals(5, store.read(PreferenceKey("number", 5)))
        assertEquals(false, store.read(PreferenceKey("bool", false)))
        assertEquals(setOf("default"), store.read(PreferenceKey("set", setOf("default"))))
    }

    @Test fun observesInitialChangesAndClearThenUnregistersOnCancellation() = runBlocking {
        val prefs = FakePreferences(mutableMapOf("number" to 2))
        val store = SharedPreferenceStore(prefs)
        val key = PreferenceKey("number", 1)
        val values = Channel<Int>(Channel.UNLIMITED)
        val collector = launch { store.observe(key).collect { values.send(it) } }
        try {
            assertEquals(2, withTimeout(2_000) { values.receive() })
            assertEquals("register", prefs.events.first())
            store.write(key, 3)
            assertEquals(3, withTimeout(2_000) { values.receive() })
            store.clear() // Android R+ reports a null key for a clear.
            assertEquals(1, withTimeout(2_000) { values.receive() })
        } finally {
            collector.cancelAndJoin()
        }
        assertEquals(0, prefs.listenerCount)
    }

    @Test fun returnedStringSetsDoNotMutatePreferences() = runBlocking {
        val original = linkedSetOf("a", "b")
        val prefs = FakePreferences(mutableMapOf("set" to original))
        val store = SharedPreferenceStore(prefs)
        val read = store.read(PreferenceKey("set", emptySet<String>()))
        (read as MutableSet<String>).clear()
        assertEquals(setOf("a", "b"), store.read(PreferenceKey("set", emptySet<String>())))
        val input = linkedSetOf("x", "y")
        store.write(PreferenceKey("set", emptySet<String>()), input)
        input.clear()
        assertEquals(setOf("x", "y"), store.read(PreferenceKey("set", emptySet<String>())))
    }

    @Test fun deletePersistsAndMobileDefaultsStayUnchanged() = runBlocking {
        val prefs = FakePreferences(mutableMapOf("value" to 2, "custom_sources_json" to "old profile"))
        val store = SharedPreferenceStore(prefs)
        assertEquals(PreferenceWriteResult.Success, store.delete(PreferenceKey("value", 1)))
        assertFalse(prefs.contains("value"))
        assertEquals("old profile", prefs.getString("custom_sources_json", null))
        assertEquals(listOf(6, 16, 4, 8, 2), listOf(
            Settings.ThreadsDl.defaultValue, Settings.ThreadsDlMax.defaultValue,
            Settings.ThreadsUl.defaultValue, Settings.ThreadsUlMax.defaultValue, Settings.WarmupSec.defaultValue,
        ))
    }

    @Test fun switchingDirectionUsesOneCommitAndEveryCallbackSeesBothFinalValues() = runBlocking {
        val prefs = FakePreferences(mutableMapOf(Settings.DoDownload.name to true, Settings.DoUpload.name to false))
        val snapshots = mutableListOf<Pair<Boolean, Boolean>>()
        prefs.registerOnSharedPreferenceChangeListener { preferences, _ ->
            snapshots += preferences.getBoolean(Settings.DoDownload.name, true) to
                preferences.getBoolean(Settings.DoUpload.name, true)
        }
        val store = SharedPreferenceStore(prefs)
        assertEquals(PreferenceWriteResult.Success, store.writeTestMode(download = false, upload = true))
        assertEquals(1, prefs.commitCount)
        assertEquals(listOf(false to true, false to true), snapshots)
        assertTrue(store.writeTestMode(download = false, upload = false) is PreferenceWriteResult.Failure)
        assertEquals(1, prefs.commitCount)
        assertEquals(false, store.read(Settings.DoDownload))
        assertEquals(true, store.read(Settings.DoUpload))
    }

    @Test fun modeReadsOneSnapshotAndUsesDefaultsForMissingOrWrongTypes() {
        val prefs = FakePreferences(mutableMapOf(Settings.DoDownload.name to false, Settings.DoUpload.name to "wrong"))
        val store = SharedPreferenceStore(prefs)
        assertEquals(false to true, store.readTestMode())
        assertEquals(listOf("all"), prefs.events)
        assertEquals(true to true, SharedPreferenceStore(FakePreferences()).readTestMode())
    }

    @Test fun reducingDurationUpdatesWarmupInTheSameCommit() = runBlocking {
        val prefs = FakePreferences(mutableMapOf(Settings.DurationSec.name to 15, Settings.WarmupSec.name to 5))
        val snapshots = mutableListOf<Pair<Int, Int>>()
        prefs.registerOnSharedPreferenceChangeListener { preferences, _ ->
            snapshots += preferences.getInt(Settings.DurationSec.name, 15) to preferences.getInt(Settings.WarmupSec.name, 2)
        }
        val store = SharedPreferenceStore(prefs)
        assertEquals(PreferenceWriteResult.Success, store.writeTestDuration(5))
        assertEquals(1, prefs.commitCount)
        assertEquals(listOf(5 to 3, 5 to 3), snapshots)
        assertEquals(5 to 3, store.readTestTiming())
    }

    @Test fun legacyTimingIsReadConsistentlyWithoutRewritingTheOldPreferences() = runBlocking {
        val prefs = FakePreferences(mutableMapOf(Settings.DurationSec.name to 5, Settings.WarmupSec.name to 5))
        val store = SharedPreferenceStore(prefs)
        assertEquals(5 to 3, store.readTestTiming())
        assertEquals(5, prefs.getInt(Settings.WarmupSec.name, 0))
        assertEquals(0, prefs.commitCount)
        assertEquals(3, Settings.effectiveWarmup(5, 5))
        assertEquals(0, Settings.effectiveWarmup(5, -1))
        assertEquals(2, Settings.effectiveWarmup(3, 5))
        store.writeTestWarmup(5)
        assertEquals(3, prefs.getInt(Settings.WarmupSec.name, 0))
    }

    @Test fun timingWritesReportDiskFailure() = runBlocking {
        val prefs = FakePreferences().apply { commitSucceeds = false }
        val store = SharedPreferenceStore(prefs)
        assertTrue(store.writeTestDuration(5) is PreferenceWriteResult.Failure)
        assertTrue(store.writeTestWarmup(3) is PreferenceWriteResult.Failure)
    }

    private suspend fun <T : Any> verifyRoundTrip(store: SharedPreferenceStore, key: PreferenceKey<T>, value: T) {
        assertEquals(PreferenceWriteResult.Success, store.write(key, value))
        assertEquals(value, store.read(key))
    }
}

private class FakePreferences(private val values: MutableMap<String, Any> = mutableMapOf()) : SharedPreferences {
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    val events = mutableListOf<String>()
    var commitSucceeds = true
    var commitException: IOException? = null
    var commitCount = 0
    var commitThread: Thread? = null
    val listenerCount get() = synchronized(this) { listeners.size }

    override fun getAll(): MutableMap<String, *> = synchronized(this) { events += "all"; values.toMutableMap() }
    override fun contains(key: String): Boolean = synchronized(this) { values.containsKey(key) }
    override fun getBoolean(key: String, defValue: Boolean) = read(key, defValue) as Boolean
    override fun getInt(key: String, defValue: Int) = read(key, defValue) as Int
    override fun getLong(key: String, defValue: Long) = read(key, defValue) as Long
    override fun getFloat(key: String, defValue: Float) = read(key, defValue) as Float
    override fun getString(key: String, defValue: String?) = read(key, defValue) as String?
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        read(key, defValues) as MutableSet<String>?

    private fun read(key: String, default: Any?): Any? = synchronized(this) {
        events += "read"
        values[key] ?: default
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(this) { events += "register"; listeners += listener }
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(this) { listeners -= listener }
    }
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clearing = false
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toMutableSet() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clearing = true }
        override fun apply() { error("Store must use commit to report persistence errors") }
        override fun commit(): Boolean {
            val callbacks = synchronized(this@FakePreferences) {
                commitThread = Thread.currentThread()
                commitCount++
                commitException?.let { throw it }
                if (clearing) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                listeners.toList()
            }
            callbacks.forEach { listener ->
                if (clearing) listener.onSharedPreferenceChanged(this@FakePreferences, null)
                changes.keys.forEach { listener.onSharedPreferenceChanged(this@FakePreferences, it) }
            }
            return commitSucceeds
        }
    }
}
