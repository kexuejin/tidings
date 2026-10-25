// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.recovery.RecoveryPendingPeers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * L2 待拉集合的落盘往返：这个文件存在与否，就是「进程被杀后恢复在途的拉取
 * 会不会静默丢失」的全部差别（见 RecoveryPendingPeers 的 KDoc）。
 */
class RecoveryPendingPeersTest {

    @Test
    fun pendingPeersFileRoundTrips() {
        val dir = java.nio.file.Files.createTempDirectory("relay-pending-test").toString()
        try {
            RecoveryPendingPeers.save(dir, setOf("p1", "p2"))
            assertEquals(setOf("p1", "p2"), RecoveryPendingPeers.load(dir), "落盘后读回同一集合")
            RecoveryPendingPeers.save(dir, emptySet())
            assertEquals(emptySet(), RecoveryPendingPeers.load(dir), "清空即删文件，读回空集")
        } finally {
            java.nio.file.Paths.get(dir).toFile().walkBottomUp().forEach { it.delete() }
        }
    }
}
