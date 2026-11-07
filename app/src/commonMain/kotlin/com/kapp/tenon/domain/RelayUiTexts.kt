// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.ScreenRecordUi

/**
 * 领域层需要**一句用户可读的话**时的唯一出口。
 *
 * ## 为什么要有这个接口（而不是各自调 `getString`）
 *
 * 本仓的约定是「用户可见字符串只住在 composeResources 的 `strings.xml`」。而有三处
 * 领域逻辑确实要产出聊天正文 / 通知正文：
 *
 * - 投屏记录落库时的卡片正文（`screen_card_text_*`）；
 * - 戳一碰的通知正文（`chat_nudge_peer`）；
 * - 建群时那条 agent 开场白与工作区状态播报（`workspace_group_*`）。
 *
 * 这些**是有状态机判据的**（什么时候该说、说什么由领域决定），所以「要不要说」
 * 不能搬到 UI；而「具体用什么措辞」必须留在资源里。两者相交的解法就是这个接口：
 * 领域侧只声明**要什么语义**，界面层提供**怎么措辞**。
 *
 * 直接 `import org.jetbrains.compose.resources.getString` 的代价是把 Compose 拖进
 * 领域层 —— 那会让「要不要落一条投屏记录」依赖 UI 框架存在。按模块拆开之后它就是
 * 编译不过的循环依赖。
 *
 * ## 三条契约（实现方必须遵守）
 *
 * 1. **不抛异常**。取不到资源时返回空串或兜底串 —— 一条记录 / 一次通知比一句文案重要。
 * 2. **实现可能挂起**（`getString` 是 suspend），所以调用点必须在**锁外**先算好，
 *    持锁期间不能挂起（见 `handleInterruptUnfinishedScreenRecords` 的 KDoc）。
 * 3. **没装桥时降级成空串**，不是崩溃、也不是编一句中文出来 —— 领域侧不许自己拼文案。
 */
interface RelayUiTexts {

    /** 投屏卡片正文（那条消息的 `text`，也进会话列表预览与读屏）。 */
    suspend fun screenCardText(record: ScreenRecordUi): String

    /** 「对方戳了你一下」的通知正文。 */
    suspend fun nudgeNotice(): String

    /** 建群时那条 agent 开场白：[agentKind] 是 agent 类型，[workspaceTitle] 是工作区名。 */
    suspend fun workspaceGreeting(agentKind: String, workspaceTitle: String): String

    /**
     * 工作区当前会话状态的播报句。
     *
     * @param sessionTitle 会话标题
     * @param busy 该会话此刻在不在忙（由领域判定，措辞由实现选）
     */
    suspend fun workspaceSessionBrief(sessionTitle: String, busy: Boolean): String

    /**
     * 跨端派单超时卡的标题（M4 §7.3-1）。
     *
     * 它是看板卡 JSON 里的**数据**（banner.text），要随群文本广播给全群 ——
     * 所以必须从 strings.xml 取，而不是在领域层硬编码（CJK 门禁 + 多语言对账）。
     *
     * @param agentName 承接子任务的 agent 展示名（群里的人认得的是它，不是 nodeId）
     */
    suspend fun invokeTimeoutTitle(agentName: String): String

    /** 超时卡上「重试」按钮的标签。 */
    suspend fun invokeActionRetryLabel(): String

    /** 超时卡上「降级为本地执行」按钮的标签。 */
    suspend fun invokeActionFallbackLabel(): String

    /** R3 柔性卡（T-G4）：标签无匹配的挂起横幅文案（tag 会被插进占位）。 */
    suspend fun tagNoMatchTitle(tag: String): String

    /**
     * 「这个 agent 现在接不了活」的群流横幅（设计稿 v2 §5「失败如实」）。
     *
     * 触发面：宿主收到带 `agentIntent` 的群文本，而本机**没有执行器**
     * （插件被 kill、从没注册、宿主壳没接桥）。此前那条路径 `?: return`，
     * 群流里一条都没有 —— 点 @ 的人看到的和「派单成功、任务在跑」一模一样。
     *
     * 文案要进群文本（随帧广播），所以必须来自 strings.xml：领域层不编中文。
     *
     * @param agentName 席位的展示名 —— 群里认的是它，不是 agentId
     */
    suspend fun agentUnavailableNotice(agentName: String): String

    /**
     * 「这次任务没做完」的群流横幅：执行器抛错，或执行器回了非 done 且没给卡片。
     *
     * 与 [agentUnavailableNotice] 分开：一个是「它不在」，一个是「它在但没做成」——
     * 混成一句会让用户以为插件掉线了。
     */
    suspend fun agentNotCompletedNotice(agentName: String): String

    /**
     * 派单走向终局失败时的一句话（哪一单、哪个 agent、为什么）。
     *
     * 触发面：[com.kapp.tenon.domain.model.NoticeKind.AgentDispatchFailed] 的群流版本
     * （群意图路径与工单派单路径共用同一个发布入口）。1:1 的 notice 由界面按结构化
     * `NoticeDetail` 翻文案，不经过这里；群文本没有结构化槽位，所以必须在这里成句。
     *
     * 取不到文案返回空串 —— 调用方会退回既有的「没做成 / 接不了」文案，不编中文。
     */
    suspend fun dispatchFailedNotice(title: String, agentName: String, reason: String): String = ""

    /**
     * 「这个人没有资格驱动这个 agent」的群流横幅（设计稿 v2 §4.8）。
     *
     * 触发面：卡片动作到了宿主，但点击者过不了 `AgentExecAuthorizer`。
     * 宿主**必须**回一句 —— 静默丢弃等于把「点了没反应」换个地方发生。
     *
     * @param actor 点击者的展示名（群成员昵称；认不出时是 nodeId）
     * @param agentName 被驱动的 agent 展示名
     */
    suspend fun agentActionDeniedNotice(actor: String, agentName: String): String

    /**
     * 「这一轮只有思考、没有正文」时的**替代正文**（agent 事件进群聊时用）。
     *
     * 触发面：CLI/插件在回合结束时发来的正文剥掉思考块后为空。空正文不等于「没有发生」，
     * 静默丢弃会让用户看到「回合明明结束了，群里一个字都没有」；这时用一句如实的话代替
     * （思考块照样折叠显示）。取不到文案的宿主返回空串 —— 那等价于旧行为（少一条气泡），
     * 不编中文、不伪造内容。
     */
    suspend fun agentEventThinkingOnlyFallback(): String
    // ---- 编排面文案（task-9）：:orchestration 只声明语义，措辞在 strings.xml ----

    /** 待定卡横幅：既可能是「调整这个原型」，也可能是「一个新任务」。 */
    suspend fun adjustOrNewTaskPrompt(excerpt: String): String = ""

    /** 待定卡上的「作为调整发送」按钮。 */
    suspend fun sendAsAdjustmentLabel(): String = ""

    /** 待定卡上的「派为新任务」按钮。 */
    suspend fun sendAsNewTaskLabel(): String = ""

    /** 承办人后面那句「（本机）」。 */
    suspend fun localAssignee(role: String): String = role

    /** 团队点将的岗位拆分：原型单与普通单各三步。 */
    suspend fun stageRoles(prototype: Boolean): List<String> = emptyList()

    /** 「团队已就位」横幅（role、倒计时秒数）。 */
    suspend fun teamReadyBanner(role: String, seconds: Long): String = ""

    /** diff 就绪但没摘要时的兜底句。 */
    suspend fun diffReadyPrompt(): String = ""

    /** diff 摘要行（摘要 + 增删统计）。 */
    suspend fun diffReadyDetail(summary: String?, additions: Long, deletions: Long): String = summary.orEmpty()

    /** 补丁合并失败的一句话指引。[kind] 是分类码，[detail] 是原始 stderr 尾巴。 */
    suspend fun patchMergeHint(kind: String, detail: String): String = detail
}

/**
 * 「没装桥」的实现：每个方法返回空串。
 *
 * 空串而不是兜底中文：领域侧编一句中文会绕开 CJK 门禁与多语言对账，而那条路一旦
 * 开了一个口子，下一个需要文案的人就会照着抄。空串的可见后果是「这条记录没有正文」，
 * 而它有卡片本身（`record`）可画 —— 这正是改动前 `runCatching{}` 失败时的行为。
 */
object NoUiTexts : RelayUiTexts {
    override suspend fun screenCardText(record: ScreenRecordUi): String = ""
    override suspend fun nudgeNotice(): String = ""
    override suspend fun workspaceGreeting(agentKind: String, workspaceTitle: String): String = ""
    override suspend fun workspaceSessionBrief(sessionTitle: String, busy: Boolean): String = ""
    override suspend fun invokeTimeoutTitle(agentName: String): String = ""
    override suspend fun invokeActionRetryLabel(): String = ""
    override suspend fun invokeActionFallbackLabel(): String = ""
    override suspend fun tagNoMatchTitle(tag: String): String = ""
    override suspend fun agentUnavailableNotice(agentName: String): String = ""
    override suspend fun agentNotCompletedNotice(agentName: String): String = ""
    override suspend fun agentActionDeniedNotice(actor: String, agentName: String): String = ""
    override suspend fun agentEventThinkingOnlyFallback(): String = ""
    override suspend fun adjustOrNewTaskPrompt(excerpt: String): String = ""
    override suspend fun sendAsAdjustmentLabel(): String = ""
    override suspend fun sendAsNewTaskLabel(): String = ""
    override suspend fun localAssignee(role: String): String = role
    override suspend fun stageRoles(prototype: Boolean): List<String> = emptyList()
    override suspend fun teamReadyBanner(role: String, seconds: Long): String = ""
    override suspend fun diffReadyPrompt(): String = ""
    override suspend fun diffReadyDetail(summary: String?, additions: Long, deletions: Long): String = summary.orEmpty()
    override suspend fun patchMergeHint(kind: String, detail: String): String = detail
}
