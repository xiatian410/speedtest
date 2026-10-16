package dev.gaimoy.betterspeedtest.core

import org.junit.Assert.*
import org.junit.Test

class TransferMeterTest {
    @Test fun stableTransferDoesNotDropToZeroAtWarmupBoundary() {
        val meter = TransferMeter(2.0)
        assertEquals(8000.0, meter.record(1.0, 1000), 0.001)
        assertEquals(8000.0, meter.record(2.01, 2010), 0.001)
        assertEquals(0.0, meter.averageBps, 0.001)
        assertEquals(8000.0, meter.record(2.21, 2210), 0.001)
        assertEquals(8000.0, meter.averageBps, 0.001)
    }

    @Test fun boundaryDisplayDoesNotAddWarmupBytesToTheAverage() {
        val meter = TransferMeter(2.0)
        meter.record(1.0, 1_000_000)
        assertTrue(meter.record(2.01, 2_010_000) > 0)
        assertEquals(0.0, meter.averageBps, 0.001)
        meter.record(2.51, 2_010_500)
        assertEquals(8000.0, meter.finish(2.51, 2_010_500).first, 0.001)
    }

    @Test fun warmupBurstDoesNotPolluteMeasuredSpeed() {
        val meter = TransferMeter(2.0)
        meter.record(1.0, 1_000_000)
        meter.record(2.0, 2_000_000)
        meter.record(2.5, 2_000_500)
        assertEquals(8000.0, meter.record(3.0, 2_001_000), 0.001)
        val (average, peak) = meter.finish(3.0, 2_001_000)
        assertEquals(8000.0, average, 0.001)
        assertEquals(8000.0, peak, 0.001)
    }

    @Test fun averageUsesElapsedTimeWithIrregularTicks() {
        val meter = TransferMeter(0.0)
        meter.record(0.1, 100)
        meter.record(0.9, 100)
        meter.record(1.0, 1000)
        assertEquals(8000.0, meter.finish(1.0, 1000).first, 0.001)
    }

    @Test fun liveWindowIsOneSecondRegardlessOfSamplingInterval() {
        val meter = TransferMeter(0.0)
        meter.record(0.5, 500)
        meter.record(1.0, 1000)
        meter.record(1.5, 1000)
        assertEquals(0.0, meter.record(2.5, 1000), 0.001)
    }

    @Test fun optionsAreBoundedAndHaveConsistentDefaults() {
        val defaults = TestOptions()
        assertEquals(6, defaults.threadsDl)
        assertEquals(16, defaults.threadsDlMax)
        assertEquals(4, defaults.threadsUl)
        assertEquals(8, defaults.threadsUlMax)
        assertEquals(2, defaults.warmupSec)
        val normalized = TestOptions(durationSec = -1, warmupSec = 80, threadsDl = 0, threadsDlMax = -1).normalized()
        assertTrue(normalized.warmupSec < normalized.durationSec)
        assertTrue(normalized.threadsDl >= 1)
        assertTrue(normalized.threadsDlMax >= normalized.threadsDl)
        val oneConnection = TestOptions(multi = 4, threadsDl = 1, threadsDlMax = 1).normalized()
        assertEquals(1, oneConnection.multi)
    }
}
