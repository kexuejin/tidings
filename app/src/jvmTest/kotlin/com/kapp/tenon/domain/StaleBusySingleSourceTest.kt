// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentSessionStates
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「僵死 busy 判定只有一份」守卫（阶段 0 / C2 收编）。
 *
 * 收编前有两套实现：`InboxWorkspaceUnits` 有私有的 `AGENT_STATE_BUSY = "busy"` 与
 * `isStaleBusy`，`healStaleBusySessions` 则用裸字符串并自己抄了一遍
 * `(now - lastActivity) > timeoutMs`。阈值同源、比较各写一份 —— 改门限时漏改一处，
 * 两屏就会对「僵死」给出不同答案。
 *
 * 判据：
 * 1. 两个文件里都不许再出现 `"busy"` / `"idle"` 字面量（取值走 `AgentSessionStates`）；
 * 2. `healStaleBusySessions` 必须转交 `isStaleBusy`，不许就地展开阈值比较；
 * 3. 行为等价：超时→idle、未超时→保持、无活动→保持、非 busy→不动。
 *
 * 反向验证：把 `healStaleBusySessions` 里改回 `"busy"` 字面量，或把 `isStaleBusy(` 换成
 * 手写比较，本用例即变红。
 */
class StaleBusySingleSourceTest {

    private val units = "app/src/commonMain/kotlin/com/kapp/tenon/domain/InboxWorkspaceUnits.kt"
    private val component =
        "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentComponent.kt"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun stateValuesAreNotStringLiterals() {
        for (path in listOf(units, component)) {
            val source = live(path)
            for (literal in listOf("\"busy\"", "\"idle\"")) {
                assertTrue(
                    !source.contains(literal),
                    path + " 出现字面量 " + literal + "：状态取值必须走 AgentSessionStates，" +
                        "否则改常量时这一处会静默漂移",
                )
            }
        }
    }

    @Test
    fun healDelegatesToSharedStaleJudge() {
        val body = GuardSourceScan.bracedBlockAfter(
            live(component),
            "internal fun healStaleBusySessions(",
        )
        assertTrue(
            body.contains("isStaleBusy("),
            "healStaleBusySessions 必须转交 isStaleBusy —— 自己抄 (now - lastActivity) > timeoutMs " +
                "会让阈值有两份实现",
        )
        assertTrue(
            body.contains("AgentSessionStates.BUSY") && body.contains("AgentSessionStates.IDLE"),
            "healStaleBusySessions 的状态取值必须来自 AgentSessionStates",
        )
    }

    @Test
    fun constantsKeepWireValues() {
        // 常量值本身是协议事实：改了它等于改线上语义，守卫先钉住。
        assertEquals("busy", AgentSessionStates.BUSY)
        assertEquals("idle", AgentSessionStates.IDLE)
    }

    // ---------------------------------------------------------------- 行为等价

    private val key = AgentSessionKey("peer-1", "sess-1")

    private fun session(state: String?, activityAt: Long) = AgentSessionUi(
        peerId = key.peerId,
        sessionId = key.sessionId,
        state = state,
        updatedAt = activityAt,
    )

    @Test
    fun busyOverTimeoutHealsToIdle() {
        val now = 10_000L
        val (sessions, healed) = healStaleBusySessions(
            sessions = mapOf(key to session(AgentSessionStates.BUSY, activityAt = now - 5_000)),
            events = emptyMap(),
            now = now,
            timeoutMs = 1_000L,
        )
        assertEquals(AgentSessionStates.IDLE, sessions[key]?.state)
        assertEquals(1, healed.size)
    }

    @Test
    fun busyWithinTimeoutStaysBusy() {
        val now = 10_000L
        val (sessions, healed) = healStaleBusySessions(
            sessions = mapOf(key to session(AgentSessionStates.BUSY, activityAt = now - 500)),
            events = emptyMap(),
            now = now,
            timeoutMs = 1_000L,
        )
        assertEquals(AgentSessionStates.BUSY, sessions[key]?.state)
        assertTrue(healed.isEmpty())
    }

    @Test
    fun busyWithoutActivityIsNotHealed() {
        // lastActivity = 0 表示"根本没动过"——不能凭 0 算出无穷大再判僵死。
        val (sessions, healed) = healStaleBusySessions(
            sessions = mapOf(key to session(AgentSessionStates.BUSY, activityAt = 0L)),
            events = emptyMap(),
            now = 10_000L,
            timeoutMs = 1_000L,
        )
        assertEquals(AgentSessionStates.BUSY, sessions[key]?.state)
        assertTrue(healed.isEmpty())
    }

    @Test
    fun nonBusySessionsAreUntouched() {
        val (sessions, healed) = healStaleBusySessions(
            sessions = mapOf(key to session(AgentSessionStates.IDLE, activityAt = 0L)),
            events = emptyMap(),
            now = 10_000L,
            timeoutMs = 1_000L,
        )
        assertEquals(AgentSessionStates.IDLE, sessions[key]?.state)
        assertTrue(healed.isEmpty())
    }
}
