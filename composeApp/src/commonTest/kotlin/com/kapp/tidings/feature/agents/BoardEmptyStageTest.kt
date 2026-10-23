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

    @Test
    fun aRunningNodeThatHasSeenNobodyIsStillLooking() {
        assertEquals(BoardEmptyStage.Discovering, boardEmptyStage(nodeRunning = true, scanning = false, peerCount = 0))
    }

    @Test
    fun onlyARunningNodeWithPeersCanSayTrulyEmpty() {
        assertEquals(BoardEmptyStage.TrulyEmpty, boardEmptyStage(nodeRunning = true, scanning = false, peerCount = 2))
    }
}
