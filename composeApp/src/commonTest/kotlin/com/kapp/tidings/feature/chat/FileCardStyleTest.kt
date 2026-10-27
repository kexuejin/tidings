// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.TransferState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文件消息卡片的**行为判据**（task-71）。
 *
 * 卡片长什么样要靠截图门禁，但「哪个按钮该出现」「路径怎么显示」是**判据**，
 * 不该只活在布局代码里 —— 这里把两件事钉住：
 *
 *  1. **长路径从中间省略**：完整路径是应用内部实现细节，折行会压住卡片；
 *     但「已存到」这个事实陈述不能丢，所以省略必须**显式**（`…`）且保留文件名那一段；
 *  2. **进度态不出「接收」**：正在接收时再给一个「接收」按钮，用户会以为点了能重来。
 */
class FileCardStyleTest {

    private fun card(state: TransferState, mine: Boolean = false) = FileCardUi(
        transferId = "t1",
        name = "q1.bin",
        size = 3_000_000L,
        mine = mine,
        state = state,
    )

    @Test
    fun aLongImplementationPathIsElidedButKeepsItsTail() {
        val shown = elidePathTail("/data/user/0/com.kapp.tidings/files/relay/downloads/q1.bin")
        assertTrue(shown.startsWith("…"), "省略必须显式标记，不能看起来像完整路径：$shown")
        assertTrue(shown.endsWith("q1.bin"), "文件名那一段必须留着，它是用户唯一要看的：$shown")
        assertTrue(shown.length <= 34, "上限就是卡片里那一行的宽度：$shown")
        assertTrue(shown.contains("/"), "至少留一段目录，否则用户不知道它在哪个子目录：$shown")
    }

    @Test
    fun aShortPathIsShownAsIs() {
        assertEquals("q1.bin", elidePathTail("q1.bin"), "短路径不该被加上省略号")
    }

    @Test
    fun theElidedTailStartsAtAPathBoundaryWhenItCan() {
        assertTrue(
            elidePathTail("/data/user/0/com.kapp.tidings/files/relay/downloads/q1.bin").startsWith("…/"),
            "优先从 / 起，读起来才像一段路径",
        )
    }

    @Test
    fun anAutoAcceptedImageShowsNoAcceptButton() {
        // 2026-10-27 模拟器实测：小图在协议层已被自动接收（字节落地、sha256 一致），
        // 界面却还画着「接收 / 拒绝」—— 因为判据没看 autoAccepted。
        // 这条钉住的是「结论由 core 给、界面只管照做」：卡片带着 autoAccepted 时不出按钮。
        val card = FileCardUi(
            transferId = "t-auto",
            name = "ios1m.png",
            size = 1_048_576L,
            mine = false,
            state = TransferState.Offered,
            autoAccepted = true,
        )
        assertFalse(
            fileCardShowsAccept(card),
            "已自动接收的图片不该再让用户点「接收」",
        )
    }

    @Test
    fun acceptIsOnlyOfferedForAnInboundOffer() {
        assertTrue(fileCardShowsAccept(card(TransferState.Offered, mine = false)))
        assertFalse(
            fileCardShowsAccept(card(TransferState.Offered, mine = true)),
            "自己发出去的出价在等对方点，本端不该给「接收」",
        )
    }

    @Test
    fun anInProgressTransferNeverOffersAccept() {
        for (state in listOf(
            TransferState.Active,
            TransferState.Completed,
            TransferState.Failed,
            TransferState.Declined,
        )) {
            assertFalse(fileCardShowsAccept(card(state, mine = false)), "$state 不许出现「接收」按钮")
        }
    }
}
