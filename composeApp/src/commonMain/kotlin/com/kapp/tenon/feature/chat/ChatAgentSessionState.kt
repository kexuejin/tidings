// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.SenderIdentity
import com.kapp.tenon.domain.SenderIdentityContext
import com.kapp.tenon.domain.agentHostsInGroup
import com.kapp.tenon.domain.agentEventsOf
import com.kapp.tenon.domain.agentSessionsOf
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.domain.model.AgentRunUi
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.visibleAgentSessionsOf

/**
 * ChatScreen 的 **agent 会话派生状态**（ARP-14：从 ChatScreen.kt 移出，逐行搬移）。
 *
 * 搬了什么：工作区群判定、被移出群判定、「新任务」chip token、当前会话与归档列表、
 * 会话参与判定、输入状态条快照（approval/queued/run/lastActivity 四件）。
 *
 * 为什么这样切：这一段的 12 个产出被主体 ~50 处引用，但它们之间只有一条派生链
 * （`groupAgentHosts` → `agentSessions` → `activeSession` → 四件快照）—— 整块抽出
 * 成一个 remember 化的值对象，主体用同名别名接住，引用零改动；`groupAgentHosts`
 * 与 `activeSessionEvents` 是派生内部细节，不进值对象。
 *
 * remember 键逐条原样保留 —— 这段键曾经修过两个真 bug（peerId 记账、条数相同的
 * 会话互切），动键前先读注释。
 */
internal data class ChatAgentSessionState(
    val isWorkspaceGroup: Boolean,
    val leftGroup: Boolean,
    val newTaskToken: String?,
    val agentSessions: List<AgentSessionUi>,
    val archivedSessions: List<AgentSessionUi>,
    val agentInvolved: Boolean,
    val activeSession: AgentSessionUi?,
    val hasPendingApproval: Boolean,
    val queuedCount: Int,
    val activeRun: AgentRunUi?,
    val lastActivityAt: Long,
    val statusSnapshot: AgentComposerStatus?,
)

@Composable
internal fun rememberChatAgentSessionState(
    agentSlice: AgentSlice,
    peerNodeId: String,
    effectiveWsId: String?,
    group: GroupMeta?,
    identityCtx: SenderIdentityContext,
    groupAgentIdentities: List<SenderIdentity>,
    groupHasAgent: Boolean,
    peerIsAgent: Boolean,
    selfNodeId: String,
    activeSessionId: String?,
    now: Long,
): ChatAgentSessionState {
    // 会话列表按**工作区**收窄：`effectiveWsId` 为空 = 不按工作区收窄（直接在跟节点聊），
    // 非空则只要属于该工作区的会话。
    //
    // 为什么用 `filter` 而不是新增一个 `agentSessionsOf(peerId, wsId)`：这一屏是那个口径的
    // 唯一使用者，而筛选条件很短；把它做进 `RelayUiState` 会让「按节点」与「按工作区」
    // 两个口径并列存在、以后又有人选错。
    // 取数口径是 `visibleAgentSessionsOf`（已按 DSH 的可见性两键过滤掉子代理会话与空壳会话），
    // **不是** `agentSessionsOf` —— 后者是未过滤的原始表，分页游标要用它（见 RelayUiState 的 KDoc）。
    // 真机实测：111 行落库会话里 68 行是 DSH 自己一定不显示的（子代理 19 + 空壳 49），
    // 它们成批出现在这个浮层里，而用户的原话是「怎么都是 Route this bounded MEMORY…」。
    val isWorkspaceGroup = group != null && effectiveWsId != null
    // 被移出群：群元数据里已经没有自己。
    //
    // 真身：被踢出群后输入栏仍完全可用，用户打完字点发送，只得到一条红色「发送失败」——
    // 反复试，因为界面从没说过原因。`GroupMeta.members` 是权威（协议同步的成员表），
    // 不是从消息里猜的。
    val leftGroup = group != null && selfNodeId !in group.members
    // 「新任务」chip 点名的 agent token：**手机内置输入栏与桌面输入卡片共用这一个值**
    // （§2.6：同一事实一个来源 —— 桌面 chip 从前在这里之外自己写死 "@dsh "，
    // 在别的 agent 群里叫不醒）。判据与手机 onPick 原有的一条一致。
    // null = 不是工作区群（chip 在两条壳里都不画）。
    val newTaskToken = if (isWorkspaceGroup) {
        groupAgentIdentities
            .firstOrNull { !it.agentKind.isNullOrBlank() }
            ?.mentionToken
            ?: "@dsh"
    } else {
        null
    }
    // 群里 agent 宿主是谁（本机或远端都算）。这是群上下文里"该看谁的会话"的唯一来源：
    // 群会话键是 `group:<id>`，拿它去 `agentSessionsOf` 一条都查不到 —— 于是「正在思考」
    // 永远不显示，只剩输入区那句「有成员不在线」。
    val groupAgentHosts = remember(identityCtx) {
        if (group != null) agentHostsInGroup(identityCtx) else emptySet()
    }
    val agentSessions = remember(agentSlice.agentSessions, peerNodeId, effectiveWsId, groupAgentHosts) {
        if (effectiveWsId != null) {
            agentSlice.agentSessions.values
                .filter { it.wsId == effectiveWsId && !it.isSubagent && !it.isArchived && it.title?.isNotBlank() == true }
                .distinctBy { it.sessionId }
        } else if (group != null) {
            agentSlice.agentSessions.values
                .filter {
                    it.peerId in groupAgentHosts && !it.isSubagent && !it.isArchived &&
                        it.title?.isNotBlank() == true
                }
                .distinctBy { it.sessionId }
        } else {
            visibleAgentSessionsOf(agentSlice, peerNodeId)
        }
    }
    val archivedSessions = remember(agentSlice.agentSessions, peerNodeId, effectiveWsId, groupAgentHosts) {
        if (effectiveWsId != null) {
            agentSlice.agentSessions.values
                .filter { it.wsId == effectiveWsId && !it.isSubagent && it.isArchived }
                .distinctBy { it.sessionId }
        } else if (group != null) {
            agentSlice.agentSessions.values
                .filter { it.peerId in groupAgentHosts && !it.isSubagent && it.isArchived }
                .distinctBy { it.sessionId }
        } else {
            agentSessionsOf(agentSlice, peerNodeId).filter {
                it.isArchived && !it.isSubagent
            }
        }
    }
    // 这个会话算不算"有 agent 参与"：群里按**成员表**判（远端 agent 也是成员），
    // 不是按"是不是工作区群" —— 普通群里入住了一个 agent，状态线同样该出现。
    val agentInvolved = if (group != null) groupHasAgent else peerIsAgent
    // 当前会话：**用户选的那条优先**，只在没选或它不属于这一屏的集合时才退回第一条。
    //
    // 三处（头部副标题、切换条、选择器高亮）从此共用这一个值 —— 它们都在画「当前会话」，
    // 以前各自 `firstOrNull()` 一次，于是头部可以显示 A、高亮显示 B。
    val activeSession = remember(agentSessions, archivedSessions, activeSessionId) {
        (agentSessions + archivedSessions).firstOrNull { it.sessionId == activeSessionId }
            ?: agentSessions.firstOrNull()
            ?: archivedSessions.firstOrNull()
    }

    // agent 会话的「输入提示」数据（真机反馈 #3）。三样都是既有事实：
    // 会话状态、事件流的最近时刻、有没有在跑的 run / 待审批 —— 取不到就沉默。
    val activeSessionEvents = remember(agentSlice.agentEvents, activeSession) {
        agentEventsOf(agentSlice, activeSession)
    }
    val sessionHasPendingApproval = remember(agentSlice.agentApprovals, activeSession, peerNodeId) {
        agentSlice.agentApprovals.values.any { approval ->
            approval.status == "pending" && when {
                activeSession != null ->
                    approval.peerId == activeSession.peerId && approval.sessionId == activeSession.sessionId
                else -> approval.peerId == peerNodeId
            }
        }
    }
    // 这个会话里有几条指令**排在队列里还没执行**（PPE-1 §6.3 的 agent-queue）。
    //
    // 为什么统计到**会话**而不是设备：用户关心的是「我刚发的那条排上了没」，
    // 而一台设备上可能并行好几个会话。工作区看板上那个 queueCount 是按设备的,
    // 两者口径不同、用途也不同，不要合并。
    val sessionQueuedCount = remember(agentSlice.agentTasks, activeSession, peerNodeId) {
        agentSlice.agentTasks.values.count { task ->
            when {
                activeSession != null ->
                    task.peerId == activeSession.peerId && task.sessionId == activeSession.sessionId
                // 没有选中会话时退回按设备数 —— 与 pendingApproval 同一条降级口径。
                else -> task.peerId == peerNodeId
            }
        }
    }
    val sessionActiveRun = remember(agentSlice.agentRuns, activeSession, peerNodeId) {
        agentSlice.agentRuns.values.firstOrNull { run ->
            !run.isTerminal && when {
                activeSession != null ->
                    run.peerId == activeSession.peerId && run.sessionId == activeSession.sessionId
                else -> run.peerId == peerNodeId
            }
        }
    }
    // 最近一次动过的时刻：会话活动时刻、事件流最近到达、run 的自报/收帧时刻取大。
    // 只回答「多久没动了」，不用于显示时间（与协议对 `at` 的既有纪律同一条）。
    val sessionLastActivityAt = remember(activeSession, activeSessionEvents, sessionActiveRun) {
        maxOf(
            activeSession?.sortActivityAt ?: 0L,
            activeSessionEvents.maxOfOrNull { it.at } ?: 0L,
            sessionActiveRun?.at ?: 0L,
            sessionActiveRun?.updatedAt ?: 0L,
        )
    }

    // 状态线的**快照**：与 [ComposerStatusLine] 内部调用的是同一个纯函数（只是 now 不同），
    // 用来回答"状态线已经说明了情况，输入区那句「有成员不在线」还要不要说"。
    // 不在这里另写一套 busy/run 判定 —— 两处判据一旦分叉，就会出现"上面说正在思考、
    // 下面说有人在离线"的自相矛盾。
    val agentStatusSnapshot = agentComposerStatusOf(
        agentInvolved = agentInvolved,
        sessionState = activeSession?.state,
        lastActivityAt = sessionLastActivityAt,
        hasPendingApproval = sessionHasPendingApproval,
        hasActiveRun = sessionActiveRun != null,
        now = now,
    )

    // 长按头像 / 成员行「直接 @」：token 来自身份解析器，插入函数与输入栏的候选列表

    return ChatAgentSessionState(
        isWorkspaceGroup = isWorkspaceGroup,
        leftGroup = leftGroup,
        newTaskToken = newTaskToken,
        agentSessions = agentSessions,
        archivedSessions = archivedSessions,
        agentInvolved = agentInvolved,
        activeSession = activeSession,
        hasPendingApproval = sessionHasPendingApproval,
        queuedCount = sessionQueuedCount,
        activeRun = sessionActiveRun,
        lastActivityAt = sessionLastActivityAt,
        statusSnapshot = agentStatusSnapshot,
    )
}
