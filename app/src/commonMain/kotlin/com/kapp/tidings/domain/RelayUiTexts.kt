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
}

/**
 * 「没装桥」的实现：每个方法都返回空串。
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
}
