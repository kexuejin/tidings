// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 审批处置权限的唯一判据（[canApproveAgentRequest] / [mayDecideAgentApproval]）。
 *
 * 这是**安全边界**：同一份判据同时决定客户端画不画按钮、宿主认不认回执。四档必须能
 * 区分开，且未知一律拒绝（fail-closed）。
 *
 * 反向验证：把 [canApproveAgentRequest] 改回「只挡 RO」（= 修复前的看板判据，
 * `access != RO` 即放行），[adminWithoutDelegationIsDenied] 与本文件其余拒绝档都会变红；
 * 把 [isAgentApprovalExpired] 改成恒 false，[expiredRequestCannotBeDecided] 变红。
 */
class AgentApprovalAuthorityTest {

    private val now = 1_000_000L

    private fun mayDecide(
        isLocalHost: Boolean = false,
        isMyDevice: Boolean = false,
        access: String? = null,
        delegatedApproval: Boolean = false,
        expiresAt: Long = now + 60_000L,
    ): Boolean = mayDecideAgentApproval(
        isLocalHost = isLocalHost,
        isMyDevice = isMyDevice,
        access = access,
        delegatedApproval = delegatedApproval,
        expiresAt = expiresAt,
        now = now,
    )

    @Test
    fun adminWithoutDelegationIsDenied() {
        assertFalse(
            mayDecide(access = AgentGrantAccess.ADMIN, delegatedApproval = false),
            "ADMIN 没开委托审批就不许替宿主做决定（这是修复前看板放行的那一档）",
        )
    }

    @Test
    fun adminWithDelegationMayDecide() {
        assertTrue(
            mayDecide(access = AgentGrantAccess.ADMIN, delegatedApproval = true),
            "ADMIN + 委托审批是显式授予的代为处置权",
        )
    }

    @Test
    fun readOnlyAndReadWriteAreBothDenied() {
        assertFalse(mayDecide(access = AgentGrantAccess.RO), "RO 只能浏览，不能审批")
        assertFalse(mayDecide(access = AgentGrantAccess.RW), "能对话/能派活不等于能批准高危操作")
        assertFalse(mayDecide(access = null), "没有授权记录 = 拒绝（fail-closed，不猜）")
        assertFalse(mayDecide(access = "admin-ish"), "未知档位不许因为长得像 admin 就放行")
    }

    @Test
    fun ownerAndMyDeviceAndLocalHostMayDecide() {
        assertTrue(mayDecide(access = AgentGrantAccess.OWNER), "OWNER 是宿主自己的授权")
        assertTrue(mayDecide(isMyDevice = true), "标记成「我的设备」的那台天然可批")
        assertTrue(mayDecide(isLocalHost = true), "本机宿主对自己的 agent 有全部权限")
    }

    @Test
    fun expiredRequestCannotBeDecided() {
        assertFalse(
            mayDecide(isLocalHost = true, expiresAt = now - 1L),
            "过期后连本机宿主也不许再处置 —— 否则「过期」只是客户端画不画的事",
        )
        assertTrue(mayDecide(isLocalHost = true, expiresAt = 0L), "expiresAt=0 是「对端没声明」，按永不过期")
        assertTrue(mayDecide(isLocalHost = true, expiresAt = now + 1L))
        assertFalse(isAgentApprovalExpired(expiresAt = now, now = now), "恰好到期那一刻仍可处置")
        assertTrue(isAgentApprovalExpired(expiresAt = now - 1L, now = now))
    }
}
