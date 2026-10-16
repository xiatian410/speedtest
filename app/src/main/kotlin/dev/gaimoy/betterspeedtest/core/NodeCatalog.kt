package dev.gaimoy.betterspeedtest.core

import kotlinx.coroutines.CancellationException

/**
 * 节点目录:按源取节点/构建测速池的单一事实源。
 * 自定义源功能已移除(后期重新规划);当前只有全球网测和 Speedtest.net。
 */
object NodeCatalog {

    /** 节点选择页列表:当前源对应的全部可选节点。 */
    suspend fun nodes(source: TestSource): List<Node> = when (source) {
        TestSource.CNSPEED -> Cnspeed.fetchServerlist()
        TestSource.OOKLA -> Ookla.nodes()
        TestSource.AUTO -> emptyList() // 自动模式全权择优,不提供节点列表
    }

    /** 测速引擎候选池:全球网测由 runCnspeed 直连,这里只服务 Ookla 路径。 */
    suspend fun pool(
        src: TestSource,
        onNotice: (String) -> Unit = {},
    ): List<Node> {
        return try {
            when (src) {
                TestSource.CNSPEED -> Cnspeed.fetchServerlist()
                else -> Ookla.nodes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onNotice("Speedtest.net 源获取失败: ${e.message}")
            emptyList()
        }
    }
}
