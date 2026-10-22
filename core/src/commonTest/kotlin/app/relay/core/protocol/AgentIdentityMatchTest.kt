// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「两个身份是不是同一个 agent」—— 容忍精度差异，但不能串到别的实例。
 *
 * 真机数据：入驻写进群的是 `DSH`（agentKind 兜底），会话报的是 `dsh-029bbbf3`。
 * 直接 `==` 永远对不上 ⇒ 找不到候选会话 ⇒ `targetSessionId` 恒 null ⇒ 注入永远被拒。
 */
class AgentIdentityMatchTest {

    /** **真机那一对必须能对上**。 */
    @Test
    fun theKindAloneMatchesTheFullIdFromTheSameAgent() {
        assertTrue(sameAgent("dsh-029bbbf3", "DSH", "DSH"))
        assertTrue(sameAgent("dsh-029bbbf3", "DSH", "dsh"))
    }

    /** 忽略大小写。 */
    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(sameAgent("DSH", null, "dsh"))
    }

    /** 精确相等当然成立。 */
    @Test
    fun anExactMatchWins() {
        assertTrue(sameAgent("dsh-029bbbf3", null, "dsh-029bbbf3"))
    }

    /**
     * **不同 kind 不能对上。**
     *
     * `codex-xxx` 与 `dsh` 是两台不同的 agent；只比前缀会让它们互串。
     */
    @Test
    fun differentKindsDoNotMatch() {
        assertFalse(sameAgent("codex-abc", "codex", "dsh"))
    }

    /** 空 wanted ⇒ 一律不匹配（fail-closed，不靠「空就全选」）。 */
    @Test
    fun anEmptyWantedMatchesNothing() {
        assertFalse(sameAgent("dsh-1", "DSH", null))
        assertFalse(sameAgent("dsh-1", "DSH", ""))
        assertFalse(sameAgent("dsh-1", "DSH", "   "))
    }

    /** 候选两条身份都空 ⇒ 不匹配。 */
    @Test
    fun aCandidateWithNoIdentityNeverMatches() {
        assertFalse(sameAgent(null, null, "dsh"))
        assertFalse(sameAgent("", "  ", "dsh"))
    }

    /**
     * **同一个 kind 的不同实例仍然都算命中**（由 wsId 再去区分）。
     *
     * 这是放宽的代价，写明在这里：`dsh-a` 与 `dsh-b` 都会匹配 wanted=`dsh`，
     * 真正决定投给谁的是 `AgentSessionPick` 的工作区绑定，不是身份键。
     */
    @Test
    fun siblingsOfTheSameKindBothMatch() {
        assertTrue(sameAgent("dsh-a", null, "dsh"))
        assertTrue(sameAgent("dsh-b", null, "dsh"))
    }
}
