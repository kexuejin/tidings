// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 登录授权等待检测（真机验收现场：quest 挂 60 秒，FAILED 卡只说 "cli task failed"，
 * 机主不知道是没登录、也不知道登录之后还有救）。
 *
 * 设计边界写在 [QuestAuthWaitDetector] 的 KDoc 里：**不做粘码问卡** —— 子进程
 * stdin 是 /dev/null（既定决策，防 stdin 读取型 CLI 挂死），CLI 的授权流是云端
 * 回调不读 stdin。所以检测的产物是「失败摘要改写 + 可用的重试」，不是假按钮。
 *
 * **反向验证**：把「不检测」的旧行为放回去（decorateError(false, …)），
 * 下面的改写断言立刻变红 —— 这条测试确实在区分两个世界。
 */
class QuestAuthWaitDetectorTest {

    // ---- 判据：真机终端流里实际出现过的形状 ----

    @Test
    fun matchesTheLinesTheCliActuallyPrintedOnDevice() {
        assertTrue(
            QuestAuthWaitDetector.isAuthWaitLine("Authentication required"),
            "真机输出的首行必须命中",
        )
        assertTrue(
            QuestAuthWaitDetector.isAuthWaitLine("Waiting for authentication (timeout 60s)"),
        )
        assertTrue(
            QuestAuthWaitDetector.isAuthWaitLine("… paste the authorization code here"),
        )
        assertTrue(
            QuestAuthWaitDetector.isAuthWaitLine("Error: authentication timed out"),
        )
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertTrue(QuestAuthWaitDetector.isAuthWaitLine("AUTHENTICATION REQUIRED"))
        assertTrue(QuestAuthWaitDetector.isAuthWaitLine("Waiting For Authentication"))
    }

    @Test
    fun doesNotMatchOrdinaryOutput() {
        // 误报会把正常失败改写成登录指引 —— 清单必须短而准。
        assertFalse(QuestAuthWaitDetector.isAuthWaitLine("Compiling module auth-requirements.kt"))
        assertFalse(QuestAuthWaitDetector.isAuthWaitLine("2026-01-01 INFO session established"))
        assertFalse(QuestAuthWaitDetector.isAuthWaitLine(""))
    }

    // ---- 改写：FAILED 摘要的补救指引 ----

    @Test
    fun decoratesTheFailureWithRemediationAndKeepsTheTraceableTail() {
        val decorated = QuestAuthWaitDetector.decorateError(
            authWaitSeen = true,
            errorTail = "exit code 1 · authentication timed out",
            fallback = "error",
        )
        assertTrue(decorated!!.startsWith("Authentication required"), "补救指引必须在前——用户第一眼看到该干什么")
        assertTrue(decorated.endsWith("exit code 1 · authentication timed out"), "A1 的错误尾巴不能被改写吃掉")
    }

    @Test
    fun exactLoginCommandLandsInTheFailureHint() {
        // 仿 multica「失败后只给一句该怎么修」：其实行动作来自唯一词表，
        // 终端登录用 Run，用户可直接照着敲。
        val decorated = QuestAuthWaitDetector.decorateError(
            authWaitSeen = true,
            errorTail = "exit code 1",
            fallback = "error",
            remediationDetail = "codex login",
            remediationKind = com.kapp.tenon.domain.DriverAuthRemediationUi.TERMINAL_LOGIN,
        )
        assertTrue(decorated!!.contains("Run 'codex login'"), "确切命令必须出现在失败提示里")
        assertTrue(decorated.endsWith(" · exit code 1"), "错误尾巴仍在最后")
    }

    @Test
    fun envVarActionUsesSetNotRun() {
        // dsh 的词表值是环境变量名（DEEPSEEK_API_KEY），措辞必须是 Set 而非 Run，
        // 否则用户会去终端敲一个变量名。
        val decorated = QuestAuthWaitDetector.decorateError(
            authWaitSeen = true,
            errorTail = null,
            fallback = "error",
            remediationDetail = "DEEPSEEK_API_KEY",
            remediationKind = com.kapp.tenon.domain.DriverAuthRemediationUi.TOKEN_ENV,
        )
        assertTrue(decorated!!.contains("Set 'DEEPSEEK_API_KEY'"))
        assertFalse(decorated.contains("Run '"))
    }

    @Test
    fun emptyDetailFallsBackToGenericRemediation() {
        val decorated = QuestAuthWaitDetector.decorateError(
            authWaitSeen = true,
            errorTail = null,
            fallback = "error",
            remediationDetail = "",
        )
        assertTrue(decorated!!.startsWith("Authentication required: the CLI was waiting for login."))
        assertFalse(decorated.contains("Run '"))
    }

    @Test
    fun usesFallbackWhenThereIsNoTail() {
        val decorated = QuestAuthWaitDetector.decorateError(
            authWaitSeen = true,
            errorTail = null,
            fallback = "error",
        )
        assertTrue(decorated!!.contains(" · error"), "没有尾巴时兜底也带上（摘要绝不空白）")
    }

    @Test
    fun leavesErrorsUntouchedWhenAuthWasNotSeen() {
        assertEquals(
            "exit code 1",
            QuestAuthWaitDetector.decorateError(authWaitSeen = false, errorTail = "exit code 1", fallback = "error"),
        )
        assertNull(
            QuestAuthWaitDetector.decorateError(authWaitSeen = false, errorTail = null, fallback = "error"),
            "未命中时不许无中生有——正常失败保持原样",
        )
    }

    @Test
    fun successPathNeverCarriesTheRemediation() {
        // CLI 内部重试自愈的场景：auth 行出现过但任务 finished —— 调用方按
        // authWaitSeen && !ok 调用；这里钉住装饰函数本身不改判 ok。
        assertNull(
            QuestAuthWaitDetector.decorateError(authWaitSeen = false, errorTail = null, fallback = "finished"),
        )
    }

    // ---- 反向验证 ----

    @Test
    fun oldUndetectedBehaviorWouldFailTheDecorationAssertions() {
        val decorated = QuestAuthWaitDetector.decorateError(
            authWaitSeen = true,
            errorTail = "authentication timed out",
            fallback = "error",
        )
        // 旧行为（不检测）的输出 = 原始尾巴，没有补救指引 —— 断言两者不同，
        // 证明本测试区分得出新旧两种世界。
        kotlin.test.assertNotEquals("authentication timed out", decorated)
    }
}
