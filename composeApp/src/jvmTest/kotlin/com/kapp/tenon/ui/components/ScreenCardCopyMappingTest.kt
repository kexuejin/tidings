// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.core.protocol.ScreenPhase
import com.kapp.tenon.core.protocol.ScreenRole
import com.kapp.tenon.domain.SCREEN_INTERRUPTED_REASON
import com.kapp.tenon.domain.ScreenCardCase
import com.kapp.tenon.domain.model.SCREEN_RECORD_VERSION
import com.kapp.tenon.domain.model.ScreenRecordUi
import com.kapp.tenon.domain.screenCardCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 屏幕协助卡（原型 `m-screencard`）的**文案口径**。
 *
 * 领域侧已有 `ScreenHistoryRecordTest.statusWordMappingCoversEveryCase` 在判「发生了什么」
 * （19 档 `ScreenCardCase` 的分叉），这里补的是另一半 —— **判完之后写哪句话**：
 *
 * - `screenCardStatusKey` / `screenCardTextKey` 是两个 19 分支的 `when`，两处都靠手写。
 *   少一档会编译失败，但**两档撞到同一个键不会** —— 症状是「拒绝者自己看到对方拒绝」
 *   这类错话，而且截图上看不出异常。所以这里钉**单射**：19 档必须映到 19 个不同键。
 * - `screenDurationText` / `screenCardClock` 是纯算术，卡面上逐字比对的就是它们。
 *   整数除法、进位、跨天三个边界都得钉住（时长格式规范 / 时间分割线规则）。
 *
 * 徽标与状态词的**文案本身**不在这里断言：那是资源文件的事，
 * `guards/CardVocabularyGuardTest` 守「每档都有中英文」。
 */
class ScreenCardCopyMappingTest {

    private fun record(
        phase: ScreenPhase,
        reason: String? = null,
        role: ScreenRole = ScreenRole.Viewer,
        startedAt: Long? = null,
        endedAt: Long? = null,
        durationMs: Long? = null,
    ) = ScreenRecordUi(
        v = SCREEN_RECORD_VERSION,
        role = role,
        phase = phase,
        reason = reason,
        requestedAt = 1L,
        startedAt = startedAt,
        endedAt = endedAt,
        durationMs = durationMs,
    )

    /**
     * 19 档各一条记录（构造与领域侧用例同一套：原因码 + 角色决定分叉）。
     *
     * 用 `ScreenCardCase` 当键，是为了能断言「这条表覆盖了枚举的每一档」——
     * 将来新增一档而这里没跟上，`size` 与 `entries` 对不上会直接红。
     */
    private fun recordsByCase(): Map<ScreenCardCase, ScreenRecordUi> = mapOf(
        ScreenCardCase.Requesting to record(ScreenPhase.Requesting),
        ScreenCardCase.AwaitingConsent to record(ScreenPhase.AwaitingConsent),
        ScreenCardCase.Connecting to record(ScreenPhase.Connecting),
        ScreenCardCase.Active to record(ScreenPhase.Active, startedAt = 2L),
        ScreenCardCase.DeclinedSelf to record(ScreenPhase.Ended, "declined", role = ScreenRole.Sharer),
        ScreenCardCase.DeclinedPeer to record(ScreenPhase.Ended, "declined", role = ScreenRole.Viewer),
        ScreenCardCase.BusySelf to record(ScreenPhase.Error, "busy", role = ScreenRole.Sharer),
        ScreenCardCase.BusyPeer to record(ScreenPhase.Error, "busy", role = ScreenRole.Viewer),
        ScreenCardCase.Unsupported to record(ScreenPhase.Error, "unsupported"),
        ScreenCardCase.PermissionDenied to record(ScreenPhase.Error, "permission-denied"),
        ScreenCardCase.CaptureStopped to record(ScreenPhase.Error, "capture-failed"),
        ScreenCardCase.Timeout to record(ScreenPhase.Error, "timeout"),
        ScreenCardCase.Disconnected to record(ScreenPhase.Error, "disconnected"),
        ScreenCardCase.ProtocolError to record(ScreenPhase.Error, "protocol-error"),
        ScreenCardCase.Locked to record(ScreenPhase.Ended, "locked"),
        ScreenCardCase.Suspended to record(ScreenPhase.Ended, "suspended"),
        ScreenCardCase.Interrupted to record(ScreenPhase.Ended, SCREEN_INTERRUPTED_REASON),
        ScreenCardCase.Ended to record(ScreenPhase.Ended, null, startedAt = 2L),
        ScreenCardCase.Canceled to record(ScreenPhase.Ended, null, startedAt = null),
    )

    /** 这张表本身要成立：每档记录真的判成它自己那一档，且枚举一档不落。 */
    @Test
    fun caseTableCoversTheWholeEnumAndMatchesItsOwnKeys() {
        val table = recordsByCase()
        assertEquals(
            ScreenCardCase.entries.toSet(),
            table.keys,
            "枚举与这张表必须一一对应：新增一档而这里没跟上，文案就没人验",
        )
        val mismatched = table.filter { (expected, rec) -> screenCardCase(rec) != expected }
        assertTrue(mismatched.isEmpty(), "这些记录判出来的档与自己标的不一致：${mismatched.keys}")
    }

    /**
     * 19 档必须映到 19 个**不同**的状态词键。
     *
     * 撞键的后果不是样式问题，是**说错话**：`DeclinedSelf`（本侧拒绝了对方）与
     * `DeclinedPeer`（对方拒绝了我）共用一个键，就必然有一边看到相反的叙述。
     */
    @Test
    fun statusKeysAreInjective() {
        val keys = recordsByCase().mapValues { (_, rec) -> screenCardStatusKey(rec).key }
        assertTrue(keys.values.all { it.isNotBlank() }, "有空键：那一档会显示成空字符串")
        val byKey = keys.entries.groupBy({ it.value }, { it.key })
        val collisions = byKey.filterValues { it.size > 1 }
        assertTrue(collisions.isEmpty(), "这些档共用同一个状态词键（必然有一边说错话）：$collisions")
        assertEquals(ScreenCardCase.entries.size, keys.values.toSet().size, "19 档应映到 19 个键")
    }

    /** 完整句子同上：正文要进库、进列表预览，撞键会让两条不同的经历写成同一句话。 */
    @Test
    fun textKeysAreInjective() {
        val keys = recordsByCase().mapValues { (_, rec) -> screenCardTextKey(rec).key }
        assertTrue(keys.values.all { it.isNotBlank() }, "有空键：那一档的正文会是空字符串")
        val byKey = keys.entries.groupBy({ it.value }, { it.key })
        val collisions = byKey.filterValues { it.size > 1 }
        assertTrue(collisions.isEmpty(), "这些档共用同一句正文（两条不同的经历写成同一句话）：$collisions")
        assertEquals(ScreenCardCase.entries.size, keys.values.toSet().size, "19 档应映到 19 个键")
    }

    /**
     * 一处容易写错的映射：`CaptureStopped` 的正文键是 `capture_failed`，不是 `capture_stopped`。
     *
     * 状态词叫「已停止」、正文叫「截取中断」，两套词本来就不同名 —— 曾经靠人肉对照，
     * 现在钉住；改文案时改 `strings.xml` 里的值，不要改键。
     */
    @Test
    fun captureStoppedUsesTheFailedCopyKeyNotItsOwnName() {
        val rec = recordsByCase().getValue(ScreenCardCase.CaptureStopped)
        assertEquals("screen_card_text_capture_failed", screenCardTextKey(rec).key)
    }

    /** 时长：`mm:ss`，满一小时才带小时段；负值夹到 0（clock 抖动不该画出「-1:-1」）。 */
    @Test
    fun durationTextIsMinutesAndSecondsUntilOneHour() {
        assertEquals("00:00", screenDurationText(0L))
        assertEquals("00:00", screenDurationText(-5_000L), "负值夹到 0：时钟回拨不该画出负时长")
        assertEquals("00:00", screenDurationText(999L), "不足一秒向下取整")
        assertEquals("00:59", screenDurationText(59_000L))
        assertEquals("01:00", screenDurationText(60_000L))
        assertEquals("59:59", screenDurationText(3_599_000L))
        assertEquals("1:00:00", screenDurationText(3_600_000L), "满一小时才出小时段")
        assertEquals("1:01:01", screenDurationText(3_661_000L))
        assertEquals("100:00:00", screenDurationText(360_000_000L), "小时段不补零")
    }

    /** 时间：**今天只写时分，跨天带年月日**（时间分割线规则）。 */
    @Test
    fun clockShowsDateOnlyWhenItIsNotToday() {
        // 0L = 1970-01-01 08:00（显示口径硬编码 UTC+8，与 formatClock 同源）。
        assertEquals("08:00", screenCardClock(0L, 0L), "同一天只写时分")
        assertEquals(
            "1970-01-01 08:00",
            screenCardClock(0L, 86_400_000L),
            "隔天必须带日期，否则「昨天 23:50」与「今天 23:50」无法区分",
        )
        // 跨年界：1970-12-31 23:59（UTC+8）与 1971-01-01 00:01。
        val dec31 = 31_507_140_000L
        assertEquals("1970-12-31 23:59", screenCardClock(dec31, dec31 + 120_000L))
    }
}
