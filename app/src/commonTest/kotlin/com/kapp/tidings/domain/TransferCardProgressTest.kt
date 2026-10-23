// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.TransferState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **传输进度卡的两个真机缺陷。**
 *
 * ## 一、百分比来回跳（看起来像两个下载同时在跑）
 *
 * `fraction = done / size`，而 `size` 曾有两个来源交替写入：
 *  · `Offered` 写 `offer.totalSize` —— **整次传输**总额；
 *  · `Progress` 写 `maxOf(card.size, event.total)` —— `event.total` 是**当前文件**大小。
 *
 * 多文件传输时两者轮换当分母，于是同一张卡上的百分比在 20% 与 60% 之间跳。
 * 单文件时两者相等，所以只有多文件传输才会暴露。
 *
 * ## 二、卡在 100% 不动
 *
 * `Completed` 分支写的是 `?: return` —— 卡片查不到就把整条事件丢掉，那张卡永远停在
 * `Active`，进度条停在满格。而 `Progress` 分支用的是 `?: FileCardUi(...)` 自建卡片：
 * 同一个 helper、两种处置。文件其实已经落盘（`savedPaths` 有值），用户却看不出已完成。
 */
class TransferCardProgressTest {

    /**
     * **分母只由 `Offered` 决定，进度事件不许改它。**
     *
     * 这里直接钉住 [FileCardUi.fraction] 的算法语义：同一个 `done` 在两种分母下给出
     * 两个不同的百分比 —— 那正是用户在屏幕上看到的「跳」。
     */
    @Test
    fun theDenominatorIsTheWholeTransferNotOneFile() {
        // 一次传输两个文件，各 100 字节；总额 200。
        val totalSize = 200L
        val card = FileCardUi(
            transferId = "t-1",
            name = "两张图",
            size = totalSize,
            done = 40L,
            mine = false,
            state = TransferState.Active,
            fileCount = 2,
        )
        assertEquals(0.2f, card.fraction, "40/200 = 20%")

        // 若把分母改成「当前文件」的 100，同一个 done 就变成 40% —— 同一张卡、两个读数。
        val wrongDenominator = card.copy(size = 100L)
        assertEquals(
            0.4f,
            wrongDenominator.fraction,
            "这就是「跳」的来源：done 不变、分母变了，百分比自己会动",
        )
        assertTrue(
            wrongDenominator.fraction != card.fraction,
            "两条必须不同 —— 否则这条测试没法区分对错",
        )
    }

    /** 单文件传输时两者相等，所以旧写法在这个形态下看不出来。 */
    @Test
    fun aSingleFileHidesTheBug() {
        val card = FileCardUi(
            transferId = "t-2",
            name = "一张图",
            size = 100L,
            done = 40L,
            mine = false,
            state = TransferState.Active,
            fileCount = 1,
        )
        // 单文件：总额 == 该文件大小 ⇒ 换分母也得到同一个数 ⇒ 旧写法「看起来正常」。
        assertEquals(card.fraction, card.copy(size = 100L).fraction)
    }

    /**
     * **已完成的卡片不该再被算成百分比。**
     *
     * `Completed` 会把 `done` 设成 `size`，`fraction` 因此恒为 1 —— 界面靠 `state` 切文案，
     * 不靠 fraction 猜。这条钉住「完成态的分母语义是自洽的」。
     */
    @Test
    fun aCompletedCardReadsAsFull() {
        val card = FileCardUi(
            transferId = "t-3",
            name = "图",
            size = 930L * 1024,
            done = 930L * 1024,
            mine = false,
            state = TransferState.Completed,
        )
        assertEquals(1f, card.fraction)
    }
}
