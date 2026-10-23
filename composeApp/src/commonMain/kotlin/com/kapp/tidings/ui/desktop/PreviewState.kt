// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.domain.DesktopPaneActions

import com.kapp.tidings.domain.BannerMessage
import com.kapp.tidings.domain.BannerTone
import com.kapp.tidings.domain.BannerUi
import com.kapp.tidings.domain.CabinetRecentUpload
import com.kapp.tidings.domain.GlobalSearchChatHit
import com.kapp.tidings.domain.GlobalSearchContactHit
import com.kapp.tidings.domain.GlobalSearchFileHit
import com.kapp.tidings.domain.GlobalSearchResult
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.domain.RelaySettings
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.ShareGrantEntry
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.platform.Transport
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentEventUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.model.CabinetEntryUi
import com.kapp.tidings.domain.model.CabinetUiState
import com.kapp.tidings.domain.model.ConversationUi
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MdnsServiceUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NetworkUi
import com.kapp.tidings.domain.model.NodePhase
import com.kapp.tidings.domain.model.NoticeKind
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.model.TransferState

/**
 * 桌面端 `@Preview` 与截图对账用的**唯一假数据源**。
 *
 * 为什么要有它：桌面壳原先是一个 1,709 行、24 个 `private` 组件的单文件，谁也没法在
 * IDE 里单独看一个 pane 长什么样。提取之后每个 pane 都要能独立预览，而"独立预览"
 * 要求状态能从参数拼出来 —— 于是这里把状态模型按真实场景拼好，谁需要就 `copy()`。
 *
 * 三个纪律：
 * 1. **只读**。这里不产生任何副作用，也不引用 `RelayController`；预览不该启动节点。
 * 2. **不含界面文案**。这里只放数据（昵称、文件名、地址）。用户可见字符串仍只住在
 *    `composeResources` 的 `values` 目录下的 `strings.xml`（见 `composeApp/build.gradle.kts` 的注释）。
 * 3. **时间用固定基准**。`at` / `lastSeenAt` 一律相对 [PREVIEW_NOW] 偏移，
 *    不用 `System.currentTimeMillis()`：截图基线必须是可复现的。
 */

/** 假数据的固定时间基准：2026-10-05 20:00:00 本地时间。 */
public const val PREVIEW_NOW: Long = 1_789_000_000_000L

private const val SELF_NODE_ID = "n-self0000000001"
private const val PEER_LIN = "n-lin00000000002"
private const val PEER_ZHOU = "n-zhou0000000003"
private const val PEER_AGENT = "n-agent000000004"

/** 本机视角的一份「一切正常」状态：节点在跑、地址齐全、已发现两个对端。 */
public fun desktopPreviewState(): RelayUiState = RelayUiState(
    settings = RelaySettings(
        nodeId = SELF_NODE_ID,
        nick = "我的桌面",
        company = "捎话",
        dept = "桌面端",
        team = "Tidings",
        avatar = 0,
        profileRev = 3,
        persistMessages = true,
    ),
    network = NetworkUi(
        phase = NodePhase.Running,
        transport = Transport.Wifi,
        localAddresses = listOf("192.168.1.23"),
        boundUdpPort = 49213,
        mdnsAdvertised = true,
    ),
    peers = listOf(linPeer(), zhouPeer(), agentPeer()),
    messages = mapOf(
        PEER_LIN to linThread(),
        PEER_ZHOU to zhouThread(),
    ),
    unreadByPeer = mapOf(PEER_LIN to 2),
    pendingByPeer = mapOf(PEER_ZHOU to 1),
    mdnsServices = listOf(
        MdnsServiceUi(serviceName = "捎话@living-room", host = "192.168.1.11", port = 49213),
    ),
)

/** 冷启动空态：一个对端都没有、一条消息都没有 —— 原型的 `04-desk-empty`。 */
public fun desktopPreviewEmptyState(): RelayUiState = RelayUiState(
    settings = desktopPreviewState().settings,
    network = NetworkUi(
        phase = NodePhase.Running,
        transport = Transport.Wifi,
        localAddresses = listOf("192.168.1.23"),
        boundUdpPort = 49213,
        mdnsAdvertised = true,
    ),
)

/** 未选会话 —— 原型的 `05-desk-nosel`：有列表、有对端，但右栏没得画。 */
public fun desktopPreviewNoSelectionState(): RelayUiState = desktopPreviewState()

/**
 * 选中 Agent 会话 —— 原型 `d-chat`：正文里有一条 Agent 线程（见 [agentThread]）。
 *
 * 与 [desktopPreviewNoSelectionState] 的区别只有一个 `messages` 里多出的 Agent 线程，
 * 其余字段逐字取自 [desktopPreviewState]（不新增、不改既有夹具的取值）。
 */
public fun desktopPreviewAgentThreadState(): RelayUiState = desktopPreviewState().let { base ->
    base.copy(messages = base.messages + (PEER_AGENT to agentThread()))
}

/**
 * 消息页签的「工作区」段（原型 `m01-home-recent` 下半屏）用的夹具。
 *
 * 为什么不能拿 [desktopPreviewState] 直接用：那里 `agentPeer()` 虽然声明了 `ag1`，
 * 但 `agentSessions` / `agentEvents` / `agentApprovals` 三张表**都是空的** ——
 * 派生 `inboxWorkspaceUnits()` 只能画出一行「未上报类型 · 尚未收到会话宣告」，
 * 而这一段要拍的是**有类型、有会话、有待审批**那一档（原型里那颗「2 项待批」药丸）。
 *
 * 一帧里凑齐三档降级：
 * 1. 同一台设备上两个 Agent（`dsh` 带一条待审批、`opencode` 只有会话）——
 *    组头的「2 个 Agent」只有这样才能拍出来；一台设备跑 `dsh-main` + `oc-main`
 *    照抄原型 `relay.html` 的 `WORKSPACES` 夹具。
 * 2. 一台**离线、且没有 agentKind** 的旧版节点（[legacyAgentPeer]）——
 *    降级链最底那一档：「未上报类型」+「尚未收到会话宣告」。
 * 3. 会话区仍是 [desktopPreviewState] 的 Lin / 周两行 —— 工作区段排在会话区之后，
 *    两段要在同一屏里，否则拍不出「分段」这个结论本身。
 *
 * 数值全部相对 [PREVIEW_NOW] 偏移（本文件第 3 条纪律），截图基线才可复现。
 */
public fun desktopPreviewInboxWorkspaceState(): RelayUiState = desktopPreviewState().let { base ->
    val dshSession = "s-dsh-main-0001"
    val ocSession = "s-oc-main-0002"
    base.copy(
        peers = base.peers + legacyAgentPeer(),
        agentSessions = mapOf(
            AgentSessionKey(PEER_AGENT, dshSession) to AgentSessionUi(
                peerId = PEER_AGENT,
                sessionId = dshSession,
                alias = "dsh-main",
                agentKind = "dsh",
                state = "busy",
                title = "把消息页签的「工作区」段接进 Compose",
                startedAt = PREVIEW_NOW - 900_000L,
                updatedAt = PREVIEW_NOW - 120_000L,
            ),
            AgentSessionKey(PEER_AGENT, ocSession) to AgentSessionUi(
                peerId = PEER_AGENT,
                sessionId = ocSession,
                alias = "oc-main",
                agentKind = "opencode",
                state = "idle",
                title = "核对图标层对账",
                startedAt = PREVIEW_NOW - 1_800_000L,
                updatedAt = PREVIEW_NOW - 600_000L,
            ),
        ),
        agentEvents = mapOf(
            AgentSessionKey(PEER_AGENT, dshSession) to listOf(
                AgentEventUi(
                    sessionId = dshSession,
                    peerId = PEER_AGENT,
                    cursor = 12,
                    eventType = "tool.started",
                    summary = "gradlew :composeApp:jvmTest",
                    at = PREVIEW_NOW - 90_000L,
                ),
            ),
        ),
        agentApprovals = mapOf(
            "ap-dsh-0001" to AgentApprovalUi(
                approvalId = "ap-dsh-0001",
                sessionId = dshSession,
                peerId = PEER_AGENT,
                tool = "bash",
                summary = "git push origin main",
                expiresAt = PREVIEW_NOW + 240_000L,
            ),
        ),
    )
}

/**
 * 一台声明了 `ag1`、但**一个 `agentKind` 都没上报过**、而且已经离线的旧版节点
 * （原型 `relay.html` 里的「小陈的旧版」）。
 *
 * 它在「工作区」段里是降级链最底那一档：标题只能写「未上报类型」、副行写
 * 「尚未收到会话宣告」—— 父稿 §3.1 的降级铁律不许拿 host / IP / path 末段顶替名字。
 */
public fun legacyAgentPeer(): PeerUi = PeerUi(
    nodeId = "n-legacy00000005",
    nick = "小陈的旧版",
    host = "old-mbp.local",
    ip = "192.168.1.31",
    udpPort = 49221,
    platform = "macOS 12",
    caps = listOf(com.kapp.tidings.core.protocol.MsgTypes.CAP_AGENT),
    online = false,
    lastSeenAt = PREVIEW_NOW - 10_800_000L,
)

/**
 * 冷启动、节点还在启动：一个对端都没有、一条消息都没有，阶段是 `Starting`。
 *
 * 与 [desktopPreviewEmptyState] 的区别只有一个 `phase`，但这一个字段决定会话侧栏
 * 画的是「正在恢复会话…」还是「还没有会话」—— 两者在参考图里必须看得出不同。
 */
public fun desktopPreviewStartingState(): RelayUiState = desktopPreviewEmptyState().let { base ->
    base.copy(network = base.network.copy(phase = NodePhase.Starting))
}

/** 有会话但选了离线对端：头像/地址/最后在线都要给出诚实取值，不能画成已连接。 */
public fun desktopPreviewOfflinePeerState(): RelayUiState = desktopPreviewState().let { base ->
    base.copy(
        peers = base.peers.map { if (it.nodeId == PEER_ZHOU) it.copy(online = false) else it },
        unreadByPeer = emptyMap(),
    )
}

/**
 * 节点启动失败：横幅 + 状态栏都要有话说，状态栏的 phase 文案不能还写「运行中」。
 *
 * 两处证据缺一不可：
 * - 状态栏先数在线节点、再看 phase（`DesktopStatusBar` 里 `onlineCount > 0` 优先于 `phaseLabel`），
 *   所以这里必须把对端一起转离线，`onlineCount` 归零后才会落到 `phaseLabel(Failed)`「启动失败」；
 *   留着两个在线对端只会把失败文案盖成「已连接 2 个节点」。
 *   这也让场景本身更诚实：绑不上端口的本机根本没法建立这些连接。
 * - 横幅取自真实运行路径 `RelayController` 绑定失败时的 `BannerMessage.BindFailed`，
 *   文本走 `Common.kt` 里已有的 `node_bind_failed_reason` 资源，不另造文案。
 */
public fun desktopPreviewFailedState(): RelayUiState = desktopPreviewState().let { base ->
    base.copy(
        network = base.network.copy(
            phase = NodePhase.Failed,
            localAddresses = emptyList(),
            mdnsAdvertised = false,
        ),
        peers = base.peers.map { it.copy(online = false) },
        banner = BannerUi(
            message = BannerMessage.BindFailed(port = 49_213, reason = "Address already in use"),
            tone = BannerTone.Error,
        ),
    )
}

// ── 对端 ─────────────────────────────────────────────────

public fun linPeer(): PeerUi = PeerUi(
    nodeId = PEER_LIN,
    nick = "林岚",
    company = "捎话",
    dept = "设计",
    team = "Tidings",
    avatar = 1,
    host = "living-room.local",
    ip = "192.168.1.11",
    udpPort = 49213,
    platform = "macOS 15.3",
    tcpPort = 49214,
    online = true,
    lastSeenAt = PREVIEW_NOW - 12_000L,
)

public fun zhouPeer(): PeerUi = PeerUi(
    nodeId = PEER_ZHOU,
    nick = "老周",
    company = "捎话",
    dept = "后端",
    team = "Tidings",
    avatar = 2,
    host = "studio.local",
    ip = "192.168.1.34",
    udpPort = 49215,
    platform = "Ubuntu 24.04",
    tcpPort = 49216,
    online = false,
    lastSeenAt = PREVIEW_NOW - 3 * 24 * 3_600_000L,
)

/** agent 节点：`caps` 里带 `CAP_AGENT` 才算 —— 桌面端靠它画 kind badge，不另设字段。 */
public fun agentPeer(): PeerUi = PeerUi(
    nodeId = PEER_AGENT,
    nick = "agent-node",
    host = "nas.local",
    ip = "192.168.1.9",
    udpPort = 49217,
    platform = "Linux 6.8",
    caps = listOf(com.kapp.tidings.core.protocol.MsgTypes.CAP_AGENT),
    online = true,
    lastSeenAt = PREVIEW_NOW - 3_000L,
)

// ── 消息 ─────────────────────────────────────────────────

private fun linThread(): List<MessageUi> = listOf(
    previewMessage(PEER_LIN, text = "设计规范更新了，输入框那一段要按新的来", at = PREVIEW_NOW - 300_000L),
    previewMessage(PEER_LIN, mine = true, text = "收到，我下午改", at = PREVIEW_NOW - 280_000L, delivery = DeliveryState.Sent),
    previewMessage(
        peerId = PEER_LIN,
        text = "",
        at = PREVIEW_NOW - 120_000L,
        delivery = DeliveryState.Queued,
        file = sampleFileCard(TransferState.Active, done = 1_400_000L),
    ),
    previewMessage(PEER_LIN, text = "顺便把这份也发你", at = PREVIEW_NOW - 90_000L),
    previewMessage(PEER_LIN, text = "收到没？", at = PREVIEW_NOW - 60_000L),
    previewMessage(PEER_LIN, text = "文件呢", at = PREVIEW_NOW - 30_000L),
)

private fun zhouThread(): List<MessageUi> = listOf(
    previewMessage(PEER_ZHOU, text = "昨天那个配色我看着有点闷", at = PREVIEW_NOW - 86_400_000L),
    previewMessage(PEER_ZHOU, mine = true, text = "换深茶绿了", at = PREVIEW_NOW - 86_000_000L, delivery = DeliveryState.Queued),
)

/**
 * Agent 会话的线程 —— 原型 `d-chat`（`prototype/screenshots/relay/d01-chat.png`）的正文里
 * 有一张**入站文件卡**与两条气泡（`prototype/relay.html:4892-4913` 的 `screenDChat()`）。
 *
 * 既有夹具只给 Lin / 周 两个会话配了线程，选到 Agent 时那一格是空的 ——
 * 第二轮五条验收的第 2 条（原型图 ↔ 代码图并排开图）在空正文上不成立。
 * 文件名与文案是演示数据（原型是 `build-jvm.log`），文件卡沿用既有的 [sampleFileCard]。
 */
private fun agentThread(): List<MessageUi> = listOf(
    previewMessage(PEER_AGENT, text = "重新跑一遍全量向量，我这边看 checkpoint 补齐了。", at = PREVIEW_NOW - 420_000L),
    previewMessage(
        peerId = PEER_AGENT,
        text = "",
        at = PREVIEW_NOW - 360_000L,
        file = sampleFileCard(TransferState.Active, done = 2_400_000L),
    ),
    previewMessage(
        peerId = PEER_AGENT,
        mine = true,
        text = "收到，跑完把日志发我。",
        at = PREVIEW_NOW - 300_000L,
        delivery = DeliveryState.Sent,
    ),
)

public fun previewMessage(
    peerId: String,
    text: String,
    at: Long,
    mine: Boolean = false,
    delivery: DeliveryState? = null,
    file: FileCardUi? = null,
): MessageUi = MessageUi(
    id = "m-" + peerId.takeLast(4) + "-" + at,
    peerId = peerId,
    mine = mine,
    text = text,
    at = at,
    delivery = delivery,
    file = file,
)

/** 四种传输态各一个：文件视图与聊天卡片要同时展示它们，才看得出状态样式对不对。 */
public fun sampleFileCard(state: TransferState, done: Long = 0L): FileCardUi = when (state) {
    TransferState.Offered -> FileCardUi(
        transferId = "t-offer",
        name = "design-spec.pdf",
        size = 2_400_000L,
        done = 0L,
        mine = true,
        state = TransferState.Offered,
    )

    TransferState.Active -> FileCardUi(
        transferId = "t-active",
        name = "screenshots.zip",
        size = 12_000_000L,
        fileCount = 21,
        done = done,
        mine = false,
        state = TransferState.Active,
    )

    TransferState.Completed -> FileCardUi(
        transferId = "t-done",
        name = "relay-desktop.dmg",
        size = 48_000_000L,
        done = 48_000_000L,
        mine = false,
        state = TransferState.Completed,
        // 形状必须是**产品真能产出的**：真实落盘目录 = `pathJoin(dataDirectory, "downloads")`
        // （`app/relay/app/RelayController.kt` 的 `downloadDir`），Android 上 `dataDirectory` 形如
        // `/data/user/0/com.kapp.tidings/files`（`app/relay/feature/settings/SettingsPageChrome.kt`
        // 的 `DEFAULT_DATA_DIRECTORY`；jvmTest 的 `FileMessageStoreTest` 里也用同一形状）。
        // 旧值 `/Users/demo/Downloads/relay-desktop.dmg` 是 P1-9 审计废掉的桌面旧行为
        // （`desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWindow.kt` 的 KDoc
        // 「以前写死 ~/Downloads/捎话」），夹具画它等于把已消失的行为摆回参考图里。
        savedPaths = listOf("/data/user/0/com.kapp.tidings/files/downloads/relay-desktop.dmg"),
    )

    TransferState.Failed -> FileCardUi(
        transferId = "t-fail",
        name = "handbook.pdf",
        size = 900_000L,
        done = 120_000L,
        mine = false,
        state = TransferState.Failed,
        reason = "hash-mismatch",
    )

    TransferState.Declined -> FileCardUi(
        transferId = "t-declined",
        name = "archive.zip",
        size = 5_000_000L,
        done = 0L,
        mine = true,
        state = TransferState.Declined,
    )
}

/**
 * 对方发来的报价，**等我接受或拒绝**。
 *
 * [sampleFileCard] 里的 `Offered` 是 `mine = true`（我发出去、等对方点头），
 * 与这一支的文案不同：文件屏按 `mine` 分成「等对方接受」与「等我确认」两句
 * （`DesktopFilesPane.kt` 的 `fileStateLabel`）。此前所有 fixture 都只有前者，
 * 于是「等我确认」这一支从来没有图证明它渲染得对，文件屏的入向邀约角标
 * （[desktopNavBadges]）也永远是 0。
 */
public fun incomingOfferCard(): FileCardUi = FileCardUi(
    transferId = "t-incoming",
    name = "季度预算.xlsx",
    size = 3_600_000L,
    mine = false,
    state = TransferState.Offered,
)

/**
 * 文件视图专用状态。
 *
 * [FilesPane] 的行是从 `state.messages` 里带 `file` 的消息聚合出来的，
 * 直接喂 `List<FileCardUi>` 是喂不进去的（列表参数根本没人用）。所以这里给
 * 每个传输态配一条真正落在会话里的文件消息：会话、文件列表、文件详情
 * 三个视图对同一份数据的读法才一致。
 *
 * 五种传输态各一次，跨越三个对端，文件名 / 大小 / 落盘路径都不一样，
 * 这样列表里能同时看出：已落盘可拖出、进行中有进度、失败带原因、
 * 被拒只留一行、待确认等对方。`Offered` 出现两次 —— 出向（等对方接受）与
 * 入向（[incomingOfferCard]，等我确认）文案不同，只画一支就看不出这个分支。
 * `Completed` 也出现两次：带落盘路径的（[sampleFileCard]）与不带路径的
 * （[completedUnsavedCard]），后者的详情面板写「还没落盘」，是另一支文案。
 */
public fun desktopPreviewFilesState(): RelayUiState {
    val base = desktopPreviewState()
    fun fileMessage(peerId: String, card: FileCardUi, at: Long) = previewMessage(
        peerId = peerId,
        text = "",
        at = at,
        mine = card.mine,
        delivery = if (card.mine) DeliveryState.Sent else null,
        file = card,
    )
    return base.copy(
        messages = mapOf(
            // 林岚：进行中（linThread 里那条）＋ 已落盘 ＋ 对方发来等我确认的邀约 ＋ 完成但没落盘
            PEER_LIN to linThread() +
                fileMessage(
                    peerId = PEER_LIN,
                    card = sampleFileCard(TransferState.Completed),
                    at = PREVIEW_NOW - 900_000L,
                ) +
                fileMessage(PEER_LIN, incomingOfferCard(), PREVIEW_NOW - 200_000L) +
                fileMessage(PEER_LIN, completedUnsavedCard(), PREVIEW_NOW - 150_000L),
            // 老周：失败（带原因）＋ 被拒
            PEER_ZHOU to zhouThread() +
                fileMessage(PEER_ZHOU, sampleFileCard(TransferState.Failed), PREVIEW_NOW - 700_000L) +
                fileMessage(PEER_ZHOU, sampleFileCard(TransferState.Declined), PREVIEW_NOW - 500_000L),
            // Agent 节点：等对方接受
            PEER_AGENT to listOf(
                fileMessage(PEER_AGENT, sampleFileCard(TransferState.Offered), PREVIEW_NOW - 300_000L),
            ),
        ),
    )
}

/**
 * 已完成、但 `savedPaths` 为空的卡片。
 *
 * [sampleFileCard] 的 `Completed` 带着一条落盘路径，于是文件详情里
 * 「还没落盘」那一支（`desktop_files_saved_empty`）从来没有图证明它渲染得对。
 * 这一支不是边角：接收完成后落盘失败、或用户把文件挪走之后再回看，都会走到它。
 */
public fun completedUnsavedCard(): FileCardUi = FileCardUi(
    transferId = "t-done-unsaved",
    name = "会议纪要.md",
    size = 24_000L,
    done = 24_000L,
    mine = false,
    state = TransferState.Completed,
)

/**
 * 有会话、但一条带文件的消息都没有 —— 文件视图的空态。
 *
 * 直接把 `desktopPreviewState()` 的消息表里带 `file` 的条目滤掉即可：会话列表
 * 仍有三条（`peers` 不动），文件屏因此画「还没有文件」+「打开保存目录」，
 * 而不是「搜索无结果」。两者的 CTA 不同，必须各有一帧。
 */
public fun desktopPreviewNoFilesState(): RelayUiState {
    val base = desktopPreviewState()
    return base.copy(
        messages = base.messages.mapValues { (_, list) -> list.filter { it.file == null } },
    )
}

/**
 * 文件视图 + 传输失败横幅。
 *
 * 横幅是 `RelayUiState.banner` 这一个全局字段，任何一屏都会画它，所以
 * 「文件屏自己那条横幅」只能靠落在文件视图的帧来证明 —— 已有的
 * `desk-failed-*` 落在会话视图，证明不了。文案取真实运行路径里的
 * `TransferFailed`（`RelayController.kt` 在报价失败与传输失败处各发一次），
 * 原因串与 [sampleFileCard] 的 `Failed` 一致，不是编出来的。
 */
public fun desktopPreviewFilesBannerState(): RelayUiState =
    desktopPreviewFilesState().copy(
        banner = BannerUi(message = BannerMessage.TransferFailed("hash-mismatch"), tone = BannerTone.Error),
    )

/**
 * 节点视图 + 启动失败横幅。
 *
 * 与 [desktopPreviewFailedState] 同一类事实（节点起不来），但落在节点视图上：
 * 节点屏是「这条横幅该不该出现在这里、会不会和本机卡片打架」的现场。
 */
public fun desktopPreviewNodesBannerState(): RelayUiState =
    desktopPreviewState().copy(
        banner = BannerUi(
            message = BannerMessage.BindFailed(port = 49_213, reason = "Address already in use"),
            tone = BannerTone.Error,
        ),
    )

/** 会话行直接由 [RelayUiState.conversations] 算出来；这里只保证它非空且有未读差异。 */
public fun desktopPreviewConversations(): List<ConversationUi> = desktopPreviewState().conversations

// ── 群聊（README TODO ⑤）────────────────────────────────────────────────────

/**
 * 群 id 与三条群消息用的固定值。
 *
 * `members` 里**必须有自己**：本地不认识的群，`GroupMerge.decide` 先看"我在不在
 * 成员表里"，不在就整帧不收 —— 预览少写自己，这个群根本不会出现在侧栏，
 * 参考图会拍成一张普通的两栏会话，而门禁以为覆盖了群。
 */
private const val GROUP_ID = "g-20261007-desk"

private val groupMeta = GroupMeta(
    groupId = GROUP_ID,
    name = "桌面端联调",
    members = listOf(SELF_NODE_ID, PEER_LIN, PEER_ZHOU),
    rev = 4,
    updatedBy = SELF_NODE_ID,
    updatedTs = PREVIEW_NOW - 3_600_000L,
    ownerId = SELF_NODE_ID,
)

/**
 * 群会话被选中：侧栏里既有私聊也有群，右栏画的是群。
 *
 * 这一帧同时钉住四件事：侧栏群行画的是群名与 `n/3 人在线`（不是某个人的在线状态）、
 * 头部画群名与成员数、气泡上有**别人**的署名（自己那条没有）、
 * 以及时间线里那条「群成员有变化」是灰字而不是气泡。
 */
public fun desktopPreviewGroupState(): RelayUiState {
    val base = desktopPreviewState()
    val convId = ConvId.group(GROUP_ID)
    return base.copy(
        settings = base.settings.copy(groups = listOf(groupMeta)),
        messages = base.messages + (
            convId to listOf(
                previewMessage(convId, text = "桌面端这版我先跑一轮", at = PREVIEW_NOW - 900_000L)
                    .copy(senderId = PEER_LIN),
                previewMessage(convId, mine = true, text = "好，我这边同步改一下拖拽", at = PREVIEW_NOW - 840_000L, delivery = DeliveryState.Sent)
                    .copy(senderId = SELF_NODE_ID),
                MessageUi(
                    id = "m-group-notice",
                    peerId = convId,
                    mine = false,
                    text = "",
                    at = PREVIEW_NOW - 780_000L,
                    notice = NoticeKind.GroupMembersChanged,
                ),
                previewMessage(convId, text = "拖拽那条我昨天试过，能用", at = PREVIEW_NOW - 720_000L)
                    .copy(senderId = PEER_ZHOU),
            )
            ),
        unreadByPeer = base.unreadByPeer + (convId to 1),
    )
}

// ── 文件柜 ────────────────────────────────────────────────────────────────
//
// 为什么这一段是本文件里最后补上的：文件柜是**唯一一个在任何形态都没有截图门禁的
// 一级页面**（审计 §9 缺口①）。它没有门禁的原因不是被判定为不重要，而是它此前
// 根本没有假数据 —— 没有数据就没有静态帧，没有帧就没有参考图。下面这几个
// fixture 就是为了把这个洞补上，让 `CabinetPane` / `CabinetScreen` 能被 `@Preview`
// 渲染成确定的两帧。

/** 一个目录条目。`size` 用真实量级，好让 `formatBytes` 的分支（B/KB/MB）都走到。 */
private fun cabinetEntry(name: String, isDir: Boolean, size: Long, minutesAgo: Long) =
    CabinetEntryUi(name = name, isDir = isDir, size = size, mtime = PREVIEW_NOW - minutesAgo * 60_000L)

/**
 * 我的文件柜：已设好共享目录、默认权限 `read`、给两个人单独开过权限、最近有人放进来过。
 *
 * 选「已配置」而不是空态作基准，是因为空态只有一张卡片，四张卡片的排版问题
 * （共享目录 / 默认权限 / 单独设过的同事 / 最近有人放进来）在空态里一个都看不见。
 * 单独设过的同事给两条、且**权限不同**（一条 read 一条 write）—— 只给一条的话，
 * 「每行显示各自的 mode」这条渲染规则等于没测。
 * 最近有人放进来给两条，上传者不同、文件数不同，时间一近一远。
 */
public fun desktopPreviewCabinetState(): RelayUiState = desktopPreviewState().let { base ->
    base.copy(
        settings = base.settings.copy(
            shareRoot = "~/Documents/捎话共享",
            shareMode = "read",
            shareGrants = listOf(
                ShareGrantEntry(nodeId = PEER_LIN, mode = "write"),
                ShareGrantEntry(nodeId = PEER_ZHOU, mode = "read"),
            ),
            cabinetRecentUploads = listOf(
                CabinetRecentUpload(
                    transferId = "t-cab-1",
                    uploaderNodeId = PEER_LIN,
                    uploaderNick = "林岚",
                    fileCount = 3,
                    totalSize = 4_812_000L,
                    ts = PREVIEW_NOW - 1_500_000L,
                ),
                CabinetRecentUpload(
                    transferId = "t-cab-2",
                    uploaderNodeId = PEER_ZHOU,
                    uploaderNick = "老周",
                    fileCount = 1,
                    totalSize = 268_000L,
                    ts = PREVIEW_NOW - 86_400_000L,
                ),
            ),
        ),
    )
}

/**
 * 我的文件柜、**还没配置过**：只有那张「还没设置共享目录」的卡片。
 *
 * 与 [desktopPreviewCabinetState] 是一对。这一支是用户第一次进文件柜必然看到的
 * 那一屏，而它此前连一张图都没有 —— 恰恰是最该有图的一屏。
 */
public fun desktopPreviewCabinetUnsetState(): RelayUiState = desktopPreviewState()

/**
 * 正在浏览**同事**的文件柜：右栏是 [PeerCabinetBrowser]，左侧选中态落在林岚那一行。
 *
 * 这一帧钉三件事：头部画的是对端昵称而不是「我的文件柜」、权限徽标取的是
 * `currentPerm`、条目列表里目录与文件混排且大小列格式不同。
 * 给两个子目录 + 三个文件，是为了让 `GridCells.Adaptive(120.dp)` 的网格在宽档
 * 真的排出多列 —— 只给两个条目的话，列数自适应那一段等于没被截到。
 */
public fun desktopPreviewCabinetPeerState(): RelayUiState = desktopPreviewCabinetState().let { base ->
    base.copy(
        cabinet = CabinetUiState(
            activePeerId = PEER_LIN,
            currentPath = "2026 会议",
            entries = listOf(
                cabinetEntry("第一季度", isDir = true, size = 0L, minutesAgo = 2_880),
                cabinetEntry("第二季度", isDir = true, size = 0L, minutesAgo = 1_440),
                cabinetEntry("排期表.ods", isDir = false, size = 84_000L, minutesAgo = 300),
                cabinetEntry("会议纪要.md", isDir = false, size = 12_400L, minutesAgo = 180),
                cabinetEntry("现场录音.m4a", isDir = false, size = 18_600_000L, minutesAgo = 90),
            ),
            totalEntries = 5,
            currentPerm = "write",
        ),
    )
}

/**
 * 同事的文件柜**正在加载**：列表区该画转圈，而不是画成「这个目录是空的」。
 *
 * 「加载中」与「空目录」画成一样是这类页面最常见的谎 —— 有 `loading` 这个字段
 * 却没有任何一帧证明它被用上了，就等于它不存在。
 */
public fun desktopPreviewCabinetPeerLoadingState(): RelayUiState =
    desktopPreviewCabinetPeerState().let { base ->
        base.copy(
            cabinet = base.cabinet.copy(
                entries = emptyList(),
                totalEntries = 0,
                loading = true,
            ),
        )
    }

/**
 * 一个**文件夹**传输（`isFolder = true`）。
 *
 * 为什么单独立一支：`isFolder = true` 在全仓任何 fixture 里都不存在（本轮实测
 * `grep -rn 'isFolder = true'` 零命中），于是文件夹角标（`transfer_folder_badge`）
 * 与「N 个文件」计数（`transfer_folder_count`）这四处分支
 * （`ChatScreen.kt` 与 `DesktopFilesPane.kt` 各两处）**从来没有被任何一帧渲染过**。
 * 它们不是边角：拖一整个目录进来是文件柜之外唯一的批量入口。
 *
 * `fileCount = 3` 而不是 1：计数那一支要证明的是「数字 + 字节数」两段拼接，
 * 只有 `fileCount > 1` 时才会走 `transfer_folder_count` 分支而不是单文件那支。
 */
public fun folderCard(): FileCardUi = FileCardUi(
    transferId = "t-folder",
    name = "2026 会议材料",
    size = 24_600_000L,
    fileCount = 3,
    done = 0L,
    mine = false,
    isFolder = true,
    state = TransferState.Offered,
)

/**
 * 文件视图 + 一个待确认的文件夹。
 *
 * 在 [desktopPreviewFilesState] 之上加一条文件夹消息，其余不动 —— 这样文件列表里
 * 「普通文件行」与「文件夹行」同框，才能看出角标有没有把行高撑歪。
 * 预置选中项用 `transferId = "t-folder"`（见 `Desk(transferId = ...)`），
 * 详情面板里那两条 `isFolder` 分支（`DesktopFilesPane` 的 `FileDetail`）才会画出来。
 */
public fun desktopPreviewFolderState(): RelayUiState {
    val base = desktopPreviewFilesState()
    return base.copy(
        messages = base.messages + (
            PEER_ZHOU to (
                base.messages[PEER_ZHOU].orEmpty() +
                    previewMessage(
                        peerId = PEER_ZHOU,
                        text = "",
                        at = PREVIEW_NOW - 400_000L,
                        file = folderCard(),
                    )
                )
            ),
    )
}

/**
 * 预览专用的**非空检索**实现：只有 `searchGlobal` / `searchHistoricalMessages`
 * 两条返回真数据，其余方法与 [DesktopPreviewActions] 一样什么都不做。
 *
 * 为什么必须另起一个对象：全局搜索面板有四支状态
 * （`DesktopGlobalSearch.kt` 的 `GlobalSearchState.{Loading, Failed, Empty, Success}`），
 * 其中 `Success` 那一支（三段分组：联系人 / 聊天记录 / 文件）此前**一张图都没有** ——
 * 因为 [DesktopPreviewActions] 没 override `searchGlobal`，落到接口默认实现
 * （`DesktopPaneActions.searchGlobal` 的默认实现）返回 `GlobalSearchResult(query = query)`，
 * 三个 list 全空 ⇒ `isEmpty` 为真 ⇒ 面板永远走 `Empty`。
 *
 * 换句话说：用旧的 actions 拍搜索，拍出来的"空结果"不是数据决定的，是**实现决定的** ——
 * 那种帧钉不住任何东西。这里把两条检索都接上固定假数据，`Success` 那一支才有证据。
 *
 * 数据形状照真实命中来：联系人是节点、聊天记录是会话、文件是带附件名的消息。
 * `isImage` 一真一假，因为文件行按它画两种图标（原型 `HITS` 两种各一个）。
 *
 * 注意它**不** override `searchInConversation`：会话内检索走的是 `ChatScreen` 的
 * `onSearchInConversation` 回调，由截图帧自己传 lambda（见 `chat-history-search`），
 * 不经过这个接口。
 *
 * 住在这里而不是 `DesktopPaneActions.kt`：这个对象只服务 `@Preview`，而
 * `DesktopPaneActions.kt` 是生产文件、被 CJK 字面量门禁扫描（`tools/cjk-literal-gate.gradle.kts`），
 * 底下的假数据中文名会把它算成"新增硬编码文案"。`PreviewState.kt` 按文件名被门禁显式豁免
 * （`isProductionSource` 里 `name == "PreviewState.kt"`），这也正是本文件存在的中文假数据惯例。
 */
public object DesktopPreviewSearchActions : DesktopPaneActions by DesktopPreviewActions {

    override fun searchGlobal(query: String): GlobalSearchResult =
        GlobalSearchResult(
            query = query,
            contacts = listOf(
                GlobalSearchContactHit(
                    nodeId = PEER_ZHOU,
                    title = "老周",
                    subtitle = "研发 · 已连接",
                    isAgent = false,
                    online = true,
                ),
            ),
            chats = listOf(
                GlobalSearchChatHit(
                    convId = PEER_LIN,
                    title = "林岚",
                    matchCount = 3,
                    latestAt = PREVIEW_NOW - 280_000L,
                    snippet = "设计规范更新了，输入框那一段要按新的来",
                ),
            ),
            files = listOf(
                GlobalSearchFileHit(
                    messageId = "m-lin-1",
                    convId = PEER_LIN,
                    convTitle = "林岚",
                    name = "设计规范.pdf",
                    at = PREVIEW_NOW - 900_000L,
                    isImage = false,
                ),
                GlobalSearchFileHit(
                    messageId = "m-lin-2",
                    convId = PEER_LIN,
                    convTitle = "林岚",
                    name = "首页稿.png",
                    at = PREVIEW_NOW - 600_000L,
                    isImage = true,
                ),
            ),
        )

    override fun searchHistoricalMessages(query: String, limit: Long): List<MessageSearchResult> =
        listOf(
            MessageSearchResult(
                id = "m-lin-1",
                convId = PEER_LIN,
                peerId = PEER_ZHOU,
                senderId = PEER_ZHOU,
                mine = false,
                timestamp = PREVIEW_NOW - 300_000L,
                snippetText = "设计规范更新了，输入框那一段要按新的来",
            ),
        )
}
