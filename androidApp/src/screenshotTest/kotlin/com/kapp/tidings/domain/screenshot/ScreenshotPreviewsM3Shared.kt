// M3 共享组件的双端帧（AGENTS §2.6 第五步：手机帧 + 桌面帧都进 componentSpecs.previews）。
//
// 为什么单独一个文件：三件套（卡片引擎 / 上下文唤起胶囊 / 授权面板）是 M3 新增的
// 共享实现，全部长在 composeApp commonMain —— 手机的 ChatScreen 与桌面的
// DesktopChatPane 走的是**同一个函数**，这里的两列帧（393 宽 / 1280 宽）钉的是
// 同一份实现在两种外壳宽度下的样子，不是两份实现各拍一张。
//
// 底衬统一 `surface` 纯色：这三件都是聊天页内容层（卡片满宽、胶囊横排、面板是
// 弹层自带遮罩），整屏组合由既有帧负责。桌面帧用宽幅视口（与既有 desk-* 帧同一档）
// 验证的是「内容在 1280 宽下不散架」，仍调用同一个共享 Composable。
package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.kapp.tidings.core.protocol.GroupWorkspaceInfo
import com.kapp.tidings.core.protocol.TeamMemberInfo
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.feature.chat.AgentCardBubble
import com.kapp.tidings.feature.chat.AgentContextCapsule
import com.kapp.tidings.feature.chat.AgentTaskPickerSheet
import com.kapp.tidings.feature.chat.CapsuleSelection
import com.kapp.tidings.feature.chat.CapsuleTaskOption
import com.kapp.tidings.feature.chat.CapsuleWorkspaceOption
import com.kapp.tidings.feature.chat.CapsuleAgentInfo
import com.kapp.tidings.feature.chat.parseAgentCard
import com.kapp.tidings.ui.components.SetGroupAgentWorkspacesDialog
import com.kapp.tidings.ui.theme.AppTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ── 夹具 ─────────────────────────────────────────────────────────────────

/** §4.3 的样例卡片：标题 + 进度 + 三步 + 动作（含一颗审批）。 */
private fun previewCardJson(decidedBy: String? = null): JsonObject = buildJsonObject {
    put("type", "team-task-board")
    put("version", 1)
    put("cardId", "task-89")
    put("rev", 3)
    put("title", "重构网络传输层")
    put("progress", 70)
    putJsonArray("items") {
        add(buildJsonObject {
            put("id", "1"); put("label", "协议定义更新"); put("status", "done"); put("assignee", "@architect")
        })
        add(buildJsonObject {
            put("id", "2"); put("label", "核心通道编写"); put("status", "running"); put("assignee", "@coder")
        })
        add(buildJsonObject {
            put("id", "3"); put("label", "回归测试校验"); put("status", "pending"); put("assignee", "@tester")
        })
    }
    putJsonObject("diff") {
        put("path", "core/src/main/…/NodeOutbound.kt")
        put("additions", 128)
        put("deletions", 34)
        put("summary", "发送侧三处预算对齐")
    }
    putJsonObject("banner") {
        put("text", "回归单测进行中，预计 3 分钟")
        put("tone", "info")
    }
    putJsonArray("actions") {
        add(buildJsonObject { put("actionId", "pause-task"); put("label", "暂停任务"); put("style", "secondary") })
        add(buildJsonObject { put("actionId", "approve"); put("label", "批准"); put("style", "primary"); put("approval", true) })
    }
    if (decidedBy != null) {
        put("decidedBy", decidedBy)
        put("decidedActionId", "approve")
    }
}

private fun previewCapsuleAgent() = CapsuleAgentInfo(
    agentToken = "@dev-lead",
    agentName = "dev-lead",
    agentId = "dev-lead",
    hostNodeId = "preview-host",
    isTeam = true,
    workspaces = listOf(
        CapsuleWorkspaceOption("teahouse-mobile", "Teahouse Mobile"),
        CapsuleWorkspaceOption("teahouse-backend", "Teahouse Backend"),
    ),
    defaultWsId = "teahouse-mobile",
)

private fun previewPanelAgents() = listOf(
    VirtualAgentMember(
        agentId = "dev-lead",
        hostNodeId = "preview-host-self",
        name = "dev-lead",
        entityType = "team",
        teamMembers = listOf(
            TeamMemberInfo("coder", "coder", "FullStack"),
            TeamMemberInfo("tester", "tester", "QA"),
        ),
        allowedWorkspaces = listOf(
            GroupWorkspaceInfo(wsId = "ws-a", title = "Teahouse Mobile"),
            GroupWorkspaceInfo(wsId = "ws-b", title = "Teahouse Backend"),
        ),
        defaultWsId = "ws-a",
    ),
    VirtualAgentMember(
        agentId = "remote",
        hostNodeId = "preview-host-foreign",
        name = "remote-agent",
        defaultWsId = "ws-z",
    ),
)

private fun previewPanelWorkspaces() = listOf(
    WorkspaceUi(
        peerId = "preview-host-self",
        agentKey = "dev-lead",
        wsId = "ws-a",
        wsTitle = "Teahouse Mobile",
        wsPath = "/srv/teahouse-mobile",
        agentKind = "DSH",
        online = true,
        sessionCount = 2,
        at = 0L,
    ),
    WorkspaceUi(
        peerId = "preview-host-self",
        agentKey = "dev-lead",
        wsId = "ws-b",
        wsTitle = "Teahouse Backend",
        wsPath = "/srv/teahouse-backend",
        agentKind = "DSH",
        online = true,
        sessionCount = 0,
        at = 0L,
    ),
)

/** 预览夹具直接走**生产解析器**：钉住「解析产物就是渲染输入」这一条。 */
private fun previewCard(decidedBy: String? = null) =
    parseAgentCard(previewCardJson(decidedBy))!!

// ── 卡片引擎：手机帧 / 桌面帧 ────────────────────────────────────────────

@PreviewTest
@Preview(name = "m3-agent-card", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentCardPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = previewCard(),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-m3-agent-card", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentCardDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            // 桌面壳里卡片不占满 1280：聊天列宽（与桌面气泡同一档的 720 上限内）。
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = previewCard(),
                    // **改一处两端都变的证据之一**：桌帧多传乐观锁集合 —— 同一个函数
                    // 的同一条参数通路，手机帧传空集、桌帧传已点集合，两帧各自如实。
                    pendingActions = setOf("task-89|approve"),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(720.dp),
                )
            }
        }
    }
}

/** 审批锁终态：宿主判定到达（decidedBy），全员显示批准人、按钮组收掉。 */
@PreviewTest
@Preview(name = "m3-agent-card-decided", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun AgentCardDecidedPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = previewCard(decidedBy = "Alice"),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

// ── 上下文唤起胶囊：手机帧 / 桌面帧 ──────────────────────────────────────

@PreviewTest
@Preview(name = "m3-context-capsule", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ContextCapsulePhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentContextCapsule(
                    agent = previewCapsuleAgent(),
                    selection = CapsuleSelection("dev-lead", "teahouse-mobile", null, null),
                    onExpand = {},
                    onDismiss = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-m3-context-capsule", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun ContextCapsuleDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentContextCapsule(
                    agent = previewCapsuleAgent(),
                    selection = CapsuleSelection("dev-lead", "teahouse-mobile", "sess-9", "修跨网重连"),
                    onExpand = {},
                    onDismiss = {},
                    modifier = Modifier.width(720.dp),
                )
            }
        }
    }
}

/** 目标选择 Bottom Sheet：按授权工作区分组、最近任务、发起新任务。 */
@PreviewTest
@Preview(name = "m3-capsule-picker", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun CapsulePickerPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            AgentTaskPickerSheet(
                agent = previewCapsuleAgent(),
                selection = CapsuleSelection("dev-lead", "teahouse-mobile", null, null),
                recentTasks = listOf(
                    CapsuleTaskOption("sess-9", "修跨网重连", "teahouse-mobile"),
                    CapsuleTaskOption("sess-7", "协议白名单对齐", "teahouse-mobile"),
                    CapsuleTaskOption("sess-4", "桌面托盘常驻", "teahouse-backend"),
                ),
                onPick = {},
                onDismiss = {},
            )
        }
    }
}

// ── 授权面板：手机帧 / 桌面帧（宿主可编辑 + 非宿主只读）─────────────────

@PreviewTest
@Preview(name = "m3-group-auth", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun GroupAuthPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            SetGroupAgentWorkspacesDialog(
                declaredAgents = previewPanelAgents(),
                localWorkspaces = previewPanelWorkspaces(),
                exposedWsIds = setOf("ws-a"),
                selfNodeId = "preview-host-self",
                wsTitleOf = { wsId -> previewPanelWorkspaces().firstOrNull { it.wsId == wsId }?.wsTitle },
                onDismiss = {},
                onSave = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "desk-m3-group-auth", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun GroupAuthDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            SetGroupAgentWorkspacesDialog(
                declaredAgents = previewPanelAgents(),
                localWorkspaces = previewPanelWorkspaces(),
                exposedWsIds = setOf("ws-a"),
                selfNodeId = "preview-host-self",
                wsTitleOf = { wsId -> previewPanelWorkspaces().firstOrNull { it.wsId == wsId }?.wsTitle },
                onDismiss = {},
                onSave = {},
            )
        }
    }
}

/** 非宿主只读帧：无勾选框、无保存按钮，路径列显示「仅宿主可见」。 */
@PreviewTest
@Preview(name = "m3-group-auth-readonly", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun GroupAuthReadonlyPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            SetGroupAgentWorkspacesDialog(
                declaredAgents = previewPanelAgents(),
                localWorkspaces = emptyList(),
                exposedWsIds = emptySet(),
                selfNodeId = "preview-host-foreign",
                wsTitleOf = { null },
                onDismiss = {},
                onSave = null,
            )
        }
    }
}
