// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.HighRiskCommands
import com.kapp.tenon.orchestration.LeaseBarrier
import com.kapp.tenon.orchestration.LeaseToken

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L3 高危租约屏障判定件的全组用例（计划书 §八.2 L3 / §九.2「高危操作租约屏障」）。
 *
 * 时序判定全部走**注入时钟**（拨毫秒推进「审批迟到 60 秒」），不用固定 delay 赌 ——
 * AGENTS 测试纪律与 envDependentTestGate 的共同要求，与 [DesktopWorkerWatchdogTest]
 * 的看门狗用例同一口径。
 */
class HighRiskLeaseTest {

    // ------------------------------------------------------------ 词表：命中与边界

    /** 词表三类动作各抽一条正样本：命中 ⇒ 必须先拿租约。 */
    @Test
    fun wordListHitsAllThreeHighRiskCategories() {
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("rm -rf build"), "破坏性删除命中");
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("git reset --hard"), "配置重置命中");
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("git clean -fd"), "配置清扫命中");
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("drop table users"), "库表抹除命中");
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("ngrok http 8080"), "网络穿透命中");
    }

    /** 词表常量本身可测：三类词都在表里，防止哪天被静默删空。 */
    @Test
    fun wordListConstantCarriesTheThreeCategories() {
        assertTrue(HighRiskCommands.LIST.contains("rm -rf"), "递归强删必须在表里");
        assertTrue(HighRiskCommands.LIST.contains("git reset --hard"), "配置重置必须在表里");
        assertTrue(HighRiskCommands.LIST.contains("ngrok"), "网络穿透必须在表里");
        assertTrue(HighRiskCommands.LIST.isNotEmpty(), "词表不许为空 —— 空表 = 屏障不存在");
    }

    /** 边界：`rm -rfx` 是另一个词，不许被前缀同形误伤。 */
    @Test
    fun safeWordSharingThePrefixIsNotCollateralDamage() {
        assertEquals(HighRiskCommands.Verdict.SAFE, HighRiskCommands.classify("rm -rfx"), "尾部多出的字符 = 另一个词");
        assertEquals(HighRiskCommands.Verdict.SAFE, HighRiskCommands.classify("rm -rfx file"), "带参数的 rfx 同样不命中");
    }

    /** 边界：大小写不敏感 —— 删除不区分大小写，屏障也不该区分。 */
    @Test
    fun matchingIgnoresCase() {
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("RM -RF build"));
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("Rm -Rf src"));
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("NGROK http 8080"));
    }

    /** 边界：参数中缀命中 —— 危险不因为前面垫了别的词而消失。 */
    @Test
    fun hitInsideTheArgumentPositionStillMatches() {
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("xargs rm -rf"), "词表词出现在参数位");
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("cd /tmp && rm -rf build"), "拼接命令里的删除段");
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("echo go && ngrok start"), "拼接命令里的隧道段");
    }

    /** 边界：多 token 词表词要求相邻 —— 中间隔开就不是那条命令。 */
    @Test
    fun splitTokensOfAMultiWordEntryDoNotMatch() {
        assertEquals(HighRiskCommands.Verdict.SAFE, HighRiskCommands.classify("rm -f x -rf"), "-f 与 -rf 被隔开，不是递归强删");
        assertEquals(HighRiskCommands.Verdict.SAFE, HighRiskCommands.classify("git reset"), "缺 --hard 的软重置不走屏障");
        assertEquals(HighRiskCommands.Verdict.HIGH_RISK, HighRiskCommands.classify("git reset --hard HEAD~1"), "完整重置串命中");
    }

    /** 日常安全命令整批放行：屏障不许把正常工作也扣下来（误伤 = 屏障被拆掉的压力）。 */
    @Test
    fun everydayCommandsStaySafe() {
        for (safe in listOf(
            "ls -la", "./gradlew test", "git status", "git commit -m msg",
            "rm build/single.txt", "mkdir out", "rm -r build/objects",
        )) {
            assertEquals(HighRiskCommands.Verdict.SAFE, HighRiskCommands.classify(safe), "误伤：" + safe);
        }
    }

    /** 空串与纯空白不是高危 —— 没有命令就没有动作。 */
    @Test
    fun blankCommandIsSafe() {
        assertEquals(HighRiskCommands.Verdict.SAFE, HighRiskCommands.classify(""));
        assertEquals(HighRiskCommands.Verdict.SAFE, HighRiskCommands.classify("   "));
    }

    // ------------------------------------------------------------ 租约时效三态

    private fun token(issuedAtMs: Long, ttlMs: Long = LeaseBarrier.DEFAULT_TTL_MS) =
        LeaseToken(leaseId = "lease-1", command = "rm -rf build", issuedAtMs = issuedAtMs, ttlMs = ttlMs);

    /** 审批没回来 = PENDING：宿主扣着动作不放，也不提前作废。 */
    @Test
    fun noApprovalYetMeansPendingAndTheActionStaysHeld() {
        val t = token(issuedAtMs = 1_000_000L)
        assertFalse(LeaseBarrier.isTimedOut(t, nowMs = 1_000_000L), "签发瞬间没超时");
        assertFalse(LeaseBarrier.isTimedOut(t, nowMs = 1_000_000L + t.ttlMs), "TTL 边界那一拍还没超时（严格大于口径）");
        assertTrue(LeaseBarrier.isTimedOut(t, nowMs = 1_000_000L + t.ttlMs + 1), "TTL 过后必须报超时");
    }

    /** TTL 内到达的审批 = GRANTED（边界含端点：恰在 60s 整到达也算有效）。 */
    @Test
    fun approvalInsideTheTtlIsGranted() {
        val t = token(issuedAtMs = 0L, ttlMs = 60_000L)
        assertEquals(LeaseBarrier.Verdict.GRANTED, LeaseBarrier.evaluate(t, arrivedAtMs = 1), "秒批放行");
        assertEquals(LeaseBarrier.Verdict.GRANTED, LeaseBarrier.evaluate(t, arrivedAtMs = 59_999), "压线前一刻仍有效");
        assertEquals(LeaseBarrier.Verdict.GRANTED, LeaseBarrier.evaluate(t, arrivedAtMs = 60_000), "恰在 TTL 边界到达仍按有效读");
    }

    /** 超过 TTL 才到的审批 = EXPIRED（fail-closed：执行器拒绝并销毁现场）。 */
    @Test
    fun approvalAfterTheTtlIsExpiredFailClosed() {
        val t = token(issuedAtMs = 0L, ttlMs = 60_000L)
        assertEquals(LeaseBarrier.Verdict.EXPIRED, LeaseBarrier.evaluate(t, arrivedAtMs = 60_001), "过线 1ms 也作废");
        assertEquals(LeaseBarrier.Verdict.EXPIRED, LeaseBarrier.evaluate(t, arrivedAtMs = 3600_000), "迟到一个小时更是作废");
    }

    /** 异常时钟：审批时刻早于签发时刻按 EXPIRED 读 —— 时钟回拨不许变成「永远有效」。 */
    @Test
    fun arrivalBeforeIssuanceIsExpiredNotEternallyValid() {
        val t = token(issuedAtMs = 10_000L, ttlMs = 60_000L)
        assertEquals(LeaseBarrier.Verdict.EXPIRED, LeaseBarrier.evaluate(t, arrivedAtMs = 9_999));
        assertEquals(LeaseBarrier.Verdict.EXPIRED, LeaseBarrier.evaluate(t, arrivedAtMs = 0L));
    }

    /** 短 TTL 的同一条判定：TTL 是参数，不是常数假设。 */
    @Test
    fun shortTtlFollowsTheSameBoundarySemantics() {
        val t = token(issuedAtMs = 500L, ttlMs = 100L)
        assertEquals(LeaseBarrier.Verdict.GRANTED, LeaseBarrier.evaluate(t, arrivedAtMs = 600));
        assertEquals(LeaseBarrier.Verdict.EXPIRED, LeaseBarrier.evaluate(t, arrivedAtMs = 601));
        assertTrue(LeaseBarrier.isTimedOut(t, nowMs = 601));
        assertFalse(LeaseBarrier.isTimedOut(t, nowMs = 600));
    }

    /** 默认 TTL 常量钉在 60 秒 —— 计划书 §八.2 的时效，改它必须是有意的。 */
    @Test
    fun defaultTtlIsSixtySeconds() {
        assertEquals(60_000L, LeaseBarrier.DEFAULT_TTL_MS);
    }
}
