package dev.gaimoy.betterspeedtest.core

import java.io.IOException
import kotlinx.coroutines.Job

/** Shared across both directions and all workers; reservations bound application payload reads/writes. */
internal class TrafficBudget(val limit: Long, private val rateMbps: Double) {
    // Okio reads in 8 KiB segments; pacing larger reservations would under-utilize a configured cap.
    val blockSize = if (rateMbps == 0.0) 64 * 1024 else 8 * 1024
    private var reserved = 0L
    private var download = 0L
    private var upload = 0L
    private var nextSlot = System.nanoTime()

    @Synchronized fun totals(): Pair<Long, Long> = download to upload
    @Synchronized fun exhausted(): Boolean = download + upload >= limit

    /** Called only on I/O threads. A cancelled request must refund its unconsumed reservation. */
    fun acquire(maxBytes: Int, job: Job): Int {
        var count: Int
        var deadline: Long
        while (true) {
            checkJob(job)
            synchronized(this) {
                if (download + upload >= limit) return 0
                count = minOf(maxBytes.toLong(), limit - download - upload - reserved).toInt()
                if (count > 0) {
                    reserved += count
                    val now = System.nanoTime()
                    deadline = if (rateMbps == 0.0) now else maxOf(now, nextSlot) +
                        (count * 8_000.0 / rateMbps).toLong()
                    nextSlot = deadline
                } else deadline = 0L
            }
            if (count > 0) break
            Thread.sleep(10)
        }
        try {
            while (true) {
                checkJob(job)
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return count
                Thread.sleep(minOf(25L, (remaining + 999_999) / 1_000_000))
            }
        } catch (e: Exception) {
            finish(count, 0, false)
            throw e
        }
    }

    @Synchronized fun finish(reservation: Int, actual: Int, isUpload: Boolean) {
        require(actual in 0..reservation)
        reserved -= reservation
        check(reserved >= 0)
        if (isUpload) upload += actual else download += actual
    }

    private fun checkJob(job: Job) {
        // IOException is intentional: OkHttp's callback executor does not catch arbitrary runtime exceptions.
        if (!job.isActive) throw IOException("传输已停止")
    }
}
