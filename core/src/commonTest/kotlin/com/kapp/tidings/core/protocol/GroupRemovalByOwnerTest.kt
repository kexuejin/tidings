// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真机数据复现：**群主把本机移出后，本机必须判 Removed。**
 *
 * ## 这条用例的来历
 *
 * 真机现场：手机（群主）把桌面端移出，`messages.log` 里确实收到了那一帧 ——
 * `rev=2`、`updatedBy` 与 `ownerId` 都是手机、成员表只剩手机自己。桌面却仍停在
 * `rev=1`、成员表 2 人。拆开那帧逐字段看，没有任何越界或缺失；于是把同样的数据
 * 喂进 `GroupMerge.decide` 直接求证。
 *
 * ## 为什么要单独一条
 *
 * 既有的 `onlyAManagerCanRemoveUsAndThenWeDropTheGroup` 用的是 `adminIds = [ADMIN]`、
 * `ownerId = OWNER` 的夹具；而真机那一帧是 **`adminIds` 为空数组、owner 就是操作者**。
 * 两条数据的差别正好落在 `normalize` 的兜底与角色判定上 —— 而这两处都不该因为
 * 「管理员列表为空」而改变结论：群主本来就是最高权限。
 */
class GroupRemovalByOwnerTest {

    private companion object {
        const val GROUP = "group-real-device"
        const val SELF = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
        const val PHONE = "00000000-0000-4000-8000-0000000000a1"
        const val TS = 1_793_000_000_000L
    }

    private fun meta(
        rev: Int,
        members: List<String>,
        updatedBy: String = PHONE,
    ) = GroupMeta(
        groupId = GROUP,
        name = "协同群",
        members = members,
        rev = rev,
        updatedBy = updatedBy,
        updatedTs = TS + rev,
        creatorIp = "",
        creatorId = PHONE,
        ownerId = PHONE,
        adminIds = emptyList(),
    )

    @Test
    fun theOwnerRemovingUsWithAnEmptyAdminListIsStillRemoved() {
        // 与真机那一帧逐字段一致：adminIds 为空、owner 是操作者本人。
        val local = meta(rev = 1, members = listOf(PHONE, SELF))
        val kicked = meta(rev = 2, members = listOf(PHONE))

        val decision = GroupMerge.decide(local, kicked, SELF)
        assertTrue(
            decision is GroupMergeDecision.Removed,
            "群主移出本机（管理员列表为空）必须判 Removed，实际=$decision",
        )
    }

    @Test
    fun aPlainMemberRemovingUsIsStillRejected() {
        // 负向对照：同样数据、操作者换成普通成员 ⇒ 必须拒（伪造的踢人）。
        val local = meta(rev = 1, members = listOf(PHONE, SELF))
        val kicked = meta(rev = 2, members = listOf(PHONE), updatedBy = SELF)

        val decision = GroupMerge.decide(local, kicked, SELF)
        assertTrue(
            decision is GroupMergeDecision.Reject,
            "非群主发来的移出必须拒，实际=$decision",
        )
    }
}
