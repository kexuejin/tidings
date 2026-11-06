// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **源码级守卫**：「哪些帧属于文件数据面」这个判据只许有一份，且闸门必须复用它。
 *
 * ## 为什么纯函数测试不够（真机缺陷 B 的教训）
 *
 * [FileChannelFrameGateTest] 能证明 `FileChannelRouter.isChannelFrame` 认得 `TDCA`，
 * 却证明不了**闸门用的是它**。而缺陷 B 恰恰是"函数对了、闸门没接上"：
 * `SecureChannelTransport` 的默认参数自己写了一遍 `{ FileChunkFrame.isChunkFrame(it) }`，
 * 于是收端的窗口确认在进路由器之前就被判成"不是数据面帧"，按 Envelope 解又解不出来，
 * 整帧静默丢弃。
 *
 * 这个判据住在 Composable 之外的普通类里，行为测试其实也能覆盖 —— 但要覆盖它得拉起
 * 真正的 luciola 信道（native 依赖）。这一层守卫用**源码事实**替代：抄第二份就红。
 * 与 `SessionDividerUsesTheSameSourceTest` 同一条纪律（那份守的是"渲染路径不许另取一套"）。
 */
class DataplaneGateUsesTheRouterPredicateTest {

    private fun transportSource(name: String): String {
        val candidates = listOf(
            // 测试进程 cwd 是模块目录（core/）
            File("src/commonMain/kotlin/com/kapp/tenon/core/transport/$name"),
            File("core/src/commonMain/kotlin/com/kapp/tenon/core/transport/$name"),
        )
        val hit = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name，candidates=" + candidates.map { it.absolutePath })
        return hit.readText()
    }

    @Test
    fun theGateDelegatesToTheRouterPredicate() {
        val src = transportSource("SecureChannelTransport.kt")
        assertTrue(
            src.contains("isBinaryFrame: (ByteArray) -> Boolean = FileChannelRouter::isChannelFrame"),
            "闸门的默认判据必须是 FileChannelRouter::isChannelFrame —— 抄一份就会漂移（缺陷 B）",
        )
    }

    @Test
    fun theGateDoesNotInlineItsOwnMagicCheck() {
        val src = transportSource("SecureChannelTransport.kt")
        // 逐字钉住那条回归：默认判据里再出现 `{ FileChunkFrame.isChunkFrame(it) }` 就红。
        assertFalse(
            src.contains("= { FileChunkFrame.isChunkFrame(it) }"),
            "闸门不许内联自己的魔数判据：只认 TDCH 会让 TDCA 在进路由器之前就消失",
        )
    }

    @Test
    fun onlyTheRouterKnowsTheAckMagic() {
        val dir = listOf(
            File("src/commonMain/kotlin/com/kapp/tenon/core/transport"),
            File("core/src/commonMain/kotlin/com/kapp/tenon/core/transport"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到 transport 源码目录")

        val holders = dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }
            .orEmpty()
            .filter { it.name != "FileChunkAck.kt" }
            .filter { it.readText().contains("FileChunkAck.isAck(") }
            .map { it.name }

        assertEquals(
            listOf("FileChannelRouter.kt"),
            holders,
            "`FileChunkAck.isAck` 只许出现在路由器的判据里；别处再写一遍就是第二份事实源",
        )
    }
}
