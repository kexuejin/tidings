// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `sameAgent` 的**源码指纹**守卫（方案 §7 A8）：它永久只做**路由聚类**，
 * 一条都不许改 —— 改它等于把路由尺当身份尺（洞 E 的病根）。
 *
 * ## 为什么是源码守卫
 *
 * 身份判据已经全面改按 `sameEmployee`（employeeId 精确），`sameAgent` 从「身份判定」
 * 降级为「路由兜底」。这个降级最怕的复发方式不是有人删测试，而是有人「顺手优化」
 * `sameAgent` 的前缀规则 —— 那会让它在路由场景悄悄变严或变宽，两条链路一起遭殃。
 * 类型系统表达不了「这个函数的语义永不变化」，只能在源码文本上钉。
 *
 * 每条断言都先 `stripComments`：注释里正当讨论旧写法不能让守卫假绿。
 */
class SameAgentRoutingScaleFingerprintTest {

    private fun src(): String = GuardSourceScan.stripComments(
        GuardSourceScan.read("core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/AgentIdentityMatch.kt"),
    )

    @Test
    fun sameAgentBodyKeepsItsRoutingScaleSemantics() {
        val body = src().substringAfter("fun sameAgent").substringBefore("fun sameEmployee")
        // 前缀规则的两条腿：大小写归一后的精确相等 + 截到 `-` 之前的 head 相等。
        assertTrue(
            body.contains("substringBefore('-')"),
            "sameAgent 的前缀到 `-` 规则必须原样保留 —— 它是路由聚类（dsh ~ dsh-029bbbf3）的定义",
        )
        assertTrue(
            body.contains("want.isEmpty()) return false"),
            "sameAgent 对空 wanted 必须 fail-closed —— MINT 档案 agentId 为空串时靠这条不误命中",
        )
    }

    @Test
    fun sameAgentNeverTouchesEmployeeIdentity() {
        val body = src().substringAfter("fun sameAgent").substringBefore("fun sameEmployee")
        assertFalse(
            body.contains("employeeId"),
            "sameAgent 不得引用 employeeId —— 它是路由尺，不是身份尺（洞 E）；身份判定只准用 sameEmployee",
        )
    }

    @Test
    fun sameEmployeeIsAnExactOpaqueKeyComparison() {
        val body = src().substringAfter("fun sameEmployee")
        assertTrue(
            body.contains("return x.isNotEmpty() && x == y"),
            "sameEmployee 必须保持 trim + 精确相等 + 空值不匹配（fail-closed）——它是唯一的身份尺",
        )
    }
}
