package dev.gaimoy.betterspeedtest.core

/** Cumulative byte measurements: averages are time weighted, not averages of overlapping windows. */
internal class TransferMeter(private val warmupSec: Double, private val windowSec: Double = 1.0) {
    private val window = ArrayDeque<Pair<Double, Long>>()
    private var baseline: Pair<Double, Long>? = if (warmupSec <= 0.0) 0.0 to 0L else null
    var averageBps = 0.0
        private set
    var peakBps = 0.0
        private set

    init { window.addLast(0.0 to 0L) }

    fun record(seconds: Double, bytes: Long): Double {
        require(seconds >= window.last().first && bytes >= window.last().second)
        window.addLast(seconds to bytes)
        val cutoff = seconds - windowSec
        while (window.size > 2 && window.elementAt(1).first <= cutoff) window.removeFirst()
        val a = window.first()
        val b = window.elementAtOrNull(1)
        val start = if (a.first < cutoff && b != null && b.first > a.first) {
            cutoff to (a.second + (b.second - a.second) * (cutoff - a.first) / (b.first - a.first))
        } else a.first to a.second.toDouble()
        val bps = if (seconds > start.first) (bytes - start.second) * 8.0 / (seconds - start.first) else 0.0
        if (baseline == null && seconds >= warmupSec) {
            // Keep the rolling rate on the gauge, but start effective measurement after this point.
            baseline = seconds to bytes
            window.clear()
            window.addLast(seconds to bytes)
            return bps
        }
        val base = baseline
        if (base != null && seconds > base.first) {
            averageBps = (bytes - base.second) * 8.0 / (seconds - base.first)
            if (seconds - base.first >= windowSec) peakBps = maxOf(peakBps, bps)
        }
        return bps
    }

    fun finish(seconds: Double, bytes: Long): Pair<Double, Double> {
        if (baseline == null || seconds <= baseline!!.first) {
            averageBps = if (seconds > 0) bytes * 8.0 / seconds else 0.0
        }
        return averageBps to maxOf(peakBps, averageBps)
    }
}
