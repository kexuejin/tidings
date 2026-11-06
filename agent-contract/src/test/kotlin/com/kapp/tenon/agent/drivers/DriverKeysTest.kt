// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 驱动键归一化的取值域（与抽取前的两份实现逐条对表）。
 *
 * 这些用例的存在理由是「搬模块零行为变更」：别名规则从
 * ControlPlane.resolveCliDriver 与 AgentProfiles.normalizeKey 两处收敛到这里，
 * 收敛本身若改了任何一条匹配，都会在这里的某一格上显形。
 */
class DriverKeysTest {

    @Test
    fun canonicalKeyLowercasesTrimsAndFoldsTheAntigravityAlias() {
        assertEquals("agy", DriverKeys.canonicalKey("antigravity"))
        assertEquals("agy", DriverKeys.canonicalKey("  Antigravity  "))
        assertEquals("agy", DriverKeys.canonicalKey("agy"))
        assertEquals("codex", DriverKeys.canonicalKey(" CODEX "))
        // 去 `-cli` 后缀不在这里做：那是 stripCliSuffix 的职责，canonicalKey 保持单一语义。
        assertEquals("codex-cli", DriverKeys.canonicalKey("codex-cli"))
        assertNull(DriverKeys.canonicalKey(null))
        assertNull(DriverKeys.canonicalKey(""))
        assertNull(DriverKeys.canonicalKey("   "))
    }

    @Test
    fun stripCliSuffixIsIdempotent() {
        assertEquals("codex", DriverKeys.stripCliSuffix("codex-cli"))
        assertEquals("codex", DriverKeys.stripCliSuffix("codex"))
        assertEquals("agy", DriverKeys.stripCliSuffix("agy"))
        assertEquals("", DriverKeys.stripCliSuffix("-cli"))
        // 只去**一个**后缀：`codex-cli-cli` 不是约定形态，别在这里做清洗。
        assertEquals("codex-cli", DriverKeys.stripCliSuffix("codex-cli-cli"))
    }

    @Test
    fun aliasesReproduceTheFourHistoricMatchRules() {
        // 规则 1/2：键本身、键 + `-cli`（去后缀再拼与前者同形时自然去重）。
        assertEquals(linkedSetOf("codex", "codex-cli"), DriverKeys.driverIdAliases("codex"))
        // 规则 3：`antigravity` 额外映射 `agy-cli`。
        assertEquals(
            linkedSetOf("antigravity", "antigravity-cli", "agy-cli"),
            DriverKeys.driverIdAliases("antigravity"),
        )
        // 规则 4：已经带后缀的写法，去后缀再拼会回到自身；`key + "-cli"` 仍按原样展开。
        assertEquals(linkedSetOf("codex-cli", "codex-cli-cli"), DriverKeys.driverIdAliases("codex-cli"))
        // 空键 = 空集（调用方据此提前返回 null，而不是拿空串去撞第一个驱动）。
        assertTrue(DriverKeys.driverIdAliases(null).isEmpty())
        assertTrue(DriverKeys.driverIdAliases("  ").isEmpty())
    }

    @Test
    fun matchesDriverIdUsesTheAliasSetOnly() {
        assertTrue(DriverKeys.matchesDriverId("codex", "codex"))
        assertTrue(DriverKeys.matchesDriverId("codex", "codex-cli"))
        assertTrue(DriverKeys.matchesDriverId("antigravity", "agy-cli"))
        assertTrue(DriverKeys.matchesDriverId("agy", "agy-cli"))
        assertFalse(DriverKeys.matchesDriverId("codex", "claude-cli"))
        assertFalse(DriverKeys.matchesDriverId("codex", "native"))
        assertFalse(DriverKeys.matchesDriverId("", "native"))
        // 驱动 id 不做二次归一：注入一个不合约定的大写 id 必须判不匹配（否则约定永远不被发现）。
        assertFalse(DriverKeys.matchesDriverId("codex", "CODEX-CLI"))
    }
}
