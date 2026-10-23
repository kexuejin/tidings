// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

/**
 * **`grantReply` 只回帧、不改台账，但它也算一次「做过事」。**
 *
 * ## 缺陷
 *
 * `writeGrants` 末尾用 `changed` 判断「有没有做过任何事」，没有就回 400。
 * `policy` / `grant` / `revoke` 三个分支都会置 `changed = true`，
 * **只有 `grantReply` 从头到尾没碰过它**。于是：
 *
 *     只传 grantReply ⇒ 帧真的发出去了，却回 400
 *     "body 至少要带 policy / grant / revoke / grantReply 之一"
 *
 * 那句话还会**反过来误导调用方**以为是自己没带字段 —— 而他明明带了。
 *
 * ## 为什么这条值得一个测试
 *
 * 它不是「会崩」，而是「明明做了却报没做」。这类错误在真机上表现为
 * 「面板点了批准、手机没反应」，而桌面端日志写着 400 —— 排查方向会被完全带偏。
 *
 * 用源码级守卫：这个缺陷的类型系统表达不出来（`changed` 是个 var，漏赋值照样编译）。
 */
class GrantReplySetsChangedFlagTest {

    private fun source(): String {
        for (root in listOf("../", "../../", "")) {
            val f = File(root + "agentNode/src/main/kotlin/com/kapp/tidings/agent/ControlPlane.kt")
            if (f.exists()) return f.readText()
        }
        error("读不到 ControlPlane.kt")
    }

    /** 只看活代码：注释里提到 `changed = true` 也会让守卫假绿。 */
    private fun live(): String =
        source().lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /**
     * `grantReply` 分支内部必须有 `changed = true`。
     *
     * 判据：从 `obj["grantReply"]` 那段开始，到下一个 `if (!changed)` 之前，
     * 必须有这一句。
     */
    @Test
    fun theGrantReplyBranchMarksTheRequestAsChanged() {
        val live = live()
        val start = live.indexOf("obj[\"grantReply\"] as? JsonObject")
        assertTrue(start >= 0, "找不到 grantReply 分支（重构后请同步本测试）")
        val end = live.indexOf("if (!changed)", start)
        assertTrue(end > start, "grantReply 分支之后应当有 if (!changed) 收尾")
        val branch = live.substring(start, end)
        assertTrue(
            branch.contains("changed = true"),
            "grantReply 分支必须置 changed = true —— 否则只回帧的调用会被判 400，" +
                "而对端可能已经真的收到帧了",
        )
    }
}
