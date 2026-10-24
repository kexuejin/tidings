// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.nodes

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「填地址」表单的地址判据（[isPlausibleHost]）必须与控制器那份**同一套规则**。
 *
 * 为什么需要镜像：控制器的是 `internal`（`:app` 内部），界面层看不见，跨模块也没有
 * 复用点 —— 于是节点页有一份逐字抄来的实现。抄写的东西会漂：表单宽一格，「连接」就亮着
 * 把注定被拒的地址发出去（控制器只发一条横幅打回），用户看到的是按钮在骗他。
 *
 * 这份测试因此钉两件事：
 *  1. 三条规则的**行为**（空/超长/空白/无字母数字都判不过）；
 *  2. `:app` 那份与这份**逐字同规则**（改一侧不改另一侧 → 红）。
 *
 * 反向验证：把镜像的 253 改成 128 → [threeRulesArePinned] 变红；把控制器那份的
 * `length > 253` 改掉、镜像不动 → [mirrorCarriesTheSameRulesAsTheController] 变红。
 */
class NodeSheetsHostCheckTest {

    private val nodeSheets = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodeSheets.kt"
    private val relayController = "app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayController.kt"

    /** 判据的三条规则，逐字。两侧各一份，必须一致。 */
    private val rules = listOf(
        "if (host.isEmpty() || host.length > 253) return false",
        "if (host.any { it.isWhitespace() }) return false",
        "return host.any { it.isLetterOrDigit() }",
    )

    @Test
    fun threeRulesArePinned() {
        // 第一条：非空且不超过 DNS 域名上限 253。
        assertFalse(isPlausibleHost(""), "空串不是地址")
        assertFalse(isPlausibleHost("a".repeat(254)), "超过 253 的串控制器会拒，表单不能放行")
        assertTrue(isPlausibleHost("a".repeat(253)))
        // 第二条：不许有空白。
        assertFalse(isPlausibleHost("192.168.1.31 5"), "空白让地址变成两条")
        // 第三条：至少一个字母或数字。
        assertFalse(isPlausibleHost(":::==="), "一个字母数字都没有的串不是地址")
        // 正常地址仍然通过（别把判据改到拒真）。
        assertTrue(isPlausibleHost("192.168.1.31"))
        assertTrue(isPlausibleHost("zhou-pc.local"))
    }

    @Test
    fun mirrorCarriesTheSameRulesAsTheController() {
        val mirror = GuardSourceScan.stripComments(GuardSourceScan.read(nodeSheets))
        val controller = GuardSourceScan.stripComments(GuardSourceScan.read(relayController))
        for (rule in rules) {
            assertTrue(
                mirror.contains(rule),
                "节点页那份地址判据少了这条规则：「" + rule + "」。判据只能有一份口径 —— " +
                    "表单比控制器宽，就会出现「按钮亮着、按下去被打回」。",
            )
            assertTrue(
                controller.contains(rule),
                "控制器那份地址判据少了这条规则：「" + rule + "」。如果规则确实要改，" +
                    "两侧必须同一提交里一起改（镜像没有跨模块复用点）。",
            )
        }
    }
}
