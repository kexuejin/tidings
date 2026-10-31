// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.ScreenRecordUi

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
     * 「这个人没有资格驱动这个 agent」的群流横幅（设计稿 v2 §4.8）。
     *
     * 触发面：卡片动作到了宿主，但点击者过不了 `AgentExecAuthorizer`。
     * 宿主**必须**回一句 —— 静默丢弃等于把「点了没反应」换个地方发生。
     *
     * @param actor 点击者的展示名（群成员昵称；认不出时是 nodeId）
     * @param agentName 被驱动的 agent 展示名
     */
    suspend fun agentActionDeniedNotice(actor: String, agentName: String): String
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
}
