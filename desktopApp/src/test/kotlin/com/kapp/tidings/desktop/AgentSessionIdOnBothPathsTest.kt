// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **两条转发路径都必须给 `MessageUi` 带上 `agentSessionId`。**
 *
 * ## 真身（探针实测）
 *
 * 桌面端的 agent 消息有两条路进群：
 *  · **实时**：`onRecord` 里自己手写 `MessageUi(...)`；
 *  · **补发**：走共用构造 `agentChatMessageFor`（它带 `agentSessionId = rec.sessionId`）。
 *
 * 实时那条漏了这两个字段，于是 `agent_session_id` 一直是 NULL —— 探针读数
 * `SAVE agent=5212 withSid=0`：进 `save()` 时就已经是空，不是落库丢的。
 *
 * 症状正是用户报的那条：**桌面有分段线、手机没有**。手机只从群里收消息，
 * 拿不到 id 前缀那条回退（转发时 id 会换成信封 UUID），只能靠协议字段。
 *
 * 这条守卫盯住「实时那条构造里必须有 agentSessionId」——它没有类型层面的保障：
 * `MessageUi` 的参数有默认值，漏传照样编译通过。
 */
class AgentSessionIdOnBothPathsTest {

    private fun source(): String {
        // 测试进程的 cwd 是模块目录（desktopApp/），源码在同仓的 src/main/kotlin
        val f = File("src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt")
        assertTrue(f.exists(), "找不到 DesktopExtension.kt（cwd=" + File(".").absolutePath + "）")
        return f.readText()
    }

    /** 实时路径那次 `MessageUi(` 构造必须带 `agentSessionId`。 */
    @Test
    fun theLiveForwardPathCarriesTheSessionId() {
        val src = source()
        // 定位实时路径的特征行：它自己拼 `agent-ev-` 的 msgId（后面紧跟 MessageUi 构造）。
        val marker = "val msgId = \"agent-ev-\${rec.sessionId}-\${rec.cursor}\""
        val at = src.indexOf(marker)
        assertTrue(at >= 0, "实时路径的特征行没找到 —— 这段代码被重构了，本守卫要跟着改")
        // 从特征行往后取到下一次 appendMessage 为止，就是那一次构造。
        val end = src.indexOf("controller.appendMessage(msg", at)
        assertTrue(end > at, "实时路径后面没找到 appendMessage —— 结构变了")
        val block = src.substring(at, end)
        assertTrue(
            block.contains("agentSessionId = rec.sessionId"),
            "实时路径的 MessageUi 没带 agentSessionId ⇒ agent_session_id 落库为 NULL" +
                " ⇒ 手机端画不出分段线（桌面靠 id 前缀回退，所以只有桌面有）",
        )
    }

    /** 共用构造（补发那条走它）必须带。 */
    @Test
    fun theSharedConstructorCarriesTheSessionId() {
        val src = source()
        val at = src.indexOf("internal fun agentChatMessageFor(")
        assertTrue(at >= 0, "`agentChatMessageFor` 不见了 —— 两条路共用的前提没了")
        val end = src.indexOf("\n}", at)
        val block = src.substring(at, end)
        assertTrue(
            block.contains("agentSessionId = rec.sessionId"),
            "共用构造没带 agentSessionId：补发那条路也会丢",
        )
    }
}
