// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.agents

import com.kapp.tidings.domain.model.GrantRequestStatus
import com.kapp.tidings.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「申请授权」入口的档位与判因（task-10）。
 *
 * 这一层存在的唯一理由：`sendAgentGrantRequest` 返回 null 时**分不出**是哪种失败
 * （三道闸都 return null），而 UI 文案必须分开。所以判因只能靠调用方手里的输入，
 * 这里把它抽成纯函数并钉住。
 */
class AgentGrantRequestUiTest {

    private val full = listOf(MsgTypes.CAP_AGENT, MsgTypes.CAP_AGENT_GRANT)

    @Test
    fun theEntryIsReadyOnlyWhenThePeerCanReceiveTheRequest() {
        assertEquals(GrantRequestEntry.Ready, grantRequestEntry(full, GrantRequestStatus.NONE))
    }

    @Test
    fun pendingBeatsEverythingElse() {
        // 已经发过一条、还没等到表态：按钮是「等待中」，不该又能再点一次。
        assertEquals(GrantRequestEntry.Pending, grantRequestEntry(full, GrantRequestStatus.PENDING))
        assertEquals(GrantRequestEntry.Pending, grantRequestEntry(emptyList(), GrantRequestStatus.PENDING))
    }

    @Test
    fun eachMissingCapabilityHasItsOwnEntry() {
        assertEquals(
            GrantRequestEntry.PeerNotAgent,
            grantRequestEntry(listOf(MsgTypes.CAP_AGENT_GRANT), GrantRequestStatus.NONE),
            "没有 ag1 = 不是 agent 节点",
        )
        assertEquals(
            GrantRequestEntry.PeerNoGrantLoop,
            grantRequestEntry(listOf(MsgTypes.CAP_AGENT), GrantRequestStatus.NONE),
            "没有 agr1 = 不认识授权回路（这一档必须禁用：发了会把在线设备误标离线）",
        )
        assertEquals(
            GrantRequestEntry.PeerNotAgent,
            grantRequestEntry(emptyList(), GrantRequestStatus.NONE),
            "两个都没有时先报 ag1 那档 —— 与组件里 runtime → ag1 → agr1 的闸门顺序一致",
        )
    }

    @Test
    fun theThreeNullCausesAreNotMerged() {
        // 闸门顺序照抄 handleSendGrantRequest：runtime → ag1 → agr1。
        assertEquals(GrantRequestFailure.NoRuntime, grantRequestFailure(full, nodeRunning = false))
        assertEquals(
            GrantRequestFailure.PeerNotAgent,
            grantRequestFailure(listOf(MsgTypes.CAP_AGENT_GRANT), nodeRunning = true),
        )
        assertEquals(
            GrantRequestFailure.PeerNoGrantLoop,
            grantRequestFailure(listOf(MsgTypes.CAP_AGENT), nodeRunning = true),
        )
        // 三道闸都过还是 null ⇒ 只剩运行时被拆掉这一种可能（重启窗口）。
        assertEquals(GrantRequestFailure.NoRuntime, grantRequestFailure(full, nodeRunning = true))

        // 这条断言就是「不许合并成一句」的可测形式：三种处境必须落到三个不同的值。
        val three = setOf(
            grantRequestFailure(full, nodeRunning = false),
            grantRequestFailure(listOf(MsgTypes.CAP_AGENT_GRANT), nodeRunning = true),
            grantRequestFailure(listOf(MsgTypes.CAP_AGENT), nodeRunning = true),
        )
        assertEquals(3, three.size, "三种失败必须能分开，实际 " + three)
    }

    @Test
    fun noRuntimeWinsWhenBothTheRuntimeAndThePeerAreMissing() {
        // 顺序反了会把「本机没起来」说成「对端不支持」—— 那是替对端说一个它没说过的结论。
        assertEquals(GrantRequestFailure.NoRuntime, grantRequestFailure(emptyList(), nodeRunning = false))
    }
}
