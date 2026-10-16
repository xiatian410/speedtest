package dev.gaimoy.betterspeedtest.core

import org.junit.Assert.*
import org.junit.Test

class SpeedFormatTest {
    @Test fun everyUnitIncludesItsLabelAndConvertsBitsCorrectly() {
        assertEquals("80.0 Mbps", SpeedFormat.format(80.0, "Mbps"))
        assertEquals("10.00 MB/s", SpeedFormat.format(80.0, "MB/s"))
        assertEquals("0.08 Gbps", SpeedFormat.format(80.0, "Gbps"))
    }
    @Test fun autoKeepsSmallRatesInBitsAndSwitchesAtAGigabit() {
        assertEquals("0.50 Mbps", SpeedFormat.format(0.5))
        assertEquals("999 Mbps", SpeedFormat.format(999.0))
        assertEquals("1.00 Gbps", SpeedFormat.format(1000.0))
        assertEquals("0.00 Mbps", SpeedFormat.format(0.0))
    }
    @Test fun splitAndFullTextAgreeForAllSupportedUnits() {
        for (unit in listOf("auto", "Mbps", "MB/s", "Gbps", "unknown")) {
            for (value in listOf(0.0, 0.2, 12.0, 800.0, 1200.0)) {
                val (number, label) = SpeedFormat.split(value, unit)
                assertEquals("$number $label", SpeedFormat.format(value, unit))
                assertEquals(label, SpeedFormat.unitLabel(unit, value))
            }
        }
    }
    @Test fun invalidSamplesNeverLeakNaNOrInfinityToTheUi() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("—", SpeedFormat.format(value))
        }
    }
}
