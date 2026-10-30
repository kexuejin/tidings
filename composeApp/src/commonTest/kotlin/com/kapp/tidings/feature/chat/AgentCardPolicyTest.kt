// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.core.protocol.AgentExecAuthorizer.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设计稿 v2 阶段 A 的**纯函数判据**：expired 子态 / 类型注册表 / 动作资格。
 *
 * 三条都刻意做成「入参即全部事实」：不读时钟、不读全局、不碰渲染。
 * 这样它们能被合成输入钉死，也不会被 `envDependentTestGate` 判红 ——
 * 一条「过期了没有」的测试如果自己读 `System.currentTimeMillis()`，
 * 它在慢机器与快机器上就是两条不同的测试。
 */
class AgentCardPolicyTest {

    private fun parse(json: String): AgentCardUi =
        assertNotNull(parseAgentCard(Json.parseToJsonElement(json) as JsonObject))

    // ── expired 子态（S12 / §6：渲染层按 expiresAt 判，不改协议、不占四态）──────

    @Test
    fun expiryBoundaryIsInclusive() {
        // 正好到期算过期：留给用户的时间已经用完，按钮不该还能点。
        assertTrue(isCardExpired(expiresAt = 1_000L, now = 1_000L), "正好到期算过期")
        assertTrue(isCardExpired(expiresAt = 1_000L, now = 1_001L))
        assertFalse(isCardExpired(expiresAt = 1_000L, now = 999L), "还差 1 毫秒就还没过期")
    }

    @Test
    fun missingExpiresAtNeverExpires() {
        // 没有 expiresAt 的卡（绝大多数）永远不过期 —— 不能因为字段缺席就判成过期。
        assertFalse(isCardExpired(expiresAt = null, now = Long.MAX_VALUE))
    }

    @Test
    fun expiryIsJudgedByTheGivenNowNotTheWallClock() {
        // 同一张卡、两个 now：结论必须跟着入参走。读时钟会让「预览帧」无法固定，
        // 也会让这条判据在不同机器上给出不同结论。
        val card = parse("""{"cardId":"c","rev":1,"expiresAt":5000}""")
        assertEquals(5_000L, card.expiresAt)
        assertFalse(isCardExpired(card.expiresAt, now = 4_999L))
        assertTrue(isCardExpired(card.expiresAt, now = 5_001L))
        // 字段缺席时模型是 null，而不是 0（0 会被判成「1970 年就过期了」）。
        assertNull(parse("""{"cardId":"c","rev":1}""").expiresAt)
    }

    // ── 类型注册表（§3：type → 推荐构成，**渲染建议层不是白名单**）─────────────

    @Test
    fun registryCoversTheSevenDesignedTypes() {
        assertEquals(
            setOf("task-board", "approval", "result", "status", "timeout", "diff-review", "assignment"),
            CardTypeRegistry.recommended.keys,
            "设计稿 §3 的七类卡都要有推荐构成",
        )
        // 抽查两条容易画错的：审批必须有按钮组与警示横幅；状态轻通知**不该**有按钮。
        assertTrue(CardBlock.ButtonGroup in CardTypeRegistry.recommendedBlocksOf("approval").orEmpty())
        assertTrue(CardBlock.Banner in CardTypeRegistry.recommendedBlocksOf("approval").orEmpty())
        assertEquals(
            setOf(CardBlock.Banner),
            CardTypeRegistry.recommendedBlocksOf("status"),
            "status 是轻通知：只有横幅，给按钮就把「不抢眼」破坏了",
        )
    }

    @Test
    fun unknownTypeGetsNoRecommendationButIsNotRejected() {
        // 注册表查不到 ⇒ null（不是空集：空集读作「这类卡什么都不该画」，
        // null 读作「没有建议，你自己按块渲染」—— 两者语义不同）。
        assertNull(CardTypeRegistry.recommendedBlocksOf("some-future-type"))
        assertNull(CardTypeRegistry.recommendedBlocksOf(null))
        // 而解析照旧宽容：未知 type 仍然是一张能渲染的卡。
        val card = parse(
            """{"cardId":"c","rev":1,"type":"some-future-type","banner":{"text":"hi"},
                "actions":[{"actionId":"go","label":"Go"}]}""",
        )
        assertEquals("some-future-type", card.type)
        assertEquals(1, card.actions.size)
    }

    @Test
    fun renderingFollowsTheDataNotTheRegistry() {
        // 注册表是建议层：一张**不在注册表里**的卡，只要带了块的数据，块就还在。
        // 这条是「未知 type 不拒帧」在纯函数层的投影（协议侧早已裁决）。
        val unknown = parse(
            """{"cardId":"c","rev":1,"type":"some-future-type","progress":40,
                "items":[{"id":"1","label":"a","status":"done"}],
                "actions":[{"actionId":"go","label":"Go"}]}""",
        )
        assertEquals(
            setOf(CardBlock.Progress, CardBlock.StepList, CardBlock.ButtonGroup),
            presentBlocksOf(unknown),
        )
        // 反过来：注册表说 approval 该有横幅与按钮，但数据里没有 ⇒ 就一个块都没有。
        // 建议不创造内容 —— 空卡片不会因为 type 对了就凭空长出横幅。
        assertEquals(emptySet(), presentBlocksOf(parse("""{"cardId":"c","rev":1,"type":"approval"}""")))
    }

    @Test
    fun presentBlocksCoverAllSixAtomicComponents() {
        val full = parse(
            """{"cardId":"c","rev":1,"type":"result","progress":100,
                "banner":{"text":"done","tone":"success"},
                "diff":{"path":"a.kt","additions":3,"deletions":1},
                "items":[{"id":"1","label":"a","status":"done"}],
                "actions":[{"actionId":"open","label":"Open"}],
                "artifacts":[{"name":"report.pdf","bytes":2048,"mime":"application/pdf"}]}""",
        )
        assertEquals(CardBlock.values().toSet(), presentBlocksOf(full), "六类原子组件都要能被识别出来")
        assertEquals("report.pdf", full.artifacts.single().name)
        assertEquals(2_048L, full.artifacts.single().bytes)
    }

    // ── 动作资格（§4.8：判定在宿主，UI 只消费结论；无资格**不画按钮组**）───────

    @Test
    fun verdictsThatPermitExecutionAreActionable() {
        assertEquals(CardActionEligibility.Actionable, cardActionEligibilityOf(false, Verdict.AgentHost))
        assertEquals(CardActionEligibility.Actionable, cardActionEligibilityOf(false, Verdict.GrantedDevice))
        assertEquals(CardActionEligibility.Actionable, cardActionEligibilityOf(false, Verdict.AnyMemberGranted))
    }

    @Test
    fun memberWithoutGrantIsReadOnly() {
        // 群主/管理员也没有结构性特权（`AgentExecAuthorizer` 的既有口径），
        // 在卡片上同样只读 —— 否则「进了群就能点我的审批」。
        for (verdict in listOf(
            Verdict.OwnerNeedsGrant,
            Verdict.AdminNeedsGrant,
            Verdict.MemberOnly,
            Verdict.NotAMember,
        )) {
            assertEquals(
                CardActionEligibility.ReadOnly,
                cardActionEligibilityOf(senderIsAgent = false, verdict = verdict),
                "$verdict 不该能点卡片按钮",
            )
        }
    }

    @Test
    fun unknownVerdictFailsClosed() {
        // 判不了（没有群元数据 / 没有台账）⇒ 只读。fail-closed 是这一层的默认值：
        // 「不知道能不能」和「能」不是一回事。
        assertEquals(CardActionEligibility.ReadOnly, cardActionEligibilityOf(senderIsAgent = false, verdict = null))
    }

    @Test
    fun agentEchoIsNotInteractive() {
        // agent 自己发的消息回声回来时跳过交互 —— 哪怕它被判成有资格（它就是宿主本人）。
        assertEquals(CardActionEligibility.AgentEcho, cardActionEligibilityOf(senderIsAgent = true, verdict = Verdict.AgentHost))
        assertEquals(CardActionEligibility.AgentEcho, cardActionEligibilityOf(senderIsAgent = true, verdict = null))
    }

    @Test
    fun eligibilityIsMappedThroughPermitsExecutionNotASecondList() {
        // **同源判据**：资格函数不许自己再维护一份「谁能点」的名单。
        // 枚举里将来多一档，这里自动跟着走 —— 多出来的一档不会静默变成可点。
        for (verdict in Verdict.entries) {
            val expected = if (AgentExecAuthorizer.run { verdict.permitsExecution() }) {
                CardActionEligibility.Actionable
            } else {
                CardActionEligibility.ReadOnly
            }
            assertEquals(expected, cardActionEligibilityOf(senderIsAgent = false, verdict = verdict), "$verdict")
        }
    }

    // ── 渲染决策（阶段 B：把「画不画」从 Composable 里抽成数据）────────────────

    private val actionableCard = """
        {"cardId":"c","rev":1,"type":"approval","actions":[{"actionId":"allow","label":"Allow","approval":true}]}
    """.trimIndent()

    @Test
    fun readOnlyNeverDrawsTheButtonGroup() {
        // 无资格 = **整块不画**，不是画一颗灰按钮（M3 的既有原则）。
        // 但要说清「为什么没有按钮」，否则这张卡看起来就是坏的。
        val plan = cardRenderPlanOf(
            card = parse(actionableCard),
            eligibility = CardActionEligibility.ReadOnly,
            hasActionChannel = true,
            now = 0L,
        )
        assertFalse(plan.showActions, "无资格时按钮组整块不存在")
        assertTrue(plan.showWaitingForHost, "只读态要有一行「等待机主处理」")
    }

    @Test
    fun noActionChannelMeansNoButtonsEvenForTheHost() {
        // 壳没有动作通路（onAction == null）时，机主也看不到按钮 —— 能点但不通，
        // 与「没有」是同一件事。
        val plan = cardRenderPlanOf(
            card = parse(actionableCard),
            eligibility = CardActionEligibility.Actionable,
            hasActionChannel = false,
            now = 0L,
        )
        assertFalse(plan.showActions)
        assertFalse(plan.showWaitingForHost, "没有通路不是「没资格」，不该挂等待机主那行")
    }

    @Test
    fun agentEchoDrawsNothingInteractive() {
        val plan = cardRenderPlanOf(
            card = parse(actionableCard),
            eligibility = CardActionEligibility.AgentEcho,
            hasActionChannel = true,
            now = 0L,
        )
        assertFalse(plan.showActions)
        assertFalse(plan.showWaitingForHost)
    }

    @Test
    fun expiredStillDrawsTheButtonsButDisablesThem() {
        // 过期**不隐藏**按钮组：用户要看得见「本来能点、现在不能了」，
        // 这比整块消失更能解释发生了什么。禁用是整组的事，不是每颗按钮各自判断。
        val card = parse(
            """{"cardId":"c","rev":1,"expiresAt":1000,
                "actions":[{"actionId":"allow","label":"Allow","approval":true}]}""",
        )
        val expired = cardRenderPlanOf(card, CardActionEligibility.Actionable, hasActionChannel = true, now = 1_000L)
        assertTrue(expired.showActions)
        assertFalse(expired.actionsEnabled)
        assertTrue(expired.showExpiredTag)

        val live = cardRenderPlanOf(card, CardActionEligibility.Actionable, hasActionChannel = true, now = 999L)
        assertTrue(live.actionsEnabled)
        assertFalse(live.showExpiredTag)
    }

    @Test
    fun artifactSizesAreHumanReadable() {
        assertEquals("512 B", formatArtifactSize(512L))
        assertEquals("1.0 KB", formatArtifactSize(1024L))
        assertEquals("1.5 KB", formatArtifactSize(1536L))
        assertEquals("2.0 MB", formatArtifactSize(2L * 1024L * 1024L))
    }

    /**
     * 执行状态横幅的色调（设计稿 v2 §3 的 status 口径）。
     *
     * 这条映射是**用户读到的第一个信号**：插件没做成事却画成蓝色「进行中」，
     * 比不画更糟。所以四态逐个钉，且未知值必须**不画**（null）——
     * 协议层已只放行这四个值，剩下的只可能是坏数据。
     */
    @Test
    fun agentStatesMapToBannerTonesAndUnknownStatesDrawNothing() {
        assertEquals(AgentCardBannerTone.Info, agentStateBannerToneOf("accept"))
        assertEquals(AgentCardBannerTone.Warn, agentStateBannerToneOf("blocked"))
        assertEquals(AgentCardBannerTone.Success, agentStateBannerToneOf("completed"))
        assertEquals(AgentCardBannerTone.Error, agentStateBannerToneOf("failed"))
        // 大小写不是协议事实的变体：`Failed` 不等于 `failed`（帧里写错就是坏数据）。
        assertNull(agentStateBannerToneOf("Failed"))
        assertNull(agentStateBannerToneOf(""))
        assertNull(agentStateBannerToneOf(null))
    }
}
