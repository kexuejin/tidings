// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.nodes

import com.kapp.tidings.domain.model.PeerUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「添加节点」弹层里两条纯判据：
 *
 *  1. [isPlausibleHost] —— 表单侧对控制器 `isPlausibleHost` 的镜像。两边判据不一致的
 *     后果是「按钮亮着、按下去被控制器打回」；这里把控制器的三条规则逐字钉住。
 *  2. [connectAttemptTimedOut] —— 手动「连接」的超时红字判据。上一版把它挂在
 *     `attempted` 上一点就报超时；合回 `peers` 的 effect 后又被 peers 抖动无限重置。
 *     两种假象这里都能区分。
 */
class NodeSheetsLogicTest {

    // ── isPlausibleHost：与 RelayController 同一条判据 ─────────────────────────

    @Test
    fun plausibleHostAcceptsIpv4HostnameAndIpv6() {
        assertTrue(isPlausibleHost("192.168.1.31"))
        assertTrue(isPlausibleHost("zhou-pc.local"))
        assertTrue(isPlausibleHost("[2001:db8:1234::1]".removeSurrounding("[", "]")))
    }

    @Test
    fun plausibleHostRejectsEmptyWhitespaceAndSymbols() {
        assertFalse(isPlausibleHost(""), "空串不是地址")
        assertFalse(isPlausibleHost("192.168.1.31 5"), "空白让地址变成两条")
        assertFalse(isPlausibleHost(":::==="), "一个字母数字都没有的串不是地址")
    }

    @Test
    fun plausibleHostRejectsOverLongInput() {
        // 254 个 'a'：超过 DNS 域名上限 253。控制器的判据在这一档拒绝，
        // 表单若比它宽，「连接」就会亮着把注定被拒的帧发出去。
        assertFalse(isPlausibleHost("a".repeat(254)))
        assertTrue(isPlausibleHost("a".repeat(253)))
    }

    // ── connectAttemptTimedOut：真的等过、且目标至今没来 ───────────────────────

    private fun peer(ip: String, host: String = "") =
        PeerUi(nodeId = "n1", nick = "n1", ip = ip, host = host, udpPort = 17_878)

    @Test
    fun noTimeoutBeforeTheWaitElapsed() {
        // 点下去的第一拍：timedOut 还没置位 —— 上一版在这一拍就红字"连接超时"。
        assertFalse(connectAttemptTimedOut(timedOut = false, attemptedHost = "1.2.3.4", peers = emptyList()))
    }

    @Test
    fun timeoutShowsOnlyWhenTargetStillAbsent() {
        assertTrue(connectAttemptTimedOut(timedOut = true, attemptedHost = "1.2.3.4", peers = emptyList()))
        // 目标已进表（ip 撞上）：红字必须退场 —— 成功即关弹层，就算迟到了也是成功。
        assertFalse(
            connectAttemptTimedOut(timedOut = true, attemptedHost = "1.2.3.4", peers = listOf(peer("1.2.3.4"))),
        )
        // 自报 host 撞上也算来了。
        assertFalse(
            connectAttemptTimedOut(timedOut = true, attemptedHost = "zhou-pc", peers = listOf(peer("10.0.0.9", host = "zhou-pc"))),
        )
    }

    @Test
    fun timeoutNeedsAnAttempt() {
        // 没点过「连接」（attempted 为空）：等了再久也不该骂。
        assertFalse(connectAttemptTimedOut(timedOut = true, attemptedHost = "", peers = emptyList()))
    }

    @Test
    fun timeoutIgnoresPeerListChurn() {
        // peers 抖动（列表重建）不该重置已在计时的超时：判据只看「timedOut 与目标在不在」，
        // 与 peers 的对象身份无关 —— 旧实现把计时器 key 在 state.peers 上，网络越活越报不出。
        val churned = List(5) { idx -> peer("10.0.0.$idx") }
        assertTrue(connectAttemptTimedOut(timedOut = true, attemptedHost = "1.2.3.4", peers = churned))
        assertEquals(5, churned.size)
    }
}
