// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import com.kapp.tenon.core.protocol.RosterWriteTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 写面档位的界面侧纯逻辑（[RosterWriteAdmin]）。
 *
 * 只钉一件事：**写盘失败时不许把界面改成新档**。
 *
 * 这不是洁癖。档位的全部意义是「我信了这个 agent，所以不用每次点同意」——
 * 界面显示「直接执行」而宿主内存里还是 `request` 时，用户会以为已经放行、
 * 结果每一次写都被弹卡，而他会去怀疑 agent 坏了。反过来（界面显示 request、
 * 宿主其实是 auto）更糟：他以为每次都会被问，实际上没有。
 */
class RosterWriteAdminTest {

    private class FakeSource(
        var tier: String = RosterWriteTier.REQUEST,
        var failWrite: Boolean = false,
        var loadError: String? = null,
    ) : RosterWriteTierSource {
        var writes: MutableList<String> = mutableListOf()

        override fun tier(): String = tier

        override fun error(): String? = loadError

        override fun setTier(next: String): Boolean {
            writes += next
            if (failWrite) return false
            tier = next
            return true
        }
    }

    @Test
    fun aFreshInstallReadsAsAskEveryTime() {
        val admin = RosterWriteAdmin(FakeSource())
        assertEquals(RosterWriteTier.REQUEST, admin.current(), "没配过 = 每次都问（fail-closed）")
        assertNull(admin.error())
    }

    @Test
    fun switchingToAutoLandsInTheSource() {
        val source = FakeSource()
        val admin = RosterWriteAdmin(source)
        assertEquals(RosterWriteTier.AUTO, admin.apply(RosterWriteTier.AUTO))
        assertEquals(RosterWriteTier.AUTO, source.tier(), "档位要真的写下去，不是只改界面状态")
        assertEquals(listOf(RosterWriteTier.AUTO), source.writes)
    }

    @Test
    fun aFailedWriteKeepsTheOldTier() {
        val source = FakeSource(failWrite = true)
        val admin = RosterWriteAdmin(source)
        assertEquals(
            RosterWriteTier.REQUEST,
            admin.apply(RosterWriteTier.AUTO),
            "写不进去就必须回旧档 —— 乐观切换会让界面说「直接执行」而宿主仍每次都问",
        )
        assertEquals(RosterWriteTier.REQUEST, source.tier())
    }

    @Test
    fun aValueOutsideTheDomainIsIgnored() {
        val source = FakeSource()
        val admin = RosterWriteAdmin(source)
        assertEquals(RosterWriteTier.REQUEST, admin.apply("sometimes"))
        assertEquals(emptyList(), source.writes, "域外取值连写都不该尝试")
    }

    @Test
    fun aCorruptTierFileSurfacesItsReason() {
        val source = FakeSource(tier = "whatever", loadError = "roster-write.json 的 tier=whatever 不认识")
        val admin = RosterWriteAdmin(source)
        assertEquals(RosterWriteTier.REQUEST, admin.current(), "宿主的未知取值在界面这一侧也按 request 显示")
        assertEquals("roster-write.json 的 tier=whatever 不认识", admin.error(), "读盘原因要能透到界面上")
    }
}
