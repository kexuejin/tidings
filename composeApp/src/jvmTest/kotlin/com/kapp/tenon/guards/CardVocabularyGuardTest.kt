// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.feature.chat.CardTypeRegistry
import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 卡片「词表」在两端的对账：**写的必须是读的那一套，登记的必须画得出来**。
 *
 * ## 为什么这两条只能在源码层守
 *
 * 两个缺口都不会编译失败、不会抛异常，真机上只表现为「颜色不对」和「徽标印英文」：
 *
 * 1. **tone 词表**：解析侧 `when` 只认 `success` / `warn` / `error`，其余（含 `warning`、
 *    `danger`、`done`）一律落 `else -> Info`。发射侧写错一个字母，告警横幅就变成普通
 *    信息色 —— `TicketCardBudget` 的总预算告警确实写过 `warning`。
 * 2. **类型徽标**：`CardTypeTag` 的 `when` 缺一档就落 `else -> type to ic_bot`，
 *    界面上直接印出 `team-task-board` 这样的英文原文。`team-task-board` 与
 *    `direction-ask` 都真在发，而当时注册表与徽标两边都没有它们。
 *
 * 这两件事都是**同一份词表的两处抄写**，所以判据放在这里：改了一边没改另一边，红。
 */
class CardVocabularyGuardTest {

    /** banner tone（`success`/`warn`/`error`/`info`）与 chip tone（`pri`/`ok`/`warn`/`err`）的并集。 */
    private val canonicalTones = setOf("success", "warn", "error", "info", "pri", "ok", "err")

    /**
     * 发射侧不许写词表以外的 tone。
     *
     * 扫的四个根都是**活代码**（剥注释、跳过 `build/`）。chip 与 banner 共用 `tone` 这个键，
     * 所以判据只能是并集 —— 但「`warning`」这种拼法对两边都不成立，正是要拦的。
     */
    @Test
    fun `发射侧的 tone 字面量必须落在两端词表的并集里`() {
        val roots = listOf(
            "orchestration/src/main",
            "agentNode/src/main",
            "app/src/commonMain",
            "composeApp/src/commonMain",
        )
        val literal = Regex("""put\(\s*"tone"\s*,\s*(?:JsonPrimitive\(\s*)?"([^"]*)"""")
        val offenders = mutableListOf<String>()
        var scanned = 0

        for (path in GuardSourceScan.kotlinFiles(*roots.toTypedArray())) {
            val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            for (m in literal.findAll(live)) {
                scanned++
                val tone = m.groupValues[1]
                if (tone !in canonicalTones) {
                    // 行号从剥过注释的文本里数：stripComments 保留换行，所以对得上。
                    val line = live.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "$path:$line -> \"$tone\""
                }
            }
        }

        assertTrue(scanned > 5, "只扫到 $scanned 处 tone 字面量，扫描根或正则已经失效（假绿）")
        assertTrue(
            offenders.isEmpty(),
            "这些 tone 不在词表里（$canonicalTones）：$offenders。" +
                " 解析侧对未知 tone 一律落 Info —— 告警横幅会静默变成普通信息色。",
        )
    }

    /**
     * 有徽标文案、但**不是卡片类型**的值（只作别名兜底）。
     *
     * `prototype` 是 `state`（`StageChain` 的 `progress.state == "prototype"`）与
     * 产物 intent（`ArtifactPipe.INTENT_PROTOTYPE`）的同名值，从来没有 `put("type","prototype")`
     * 的发射者，所以注册表里没有它 —— 但插件可能照同名发，保留这一档文案比印英文好。
     *
     * 表里多一项就意味着「注册表缺一条建议构成」，所以两边互斥要显式断言。
     */
    private val labelOnlyTypes = mapOf(
        "prototype" to "state=prototype 与产物 intent 的同名值，无 type 发射者",
    )

    /**
     * 注册表里每一类都必须有徽标文案，且中英两份资源都在。
     *
     * 判据分三段：注册表 → `CardTypeTag` 的 `when` 分支 → 资源键 → 两份 `strings.xml`。
     * 缺任何一段的后果都是同一个：界面上印出英文原文（或空字）。
     */
    @Test
    fun `注册表里每一类都要有中英文徽标文案`() {
        val tagSource = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/AgentCards.kt"),
        )
        val whenBody = GuardSourceScan.bracedBlockAfter(tagSource, "private fun CardTypeTag(")
        val branches = Regex(""""([a-z0-9_-]+)"\s*->\s*stringResource\(Res\.string\.(card_type_[a-z0-9_]+)\)""")
            .findAll(whenBody)
            .associate { it.groupValues[1] to it.groupValues[2] }

        val registered = CardTypeRegistry.recommended.keys
        assertTrue(registered.isNotEmpty(), "注册表空了 —— 下面两条断言会在空集合上恒真")
        assertTrue(
            labelOnlyTypes.keys.none { it in registered },
            "「仅文案」表里的值不许同时是注册表里的卡片类型：${labelOnlyTypes.keys}",
        )
        assertEquals(
            registered + labelOnlyTypes.keys,
            branches.keys,
            "注册表与徽标分支必须逐项对齐：注册了但没分支 ⇒ 徽标印英文原文；" +
                "有分支但两边都没登记 ⇒ 要么补注册表，要么写进 labelOnlyTypes 并说明理由",
        )

        val zh = GuardSourceScan.read("composeApp/src/commonMain/composeResources/values/strings.xml")
        val en = GuardSourceScan.read("composeApp/src/commonMain/composeResources/values-en/strings.xml")
        val missing = branches.filter { (_, key) ->
            "name=\"$key\"" !in zh || "name=\"$key\"" !in en
        }
        assertTrue(
            missing.isEmpty(),
            "这些徽标文案缺中英资源（缺哪份都会让那一挡显示成空或原文）：$missing",
        )
    }
}
