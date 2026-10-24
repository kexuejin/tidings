// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.agents

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 工作区页「一行都没有」时的分档（task-10）：加载态与真·空态必须分开。
 *
 * 真机反馈：切过去先看到几秒「0 个 Agent · 0 个工作区」—— 那不是空，是还没到。
 */
class BoardEmptyStageTest {

    @Test
    fun scanningWinsWhileTheSubnetScanIsRunning() {
        assertEquals(BoardEmptyStage.Scanning, boardEmptyStage(nodeRunning = true, scanning = true, peerCount = 3))
        assertEquals(BoardEmptyStage.Scanning, boardEmptyStage(nodeRunning = false, scanning = true, peerCount = 0))
    }

    @Test
    fun aStoppedNodeIsNotAnEmptyWorkspaceList() {
        assertEquals(BoardEmptyStage.NodeStarting, boardEmptyStage(nodeRunning = false, scanning = false, peerCount = 0))
    }

    /**
     * **启动失败 ≠ 还在启动**。
     *
     * 修复前这两档共用「节点还没启动 + 无限转圈」：端口被占/权限失败之后，用户永远等不到
     * 一句人话，也拿不到下一步。失败档必须有原因与出路（对应 NodeFailed）。
     *
     * 反向验证：把 boardEmptyStage 里 `!nodeRunning && hasFailure -> NodeFailed` 那一行删掉，
     * 本测试变红（会退回 NodeStarting）。
     */
    @Test
    fun aNodeWithARecordedFailureIsItsOwnStage() {
        assertEquals(
            BoardEmptyStage.NodeFailed,
            boardEmptyStage(nodeRunning = false, scanning = false, peerCount = 0, hasFailure = true),
            "有失败原因时不许再画「正在启动」的转圈",
        )
        assertEquals(
            BoardEmptyStage.NodeStarting,
            boardEmptyStage(nodeRunning = false, scanning = false, peerCount = 0, hasFailure = false),
            "没有失败原因才是真的还在启动",
        )
    }

    @Test
    fun aRunningNodeThatHasSeenNobodyIsStillLooking() {
        assertEquals(BoardEmptyStage.Discovering, boardEmptyStage(nodeRunning = true, scanning = false, peerCount = 0))
    }

    @Test
    fun onlyARunningNodeWithPeersCanSayTrulyEmpty() {
        assertEquals(BoardEmptyStage.TrulyEmpty, boardEmptyStage(nodeRunning = true, scanning = false, peerCount = 2))
    }
}
