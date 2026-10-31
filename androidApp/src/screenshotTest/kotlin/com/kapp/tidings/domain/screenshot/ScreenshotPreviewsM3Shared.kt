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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.kapp.tidings.core.protocol.GroupWorkspaceInfo
import com.kapp.tidings.core.protocol.TeamMemberInfo
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.feature.chat.AgentCardBubble
import com.kapp.tidings.feature.chat.AgentContextCapsule
import com.kapp.tidings.feature.chat.AgentTaskPickerSheet
import com.kapp.tidings.feature.chat.CardActionEligibility
import com.kapp.tidings.feature.chat.CardRowOutcome
import com.kapp.tidings.feature.chat.CapsuleSelection
import com.kapp.tidings.feature.chat.CapsuleTaskOption
import com.kapp.tidings.feature.chat.CapsuleWorkspaceOption
import com.kapp.tidings.feature.chat.CapsuleAgentInfo
import com.kapp.tidings.feature.chat.cardRowOutcome
import com.kapp.tidings.feature.chat.latestRevCardMessageIds
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

// ── 设计稿 v2 §8：卡片引擎的「每个信息形态一帧」 ──────────────────────────
//
// 为什么这些帧必须存在：卡片体是**插件定义**的 JSON，形态由对方决定。缺一帧，就等于
// 那个形态从来没被人看过 —— 而它们各自的失败方式完全不同（进度条为 0 时是不是一条
// 空壳？失败步骤与阻塞步骤长得一样吗？未知 type 会不会整张卡不画？）。
//
// 夹具一律走 `parseAgentCard`（生产解析器），不手搓 UI 模型：解析产物才是渲染输入，
// 手搓的模型能画出生产环境根本到不了的画面。

/** 一张卡片的最小夹具：只给要验的那个形态，其余留空（缺省即"插件没给"）。 */
private fun cardOf(
    progress: Int? = null,
    type: String? = "team-task-board",
    steps: List<Triple<String, String, String?>> = emptyList(),
    banner: Pair<String, String>? = null,
    diff: Triple<String, String, Pair<Int, Int>>? = null,
    actions: List<Triple<String, String, Boolean>> = emptyList(),
    artifacts: List<Triple<String, Long?, String?>> = emptyList(),
    options: List<Pair<String, Boolean>> = emptyList(),
    expiresAt: Long? = null,
) = parseAgentCard(buildJsonObject {
    put("type", type)
    put("version", 1)
    put("cardId", "task-89")
    put("rev", 3)
    put("title", "重构网络传输层")
    progress?.let { put("progress", it) }
    putJsonArray("items") {
        steps.forEach { (label, status, assignee) ->
            add(buildJsonObject {
                put("label", label)
                put("status", status)
                assignee?.let { put("assignee", it) }
            })
        }
    }
    diff?.let { (path, summary, counts) ->
        putJsonObject("diff") {
            put("path", path)
            put("summary", summary)
            put("additions", counts.first)
            put("deletions", counts.second)
        }
    }
    banner?.let { (text, tone) ->
        putJsonObject("banner") { put("text", text); put("tone", tone) }
    }
    putJsonArray("actions") {
        actions.forEach { (id, label, approval) ->
            add(buildJsonObject {
                put("actionId", id)
                put("label", label)
                if (approval) put("approval", true)
            })
        }
    }
    if (artifacts.isNotEmpty()) {
        putJsonArray("artifacts") {
            artifacts.forEach { (name, bytes, mime) ->
                add(buildJsonObject {
                    put("name", name)
                    bytes?.let { put("bytes", it) }
                    mime?.let { put("mime", it) }
                })
            }
        }
    }
    expiresAt?.let { put("expiresAt", it) }
    if (options.isNotEmpty()) {
        putJsonArray("options") {
            options.forEach { (label, recommended) ->
                add(buildJsonObject {
                    put("label", label)
                    if (recommended) put("recommended", true)
                })
            }
        }
    }
})!!

/** P1 · 进度 0%：插件刚接下任务，一步都还没走。 */
@PreviewTest
@Preview(name = "v2-card-p1-zero", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardZeroPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 0,
                        steps = listOf(
                            Triple("读取仓库结构", "pending", null),
                            Triple("生成迁移脚本", "pending", null),
                            Triple("跑回归", "pending", null),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P2 · 混合步骤态（done / running / failed / pending）：徽标与状态图标各画各的。 */
@PreviewTest
@Preview(name = "v2-card-p2-mixed", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardMixedPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 45,
                        steps = listOf(
                            Triple("协议定义更新", "done", "@architect"),
                            Triple("核心通道编写", "running", "@coder"),
                            Triple("兼容层适配", "failed", "@coder"),
                            Triple("回归测试校验", "pending", "@tester"),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P3 · 100% 全 Done：终态必须收敛（不画进度条也算说得过去，但不能还挂着"进行中"的图标）。 */
@PreviewTest
@Preview(name = "v2-card-p3-done", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardDonePhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 100,
                        steps = listOf(
                            Triple("协议定义更新", "done", "@architect"),
                            Triple("核心通道编写", "done", "@coder"),
                            Triple("回归测试校验", "done", "@tester"),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P8 · 横幅四色：一帧四卡，同一份 `bannerColorsOf` 的四个分支并排。 */
@PreviewTest
@Preview(name = "v2-card-p8-banner-tones", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardBannerTonesPhonePreview() {
    val tones = listOf(
        "info" to "正在等待宿主任命工作区",
        "success" to "三步全部通过",
        "warn" to "有一步被跳过，请确认",
        "error" to "执行失败：命令返回 128",
    )
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tones.forEach { (tone, text) ->
                    AgentCardBubble(
                        card = cardOf(progress = 60, banner = text to tone),
                        pendingActions = emptySet(),
                        onAction = null,
                    )
                }
            }
        }
    }
}

/** P9 · 超时卡：横幅说清失败，**两个动作都还可达**（重试 / 降级本地跑）。 */
@PreviewTest
@Preview(name = "v2-card-p9-timeout", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardTimeoutPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 30,
                        steps = listOf(
                            Triple("拉取依赖", "done", null),
                            Triple("构建产物", "running", null),
                        ),
                        banner = "gpu-runner 90 秒没有回音" to "error",
                        actions = listOf(
                            Triple("invoke-retry", "重试", false),
                            Triple("invoke-fallback-local", "降级为本地执行", false),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P10 · 结果 + 产物：`name / bytes / mime` 三种信息都要读得出来。 */
@PreviewTest
@Preview(name = "v2-card-p10-artifacts", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardArtifactsPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 100,
                        steps = listOf(Triple("打包", "done", null)),
                        banner = "产物已上传" to "success",
                        artifacts = listOf(
                            Triple("tidings-0.62.0.apk", 52_428_800L, "application/vnd.android.package-archive"),
                            Triple("build-report.html", 1_536L, "text/html"),
                            Triple("checksums.txt", 512L, null),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P11 · 短 diff：紧凑形态（摘要不超阈值时不画「展开」，那是骗点击）。 */
@PreviewTest
@Preview(name = "v2-card-p11-diff-short", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardDiffShortPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 80,
                        diff = Triple("core/…/NodeOutbound.kt", "发送侧三处预算对齐", 12 to 3),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P12 · 长 diff（截断）：摘要超阈值必须给「展开」，否则用户看不到改了哪儿。 */
@PreviewTest
@Preview(name = "v2-card-p12-diff-long", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardDiffLongPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 80,
                        diff = Triple(
                            "core/src/commonMain/kotlin/com/kapp/tidings/core/session/NodeOutbound.kt",
                            "把发送侧的 UDP 预算、TCP 预算与队列重放预算对齐到同一个常量，并把三处硬编码的 4096 换成协议里的唯一来源，顺带补上两条越界判据",
                            128 to 34,
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P13 · 分配事实：`assignee` 徽标（与 S7 的「谁在执行」不是同一件事，别混）。 */
@PreviewTest
@Preview(name = "v2-card-p13-assignment", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardAssignmentPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 60,
                        steps = listOf(
                            Triple("接口契约评审", "done", "@architect"),
                            Triple("实现与自测", "running", "@coder"),
                            Triple("端到端复验", "pending", "@qa-lead-with-a-very-long-name"),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P14 · 未知 type：宽容渲染 —— 认不出的 `type` 不该让整张卡消失。 */
@PreviewTest
@Preview(name = "v2-card-p14-unknown-type", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardUnknownTypePhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        type = "future-card-type-from-a-newer-plugin",
                        progress = 50,
                        steps = listOf(Triple("一步", "running", null)),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P17 · 无资格成员的只读卡：**按钮组整块不画**，改画「等待机主处理」。 */
@PreviewTest
@Preview(name = "v2-card-p17-readonly", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardReadOnlyPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 70,
                        steps = listOf(
                            Triple("协议定义更新", "done", "@architect"),
                            Triple("核心通道编写", "running", "@coder"),
                        ),
                        actions = listOf(
                            Triple("pause-task", "暂停任务", false),
                            Triple("approve", "批准", true),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    // 判定在宿主侧，这里只消费结论：没有资格 ⇒ 不画按钮组。
                    eligibility = CardActionEligibility.ReadOnly,
                )
            }
        }
    }
}

// ── 审批行（P4–P7）与执行状态横幅（P18）─────────────────────────────────

/**
 * 一条审批的夹具。
 *
 * `expiresAt = 0` 是"对端没声明有效期"（协议侧按永不过期处理）；`1L` 是**必然过期**。
 * 两者都不读墙钟 —— 帧里出现"今天几点过期"会让这张参考图每天都不一样。
 */
private fun approvalOf(
    status: String = "pending",
    tool: String? = "bash",
    summary: String? = "rm -rf build/ && ./gradlew :app:jvmTest",
    riskLevel: String? = "L3",
    expiresAt: Long = 0L,
    choices: String? = "allow,deny,allow_session,always",
) = com.kapp.tidings.domain.model.AgentApprovalUi(
    approvalId = "appr-1",
    sessionId = "sess-1",
    peerId = "preview-host",
    tool = tool,
    summary = summary,
    expiresAt = expiresAt,
    choices = choices,
    status = status,
    riskLevel = riskLevel,
)

@Composable
private fun ApprovalFrame(approvals: List<com.kapp.tidings.domain.model.AgentApprovalUi>) {
    com.kapp.tidings.feature.agents.AgentApprovalsSection(
        approvals = approvals,
        onRespond = { _, _ -> },
        onRespondChoice = { _, _ -> },
    )
}

/** P4 · 审批待处理：风险徽标 + 工具名 + 命令原文 + 三颗按钮。 */
@PreviewTest
@Preview(name = "v2-approval-p4-pending", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2ApprovalPendingPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) { ApprovalFrame(listOf(approvalOf())) }
        }
    }
}

/** P5 · 乐观锁（桌面帧）：点过的那一颗已置灰，另两颗仍可点 —— 同一份 `pendingActions` 通路。 */
@PreviewTest
@Preview(name = "desk-v2-card-p5-locked", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardLockedDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 70,
                        steps = listOf(
                            Triple("协议定义更新", "done", "@architect"),
                            Triple("核心通道编写", "running", "@coder"),
                        ),
                        actions = listOf(Triple("approve", "批准", true)),
                    ),
                    // 桌面壳多传这一格：同一个函数、同一条参数通路，手机帧传空集。
                    pendingActions = setOf("task-89|approve"),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(720.dp),
                )
            }
        }
    }
}

/** P6 · 已决：批准人上卡，按钮组换成状态胶囊。 */
@PreviewTest
@Preview(name = "v2-approval-p6-decided", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2ApprovalDecidedPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) { ApprovalFrame(listOf(approvalOf(status = "approved"))) }
        }
    }
}

/** P7 · 过期：按钮组灰掉 + 过期标签（这一档以前根本画不出来）。 */
@PreviewTest
@Preview(name = "v2-approval-p7-expired", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2ApprovalExpiredPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                com.kapp.tidings.feature.agents.AgentApprovalsSection(
                    approvals = listOf(approvalOf(expiresAt = 1L)),
                    onRespond = { _, _ -> },
                    onRespondChoice = { _, _ -> },
                    // 过期项默认不画（它是"待办"）；会话详情用这个开关把它们当历史画出来。
                    includeExpiredPending = true,
                )
            }
        }
    }
}

/** P18 · 插件离线：`agentState=failed` 的轻横幅（宿主发的群文本，不是本地 notice）。 */
@PreviewTest
@Preview(name = "v2-banner-p18-plugin-offline", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2StateBannerPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.kapp.tidings.feature.chat.AgentStateBannerRow(
                    state = "failed",
                    text = "插件离线，这个动作没有执行",
                )
                com.kapp.tidings.feature.chat.AgentStateBannerRow(
                    state = "done",
                    text = "已完成：12 个文件，3 分钟",
                )
            }
        }
    }
}

/**
 * 一条带卡片负载的会话消息夹具。
 *
 * 走的是**真实的** `MessageUi`，不是只有 JSON —— P15/P16 要验的是「这条消息在会话流里
 * 落到哪一支」，而那个判据的入参就是 `MessageUi`（`cardRowOutcome(message, 可见集)`）。
 */
private fun cardMessageOf(
    id: String,
    cardJson: JsonObject,
    text: String = "",
    at: Long = 0L,
) = MessageUi(id = id, peerId = "group:g1", mine = false, text = text, at = at, card = cardJson)

/**
 * P15 · `cardId`/`rev` 缺失的**退档**（S21）。
 *
 * 这一帧钉的是一件事：**坏负载不再消失**。以前解析不出身份键就「整行不画」——
 * 插件少写一个 `rev`，群里就凭空少一句话，两端都不报错。现在它退成一条普通文本：
 * 有正文就照原文显示，连正文都没有就显示一句本地兜底。
 *
 * 帧里第二行那颗朴素气泡是**就地画的**，不是生产气泡：`MessageBubble` 是
 * composeApp 的 `internal`，截图源集取不到。判定与文案都来自生产代码
 * （`cardRowOutcome` / `card_unreadable`），只有外壳形状是示意 —— 这一行的验证点是
 * 「它没消失、且不是一张卡」，不是气泡圆角。
 */
@PreviewTest
@Preview(name = "v2-card-p15-fallback", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardFallbackPhonePreview() {
    val withText = cardMessageOf(
        id = "m-1",
        // 少了 `rev`：这是一张**读不出来的卡**。
        cardJson = buildJsonObject { put("cardId", "task-89"); put("title", "重构网络传输层") },
        text = "插件只发了半张卡：重构网络传输层（进度 70%）",
    )
    val withoutText = cardMessageOf(
        id = "m-2",
        cardJson = buildJsonObject { put("rev", 2); put("title", "无 cardId 的卡") },
        text = "",
    )
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (message in listOf(withText, withoutText)) {
                    // 判定来自生产代码：坏负载一律 `FallbackToText`，不可能进 `Render`。
                    val outcome = cardRowOutcome(message, visibleCardMessageIds = emptySet())
                    check(outcome is CardRowOutcome.FallbackToText) { "P15 夹具必须是退档，实际 $outcome" }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            // 正文为空时补兜底文案 —— 与 ChatScreen 同一口径（那里的
                            // 取值是资源 `card_unreadable`；截图源集拿不到 compose 资源
                            // 运行时，这里写字面量并在漂移时靠人看一眼，不假装是同源）。
                            text = outcome.text.ifBlank { "这条卡片消息读不出来" },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * P16 · 同 `cardId` 多 rev 的**折叠**（§5.3）：三条消息只画一张卡。
 *
 * 落库是追加式新行（同 cardId、rev+1），所以「不刷屏」只能是 UI 语义。这一帧喂三条
 * 同 cardId 的消息进去，折叠与去向都走生产函数（`latestRevCardMessageIds` /
 * `cardRowOutcome`）：rev 1、2 落到 `Hidden`（整行不画），只有 rev 3 出卡。
 *
 * 为什么值得一帧：折叠一旦坏了，表现是「每刷新一次就多一条卡」—— 会话流被同一件事
 * 刷满，而单条渲染的测试全绿（每条都画得对，错的是**条数**）。
 */
@PreviewTest
@Preview(name = "v2-card-p16-collapse", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardCollapsePhonePreview() {
    val messages = listOf(10, 70, 100).mapIndexed { index, progress ->
        cardMessageOf(
            id = "m-${index + 1}",
            at = index * 1_000L,
            cardJson = buildJsonObject {
                put("cardId", "task-89")
                put("rev", index + 1)
                put("type", "team-task-board")
                put("title", "重构网络传输层")
                put("progress", progress)
            },
        )
    }
    val visible = latestRevCardMessageIds(messages)
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (message in messages) {
                    when (val outcome = cardRowOutcome(message, visible)) {
                        is CardRowOutcome.Render -> AgentCardBubble(
                            card = outcome.card,
                            pendingActions = emptySet(),
                            now = 0L,
                            onAction = null,
                        )
                        // 旧 rev：整行不画。帧里「只有一张卡」就是这条判据的读数。
                        is CardRowOutcome.Hidden -> Unit
                        is CardRowOutcome.FallbackToText -> error("P16 夹具的数据都是好卡：$outcome")
                    }
                }
            }
        }
    }
}

/** 桌面卡帧的统一列宽：与 [AgentCardDeskPreview] / 桌面气泡同一档（720 上限内）。 */
private val DeskCardWidth = 720.dp


// ── 桌面帧（S5 补做：WP-D5 ②）────────────────────────────────────────────
//
// §8 清单的每一条信息形态在 1280×800 再拍一张 —— 同一个 [AgentCardBubble] / 同一份
// [AgentApprovalsSection]，只有视口与列宽不同（AGENTS §2.6：内容共享、外壳参数化）。
// 这些帧此前整档缺失（designFormPairingGate 口径下「内容组件桌面无证据」）。

@PreviewTest
@Preview(name = "desk-v2-card-p1-zero", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardZeroDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 0,
                        steps = listOf(
                            Triple("读取仓库结构", "pending", null),
                            Triple("生成迁移脚本", "pending", null),
                            Triple("跑回归", "pending", null),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p2-mixed", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardMixedDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 45,
                        steps = listOf(
                            Triple("协议定义更新", "done", "@architect"),
                            Triple("核心通道编写", "running", "@coder"),
                            Triple("兼容层适配", "failed", "@coder"),
                            Triple("回归测试校验", "pending", "@tester"),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p3-done", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardDoneDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 100,
                        steps = listOf(
                            Triple("协议定义更新", "done", "@architect"),
                            Triple("核心通道编写", "done", "@coder"),
                            Triple("回归测试校验", "done", "@tester"),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

/** P4 · 审批主形态（桌面）：primary 批准 + 中性暂停，两颗都可达。 */
@PreviewTest
@Preview(name = "desk-v2-card-p4-approval", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardApprovalDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 70,
                        steps = listOf(
                            Triple("协议定义更新", "done", "@architect"),
                            Triple("核心通道编写", "running", "@coder"),
                        ),
                        actions = listOf(
                            Triple("pause-task", "暂停任务", false),
                            Triple("approve", "批准", true),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p8-banner-tones", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardBannerTonesDeskPreview() {
    val tones = listOf(
        "info" to "正在等待宿主任命工作区",
        "success" to "三步全部通过",
        "warn" to "有一步被跳过，请确认",
        "error" to "执行失败：命令返回 128",
    )
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tones.forEach { (tone, text) ->
                    AgentCardBubble(
                        card = cardOf(progress = 60, banner = text to tone),
                        pendingActions = emptySet(),
                        onAction = null,
                        modifier = Modifier.width(DeskCardWidth),
                    )
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p9-timeout", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardTimeoutDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 30,
                        steps = listOf(
                            Triple("拉取依赖", "done", null),
                            Triple("构建产物", "running", null),
                        ),
                        banner = "gpu-runner 90 秒没有回音" to "error",
                        actions = listOf(
                            Triple("invoke-retry", "重试", false),
                            Triple("invoke-fallback-local", "降级为本地执行", false),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p10-artifacts", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardArtifactsDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 100,
                        steps = listOf(Triple("打包", "done", null)),
                        banner = "产物已上传" to "success",
                        artifacts = listOf(
                            Triple("tidings-0.62.0.apk", 52_428_800L, "application/vnd.android.package-archive"),
                            Triple("build-report.html", 1_536L, "text/html"),
                            Triple("checksums.txt", 512L, null),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p11-diff-short", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardDiffShortDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 80,
                        diff = Triple("core/…/NodeOutbound.kt", "发送侧三处预算对齐", 12 to 3),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p12-diff-long", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardDiffLongDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 80,
                        diff = Triple(
                            "core/src/commonMain/kotlin/com/kapp/tidings/core/session/NodeOutbound.kt",
                            "把发送侧的 UDP 预算、TCP 预算与队列重放预算对齐到同一个常量，并把三处硬编码的 4096 换成协议里的唯一来源，顺带补上两条越界判据",
                            128 to 34,
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p13-assignment", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardAssignmentDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 60,
                        steps = listOf(
                            Triple("接口契约评审", "done", "@architect"),
                            Triple("实现与自测", "running", "@coder"),
                            Triple("端到端复验", "pending", "@qa-lead-with-a-very-long-name"),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p14-unknown-type", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardUnknownTypeDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        type = "future-card-type-from-a-newer-plugin",
                        progress = 50,
                        steps = listOf(Triple("一步", "running", null)),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-card-p17-readonly", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardReadOnlyDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        progress = 70,
                        steps = listOf(
                            Triple("协议定义更新", "done", "@architect"),
                            Triple("核心通道编写", "running", "@coder"),
                        ),
                        actions = listOf(
                            Triple("pause-task", "暂停任务", false),
                            Triple("approve", "批准", true),
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    eligibility = CardActionEligibility.ReadOnly,
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

/** P15 的桌面帧：退档两态在 1280 宽下同样可见（判定走生产 cardRowOutcome）。 */
@PreviewTest
@Preview(name = "desk-v2-card-p15-fallback", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardFallbackDeskPreview() {
    val withText = cardMessageOf(
        id = "m-1",
        cardJson = buildJsonObject { put("cardId", "task-89"); put("title", "重构网络传输层") },
        text = "插件只发了半张卡：重构网络传输层（进度 70%）",
    )
    val withoutText = cardMessageOf(
        id = "m-2",
        cardJson = buildJsonObject { put("rev", 2); put("title", "无 cardId 的卡") },
        text = "",
    )
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (message in listOf(withText, withoutText)) {
                    val outcome = cardRowOutcome(message, visibleCardMessageIds = emptySet())
                    check(outcome is CardRowOutcome.FallbackToText) { "P15 桌面夹具必须是退档，实际 $outcome" }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.width(DeskCardWidth),
                    ) {
                        Text(
                            text = outcome.text.ifBlank { "这条卡片消息读不出来" },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** P16 的桌面帧：折叠语义在桌面同一条生产函数上成立（三条只出一张卡）。 */
@PreviewTest
@Preview(name = "desk-v2-card-p16-collapse", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardCollapseDeskPreview() {
    val messages = listOf(10, 70, 100).mapIndexed { index, progress ->
        cardMessageOf(
            id = "m-${index + 1}",
            at = index * 1_000L,
            cardJson = buildJsonObject {
                put("cardId", "task-89")
                put("rev", index + 1)
                put("type", "team-task-board")
                put("title", "重构网络传输层")
                put("progress", progress)
            },
        )
    }
    val visible = latestRevCardMessageIds(messages)
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (message in messages) {
                    when (val outcome = cardRowOutcome(message, visible)) {
                        is CardRowOutcome.Render -> AgentCardBubble(
                            card = outcome.card,
                            pendingActions = emptySet(),
                            now = 0L,
                            onAction = null,
                            modifier = Modifier.width(DeskCardWidth),
                        )
                        is CardRowOutcome.Hidden -> Unit
                        is CardRowOutcome.FallbackToText -> error("P16 桌面夹具的数据都是好卡：$outcome")
                    }
                }
            }
        }
    }
}

/** P18 的桌面帧：failed / done 两条轻横幅在 720 列宽（同一份 BannerSurface 壳）。 */
@PreviewTest
@Preview(name = "desk-v2-banner-p18-plugin-offline", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2StateBannerDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.kapp.tidings.feature.chat.AgentStateBannerRow(
                    state = "failed",
                    text = "插件离线，这个动作没有执行",
                    modifier = Modifier.width(DeskCardWidth),
                )
                com.kapp.tidings.feature.chat.AgentStateBannerRow(
                    state = "done",
                    text = "已完成：12 个文件，3 分钟",
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}

// ── 审批行的桌面帧（P4/P6/P7 的 1280 形态）───────────────────────────────

@PreviewTest
@Preview(name = "desk-v2-approval-p4-pending", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2ApprovalPendingDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp).width(DeskCardWidth)) {
                ApprovalFrame(listOf(approvalOf()))
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-approval-p6-decided", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2ApprovalDecidedDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp).width(DeskCardWidth)) {
                ApprovalFrame(listOf(approvalOf(status = "approved")))
            }
        }
    }
}

@PreviewTest
@Preview(name = "desk-v2-approval-p7-expired", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2ApprovalExpiredDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp).width(DeskCardWidth)) {
                com.kapp.tidings.feature.agents.AgentApprovalsSection(
                    approvals = listOf(approvalOf(expiresAt = 1L)),
                    onRespond = { _, _ -> },
                    onRespondChoice = { _, _ -> },
                    includeExpiredPending = true,
                )
            }
        }
    }
}


// ── T-2e：待审批置顶聚焦卡 + 反向多选卡（task-20）─────────────────────────

/** P4' 置顶聚焦卡（手机）：聚合事实 + 直达按钮（原型 screenAgents 置顶条 relay.html:4692-4701）。 */
@PreviewTest
@Preview(name = "v2-pinned-approval-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PinnedApprovalFocusPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                com.kapp.tidings.feature.agents.PinnedApprovalFocusCard(
                    pendingCount = 3,
                    subtitle = "涉及 Git 远程推送与敏感文件修改，请及时处理",
                    onApproveClick = {},
                )
            }
        }
    }
}

/** P4' 置顶聚焦卡（桌面）：同一份在 720 列宽。 */
@PreviewTest
@Preview(name = "desk-v2-pinned-approval", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun PinnedApprovalFocusDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp).width(DeskCardWidth)) {
                com.kapp.tidings.feature.agents.PinnedApprovalFocusCard(
                    pendingCount = 3,
                    subtitle = "涉及 Git 远程推送与敏感文件修改，请及时处理",
                    onApproveClick = {},
                )
            }
        }
    }
}

/** P19 · 反向多选卡（手机，ask_question §7.9）：推荐实心 + 其余描边，拇指一点。 */
@PreviewTest
@Preview(name = "v2-card-p19-ask-question", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardAskQuestionPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        type = "ask_question",
                        banner = "检测到多个候选节点，按什么顺序派发任务？" to "info",
                        options = listOf(
                            "在线节点优先 (推荐)" to true,
                            "ID 字典序" to false,
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                )
            }
        }
    }
}

/** P19 桌面：反向多选卡 720 列宽（同一份实现）。 */
@PreviewTest
@Preview(name = "desk-v2-card-p19-ask-question", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun V2CardAskQuestionDeskPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp)) {
                AgentCardBubble(
                    card = cardOf(
                        type = "ask_question",
                        banner = "检测到多个候选节点，按什么顺序派发任务？" to "info",
                        options = listOf(
                            "在线节点优先 (推荐)" to true,
                            "ID 字典序" to false,
                        ),
                    ),
                    pendingActions = emptySet(),
                    onAction = { _, _ -> },
                    modifier = Modifier.width(DeskCardWidth),
                )
            }
        }
    }
}
