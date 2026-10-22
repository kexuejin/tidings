// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 生产入口的**接线守卫**：三个乐观插入的入口必须真的接到那条路上。
 *
 * ## 为什么需要它（独立验证者发现的缺口）
 *
 * D 轨的闸门测试（群聊气泡、文件卡、群扇出）都直接打 helper —— 它们证明「那条路本身是对的」，
 * 但证明不了「生产入口走的是那条路」。有人把 `handleSendToGroup` 改回直调 `rt.sendGroupText`，
 * 三条闸门测试**仍然全绿**，而用户报告的缺陷（发送后界面不出现发送中）原地回归。
 *
 * 这是本仓点名的"测试绿不等于测到了"。所以这里按源码文本钉住**调用点**：入口函数体里必须
 * 出现乐观插入的调用。剥掉注释再匹配，避免被解释这件事的注释绊倒（本仓踩过一次）。
 *
 * 与 [RelayControllerGroupSendOptimisticTest] / [RelayControllerFileCardOptimisticTest] /
 * [GroupFanOutConcurrencyTest] 是互补关系：那些守「路上对不对」，这条守「入口有没有上路」。
 */
class OptimisticWiringGuardTest {

    private val groupsPath =
        "composeApp/src/commonMain/kotlin/app/relay/app/RelayControllerGroupsComponent.kt"
    private val transfersPath =
        "composeApp/src/commonMain/kotlin/app/relay/app/RelayControllerTransfersComponent.kt"
    private val outboundPath =
        "core/src/commonMain/kotlin/app/relay/core/session/NodeOutbound.kt"

    @Test
    fun handleSendToGroupRoutesThroughTheOptimisticHelper() {
        val body = GuardSourceScan.bracedBlockAfter(live(groupsPath), "fun handleSendToGroup(")
        val optimisticAt = body.indexOf("sendGroupTextOptimistic(")
        assertTrue(
            optimisticAt >= 0,
            "handleSendToGroup 必须经过 sendGroupTextOptimistic：直调 rt.sendGroupText 会让群聊气泡不出现，用户报告的缺陷原地回归",
        )
        val directAt = body.indexOf("rt.sendGroupText(")
        assertTrue(
            directAt < 0 || (optimisticAt >= 0 && optimisticAt < directAt),
            "rt.sendGroupText 只允许作为 sendGroupTextOptimistic 的入参出现；独立直调就是绕开乐观插入",
        )
    }

    @Test
    fun handleOfferFilesRoutesThroughTheOptimisticHelper() {
        val body = declarationSlice(live(transfersPath), "suspend fun handleOfferFiles(")
        val optimisticAt = body.indexOf("handleOfferFilesWith(")
        assertTrue(
            optimisticAt >= 0,
            "handleOfferFiles 必须经过 handleOfferFilesWith：直调 rt.offerFiles 会让文件卡片不出现",
        )
        val directAt = body.indexOf("rt.offerFiles(")
        assertTrue(
            directAt < 0 || (optimisticAt >= 0 && optimisticAt < directAt),
            "rt.offerFiles 只允许作为 handleOfferFilesWith 的入参出现；独立直调就是绕开乐观插入",
        )
    }

    @Test
    fun sendGroupTextRoutesThroughTheConcurrentFanOut() {
        val body = GuardSourceScan.bracedBlockAfter(live(outboundPath), "suspend fun sendGroupText(")
        assertTrue(
            body.contains("sendGroupTextFanOut("),
            "sendGroupText 必须经过 sendGroupTextFanOut：自己写回串行 for 会让离线成员逐个叠加等待",
        )
        assertTrue(
            !body.contains("for (member in"),
            "扇出不能再自己写回串行 for（并发与总预算都在 sendGroupTextFanOut 里）",
        )
    }

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * 表达式体（`fun f(...) = ...`）没有函数花括号，[GuardSourceScan.bracedBlockAfter] 会切到 lambda
     * 的 `{`。这类入口切到下一个空行之前即可（入口本身是单表达式，内部没有空行）。
     */
    private fun declarationSlice(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "源码里找不到入口：$marker")
        val after = source.indexOf("\n\n", start)
        return source.substring(start, if (after < 0) source.length else after)
    }
}
