// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫 UX 规范 V1 §2.5②「智能体卡片底部展示最近一次交付物标签」。
 *
 * ## 病灶
 *
 * 任务产出的 HTML 原型 / Markdown 规范**只落在消息流里**：卡上能看见「正在执行」「最近活动」，
 * 却看不见「它刚给了你什么」。要找回那份 `design.html`，得先进会话、再在几十条消息里翻。
 *
 * ## 修法（纯 UI，零协议键、零迁移）
 *
 * - 投影层把**最近收到的一件产物**挂到实体卡上。归因只走 `sessionClusterBySessionId`
 *   —— 与会话、审批**同一把身份尺子**，不在产物这里另写一套 `agentId` 归一。
 * - 卡片底部画一枚标签：文件名 + 相对时刻。点它落到**产物所属的那条会话**（产物卡在
 *   会话详情里）—— 不直接拉二进制：这里只有元数据，产物本体靠 `fileId` 指的那条 `file-msg`。
 *
 * ## 为什么文案不写「最近一次**成功**的交付」
 *
 * `agent-artifact` 帧里**没有成败位**（只有文件名 / 大小 / 摘要 / 哈希）。接收侧没有
 * 「这次交付成功了」的独立证据，唯一能如实说的是「最近收到过一件产物」。所以字段名、
 * 资源文案、提交信息都不写「成功」—— 不替协议编一个它没有的语义。
 *
 * 反向验证：守卫先于实现落地，实现前判红；实现后转绿。逐片段摘除的变异自检保证
 * 「文件里出现过这个词」不算接线。
 */
class AgentLatestArtifactGuardTest {

    private val projectionPath = "app/src/commonMain/kotlin/com/kapp/tenon/domain/AgentHubProjection.kt"
    private val cardsPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentHubCards.kt"
    private val zhPath = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val enPath = "composeApp/src/commonMain/composeResources/values-en/strings.xml"

    private fun live(path: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val xmlComment = Regex("""<!--[\s\S]*?-->""")
    private val declaredEntry = Regex("""<string\s+name=""")
    private val entry = Regex("""<string\s+name="([^"]+)"\s*>([\s\S]*?)</string>""")

    private fun values(xml: String): Map<String, String> {
        val stripped = xmlComment.replace(xml, "")
        val declared = declaredEntry.findAll(stripped).count()
        val parsed = entry.findAll(stripped).associate { it.groupValues[1] to it.groupValues[2].trim() }
        return if (declared == parsed.size) parsed else emptyMap()
    }

    private val keys = listOf("hub_artifact_latest_tag")

    /** 投影层必须有的片段：模型、字段、归因（走会话→聚类那张表）、装配、出卡。 */
    private val projectionFragments = listOf(
        "public data class AgentHubArtifactUi(",
        "val latestArtifact: AgentHubArtifactUi? = null",
        "val latestArtifactByCluster: Map<HubCluster, AgentArtifactUi>",
        "sessionClusterBySessionId[artifact.sessionId]",
        "index.latestArtifactByCluster[cluster]",
        "latestArtifact = latestArtifact,",
    )

    /** 卡片层必须有的片段：取值、文案键、点击落到产物所属会话。 */
    private val cardsFragments = listOf(
        "card.latestArtifact?.let {",
        "Res.string.hub_artifact_latest_tag",
        // 两段合起来才是「点它 → 落到产物所属那条会话」：只留 `open(...)` 看不出是谁给的
        // 回调，只留 `onOpenRowSession?.let` 看不出它落到哪条会话。
        "onOpenRowSession?.let { open ->",
        "open(card.hostNodeId, artifact.sessionId)",
    )

    private fun wired(projection: String, cards: String, zh: String, en: String): Boolean {
        val zhValues = values(zh)
        val enValues = values(en)
        if (zhValues.isEmpty() || enValues.isEmpty()) return false
        if (keys.any { zhValues[it].isNullOrBlank() || enValues[it].isNullOrBlank() }) return false
        return projectionFragments.all { projection.contains(it) } &&
            cardsFragments.all { cards.contains(it) }
    }

    @Test
    fun latestArtifactIsProjectedAndRenderedEndToEnd() {
        assertTrue(
            wired(live(projectionPath), live(cardsPath), GuardSourceScan.read(zhPath), GuardSourceScan.read(enPath)),
            "§2.5② 的「最近交付」没有全链路接线：投影 → 卡片 → 资源键",
        )
    }

    @Test
    fun attributionReusesTheSessionClusterRuler() {
        val projection = live(projectionPath)
        // 归因必须走**会话→聚类**那张表；另起一套 agentId 归一 = 同一事实两个来源。
        assertTrue(
            projection.contains("sessionClusterBySessionId[artifact.sessionId]"),
            "产物没有走会话→聚类那张表归因 —— 身份尺子出现了第二把",
        )
        assertFalse(
            projection.contains("artifact.agentId"),
            "产物模型里没有 agentId 这个字段，拿它归因说明这里在猜身份",
        )
    }

    @Test
    fun wordingDoesNotClaimSuccess() {
        val zh = values(GuardSourceScan.read(zhPath))["hub_artifact_latest_tag"].orEmpty()
        val en = values(GuardSourceScan.read(enPath))["hub_artifact_latest_tag"].orEmpty()
        assertTrue(zh.isNotBlank() && en.isNotBlank(), "资源键缺失")
        // 协议没有产物的成败位，文案不许替它断言「成功」。
        assertFalse(zh.contains("成功"), "中文文案断言了协议里不存在的「成功」：$zh")
        assertFalse(en.contains("success", ignoreCase = true), "英文文案断言了协议里不存在的成功：$en")
        assertFalse(zh.contains("工作区"), "中文文案带回了工程词汇：$zh")
        assertFalse(en.contains("workspace", ignoreCase = true), "英文文案带回了工程词汇：$en")
    }

    @Test
    fun bothLocalesStayInParity() {
        val zhValues = values(GuardSourceScan.read(zhPath))
        val enValues = values(GuardSourceScan.read(enPath))
        assertTrue(zhValues.isNotEmpty() && enValues.isNotEmpty(), "资源解析完整性")
        keys.forEach { key ->
            assertTrue(zhValues.containsKey(key), "中文资源缺 $key")
            assertTrue(enValues.containsKey(key), "英文资源缺 $key")
        }
        assertEquals(zhValues.size, enValues.size, "双语逐键对账")
    }

    @Test
    fun everyCriticalFragmentIsRemovalSensitive() {
        val projection = live(projectionPath)
        val cards = live(cardsPath)
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        assertTrue(wired(projection, cards, zh, en), "变异自检的起点必须是绿")

        projectionFragments.forEach { fragment ->
            assertFalse(
                wired(projection.replace(fragment, ""), cards, zh, en),
                "投影里摘掉「$fragment」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        cardsFragments.forEach { fragment ->
            assertFalse(
                wired(projection, cards.replace(fragment, ""), zh, en),
                "卡片里摘掉「$fragment」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        keys.forEach { key ->
            val mutatedZh = zh.replace(Regex("""<string name="$key">[\s\S]*?</string>"""), "")
            assertFalse(wired(projection, cards, mutatedZh, en), "$key 从中文资源摘掉后判据仍为真")
            val mutatedEn = en.replace(Regex("""<string name="$key">[\s\S]*?</string>"""), "")
            assertFalse(wired(projection, cards, zh, mutatedEn), "$key 从英文资源摘掉后判据仍为真")
        }
    }

    @Test
    fun guardIsNotFooledByCommentsOrEmptySources() {
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        assertFalse(wired("", "", zh, en), "空源码必须判红，不能恒真")
        assertFalse(
            wired(
                "// val latestArtifact: AgentHubArtifactUi? = null\n/* sessionClusterBySessionId[artifact.sessionId] */",
                "/* card.latestArtifact?.let { */\n// open(card.hostNodeId, artifact.sessionId)",
                zh,
                en,
            ),
            "只出现在注释里的片段不能算接线",
        )
    }
}
