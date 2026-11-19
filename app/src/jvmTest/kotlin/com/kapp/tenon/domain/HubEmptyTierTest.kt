// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 空态四档的判据（方案 §3.1）。这张表是**顺序敏感**的，所以每条都单独钉：
 * 顺序一错，「只有远端在跑」会同时命中 A 与 C，两个空态抢同一块地方。
 */
class HubEmptyTierTest {

    @Test
    fun noDeviceAtAllBeatsEveryOtherTier() {
        // 一台设备都没连时，「本机有没有行」这句话本身不成立 ⇒ 必须先返回 NO_DEVICE，
        // 不能因为「行数为 0」就落进 A（那会让用户去建人，而建人也派不出去）。
        assertEquals(
            HubEmptyTier.NO_DEVICE,
            hubEmptyTier(connectedNodes = 0, localRows = 0, remoteRows = 0, hasLocalProfiles = false),
        )
    }

    @Test
    fun deviceConnectedButNobodyCreatedIsTheCreateEmployeeTier() {
        // 有设备、本机没人、远端也没有 ⇒ A：主行动是「新建员工」。
        // 这一档就是冷启动死锁在界面上的形态：不给出路，用户只会去「看看节点」然后一无所获。
        assertEquals(
            HubEmptyTier.NO_EMPLOYEE,
            hubEmptyTier(connectedNodes = 1, localRows = 0, remoteRows = 0, hasLocalProfiles = false),
        )
    }

    @Test
    fun localProfilesButNobodyRunningIsTheIdleTierRatherThanTheCreateTier() {
        // 本机建过人、这会儿谁都没跑 ⇒ B：主行动是「建团队」。
        // 说成「还没有员工」会让用户去建第二个人（他已经有员工了）。
        assertEquals(
            HubEmptyTier.LOCAL_IDLE,
            hubEmptyTier(connectedNodes = 1, localRows = 0, remoteRows = 0, hasLocalProfiles = true),
        )
    }

    @Test
    fun onlyRemoteRowsWithNoLocalProfileIsTheRemoteOnlyTier() {
        // 别的设备在跑、本机一个人都没有 ⇒ C：正文必须说清「下面这些来自其他设备」，
        // 否则就是把「别人的」说成「你的」。
        assertEquals(
            HubEmptyTier.REMOTE_ONLY,
            hubEmptyTier(connectedNodes = 2, localRows = 0, remoteRows = 3, hasLocalProfiles = false),
        )
    }

    @Test
    fun localProfilesBeatTheRemoteRowCountSoTheIdleTierIsReachable() {
        // 顺序针：本机建过人、别的设备也在跑 ⇒ 必须是 B，不是 C。
        // 这条与上一条一起把「档案判据排在远端行数之前」钉住 —— 反过来写时
        // 上一个用例会落进 A、这一个会落进 C，B 在任何输入下都触发不到。
        assertEquals(
            HubEmptyTier.LOCAL_IDLE,
            hubEmptyTier(connectedNodes = 2, localRows = 0, remoteRows = 3, hasLocalProfiles = true),
        )
    }

    @Test
    fun anOfflineLocalRowIsStillARowSoThereIsNoEmptyState() {
        // 离线的人是你的员工：它在名录里占一行（能点开改档案），所以这一页不算空。
        // 只按 `online` 判会把人从名录里「删掉」，那是这一页最容易犯的错。
        assertNull(
            hubEmptyTier(connectedNodes = 1, localRows = 1, remoteRows = 0, hasLocalProfiles = true),
        )
    }

    @Test
    fun aLocalRowSuppressesTheEmptyStateEvenWhenRemoteRowsExist() {
        assertNull(
            hubEmptyTier(connectedNodes = 3, localRows = 2, remoteRows = 5, hasLocalProfiles = true),
        )
    }
}
