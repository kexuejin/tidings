// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 会话行分类徽标的守卫：**群不画 tag**。
 *
 * 事实：原型 `convRow` 只给 agent 会话挂 `tag agent`
 * （`prototype/relay.html` 的 `${c.isAgent?'<span class="tag agent">Agent</span>':''}`），
 * 群与人类都不挂。代码里 `KindBadge(isAgent)` 只有两态（`Agent` / `同事`），早先无条件调用，
 * 于是群会话落到"同事"分支 —— 群既不是 Agent 也不是一个人；与团队折叠同屏时更会被读成
 * "这个团队是同事"。
 *
 * 这类错误在单测与截图里都不会红（它只是一枚小徽标的文字），所以只能守源码。
 * 反向验证：删掉 `if (!row.isGroup) {` 那层包裹，本用例立即变红。
 */
class ConversationRowKindBadgeGuardTest {

    private val row =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/ConversationRow.kt"

    @Test
    fun kindBadgeIsOnlyDrawnForNonGroupConversations() {
        val source = GuardSourceScan.stripComments(GuardSourceScan.read(row))
        assertTrue(
            Regex("""if \(!row\.isGroup\) \{\s*KindBadge\(row\.isAgent\)""").containsMatchIn(source),
            "KindBadge 必须包在 `if (!row.isGroup)` 里 —— 否则群会话会被标成「同事」（原型群行不挂 tag）",
        )
        // 只许这一处按会话类型取值的调用；别处（工作区行的 `isAgent = true`）是常量，不受影响。
        assertTrue(
            Regex("""KindBadge\(row\.isAgent\)""").findAll(source).count() == 1,
            "会话行只该有一处 `KindBadge(row.isAgent)`；多出来的那处会绕开上面的群判断",
        )
    }
}
