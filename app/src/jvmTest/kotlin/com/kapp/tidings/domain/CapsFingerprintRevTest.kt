// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * caps 指纹与 profileRev 的联动（[RelaySettings.withCapsFingerprint]）。
 *
 * 背景：caps 会随实验开关（luciola）/平台能力变，却不经过任何"改资料"的入口；
 * 对端刷新资料的唯一判据是 profileRev（A13c）。没有这条联动，对端永远拿着旧 caps
 * 行事 —— 真机死锁现场（验证轮抓到）：手机注册表里桌面身份一直带着 `fch1`
 * （桌面早就不声明了），文件字节被登记到一条并不存在的信道上，两端互等。
 *
 * 钉住两条：
 * 1. caps 变了 ⇒ rev 恰好 +1、指纹更新；
 * 2. caps 没变 ⇒ **同一个实例**（不写盘、rev 不动）。
 */
class CapsFingerprintRevTest {

    private val base = RelaySettings.firstRun(defaultNick = "测试机")

    @Test
    fun capsChangeBumpsProfileRevExactlyOnce() {
        val before = base.profileRev
        val withFch1 = base.withCapsFingerprint(listOf("tw1", "mrec1", "fch1"))

        assertEquals(before + 1, withFch1.profileRev, "caps 变了必须恰好 bump 一档")
        assertNull(base.capsFingerprint, "旧实例不被改动（不可变模型）")
        assertNotEquals(
            RelaySettings.capsFingerprintOf(emptyList()),
            withFch1.capsFingerprint,
            "指纹必须如实反映这次声明的 caps",
        )

        val withoutFch1 = withFch1.withCapsFingerprint(listOf("tw1", "mrec1"))
        assertEquals(before + 2, withoutFch1.profileRev, "再变一次再 bump 一档")
    }

    @Test
    fun unchangedCapsReturnsSameInstance() {
        val caps = listOf("tw1", "mrec1")
        val aligned = base.withCapsFingerprint(caps)
        val again = aligned.withCapsFingerprint(caps)

        assertEquals(aligned, again, "caps 没变 ⇒ 原样返回，不产生第二次 bump")
        assertEquals(aligned.profileRev, again.profileRev)
    }

    @Test
    fun legacySettingsWithoutFingerprintGetBaselineOnFirstAlign() {
        // 旧版本落盘的设置没有指纹字段（null）。首轮对齐按"变了"处理：
        // bump 一档并记下基准 —— 之后才进入"没变不动"的稳态。
        val caps = listOf("tw1")
        assertNull(base.capsFingerprint)
        val aligned = base.withCapsFingerprint(caps)
        assertEquals(base.profileRev + 1, aligned.profileRev)
        assertEquals(
            RelaySettings.capsFingerprintOf(caps),
            aligned.capsFingerprint,
        )
    }
}
