package dev.gaimoy.betterspeedtest.ui

import dev.gaimoy.betterspeedtest.core.SpeedtestResult
import dev.gaimoy.betterspeedtest.core.RunStatus
import dev.gaimoy.betterspeedtest.core.UPLOAD_UNCONFIRMED
import org.junit.Assert.*
import org.junit.Test

class ResultShareTest {
    @Test fun exportedResultHasUnitsSourceConnectionsAndAllTraffic() {
        val text = formatResultShare(SpeedtestResult(time = 0, node = "测试节点", source = "ookla",
            ip = "192.0.2.1", dlAvg = 80.0, dlPeak = 88.0, ulAvg = 16.0, ulPeak = 24.0,
            dlBytes = 1024, ulBytes = 1024, probeBytes = 1024, pingMs = 5.0, jitter = 0.0,
            dlConns = 6, ulConns = 4), "MB/s")
        assertTrue(text.contains("Speedtest.net"))
        assertTrue(text.contains("10.00 MB/s"))
        assertTrue(text.contains("2.00 MB/s"))
        assertTrue(text.contains("连接数: 6"))
        assertTrue(text.contains("抖动: 0.0 ms"))
        assertTrue(text.contains("总流量: 3.0 KB"))
        assertTrue(text.contains("节点探测流量: 1.0 KB"))
    }
    @Test fun legacyModeIsInferredWithoutCallingUploadFailureDualSuccess() {
        val result = SpeedtestResult(node = "节点", source = "cnspeed", dlAvg = 100.0)
        assertEquals("仅下载", resultMode(result))
        assertEquals("双向", resultMode(result.copy(mode = "双向")))
    }
    @Test fun finishedRunDoesNotMarkMissingOrUnconfirmedUploadAsComplete() {
        assertEquals("未完成", directionOutcome(RunStatus.DONE, false, 0.0, true, "上传失败或节点拒绝请求"))
        assertEquals("未完成", directionOutcome(RunStatus.DONE, false, 0.0, false, "当前节点未提供上传接口"))
        assertEquals("未确认", directionOutcome(RunStatus.DONE, false, 50.0, true, UPLOAD_UNCONFIRMED))
        assertEquals("完成", directionOutcome(RunStatus.DONE, false, 50.0, true, ""))
        assertEquals("已停止", directionOutcome(RunStatus.CANCELLED, false, null, true, ""))
    }

}
