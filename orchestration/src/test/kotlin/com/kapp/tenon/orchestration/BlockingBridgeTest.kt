// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 桥接纪律的两条判据（ARP-2）：
 * 1. 行为 —— 超时与抛错都退回显式 fallback，不把异常穿进协议回调；
 * 2. 接线 —— 编排层生产源码里 `runBlocking` 只许出现在 [BlockingBridge] 一处。
 *
 * 第 2 条为什么必须守：`runBlocking` 是「能编译、单测也过、只有真机手感」的那类东西。
 * 这一轮之前它在 `DesktopExtension` 里散着 7 处、每处都没有上界；一处没上界不影响
 * 任何一条用例，只让宿主在某个对端不可达时整条帧循环停住 —— 而**下一个人会照抄**
 * 最近的那一处。所以判据不是「这次改完了」，而是「以后只能写在工具里」。
 */
class BlockingBridgeTest {

    @Test
    fun successReturnsTheValue() {
        assertEquals(42, bridgeBlocking("test.ok", fallback = -1) { 42 })
    }

    @Test
    fun timeoutFallsBackInsteadOfBlockingForever() {
        val value = bridgeBlocking("test.timeout", fallback = "fallback", timeoutMs = 50) {
            kotlinx.coroutines.delay(5_000)
            "never"
        }
        assertEquals("fallback", value)
    }

    @Test
    fun exceptionFallsBackInsteadOfEscapingIntoTheCallback() {
        val value = bridgeBlocking<Int>("test.throw", fallback = 7) {
            error("boom")
        }
        assertEquals(7, value)
    }

    /** block 合法返回 null 与「超时」必须能区分：前者是结果，后者是 fallback。 */
    @Test
    fun aNullResultIsNotMistakenForATimeout() {
        val value = bridgeBlocking<String?>("test.null", fallback = "fallback") { null }
        assertEquals(null, value)
    }

    @Test
    fun orchestrationProductionHasExactlyOneRunBlockingSite() {
        val root = "orchestration/src/main"
        val offenders = GuardSourceScan.kotlinFiles(root)
            .filter { it.substringAfterLast('/') != "BlockingBridge.kt" }
            .filter { path ->
                GuardSourceScan.stripComments(GuardSourceScan.read(path)).contains("runBlocking")
            }
        assertTrue(
            offenders.isEmpty(),
            "编排层只许在 BlockingBridge.kt 里 runBlocking；这些文件又写回去了：" +
                offenders.joinToString("、") +
                "（要加挂起调用请用 bridgeBlocking/bridgeBlockingUnit）",
        )
    }
}
