// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Layer 5（方案 §8）：上下文唤起胶囊的**人机工学判据**（§5.2 / §7.3-5）。
 *
 * 四条全来自规格原文：
 * 1. 输入普通文本 → `showCapsule == false`；
 * 2. 键入 `@dev-lead` → `showCapsule == true` 且默认选中群默认工作区；
 * 3. 点击 `[✕]` → 胶囊收起，**输入框文字保留**（收起动作的签名里没有草稿参数 ——
 *    状态机不存在改写草稿的通道）；
 * 4. 粘帖文本不误触发；多 @ 时仅绑定首个执行主体。
 *
 * 这些判据全是**纯函数**（`AgentContextCapsule.kt` 的状态机）：Compose 的组合层
 * 只是接状态，行为在这里钉死 —— 本仓没有 Compose UI 测试基建（见 DeadButtonGuardTest
 * 的取舍说明），能抽成纯函数的交互一律抽出去测。
 */
class ContextCapsuleErgonomicsTest {

    private val devLead = CapsuleAgentInfo(
        agentToken = "@dev-lead",
        agentName = "dev-lead",
        agentId = "dev-lead",
        hostNodeId = "host-1",
        isTeam = true,
        workspaces = listOf(
            CapsuleWorkspaceOption("teahouse-mobile", "Teahouse Mobile App"),
            CapsuleWorkspaceOption("teahouse-backend", "Teahouse Backend Service"),
        ),
        defaultWsId = "teahouse-mobile",
    )
    private val agents = mapOf(devLead.agentToken to devLead)

    private fun typed(previous: String, next: String) = capsuleOnDraftEdit(
        state = CapsuleState.Empty,
        previousDraft = previous,
        nextDraft = next,
        agentsByToken = agents,
    )

    /** 把 [text] 逐字符打进状态机 —— 键入的真实粒度就是一两个字符，不是一整句。 */
    private fun typeOut(
        text: String,
        agentsByToken: Map<String, CapsuleAgentInfo> = agents,
    ): CapsuleState {
        var state = CapsuleState.Empty
        var prev = ""
        for (ch in text) {
            val next = prev + ch
            state = capsuleOnDraftEdit(state, prev, next, agentsByToken)
            prev = next
        }
        return state
    }

    // ── 1. 普通文本不唤起 ────────────────────────────────────────────────

    @Test
    fun plainTextNeverShowsTheCapsule() {
        assertNull(typed("", "晚上一起吃火锅吗").agent, "普通聊天必须 100% 纯净（§5.2 场景 1）")
        assertNull(typed("晚上好", "晚上好，吃火锅吗").agent)
        assertEquals(CapsuleState.Empty, typed("", "晚上一起吃火锅吗"))
    }

    @Test
    fun atAloneWithoutAValidAgentDoesNotTrigger() {
        // @ 后面没有跟任何已声明 agent：候选是空的，胶囊没有可绑定的主体。
        assertNull(typed("", "@").agent)
        assertNull(typed("@", "@dev").agent, "@dev 是前缀不是合法 token —— 那是 @ 候选弹窗的事，胶囊不出")
        // @ 别人（人类成员不在 agentsByToken 里）同样不触发。
        assertNull(typed("", "找 @zhangsan 对一下").agent)
    }

    // ── 2. 键入合法 @agent 唤起，默认选中群默认工作区 ────────────────────

    @Test
    fun typingAValidAgentTokenShowsTheCapsuleWithDefaultWorkspace() {
        val state = typeOut("跑一下 @dev-lead 回归")
        assertEquals(devLead, state.agent, "键入合法 @agent → 唤起（§5.2 场景 2）")
        assertEquals("teahouse-mobile", state.selection?.wsId, "默认选中**群默认**工作区（§8）")
        assertNull(state.selection?.resumeSessionId, "默认是新任务，不偷接最近会话")
        assertEquals(CapsuleSource.Typing, state.source)
    }

    @Test
    fun completingTheLastCharacterIsWhatTriggers() {
        // 逐字键入的中间态：token 还没打全，不弹（与 @ 候选弹窗互不干扰）；
        // 打全最后一个字符的那次键入才唤起。
        assertNull(capsuleOnDraftEdit(CapsuleState.Empty, "问", "问 @dev-lea", agents).agent)
        val done = capsuleOnDraftEdit(CapsuleState.Empty, "问 @dev-lea", "问 @dev-lead", agents)
        assertEquals(devLead, done.agent)
    }

    // ── 3. 粘帖不误触发 ─────────────────────────────────────────────────

    @Test
    fun pastedTextMentioningAnAgentDoesNotTrigger() {
        // 一次插入一整段（delta 远大于键入粒度）= 粘帖：即使里面带着 @agent 也不弹。
        assertEquals(
            CapsuleState.Empty,
            typed("", "会议纪要如下，请 @dev-lead 跟进：……（三百字）……"),
            "粘帖文本不误触发（§7.3-5）",
        )
    }

    @Test
    fun existingCapsuleSurvivesAPaste() {
        // 已经绑定（键入触发）之后再粘帖：粘帖不触发**也不撤销**既有的绑定 ——
        // 「不误触发」管的是唤起，不是把用户已经选好的目标拍掉。
        val bound = typeOut("@dev-lead ")
        val pasted = capsuleOnDraftEdit(
            state = bound,
            previousDraft = "@dev-lead ",
            nextDraft = "@dev-lead 这是背景材料……（三百字）",
            agentsByToken = agents,
        )
        assertEquals(devLead, pasted.agent, "既有绑定不因粘帖被拍掉")
    }

    @Test
    fun deletingTheTokenRetractsATypingBoundCapsule() {
        val bound = typeOut("跑回归 @dev-lead")
        assertEquals(devLead, bound.agent)
        // 逐字删掉 token 的最后一个字符（键入粒度的删除）：绑定依据没了 ⇒ 收起。
        val retracted = capsuleOnDraftEdit(
            state = bound,
            previousDraft = "跑回归 @dev-lead",
            nextDraft = "跑回归 @dev-lea",
            agentsByToken = agents,
        )
        assertNull(retracted.agent, "键入绑定的胶囊跟着 token 走")
    }

    // ── 4. 多 @ 只绑首个执行主体 ────────────────────────────────────────

    @Test
    fun multipleMentionsBindOnlyTheFirstAgent() {
        val tester = devLead.copy(
            agentToken = "@tester", agentName = "tester", agentId = "tester", defaultWsId = null,
        )
        val both = agents + (tester.agentToken to tester)
        val state = typeOut("@dev-lead 和 @tester 一起看", both)
        assertEquals(devLead, state.agent, "多 @ 时仅绑定首个执行主体（§7.3-5）")
        // 「首个」的定义是**文本位置最左**，不是 token 表顺序：@tester 在前时绑 tester。
        val reversed = typeOut("@tester 先看，@dev-lead 后看", both)
        assertEquals(tester, reversed.agent)
    }

    @Test
    fun longestTokenWinsWhenOneIsAPrefixOfAnother() {
        val lead = devLead.copy(agentToken = "@dsh", agentName = "dsh", agentId = "dsh")
        val leadWs = devLead.copy(agentToken = "@dsh-teahouse", agentName = "dsh-teahouse", agentId = "dsh-teahouse")
        val both = mapOf(lead.agentToken to lead, leadWs.agentToken to leadWs)
        // 文本里的 "@dsh-teahouse" 不该被 "@dsh" 抢先命中（与协议侧 mentionedVirtualAgent 同一条规则）。
        val state = typeOut("@dsh-teahouse 跑回归", both)
        assertEquals(leadWs, state.agent)
    }

    // ── 5. 回复 agent 发言唤起 ──────────────────────────────────────────

    @Test
    fun replyingToAnAgentMessageBindsItRegardlessOfDraft() {
        val state = capsuleOnReply(CapsuleState.Empty, devLead)
        assertEquals(devLead, state.agent)
        assertEquals(CapsuleSource.Reply, state.source)
        assertEquals("teahouse-mobile", state.selection?.wsId, "回复绑定同样默认到群默认工作区")
        // 回复绑定**不要求草稿里有 @**：点「回复」就是唤起时机（§5.2）。
    }

    @Test
    fun clearingTheReplyRetractsAReplyBoundCapsule() {
        val bound = capsuleOnReply(CapsuleState.Empty, devLead)
        assertNull(capsuleOnReply(bound, null).agent, "取消引用 = 回复绑定的依据也没了")
    }

    @Test
    fun replyBoundCapsuleSurvivesDraftEdits() {
        // 回复绑定的依据是「正在回复谁」，不是草稿内容：改/删草稿不收起。
        val bound = capsuleOnReply(CapsuleState.Empty, devLead)
        val edited = capsuleOnDraftEdit(
            state = bound,
            previousDraft = "@dev-lead 跑回归",
            nextDraft = "跑回归",
            agentsByToken = agents,
        )
        assertEquals(devLead, edited.agent)
    }

    @Test
    fun replyBeatsAnEarlierTypingBinding() {
        val bound = typed("", "@dev-lead ")
        val tester = devLead.copy(agentToken = "@tester", agentName = "tester", agentId = "tester", defaultWsId = null)
        val switched = capsuleOnReply(bound, tester)
        assertEquals(tester, switched.agent, "最近一次唤起意图说了算：回复把键入绑定换掉")
        assertEquals(CapsuleSource.Reply, switched.source)
    }

    // ── 6. 收起与点选 ───────────────────────────────────────────────────

    @Test
    fun dismissingClearsBindingAndSelectionOnly() {
        val bound = typeOut("@dev-lead 跑回归")
        assertEquals(devLead, bound.agent, "前提：胶囊已因键入唤起")
        val dismissed = capsuleOnDismiss(bound)
        assertNull(dismissed.agent, "✕ 收起胶囊")
        assertNull(dismissed.selection)
        assertNull(dismissed.source)
        // 「输入框文字保留」由调用方保证：capsuleOnDismiss 的签名没有草稿参数，
        // 状态机**不存在**改写草稿的通道 —— 这就是「收起不改输入」的钉法。
    }

    @Test
    fun pickingATaskTakesEffectImmediately() {
        val bound = typeOut("@dev-lead ")
        val picked = capsuleOnSelect(
            bound,
            CapsuleSelection(
                agentId = "dev-lead",
                wsId = "teahouse-backend",
                resumeSessionId = "sess-9",
                sessionTitle = "修跨网重连",
            ),
        )
        // 点选即刻生效、零锁死：状态直接落，没有「确认中」这种中间态。
        assertEquals("teahouse-backend", picked.selection?.wsId)
        assertEquals("sess-9", picked.selection?.resumeSessionId)
        assertEquals(devLead, picked.agent, "点选不换绑定的主体")
    }

    @Test
    fun selectionWithoutAWorkspaceMeansAgentOwnConversation() {
        val tester = devLead.copy(
            agentToken = "@tester", agentName = "tester", agentId = "tester",
            workspaces = emptyList(), defaultWsId = null,
        )
        val state = typeOut("@tester 帮我看看", mapOf(tester.agentToken to tester))
        assertTrue(state.agent != null)
        assertNull(state.selection?.wsId, "没有授权工作区的 agent：意图里不带 wsId（协议侧解释为它自己的会话）")
    }

    // ── 7. 意图载荷（M1 已上线的 agentIntent 键）────────────────────────

    @Test
    fun intentCarriesAgentWsSessionAndAutoMode() {
        val intent = CapsuleSelection(
            agentId = "dev-lead",
            wsId = "teahouse-mobile",
            resumeSessionId = "sess-9",
            sessionTitle = "修跨网重连",
        ).toIntentJson()
        assertEquals("\"dev-lead\"", intent["agentId"].toString())
        assertEquals("\"teahouse-mobile\"", intent["wsId"].toString())
        assertEquals("\"sess-9\"", intent["resumeSessionId"].toString())
        assertEquals("\"auto\"", intent["mode"].toString(), "§4.2 样例的 mode=auto")
        assertEquals(4, intent.size, "意图只装这四个字段 —— 多的字段属于协议改动")
    }

    @Test
    fun intentOmitsAbsentFieldsRatherThanSendingNulls() {
        val intent = CapsuleSelection(
            agentId = "tester", wsId = null, resumeSessionId = null, sessionTitle = null,
        ).toIntentJson()
        assertEquals("\"tester\"", intent["agentId"].toString())
        assertNull(intent["wsId"], "没有工作区就不带这个键：null 字段在白名单协议里是噪声")
        assertNull(intent["resumeSessionId"])
        assertEquals("\"auto\"", intent["mode"].toString())
    }

    // ── 8. 编辑分类（键入 vs 粘帖的判据本体）────────────────────────────

    @Test
    fun editClassificationMatchesTypingGranularity() {
        assertEquals(DraftEditKind.Typed, classifyDraftEdit("", "晚", explicitPick = false))
        assertEquals(DraftEditKind.Typed, classifyDraftEdit("晚上", "晚", explicitPick = false), "逐字删除也算键入")
        assertEquals(DraftEditKind.Pasted, classifyDraftEdit("", "一整段三百字的会议纪要", explicitPick = false))
        assertEquals(
            DraftEditKind.MentionPicked,
            classifyDraftEdit("", "@dev-lead", explicitPick = true),
            "@ 候选点选是手动唤起的一种（§5.2「手动键入 @ 选中」）",
        )
    }

    @Test
    fun mentionPickBindsThePickedAgent() {
        val tester = devLead.copy(agentToken = "@tester", agentName = "tester", agentId = "tester", defaultWsId = null)
        // 从候选里点选 @tester：即使草稿里已有别的 agent token，插入后最左的才算「首个」。
        val picked = capsuleOnAgentPick(
            state = CapsuleState.Empty,
            picked = tester,
            nextDraft = "@dev-lead 完了之后 @tester 接手",
            agentsByToken = agents + (tester.agentToken to tester),
        )
        assertEquals(devLead, picked.agent, "多 @ 只绑首个执行主体 —— 点选也不例外")
        // 点选的 token 就是全文唯一的 agent token 时，绑它。
        val solo = capsuleOnAgentPick(
            state = CapsuleState.Empty,
            picked = tester,
            nextDraft = "@tester 接手",
            agentsByToken = agents + (tester.agentToken to tester),
        )
        assertEquals(tester, solo.agent)
    }
}
