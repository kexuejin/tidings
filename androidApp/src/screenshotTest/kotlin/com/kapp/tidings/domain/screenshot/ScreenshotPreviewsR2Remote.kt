// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.domain.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentEventUi
import com.kapp.tidings.domain.model.AgentQueueTaskUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.ui.desktop.DesktopPreviewActions
import com.kapp.tidings.ui.desktop.DesktopShell
import com.kapp.tidings.ui.desktop.DesktopShellActions
import com.kapp.tidings.ui.desktop.DesktopView
import com.kapp.tidings.ui.desktop.PREVIEW_NOW as DESKTOP_PREVIEW_NOW
import com.kapp.tidings.ui.desktop.desktopPreviewState
import com.kapp.tidings.ui.desktop.linPeer
import com.kapp.tidings.ui.desktop.zhouPeer
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * R2-W5：**d-agents 的填充态**（Agent 控制台，桌面）。
 *
 * ## 为什么必须新开一个文件再画一张
 *
 * 台账 `docs/plans/2026-10-10-relay-screen-pairing-ledger.md` 第 314 行给 `d-agents`
 * 判 ⚠️，原因写在「代码落点」格里：现有那张 `DeskAgentPaneExpandedPreview`
 * **画的不是本屏的填充态**。这一轮我把它坐实了 —— 原因有两层，缺一层都不够：
 *
 * 1. **夹具空**：那张帧用的是 `desktopPreviewState()`，它的 `peers` 里只有一个
 *    `agentPeer()`（昵称 "agent-node"），`agentApprovals` / `agentTasks` /
 *    `agentSessions` / `agentEvents` **全是空 map**。而 `AgentApprovalsSection` 与
 *    `AgentQueueSection` 都是 `if (approvals.isEmpty()) return` —— 于是原型 d06 里
 *    占屏最多的两段（待处理审批 3 项 / 任务队列 3 项）在参考图里**一行都不存在**。
 * 2. **结构**：原型 `d06-agents.png` 是「左会话列表 + 右 Agent 控制台」并存的整窗；
 *    `DeskAgentPaneExpandedPreview` 拍的是**面板自己**（`PaneBackdrop` 直接包 `AgentPane`），
 *    所以左侧那份列表、底部状态栏、菜单栏都不在图里。
 *
 * 所以本帧走**整窗**（`DesktopShell` 的 `initialView = DesktopView.Agents` 冷启动支），
 * 并把夹具武装到与 `d06-agents.png` 同量级：两个 agent、3 条审批（2 待批 + 1 已批准）、
 * 3 条队列任务、两条会话（第二条存在的唯一理由是让 `canSwitch` 为真 —— 原型会话行尾
 * 那个「⌄」只在 `sessions.size > 1` 时才画，一条会话的夹具拍不到它）。
 *
 * ## 与 R1 那批帧的关系
 *
 * R1 的 `AgentsApprovalsQueuePhonePreview`（`ScreenshotPreviewsAgents.kt`）钉的是**手机**
 * `m-agents` 的填充态；本帧钉的是**桌面** `d-agents`。两者共用同一支
 * `AgentConsoleScreen`，所以同样的夹具思路，但壳、档位与宽度不同：
 * 手机 393×852 走 `CompactScaffold`，桌面 1152×800 走 `DesktopShell`（Expanded）。
 *
 * ## 时钟
 *
 * `now` 用桌面夹具自己的 `PREVIEW_NOW`（`com.kapp.tidings.ui.desktop.PREVIEW_NOW`，
 * 1_789_000_000_000 = 2026-09-10 08:26:40 CST），**不能**混用手机夹具那份
 * （1_735_689_600_000）—— 壳里的相对时间全部相对前者偏移，混用会让标题栏出现
 * 以天计的假读数。下面所有时间戳都由它减去分钟数派生（`r2Ago`），不写死绝对毫秒。
 * 于是任务队列那三行显示 08:20 / 07:55 / 07:10，而原型 d06 上写的是 14:30 / 13:55 / 13:10
 * —— 那是原型自己那套 mock 时钟的读数，**不是**被测的排版。要的是"三条任务、时间右对齐、
 * 格式为 HH:mm"，不是把某个具体钟点抄进参考图（抄进去反而让参考图与 `now` 自相矛盾）。
 *
 * ## 数据缺口（不画，也不编）
 *
 * 原型 chip 里那颗灰 tag（`DSH` / `CI`）来自 `workspace.source`，`.t2` 行里的
 * `~/src/teahouse-mobile` 来自 `workspace.path` —— `PeerUi`（`RelayUiState.kt:63-95`）
 * 两个字段都没有，wire 上也没有（见 `fidelity-deviations.yaml` 里 R1 那条登记）。
 * 所以这里**不写**这两个值：宁可少画一段，也不拿 host/IP 顶替成"工作区路径"。
 */

/** 桌面夹具的时钟基准。与 `com.kapp.tidings.ui.desktop.PREVIEW_NOW` 同值，见文件头「时钟」。 */
private val R2_NOW = DESKTOP_PREVIEW_NOW

private fun r2Ago(minutes: Long) = R2_NOW - minutes * 60_000L

private const val R2_TEAHOUSE_ID = "7c3e91b5-1111-4111-8111-111111111111"
private const val R2_CI_ID = "c1c1c1c1-2222-4222-8222-222222222222"
private const val R2_RUN_SESSION = "s-refactor-runner"
private const val R2_CI_SESSION = "s-build-watch"
private const val R2_GROUP_ID = "tea-house"

/**
 * 原型 d06 里被选中的那个 agent：`teahouse-mobile`，能力 `ag1 tw1`。
 *
 * `caps` 必须真的含 `ag1`（`MsgTypes.CAP_AGENT`）：`PeerUi.isAgent` 看的就是它，
 * 少了这一个键 `state.agentPeers` 为空、`AgentConsoleScreen` 会走进
 * 「一个 Agent 都没有」的空态并 `return@Column` —— 那样这一帧就什么也钉不住，
 * 而且**不会报错**（空态也是一张合法的图）。这是这个夹具最容易踩的一脚。
 */
private val r2TeahouseAgent = PeerUi(
    nodeId = R2_TEAHOUSE_ID,
    nick = "teahouse-mobile",
    company = "捎话",
    dept = "研发",
    team = "客户端",
    host = "MacBook-Pro",
    ip = "192.168.1.5",
    udpPort = 17878,
    platform = "darwin",
    tcpPort = 17879,
    caps = listOf("ag1", "tw1"),
    online = true,
    lastSeenAt = r2Ago(0),
)

/** 第二个 agent。存在的理由是 chip 那一行：只有一个 agent 时 `agents.size > 1` 为假，整行 chip 不画。 */
private val r2CiAgent = PeerUi(
    nodeId = R2_CI_ID,
    nick = "ci-runner",
    company = "捎话",
    dept = "研发",
    team = "CI",
    host = "ci-01",
    ip = "192.168.1.31",
    platform = "linux",
    tcpPort = 17881,
    caps = listOf("ag1"),
    online = true,
    lastSeenAt = r2Ago(12),
)

/**
 * d-agents 的整窗状态：两个 agent + 一条群 + 人类同事，控制台侧武装到原型同量级。
 *
 * `expiresAt` 一律 `0L`：`AgentApprovalCards.kt:60` 的过期判据是
 * `approval.expiresAt > 0L && nowMillis() > expiresAt`，这里用的是**挂钟**
 * （不是帧里的 `now`），写真实时间戳会让"已过期"随日历漂移 —— 参考图当天就作废。
 */
private fun r2DeskAgentsState(): RelayUiState {
    val base = desktopPreviewState()
    val self = base.settings.nodeId
    val groupConv = ConvId.group(R2_GROUP_ID)
    return base.copy(
        settings = base.settings.copy(
            nick = "relay-8f2a",
            groups = listOf(
                GroupMeta(
                    groupId = R2_GROUP_ID,
                    name = "茶话间 · 发布值班",
                    members = listOf(self, linPeer().nodeId, zhouPeer().nodeId),
                    rev = 3,
                    updatedBy = self,
                    updatedTs = r2Ago(10),
                    ownerId = self,
                ),
            ),
        ),
        // 顺序即 chip 顺序，也决定 `selectedPeerId = null` 时的默认选中项：
        // `AgentConsoleScreen` 取 `agents.firstOrNull()`，所以 teahouse-mobile 必须在最前。
        peers = listOf(r2TeahouseAgent, linPeer(), zhouPeer(), r2CiAgent),
        messages = base.messages + (
            R2_TEAHOUSE_ID to listOf(
                MessageUi(
                    id = "r2-a1",
                    peerId = R2_TEAHOUSE_ID,
                    mine = true,
                    text = "把移动端的门禁跑一遍，失败就把原因贴回来。",
                    at = r2Ago(18),
                    delivery = DeliveryState.Sent,
                ),
                MessageUi(
                    id = "r2-a2",
                    peerId = R2_TEAHOUSE_ID,
                    mine = false,
                    text = "六条门禁全绿：core/composeApp/agentNode 测试通过，APK 11.8 MB。",
                    at = r2Ago(16),
                ),
            )
            ) + (
            groupConv to listOf(
                MessageUi(
                    id = "r2-g1",
                    peerId = groupConv,
                    mine = false,
                    text = "insets 截图已贴，3/5 人在线",
                    at = r2Ago(24),
                    senderId = zhouPeer().nodeId,
                ),
            )
            ),
        unreadByPeer = mapOf(
            linPeer().nodeId to 2,
            zhouPeer().nodeId to 1,
            groupConv to 1,
        ),
        pendingByPeer = mapOf(R2_CI_ID to 1),
        agentApprovals = mapOf(
            "r2-ap-read" to AgentApprovalUi(
                approvalId = "r2-ap-read",
                sessionId = R2_RUN_SESSION,
                peerId = R2_TEAHOUSE_ID,
                tool = "Read",
                summary = "读取 /Users/me/Desktop/secrets.env（含密钥）",
                status = "pending",
            ),
            "r2-ap-bash" to AgentApprovalUi(
                approvalId = "r2-ap-bash",
                sessionId = R2_RUN_SESSION,
                peerId = R2_TEAHOUSE_ID,
                tool = "Bash",
                summary = "npm run deploy --prod",
                status = "pending",
            ),
            // 已批准那一条让「已批准」胶囊进图：三张卡里两张有动作、一张只有状态，
            // 原型 d06 就是这个形状（`工具: Write` 只有 `已批准`）。
            "r2-ap-write" to AgentApprovalUi(
                approvalId = "r2-ap-write",
                sessionId = R2_RUN_SESSION,
                peerId = R2_TEAHOUSE_ID,
                tool = "Write",
                summary = "把纪要草稿写进「本周纪要」笔记",
                status = "approved",
            ),
            "r2-ap-ci" to AgentApprovalUi(
                approvalId = "r2-ap-ci",
                sessionId = R2_CI_SESSION,
                peerId = R2_CI_ID,
                tool = "Bash",
                summary = "pnpm -r test --filter core",
                status = "pending",
            ),
        ),
        agentTasks = mapOf(
            "r2-q1" to AgentQueueTaskUi(
                taskId = "q-7f3a91c2d4",
                sessionId = R2_RUN_SESSION,
                peerId = R2_TEAHOUSE_ID,
                op = "整理",
                prompt = "整理今天 3 场会议的纪要",
                queuedAt = r2Ago(6),
            ),
            "r2-q2" to AgentQueueTaskUi(
                taskId = "q-2b81d0a9f6",
                sessionId = R2_RUN_SESSION,
                peerId = R2_TEAHOUSE_ID,
                op = "生成",
                prompt = "生成本周进度周报",
                queuedAt = r2Ago(31),
            ),
            "r2-q3" to AgentQueueTaskUi(
                taskId = "q-9c04e7b3a5",
                sessionId = R2_RUN_SESSION,
                peerId = R2_TEAHOUSE_ID,
                op = "同步",
                prompt = "把 design-spec.json 同步到知识库",
                queuedAt = r2Ago(76),
            ),
        ),
        agentSessions = mapOf(
            AgentSessionKey(R2_TEAHOUSE_ID, R2_RUN_SESSION) to AgentSessionUi(
                peerId = R2_TEAHOUSE_ID,
                sessionId = R2_RUN_SESSION,
                alias = "refactor runner",
                agentKind = "codex",
                state = "busy",
                title = "把 runner 拆成八条臂",
                startedAt = r2Ago(20),
                updatedAt = r2Ago(2),
            ),
            AgentSessionKey(R2_CI_ID, R2_CI_SESSION) to AgentSessionUi(
                peerId = R2_CI_ID,
                sessionId = R2_CI_SESSION,
                alias = "build watch",
                agentKind = "claude",
                state = "idle",
                title = "盯着 main 的构建",
                startedAt = r2Ago(45),
                updatedAt = r2Ago(9),
            ),
        ),
        agentEvents = mapOf(
            AgentSessionKey(R2_TEAHOUSE_ID, R2_RUN_SESSION) to listOf(
                AgentEventUi(
                    sessionId = R2_RUN_SESSION,
                    peerId = R2_TEAHOUSE_ID,
                    cursor = 41,
                    eventType = "session.started",
                    summary = "会话已开始",
                    at = r2Ago(20),
                ),
                AgentEventUi(
                    sessionId = R2_RUN_SESSION,
                    peerId = R2_TEAHOUSE_ID,
                    cursor = 42,
                    eventType = "tool.requested",
                    summary = "请求执行 Read",
                    at = r2Ago(4),
                ),
                AgentEventUi(
                    sessionId = R2_RUN_SESSION,
                    peerId = R2_TEAHOUSE_ID,
                    cursor = 43,
                    eventType = "approval.pending",
                    summary = "等你批准这条 Read",
                    at = r2Ago(3),
                ),
            ),
        ),
    )
}

/**
 * `d-agents`：Agent 控制台 · 桌面整窗 · 填充态（原型 shot `d06-agents`，2304×1600 = 1152×800 @2x）。
 *
 * 1152×800 与原型同尺寸；`initialView = DesktopView.Agents` 走壳的冷启动初值，
 * 不用快捷键/菜单那条路（静态帧抓不到按键，见 `DesktopShell` 该形参的说明）。
 * `controlPlanePort = 0`：预览没有真控制面端口，显示 0 而不是编一个
 * （与 W4 那批桌面帧同一口径）。
 */
@PreviewTest
@Preview(name = "r2-desk-agents-rich", widthDp = 1152, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun R2DeskAgentsWindowPreview() {
    AppTheme(darkTheme = false) {
        DesktopShell(
            state = r2DeskAgentsState(),
            controller = DesktopPreviewActions,
            windowWidthDp = 1152,
            windowHeightDp = 800,
            darkTheme = false,
            controlPlanePort = 0,
            initialView = DesktopView.Agents,
            initialPeerId = null,
            now = R2_NOW,
            actions = DesktopShellActions(
                onOpenDownloadsFolder = {},
                onOpenPeerWindow = {},
            ),
        )
    }
}
