// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.kapp.tenon.domain.ChatSlice
import com.kapp.tenon.domain.AgentHubDetailUi
import com.kapp.tenon.domain.AgentHubUi
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.agentHubDetail
import com.kapp.tenon.domain.agentPeers
import com.kapp.tenon.domain.byTeamId
import com.kapp.tenon.domain.visibleAgentSessionsOf
import com.kapp.tenon.domain.agentEventsOf
import com.kapp.tenon.domain.isPendingApproval
import com.kapp.tenon.domain.pickActiveSession
import com.kapp.tenon.domain.hubAgents
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.action_back
import com.kapp.tenon.generated.resources.agent_detail_missing_body
import com.kapp.tenon.generated.resources.agent_detail_missing_title
import com.kapp.tenon.generated.resources.agent_team_default_name
import com.kapp.tenon.generated.resources.agent_team_default_role
import com.kapp.tenon.generated.resources.agent_team_role_coder
import com.kapp.tenon.generated.resources.agent_team_role_reviewer
import com.kapp.tenon.generated.resources.agent_team_role_tester
import com.kapp.tenon.generated.resources.ic_bot
import com.kapp.tenon.generated.resources.team_detail_missing_body
import com.kapp.tenon.generated.resources.team_detail_missing_title
import com.kapp.tenon.navigation.resolveAgentSessionId
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.EmptyState
import com.kapp.tenon.ui.centerCapped
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.domain.model.AgentApprovalDecision
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 智能体页的**壳形态**（Phase B 决策稿 §2 的「桌面两栏」）。
 *
 * - [Stacked]（默认）：单列横段 —— 页头之下先卡片流、后治理段，一个滚动列装下。
 *   手机壳与放不下两栏的窄窗都用它（改前形态）。
 * - [TwoColumn]：左列卡片流 + 右列治理段。左列在三级链路的**所有层级**常驻，
 *   点卡 / 点状态行仍走既有回调链驱动右列（不新做导航）；卡片内容与 [Stacked]
 *   共用同一份（[AgentHubSection]，窄列收起次级信息走密度参数，不另抄一份）。
 *
 * [TwoColumn] 只由桌面壳在放得下的档位（Expanded/Wide）传；左列无实体
 * （`hubAgents` 投影为空）时屏内自动退化回 [Stacked] 的全宽，不画空左列。
 */
public enum class AgentHubLayout {
    Stacked,
    TwoColumn,
}

/**
 * 工作区（原 Agent）控制台，对齐 DSH 看板与会话详情台。
 *
 * 三级架构（issue 6，用户裁决）之上的 Phase B4 实体层：
 * 1. 一级：工作区看板（WorkspaceBoardView）——按设备列工作区，每行展示活跃会话与待审批；
 *    看板之上还有卡片流的**智能体详情页**（AgentDetailView，Phase B4）：点卡片流的第一
 *    落点，实体视角整页 —— 治理面从详情内的项目目录行可达，不消失；
 * 2. 二级：工作区操作中心（WorkspaceDetailView）——直通码 / 权限管理 / 最近会话入口，
 *    **不再整段列会话**（会话列表与事件流都归三级）；
 * 3. 三级：会话详情台（SessionDetailView）——沉浸式全屏视图，常驻置顶栏（返回 + 会话名 +
 *    切换会话），下方滚动事件流与审批队列。
 *
 * 返回层级：会话详情 ← 工作区详情 ← 实体详情 ← 看板。
 *
 * 壳形态见 [AgentHubLayout]：手机单列（决策稿 §2「不引入 560」，正文仍按
 * [ContentWidths.Pane] 收列）；桌面 Expanded/Wide 两栏（左列卡片流，右列随下钻换内容）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AgentConsoleScreen(
    selectedAgentId: String?,
    onSelectAgent: (String) -> Unit,
    selectedAgentSessionId: String? = null,
    onSelectAgentSession: (String) -> Unit = {},
    inDetail: Boolean = false,
    detailWsId: String? = null,
    detailSessionId: String? = null,
    /**
     * 智能体实体详情（Phase B4）正看着哪个实体：宿主 + 身份键成对非空 = 实体详情层在开。
     * null = 停在卡片流（看板级）。手机壳从导航状态传进来，桌面壳不传（走本地自驱）。
     */
    detailEntityHostId: String? = null,
    /** 实体的 agent 身份键；null = 「未上报类型」那一档（与卡片的 null 身份键同值）。 */
    detailEntityAgentKey: String? = null,
    /**
     * 团队详情页正看着哪支队伍：**群 + 领队**（团队键与 `agentTeams` 的行同口径）。
     * 第一格 null = 没停在团队详情里（与实体详情同一条「成对写入、判据只看第一格」）。
     *
     * ## 为什么不复用实体详情那两格
     *
     * 团队不是员工实体：它的键是 `(groupId, leaderAgentId)`，而实体详情要的是
     * `(hostNodeId, agentId)`。混用一格会让「点团队 → 右列出团队页」与「点员工 → 右列
     * 出员工页」变成两个来源争同一个字段，返回时不知道该退回哪一层。
     */
    detailTeamGroupId: String? = null,
    /** 团队详情页的领队席位键。与 [detailTeamGroupId] 成对（null 表示这一格没意义）。 */
    detailTeamLeaderAgentId: String? = null,
    onOpenWorkspaceDetail: ((peerId: String, wsId: String) -> Unit)? = null,
    onLeaveWorkspaceDetail: (() -> Unit)? = null,
    onOpenSessionDetail: ((peerId: String, sessionId: String) -> Unit)? = null,
    onLeaveSessionDetail: (() -> Unit)? = null,
    onBackToBoard: (() -> Unit)? = null,
    /**
     * 点卡片流 → **智能体实体详情**（Phase B4 的点卡第一落点，替代直冲工作区详情）。
     * 手机壳接 `nav.openAgentDetail`；null = 壳没有导航层，屏内自驱（桌面两栏右列切换）。
     */
    onOpenAgentDetail: ((hostNodeId: String, agentId: String?) -> Unit)? = null,
    /** 退出实体详情回看板。手机壳接 `nav.leaveAgentDetail`；null = 屏内自驱。 */
    onLeaveAgentDetail: (() -> Unit)? = null,
    /**
     * 点团队名录行 → **团队详情**（团队视角的实体页）。
     *
     * 手机壳接 `nav.openTeamDetail`；null = 壳没有导航层 ⇒ 屏内自驱（桌面两栏右列切换）。
     * 与 [onOpenAgentDetail] 分开而不是共用：团队与员工是两个实体，落到同一个回调里
     * 会让退回时先弹哪一层取决于参数形状。
     */
    onOpenTeamDetail: ((groupId: String, leaderAgentId: String) -> Unit)? = null,
    /** 退出团队详情，回名录。手机壳接 `nav.leaveTeamDetail`；null = 屏内自驱。 */
    onLeaveTeamDetail: (() -> Unit)? = null,
    /** 与 `WorkspaceBoardView` 同签名：wsId 非空 = 要收窄到那个工作区（见那里的 KDoc）。 */
    onOpenChat: (peerId: String, wsId: String?) -> Unit,
    /**
     * 团队卡的「打开协同群」：`groupId` → 那条群会话。
     *
     * null = 这个壳没有群会话入口（预览帧 / 无导航层）⇒ 不画那颗按钮，卡片其余内容照画
     * —— 与 `onBindFirstProject` 同一条纪律：能力缺失就是**不画**，不是「点了没反应」。
     */
    onOpenGroup: ((groupId: String) -> Unit)? = null,
    /**
     * 团队执行档端口（宿主本机才有）。非 null ⇒ 目录段上方出现「执行配置」入口，
     * 点开是**只读矩阵**（写入口只有群编制那一处，见 `AgentExecutionConfigDialog`）。
     * null ⇒ 整行不画（手机 / 预览）。
     */
    teamExecutionPort: com.kapp.tenon.domain.TeamExecutionPort? = null,
    /**
     * 员工工位端口（驱动名册 + 执行档）：非 null ⇒ 名录副行写「驱动（宿主名）」，
     * 例如 `Codex（<host>）`。null ⇒ 只写宿主名（手机读不到本机执行绑定）。
     *
     * 与 [teamExecutionPort] **同一实例的执行档**由组合根保证 —— 两者共用一份，
     * 就不会出现「编制里换了底座、名录还报旧驱动」。
     */
    agentWorkstationPort: com.kapp.tenon.domain.AgentWorkstationPort? = null,
    onFindNodes: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    now: Long = nowMillis(),
    showHeader: Boolean = true,
    /** 壳形态（见 [AgentHubLayout]）；桌面壳按窗口档位传 [AgentHubLayout.TwoColumn]，手机壳不动。 */
    layout: AgentHubLayout = AgentHubLayout.Stacked,
    onRespondApproval: ((approvalId: String, allow: Boolean) -> Unit)? = null,
    /**
     * 三参处置（issue「本次免审全链路不可达」）：比 [onRespondApproval] 多一个
     * `allowSession` —— 批准的同时声明「本会话内后续同类请求免审」。组合根应优先接
     * controller 的三参 `respondApproval`；两参版本保留给只认批准/拒绝的旧调用点。
     * null 时审批段退回 [onRespondApproval]（不画「本会话免审」按钮）。
     */
    onRespondChoice: ((approvalId: String, decision: AgentApprovalDecision) -> Unit)? = null,
    /**
     * 透传给看板的审批队列闸门：桌面宽屏的 inspector 已经常驻同一批审批卡时，
     * 壳把这里置 false，主屏只保留卡片流。默认 true —— 手机壳与预览帧行为不变。
     */
    showBoardApprovalQueue: Boolean = true,
    /**
     * 透传给看板的「原位审批面板」开关（UX 规范 §2.2②）：手机壳置 true，
     * 置顶审批卡点「立即审批」时原地弹出处置面板，不再跳进聊天页。
     * 桌面壳保持默认 false —— 右栏 inspector 已常驻同一批卡。
     */
    inPlaceApprovals: Boolean = false,
    /** 撤销「本会话免审」（见 SessionDetailView 的同名形参）。 */
    onRevokeSessionApproval: ((sessionId: String) -> Unit)? = null,
    /**
     * 详情页「登记为员工 / 编辑员工」：把当前实体交出去，由壳打开对话框。
     *
     * null = 这个壳没有员工档案面（手机：档案只住宿主机）⇒ 详情页不画按钮。
     * 只交实体、不交表单状态 —— 对话框的初始值（名字 / 职责）由壳从实体读，
     * 页面不持有第二份「当前编辑谁」。
     */
    onEditEmployee: ((com.kapp.tenon.domain.AgentHubDetailUi) -> Unit)? = null,
    /**
     * 档案视角里点一行 = **编辑那份档案**（与详情页的「编辑员工」同一个对话框）。
     *
     * null = 这个壳没有档案写通路（手机 / 预览）⇒ 档案行不可点。只交档案行、不交表单状态 ——
     * 与 [onEditEmployee] 同一条纪律：页面不持有第二份「当前编辑谁」。
     */
    onEditEmployeeProfile: ((com.kapp.tenon.domain.AgentEmployeeProfileUi) -> Unit)? = null,
    /**
     * **注销**一份员工档案（下编）：`(档案行)`。
     *
     * null = 这个壳没有档案写通路（手机 / 预览）⇒ 垃圾桶不画。与 [onDissolveTeam] 同一条
     * 纪律：删除比保存更需要在缺失时**不画**—— 一颗点了没反应的注销键，用户会以为
     * 这个人已经不在这台机器上了。
     */
    onForgetEmployee: ((com.kapp.tenon.domain.AgentEmployeeProfileUi) -> Unit)? = null,
    /**
     * 档案页的「新建档案」：`(选中的本机 agent)`。
     *
     * 档案**必须先挂在一个真实存在的本机 agent 上**，所以这条通路的入口是一张候选表
     * （[com.kapp.tenon.domain.employeeProfileCandidates]），不是一张空表单。选完之后
     * 弹的是与详情页同一个表单 —— 本屏只交人，不持有第二份表单状态。
     *
     * null = 这个壳没有档案写通路（手机：档案只住宿主机）⇒ 按钮与候选表都不画，
     * 档案那一档也不会因为「空」而多出来。
     */
    onNewEmployeeProfile: ((com.kapp.tenon.domain.AgentEmployeeCandidateUi) -> Unit)? = null,
    /**
     * 保存一份**团队档案**：`(teamId, 名字, 领队, 名册, 要加入的群)`，teamId null = 新建。
     *
     * null = 这个壳没有团队档案面（手机：档案只住宿主机）⇒ 团队视角里不画
     * 「新建团队 / 编辑团队」—— 与员工档案同一条纪律（空实现 + 不画按钮是同一条纪律的两半）。
     */
    onSaveTeam: ((
        teamId: String?,
        name: String,
        leaderAgentId: String,
        members: List<com.kapp.tenon.core.protocol.TeamMemberDraft>,
        groupIds: List<String>,
    ) -> Unit)? = null,
    /**
     * **解散**一份团队档案：`(teamId)`。
     *
     * null = 这个壳没有团队档案面（手机：档案只住宿主机）⇒ 团队详情页不画那颗入口。
     * 与 [onSaveTeam] 同一个 gate —— 解散也是一次档案写入，能保存的地方才能解散。
     */
    onDissolveTeam: ((teamId: String) -> Unit)? = null,
    /**
     * 「AI 建队」生成桥：`(需求一句话, 领队候选, 本地化缺省) -> 方案`。
     *
     * null = 这个壳没有建队助手（手机 / 预览）⇒ 团队视角不画「AI 建队」——
     * 与 [onSaveTeam] 同一条纪律（空实现 + 不画按钮是同一条纪律的两半）。
     * 生成只是**预览**；落盘仍走 [onSaveTeam] + 执行档端口（用户确认后）。
     */
    onGenerateTeam: (suspend (
        need: String,
        leaderKeys: Set<String>,
        defaults: com.kapp.tenon.domain.TeamScaffoldDefaults,
    ) -> com.kapp.tenon.domain.TeamScaffoldSpec?)? = null,
    onCancelTask: ((taskId: String) -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    /**
     * 算「谁能用这个 agent 干活」的逐设备勾选状态；null = 这个壳不提供配置入口
     * （手机连别人的机器时就没得配 —— 那是对方的主权）。
     */
    /**
     * 免批规则（本端增补 cap `atr1`）的读撤缝工厂：(宿主 nodeId) → source。
     * null 工厂或工厂返回 null = 这条路不提供（对端不声明 atr1 / 壳没接）
     * ⇒ 工作区详情不画免批规则区块（读不到 ≠ 没有规则）。
     * 手机壳接 `RelayToolApprovalsSource`（帧快照）。
     */
    toolApprovalsSourceOf: ((String) -> com.kapp.tenon.feature.settings.ToolApprovalsSource?)? = null,
    /** 拉一次免批表快照：(宿主 nodeId) -> Unit。与工厂成对（区块画出来时各触发一次）。 */
    onRequestToolRules: ((String) -> Unit)? = null,
    /** 撤销「没撤掉」的壳侧文案（透传给工作区详情，见 ToolApprovalsSection.revokeFailedNote）。 */
    toolApprovalsFailedNote: String? = null,
    execDevicesOf: ((String?) -> List<ExecDeviceRow>)? = null,
    /** 勾选 / 取消某台设备：(agentKey, nodeId, checked) -> Unit。 */
    onToggleExecDevice: ((String, String, Boolean) -> Unit)? = null,
    /** 「添加设备」的出口。 */
    onAddExecDevice: (() -> Unit)? = null,
    onCreateWorkspaceGroup: (() -> Unit)? = null,
    /**
     * 新手冷启动引导卡（UX 规范 §2.6）的出口：点 CTA → 壳开「精简建群弹窗」
     * （`WorkspaceGroupPromptDialog`），一次点选完成"建群 + 绑目录"。
     * null = 这个壳没有建群入口 ⇒ 整张卡不画。
     */
    onBindFirstProject: ((com.kapp.tenon.domain.model.WorkspaceUi) -> Unit)? = null,
    /** M7-T3 2b：产物校验的按需实测桥（见 SessionDetailView 同名形参）。 */
    artifactDigestOf: (suspend (fileName: String) -> String?)? = null,
    // 只给截图门禁用，见 `SessionDetailView(initialPicking = …)`。
    initialSessionPickerOpen: Boolean = false,
    onLoadWorkspaceSessions: ((peerId: String, wsId: String) -> Unit)? = null,
    /**
     * 「申请授权」（task-10）：照 `RelayController.sendAgentGrantRequest(peerId, agentKey,
     * reason, wsId, access)` 的签名，返回 null = 没发出去（没有运行时 / 对端没声明 ag1 / agr1）。
     *
     * 组合根（手机 = `App.kt`）把它接到 `controller::sendAgentGrantRequest`。默认 null =
     * 本宿主没有这条能力 ⇒ 工作区详情的远端分支不画「申请授权」按钮。
     */
    onRequestGrant: (suspend (
        peerId: String,
        agentKey: String?,
        reason: String?,
        wsId: String?,
        access: String?,
    ) -> com.kapp.tenon.core.transport.SendOutcome?)? = null,
    /** Phase 4 订阅面切片（蓝图 §4.4）：默认值由 [state] 投影，未迁移/预览路径行为不变。 */
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    chatSlice: ChatSlice,
) {
    val agents = agentPeers(agentSlice, nodeSlice, settingsSlice)
    val selected = agents.firstOrNull { it.nodeId == selectedAgentId } ?: agents.firstOrNull()

    // 执行配置矩阵的开关（只读视图；写仍走群编制）。端口为 null 时这颗开关永远不会被置位
    // —— 入口行本身也不画。
    var execConfigOpen by remember { mutableStateOf(false) }
    // 「AI 建队」对话框的开关（生成 → 预览 → 确认；确认走 onSaveTeam + 执行档端口）。
    var aiTeamOpen by remember { mutableStateOf(false) }
    // 「新建档案」的选择器（选人 → 交给壳打开同一个表单）。放在本屏是因为候选表来自
    // 本屏已经算好的卡片流：壳那边再算一次就是第二份实体装配。
    var profileCreateOpen by remember { mutableStateOf(false) }

    // 内部备选下钻状态：桌面端壳或未接入外层导航时提供自驱下钻支持
    var localDetailSessionId by remember { mutableStateOf<String?>(null) }
    var localDetailWsId by remember { mutableStateOf<String?>(null) }
    // 实体详情（Phase B4）的本地备选：桌面两栏右列切换上下文用（点卡 → 右列出详情）。
    var localDetailEntityHost by remember { mutableStateOf<String?>(null) }
    var localDetailEntityKey by remember { mutableStateOf<String?>(null) }
    var explicitlyClearedEntity by remember { mutableStateOf(false) }
    // 团队详情（团队视角）的本地备选。与实体详情**互斥**：点团队时清掉实体那一对，
    // 点员工时清掉团队这一对 —— 否则右列会同时"开着两层"，返回键先弹看不见的那层。
    var localDetailTeamGroup by remember { mutableStateOf<String?>(null) }
    var localDetailTeamLeader by remember { mutableStateOf<String?>(null) }

    val effectiveWsId = detailWsId ?: localDetailWsId
    val effectiveSessionId = detailSessionId ?: localDetailSessionId

    // 卡片流投影（Phase B1）。两栏形态下左列在三级链路的所有层级常驻，任何层级都要算；
    // 单列形态下实体未下钻时在看板级画。
    val hubCards: List<AgentHubUi>? = remember(agentSlice, nodeSlice, settingsSlice, now) {
        hubAgents(agentSlice, nodeSlice, settingsSlice, now)
    }

    // 团队视角投影（不变量 5）：与卡片流**同一份实体装配**（agentTeams 内部就用 hubAgents），
    // 两处各归一一次身份，就会让同一个人在员工卡与团队卡里变成两个人。
    // 团队详情要读它，所以它在实体详情判定之前算。
    val hubTeams: List<com.kapp.tenon.domain.AgentTeamUi> = if (hubCards != null) {
        remember(agentSlice, nodeSlice, settingsSlice, now) {
            com.kapp.tenon.domain.agentTeams(agentSlice, nodeSlice, settingsSlice, now)
        }
    } else {
        emptyList()
    }

    // 未入驻任何群的团队档案：名录按**席位**装配，所以席位撤光的队伍只有这里看得见
    // （档案还在，群信息的「添加团队」照样列着它）。只读 `settingsSlice` —— 手机端
    // 读不到档案（`teamProfiles` 只住宿主机）⇒ 恒空 ⇒ 整段不画。
    val hubOrphanTeams: List<com.kapp.tenon.domain.AgentTeamOrphanUi> = if (hubCards != null) {
        remember(settingsSlice) { com.kapp.tenon.domain.orphanTeams(settingsSlice) }
    } else {
        emptyList()
    }

    // 员工档案（登记簿视角，第三档）：只读 `settingsSlice` —— 档案只住宿主机本机，
    // 手机端恒空 ⇒ 那一档不出现，与团队档案同一条纪律。
    val hubEmployeeProfiles: List<com.kapp.tenon.domain.AgentEmployeeProfileUi> = if (hubCards != null) {
        remember(settingsSlice) { com.kapp.tenon.domain.employeeProfileLedger(settingsSlice) }
    } else {
        emptyList()
    }

    // 桌面两栏主从联动：未主动点选任何实体且未显式关闭时，默认选中第一位智能体进入其工作台
    val defaultCard = if (layout == AgentHubLayout.TwoColumn && !explicitlyClearedEntity) hubCards?.firstOrNull() else null
    val effectiveEntityHost = detailEntityHostId ?: localDetailEntityHost ?: defaultCard?.hostNodeId
    val effectiveEntityKey = detailEntityAgentKey ?: localDetailEntityKey ?: defaultCard?.agentKey
    // 实体详情层在开（成对状态，判据只看宿主格；身份键 null = 「未上报类型」那一档）。
    val entityDetailActive = effectiveEntityHost != null

    // 团队详情层：团队键是 `(groupId, leaderAgentId)`，判据同样只看第一格。
    // 它比实体详情**优先**（右列渲染时先判团队），因为桌面两栏会给出一个默认实体的
    // `effectiveEntityHost`：不优先的话，用户点团队、右列还画着第一个员工的详情。
    val effectiveTeamGroup = detailTeamGroupId ?: localDetailTeamGroup
    val effectiveTeamLeader = detailTeamLeaderAgentId ?: localDetailTeamLeader
    val teamDetailActive = effectiveTeamGroup != null

    val backEnabled = (localDetailSessionId != null || localDetailWsId != null ||
        localDetailTeamGroup != null || localDetailEntityHost != null) &&
        (onLeaveWorkspaceDetail == null && onLeaveSessionDetail == null && onLeaveAgentDetail == null &&
            onLeaveTeamDetail == null)
    BackHandler(enabled = backEnabled) {
        // 逐层退出（会话详情 ← 工作区详情 ← 团队详情 ← 实体详情 ← 看板）：落到哪层由
        // **还开着的更浅层**决定，只有整条链都空了才回看板。
        if (localDetailSessionId != null) {
            localDetailSessionId = null
            if (localDetailWsId == null && localDetailEntityHost == null && localDetailTeamGroup == null) {
                onBackToBoard?.invoke()
            }
        } else if (localDetailWsId != null) {
            localDetailWsId = null
            if (localDetailEntityHost == null && localDetailTeamGroup == null) {
                onBackToBoard?.invoke()
            }
        } else if (localDetailTeamGroup != null) {
            localDetailTeamGroup = null
            localDetailTeamLeader = null
            if (localDetailEntityHost == null) {
                onBackToBoard?.invoke()
            }
        } else if (localDetailEntityHost != null) {
            explicitlyClearedEntity = true
            localDetailEntityHost = null
            localDetailEntityKey = null
            onBackToBoard?.invoke()
        }
    }

    // 四级链路现在在哪一层：都没下钻 = 看板级。
    val atBoardLevel = !entityDetailActive && !teamDetailActive &&
        !(selected != null && (effectiveSessionId != null || effectiveWsId != null))

    // 实体详情投影（Phase B4）：与卡片流同一套装配（聚类 / 席位 / 归因都在投影里），
    // 实体不存在（撤销、或导航层残留了一个已消失的键）= null，正文退成整屏空态。
    val entityDetail: AgentHubDetailUi? = if (entityDetailActive) {
        val entityHost = effectiveEntityHost.orEmpty()
        val entityKey = effectiveEntityKey.orEmpty()
        remember(entityHost, entityKey, agentSlice, nodeSlice, settingsSlice, now) {
            agentHubDetail(agentSlice, nodeSlice, settingsSlice, entityHost, entityKey, now)
        }
    } else {
        null
    }

    // 团队详情投影：从已算好的名录行里定位这一支队伍并合并它的全部群 —— 不再读一遍切片
    // （名录行上写的名字/人数/待批与详情页展开的必须是同一份）。键定位不到 = 席位已撤回，
    // 正文退成整屏空态，不落回"最接近的"团队。
    val teamDetail: com.kapp.tenon.domain.AgentTeamDetailUi? = if (teamDetailActive) {
        val teamGroup = effectiveTeamGroup.orEmpty()
        val teamLeader = effectiveTeamLeader.orEmpty()
        remember(hubTeams, teamGroup, teamLeader) {
            com.kapp.tenon.domain.agentTeamDetail(hubTeams, teamGroup, teamLeader)
        }
    } else {
        null
    }

    // 团队档案对话框的目标（null = 关着）。桌面宿主独占：档案只住本机设置，
    // 手机端 `onSaveTeam` 为 null ⇒ 团队视角里根本没有那两个入口。
    var teamEditing by remember { mutableStateOf<TeamEditTarget?>(null) }

    // 两栏是否成立：形态开关 + 左列真有实体。左列无实体 ⇒ 退化回单列全宽（不画空左列）。
    val hubAside = layout == AgentHubLayout.TwoColumn && !hubCards.isNullOrEmpty()

    // 打开团队档案编辑器：名录行的「编辑团队」与详情页的同一颗按钮共用**这一份**构造
    // （档案在就用档案 —— 名册是唯一真相；档案丢了退回卡上快照，退回不等于静默：
    // 保存时会按用户眼前所见重建档案）。两处各写一遍必然漂。
    val openTeamEditor: (com.kapp.tenon.domain.AgentTeamUi) -> Unit = { team ->
        val profile = settingsSlice.settings.teamProfiles.byTeamId(team.teamId)
        teamEditing = TeamEditTarget(
            profileKey = team.teamId,
            name = profile?.name ?: team.teamName ?: team.groupName,
            leaderAgentId = profile?.leaderAgentId ?: team.leaderAgentId,
            members = profile?.rosterDrafts()
                ?: team.members.filterNot { it.isLeader }.map {
                    com.kapp.tenon.core.protocol.TeamMemberDraft(
                        agentId = it.agentId,
                        name = it.name,
                        role = it.role,
                    )
                },
        )
    }

    // 卡片流：**一份内容、一份接线**（AGENTS 组件共享纪律），全宽正文顶端（单列）与
    // 两栏左列只差密度参数（实参 = compact：true = 两栏左列窄形态，false = 全宽横段）。
    // 点卡 → 智能体实体详情（Phase B4 的第一落点，决策稿 §4 的卡片动作修订）：治理段
    // 不再从点卡直进，而是从详情内的项目目录行可达。身份键 null（「未上报类型」）原样
    // 传给回调 —— 导航状态与投影都按 null 如实记这一档。
    // [对话] 与状态行的直达保持现状（1:1 与会话详情不经过详情页）。
    // 两栏左列在详情层级也亮着，所以点卡先离开更深的会话层：单列下卡片只在看板级可见
    // （那里本地会话层本来就为空，这一步是空操作）；手机壳走外部导航，本地状态同样为空。
    val hubFlow: @Composable (Boolean) -> Unit = { compact ->
        AgentDirectorySection(
            employees = hubCards.orEmpty(),
            teams = hubTeams,
            now = now,
            compact = compact,
            selectedHostNodeId = effectiveEntityHost,
            selectedAgentKey = effectiveEntityKey,
            // 团队行的高亮与员工行分开一格（团队与领队是两个实体，宿主相同不等于同一行）。
            selectedTeamGroupId = effectiveTeamGroup,
            selectedTeamLeaderAgentId = effectiveTeamLeader,
            workstationPort = agentWorkstationPort,
            orphanTeams = hubOrphanTeams,
            // 解散的写通路与详情页那把同一个（`onDissolveTeam` 来自桌面壳的档案面）；
            // 手机壳不接 ⇒ null ⇒ 孤儿段整段不画。
            onDissolveOrphanTeam = onDissolveTeam,
            // 员工**档案**（登记簿视角）：与团队档案同一个 gate —— 手机读不到 `employeeProfiles`
            // （只住宿主机）⇒ 恒空 ⇒ 那一档不出现；写通路（编辑 / 注销）同样由桌面壳独占。
            employeeProfiles = hubEmployeeProfiles,
            onEditEmployeeProfile = onEditEmployeeProfile,
            onForgetEmployeeProfile = onForgetEmployee,
            // 「新建档案」的落点是**选择器**（本屏持有），不是壳：候选表由卡片流与档案表
            // 一起算（本屏已有 `hubCards`），壳只接「选完了、给这个人建档」这一步。
            onNewEmployeeProfile = onNewEmployeeProfile?.let { { profileCreateOpen = true } },
            onOpenEntity = { card ->
                explicitlyClearedEntity = false
                localDetailSessionId = null
                localDetailWsId = null
                // 点员工 = 离开团队详情（两层互斥，见 localDetailTeamGroup 的注释）。
                localDetailTeamGroup = null
                localDetailTeamLeader = null
                onSelectAgent(card.hostNodeId)
                if (onOpenAgentDetail != null) {
                    onOpenAgentDetail(card.hostNodeId, card.agentKey)
                } else {
                    localDetailEntityHost = card.hostNodeId
                    localDetailEntityKey = card.agentKey
                }
            },
            // [对话] → 既有 1:1 入口（wsId 收窄语义与看板行一致）。
            onOpenChat = { card ->
                onSelectAgent(card.hostNodeId)
                onOpenChat(card.hostNodeId, card.primaryWsId)
            },
            // 点状态行 → 那条会话详情（既有三级入口），挑法与看板行同源。左列常驻后，从
            // 工作区详情层级点行要先离开那一层：effectiveWsId 会把会话列表收窄到**旧的**
            // 工作区，目标会话解析不到。单列下同样因为卡片不可见而是空操作。
            onOpenRowSession = { hostNodeId, sessionId ->
                localDetailWsId = null
                // 进会话 = 离开团队详情（左列在详情层级也亮着，点行不该把团队页留在右列）。
                localDetailTeamGroup = null
                localDetailTeamLeader = null
                onSelectAgent(hostNodeId)
                if (onOpenSessionDetail != null) {
                    onOpenSessionDetail(hostNodeId, sessionId)
                } else {
                    localDetailSessionId = sessionId
                }
                onSelectAgentSession(sessionId)
            },
            // 团队名录点行 → **团队详情**（团队视角的实体页）。
            //
            // 此前这一步落到**领队**那张员工详情：标题是人名、段落是「这个人的任职」，
            // 用户点的却是「这支队」—— 团队自己的名字、名册、在哪些群里全都没有落点。
            // 团队不是员工实体，它有自己的键 `(groupId, leaderAgentId)`，就该有自己的页面。
            onOpenTeam = { team ->
                localDetailSessionId = null
                localDetailWsId = null
                // 团队与实体两层互斥：不点员工时右列不该还留着上一个人的详情。
                explicitlyClearedEntity = true
                localDetailEntityHost = null
                localDetailEntityKey = null
                onSelectAgent(team.hostNodeId)
                if (onOpenTeamDetail != null) {
                    onOpenTeamDetail(team.groupId, team.leaderAgentId)
                } else {
                    localDetailTeamGroup = team.groupId
                    localDetailTeamLeader = team.leaderAgentId
                }
            },
            onOpenGroup = onOpenGroup,
            onOpenExecutionConfig = teamExecutionPort?.let { { execConfigOpen = true } },
            // 建 / 编团队：只有壳给了保存通路才画（手机 null ⇒ 两个入口都不出现）。
            onCreateTeam = onSaveTeam?.let {
                {
                    teamEditing = TeamEditTarget(
                        profileKey = null,
                        name = "",
                        leaderAgentId = null,
                        members = emptyList(),
                    )
                }
            },
            onCreateTeamWithAi = onGenerateTeam?.takeIf { onSaveTeam != null }?.let {
                { aiTeamOpen = true }
            },
            onEditTeam = if (onSaveTeam != null) openTeamEditor else null,
        )
    }

    // 执行配置矩阵（规范 §4.0）：端口在位时才有得画 —— 它是宿主本地事实。
    val execPort = teamExecutionPort
    if (execConfigOpen && execPort != null) {
        AgentExecutionConfigDialog(
            groups = settingsSlice.settings.groups,
            selfNodeId = settingsSlice.settings.nodeId,
            port = execPort,
            onDismiss = { execConfigOpen = false },
        )
    }

    // 「新建档案」的候选表（本机、还没有档案、有身份键）：过滤规则住领域层
    // （`employeeProfileCandidates`）—— 写路径的台账检查与本表必须是同一批人。
    val newProfile = onNewEmployeeProfile
    if (profileCreateOpen && newProfile != null) {
        val candidates = remember(hubCards, settingsSlice) {
            com.kapp.tenon.domain.employeeProfileCandidates(
                cards = hubCards.orEmpty(),
                selfNodeId = settingsSlice.settings.nodeId,
            )
        }
        EmployeeProfileCreateDialog(
            candidates = candidates,
            onPick = { candidate ->
                profileCreateOpen = false
                newProfile(candidate)
            },
            onDismiss = { profileCreateOpen = false },
        )
    }

    // 团队档案对话框（新建 / 改名 / 换名册 / 进更多群）。
    // 领队候选只取**本机**卡片：别人的 agent 当不了本机团队的领队（执行点在领队宿主）。
    val editingTeam = teamEditing
    if (editingTeam != null && onSaveTeam != null) {
        val self = settingsSlice.settings.nodeId
        val leaderOptions = hubCards.orEmpty()
            .filter { it.hostNodeId == self }
            .mapNotNull { card ->
                card.agentKey?.let { key ->
                    TeamLeaderOption(agentKey = key, label = card.displayName ?: card.employeeName ?: key)
                }
            }
        val groupOptions = settingsSlice.settings.groups.map { group ->
            TeamGroupOption(
                groupId = group.groupId,
                name = group.name,
                joined = editingTeam.profileKey != null &&
                    group.virtualAgents.orEmpty().any { it.teamId == editingTeam.profileKey },
            )
        }
        TeamProfileDialog(
            profileKey = editingTeam.profileKey,
            initialName = editingTeam.name,
            leaders = leaderOptions,
            initialLeaderAgentId = editingTeam.leaderAgentId,
            initialMembers = editingTeam.members,
            groups = groupOptions,
            onSave = { name, leader, members, groupIds ->
                onSaveTeam(editingTeam.profileKey, name, leader, members, groupIds)
                teamEditing = null
            },
            onDismiss = { teamEditing = null },
        )
    }

    // 「AI 建队」助手（生成 → 预览 → 确认）：候选与手动建队同源（本机卡片当领队、
    // 群多选）；确认 = onSaveTeam 建档案 + 执行档端口逐群落执行配置 —— 助手不发明
    // 第二条写路径，只是替用户把表单填好。
    val generateTeam = onGenerateTeam
    if (aiTeamOpen && generateTeam != null && onSaveTeam != null) {
        val self = settingsSlice.settings.nodeId
        val leaderOptions = hubCards.orEmpty()
            .filter { it.hostNodeId == self }
            .mapNotNull { card ->
                card.agentKey?.let { key ->
                    TeamLeaderOption(agentKey = key, label = card.displayName ?: card.employeeName ?: key)
                }
            }
        val groupOptions = settingsSlice.settings.groups.map { group ->
            TeamGroupOption(groupId = group.groupId, name = group.name, joined = false)
        }
        val aiDefaults = com.kapp.tenon.domain.TeamScaffoldDefaults(
            teamTitle = stringResource(Res.string.agent_team_default_name),
            role = stringResource(Res.string.agent_team_default_role),
            roles = listOf(
                stringResource(Res.string.agent_team_role_coder),
                stringResource(Res.string.agent_team_role_tester),
                stringResource(Res.string.agent_team_role_reviewer),
            ),
        )
        val leaderKeys = leaderOptions.map { it.agentKey }.toSet()
        TeamBuilderDialog(
            leaders = leaderOptions,
            groups = groupOptions,
            onGenerate = { need -> generateTeam(need, leaderKeys, aiDefaults) },
            onConfirm = { spec, groupIds ->
                val reasons = mutableListOf<String>()
                onSaveTeam(null, spec.teamTitle, spec.leaderAgentId, spec.toMemberDrafts(), groupIds)
                val port = teamExecutionPort
                if (port != null) {
                    val declared = spec.members.map { it.agentId }.toSet()
                    // 执行档属于**团队**，不属于群：键是 `(目录, 领队)` ⇒ 几个群指向同一目录
                    // 就是同一份档，按群写只是把同一份覆盖 N 次。
                    val dirIds = groupIds.mapNotNull { port.groupPointerDirId(it) }.distinct()
                    for (dirId in dirIds) {
                        when (
                            val saved = port.save(
                                com.kapp.tenon.domain.TeamExecutionProfileUi(
                                    dirId = dirId,
                                    leaderAgentId = spec.leaderAgentId,
                                    members = spec.toExecutionMembers(),
                                ),
                                declared,
                            )
                        ) {
                            is com.kapp.tenon.domain.TeamExecutionSaveResult.Rejected -> reasons += saved.reasons
                            else -> Unit
                        }
                    }
                }
                reasons
            },
            onDismiss = { aiTeamOpen = false },
        )
    }

    // 主工作台：四级链路（会话详情 ← 工作区详情 ← 实体详情 ← 看板）的**一份**接线。
    // 两栏右列与全宽回落各传自己的落位修饰符；卡片流画在哪由 [hubAside] 决定 ——
    // 两栏时正文不画（在左列），全宽时画在正文顶端（单列改前形态）。
    val workbench: @Composable (Modifier) -> Unit = { area ->
        if (selected != null && effectiveSessionId != null) {
            val allSessions = visibleAgentSessionsOf(agentSlice, selected.nodeId)
            val sessions = if (effectiveWsId != null) {
                allSessions.filter { it.wsId == effectiveWsId }
            } else {
                allSessions
            }
            val currentSessionId = resolveAgentSessionId(
                selectedAgentSessionId ?: effectiveSessionId,
                sessions.map { it.sessionId },
            )
            val activeSession = sessions.firstOrNull { it.sessionId == currentSessionId } ?: sessions.firstOrNull()

            SessionDetailView(
                peer = selected,
                sessions = sessions,
                activeSession = activeSession,
                agentSlice = agentSlice,
                chatSlice = chatSlice,
                nodeSlice = nodeSlice,
                settingsSlice = settingsSlice,
                now = now,
                onBack = {
                    if (onLeaveSessionDetail != null) {
                        onLeaveSessionDetail()
                    } else {
                        localDetailSessionId = null
                        // 落回还开着的更浅层（工作区/实体详情）；整条链都空才回看板。
                        if (localDetailWsId == null && !entityDetailActive) {
                            onBackToBoard?.invoke()
                        }
                    }
                },
                onSelectSession = { sessionId ->
                    if (onOpenSessionDetail != null) {
                        onOpenSessionDetail(selected.nodeId, sessionId)
                    } else {
                        localDetailSessionId = sessionId
                    }
                    onSelectAgentSession(sessionId)
                },
                onOpenChat = { sessionPeerId ->
                    activeSession?.let { onSelectAgentSession(it.sessionId) }
                    onOpenChat(sessionPeerId, effectiveWsId)
                },
                onRespondApproval = onRespondApproval,
                onRespondChoice = onRespondChoice,
                onRevokeSessionApproval = onRevokeSessionApproval,
                onCancelTask = onCancelTask,
                modifier = area,
                initialPicking = initialSessionPickerOpen,
                artifactDigestOf = artifactDigestOf,
            )
        } else if (selected != null && effectiveWsId != null) {
            val allSessions = visibleAgentSessionsOf(agentSlice, selected.nodeId)
            val sessions = allSessions.filter { it.wsId == effectiveWsId }
            val workspace = agentSlice.agentWorkspaces.values.firstOrNull { it.wsId == effectiveWsId }
            // 这个工作区属于哪个 agent —— 执行权限的键就是它（与 store 一致）。
            val agentKeyForView: String? = workspace?.agentKey
                ?: sessions.firstOrNull()?.agentId
                ?: sessions.firstOrNull()?.agentKind

            WorkspaceDetailView(
                wsId = effectiveWsId,
                workspace = workspace,
                peer = selected,
                sessions = sessions,
                agentSlice = agentSlice,
                nodeSlice = nodeSlice,
                settingsSlice = settingsSlice,
                now = now,
                onBack = {
                    if (onLeaveWorkspaceDetail != null) {
                        onLeaveWorkspaceDetail()
                    } else {
                        localDetailWsId = null
                        // 从实体详情进来的：退回实体详情（层级：会话 ← 工作区 ← 实体 ← 看板）。
                        if (!entityDetailActive) {
                            onBackToBoard?.invoke()
                        }
                    }
                },
                onOpenSession = { sessionId ->
                    if (onOpenSessionDetail != null) {
                        onOpenSessionDetail(selected.nodeId, sessionId)
                    } else {
                        localDetailSessionId = sessionId
                    }
                    onSelectAgentSession(sessionId)
                },
                onOpenChat = {
                    // 进聊天前把"要停在哪条会话"记下来。挑法与工作区卡片、会话详情**同源**
                    // （pickActiveSession 是唯一一处规则）：此前这里是第三份手抄的
                    // 「pending → busy → first」，没有 stale-busy 判定、first 也依赖调用方列表顺序，
                    // 于是"从卡片点进来"与"卡片上显示的那条"可以是两条不同的会话。
                    pickActiveSession(
                        sessions = sessions,
                        lastEventAtOf = { s -> agentEventsOf(agentSlice, s).maxOfOrNull { it.at } ?: 0L },
                        // 与看板/角标同一判据（含过期）：否则"打开聊天时停在哪条会话"会优先停在
                        // 一条其实已经没事可做的会话上（它的审批早过期了）。
                        pendingSessionIds = agentSlice.agentApprovals.values
                            .filter { it.peerId == selected.nodeId && isPendingApproval(it, now) }
                            .map { it.sessionId }
                            .toSet(),
                        now = now,
                    )?.let { onSelectAgentSession(it.sessionId) }
                    onOpenChat(selected.nodeId, effectiveWsId)
                },
                onOpenSessionChat = { sessionId ->
                    onSelectAgentSession(sessionId)
                    onOpenChat(selected.nodeId, effectiveWsId)
                },
                onRespondApproval = onRespondApproval,
                onRespondChoice = onRespondChoice,
                onRefresh = onRefresh,
                // 谁能用这个 agent 干活：逐设备勾选。
                // 数据由调用方算好（`execDeviceRows`），这里只透传 —— 派生逻辑不该散在壳里。
                execDevices = execDevicesOf?.invoke(agentKeyForView),
                onToggleExecDevice = onToggleExecDevice?.let { cb ->
                    { nodeId, checked -> cb(agentKeyForView.orEmpty(), nodeId, checked) }
                },
                onAddExecDevice = onAddExecDevice,
                // 免批规则的读撤：source 工厂按宿主取，撤/读的 peerId 在这里绑死
                // （selected.nodeId = 这个工作区所属的宿主）—— 屏内只留"画与点"。
                toolApprovalsSource = toolApprovalsSourceOf?.invoke(selected.nodeId),
                onRequestToolRules = onRequestToolRules?.let { cb -> { cb(selected.nodeId) } },
                toolApprovalsFailedNote = toolApprovalsFailedNote,
                // 把 (peerId, agentKey, wsId) 在这里绑死，屏内只留"点一下"这件事。
                // agentKey 取工作区宣告的身份键，拿不到再退到该工作区第一条会话的身份 ——
                // 与 `grantFor` 的键口径一致（`agentId ?? agentKind`）。
                onRequestGrant = onRequestGrant?.let { send ->
                    suspend {
                        send(
                            selected.nodeId,
                            workspace?.agentKey
                                ?: sessions.firstOrNull()?.agentId
                                ?: sessions.firstOrNull()?.agentKind,
                            null,
                            effectiveWsId,
                            null,
                        )
                    }
                },
                modifier = area,
            )
        } else if (teamDetailActive) {
            // 团队详情（团队视角的实体页）：与员工详情同形同骨架，内容由
            // `agentTeamDetail` 从名录行并出来 —— 一屏的规则只留一处。
            val detail = teamDetail
            if (detail != null) {
                AgentTeamDetailView(
                    detail = detail,
                    onBack = {
                        if (onLeaveTeamDetail != null) {
                            onLeaveTeamDetail()
                        } else {
                            localDetailTeamGroup = null
                            localDetailTeamLeader = null
                            onBackToBoard?.invoke()
                        }
                    },
                    onOpenGroup = onOpenGroup,
                    onOpenWorkspaceDetail = { wsId ->
                        // 与员工详情的目录行同一条接线：外层导航优先，没有导航层的壳走本地状态。
                        if (onOpenWorkspaceDetail != null) {
                            onOpenWorkspaceDetail(detail.hostNodeId, wsId)
                        } else {
                            localDetailWsId = wsId
                        }
                        onLoadWorkspaceSessions?.invoke(detail.hostNodeId, wsId)
                    },
                    // 编辑团队：与名录行那把开同一支对话框（构造逻辑只有 openTeamEditor 一份）。
                    onEditTeam = if (onSaveTeam != null) {
                        {
                            hubTeams.firstOrNull {
                                it.groupId == detail.groupId && it.leaderAgentId == detail.leaderAgentId
                            }?.let(openTeamEditor)
                        }
                    } else {
                        null
                    },
                    // 解散：同样只有带档案（`teamId != null`）且壳有档案面时才画 —— 没有档案
                    // 就无从解散，那颗按钮点下去只能是空操作。teamId 先落成局部量再闭包捕获：
                    // 跨模块的 val 属性不做智能转换。
                    onDissolveTeam = detail.teamId?.let { teamId ->
                        onDissolveTeam?.let { dissolve ->
                            {
                                dissolve(teamId)
                                // 解散之后这支队就没有详情可言了：留在原页会看到
                                // 「这个团队已经不在了」—— 那是**席位被撤回**的措辞，
                                // 不是「你刚把它解散了」。退回名录，让横幅去说结果。
                                if (onLeaveTeamDetail != null) {
                                    onLeaveTeamDetail()
                                } else {
                                    localDetailTeamGroup = null
                                    localDetailTeamLeader = null
                                    onBackToBoard?.invoke()
                                }
                            }
                        }
                    },
                    modifier = area,
                )
            } else {
                // 席位已撤回（投影 null）：整屏空态 + 出路 —— 不悄悄落回名录，
                // 导航层还停在团队详情上，悄悄换内容会让返回键「按一下没反应」。
                EmptyState(
                    title = stringResource(Res.string.team_detail_missing_title),
                    message = stringResource(Res.string.team_detail_missing_body),
                    actionLabel = stringResource(Res.string.action_back),
                    onAction = {
                        if (onLeaveTeamDetail != null) {
                            onLeaveTeamDetail()
                        } else {
                            localDetailTeamGroup = null
                            localDetailTeamLeader = null
                            onBackToBoard?.invoke()
                        }
                    },
                    icon = painterResource(Res.drawable.ic_bot),
                    modifier = area,
                )
            }
        } else if (entityDetailActive) {
            // 智能体实体详情（Phase B4）：点卡片的第一落点，内容与手机共用同一份
            // AgentDetailView（AGENTS §2.6 —— 外壳在这里，内容不抄第二遍）。
            val detail = entityDetail
            if (detail != null) {
                AgentDetailView(
                    detail = detail,
                    now = now,
                    onBack = {
                        if (onLeaveAgentDetail != null) {
                            onLeaveAgentDetail()
                        } else {
                            explicitlyClearedEntity = true
                            localDetailEntityHost = null
                            localDetailEntityKey = null
                            onBackToBoard?.invoke()
                        }
                    },
                    onOpenWorkspaceDetail = { wsId ->
                        // 详情内的项目目录行 → 既有工作区详情：接线与看板行同一条
                        // （外层导航优先，没有导航层的壳走本地状态），会话分页顺带触发。
                        if (onOpenWorkspaceDetail != null) {
                            onOpenWorkspaceDetail(effectiveEntityHost.orEmpty(), wsId)
                        } else {
                            localDetailWsId = wsId
                        }
                        onLoadWorkspaceSessions?.invoke(effectiveEntityHost.orEmpty(), wsId)
                    },
                    onOpenSessionDetail = { sessionId ->
                        if (onOpenSessionDetail != null) {
                            onOpenSessionDetail(effectiveEntityHost.orEmpty(), sessionId)
                        } else {
                            localDetailSessionId = sessionId
                        }
                        onSelectAgentSession(sessionId)
                    },
                    onRespondApproval = onRespondApproval,
                    onRespondChoice = onRespondChoice,
                    agentSlice = agentSlice,
                    nodeSlice = nodeSlice,
                    settingsSlice = settingsSlice,
                    onEditEmployee = onEditEmployee?.let { emit -> { emit(detail) } },
                    modifier = area,
                )
            } else {
                // 实体已不在编制里（投影 null）：整屏空态 + 出路。不悄悄落回看板 ——
                // 导航层还停在实体详情上，悄悄换内容会让返回键「按一下没反应」。
                EmptyState(
                    title = stringResource(Res.string.agent_detail_missing_title),
                    message = stringResource(Res.string.agent_detail_missing_body),
                    actionLabel = stringResource(Res.string.action_back),
                    onAction = {
                        if (onLeaveAgentDetail != null) {
                            onLeaveAgentDetail()
                        } else {
                            explicitlyClearedEntity = true
                            localDetailEntityHost = null
                            localDetailEntityKey = null
                            onBackToBoard?.invoke()
                        }
                    },
                    icon = painterResource(Res.drawable.ic_bot),
                    modifier = area,
                )
            }
        } else {
            // 看板级正文：滚动列，按 [ContentWidths.Pane] 收列居中（决策稿 §2 的手机口径，
            // 两栏右列沿用同一把尺）。页头画在外层，由 showHeader 决定这一壳要不要它：
            // 手机壳画（五个首页 tab 共用一条顶栏），桌面壳不画（窗口标题栏 + 工具栏已是页头）。
            Column(
                modifier = area
                    .fillMaxSize()
                    .centerCapped(ContentWidths.Pane)
                    .verticalScroll(rememberScrollState())
                    // 顶距 14：原型 `.pad{padding:14px 16px 16px}`（relay.html:780）——
                    // 此前纵向为 0，首块（警告条）直接顶在 TitleBarDivider 上，
                    // 真机上分隔线与横幅之间的缝只剩 ~3dp。
                    .padding(
                        start = LocalAppSpacing.current.lg,
                        top = LocalAppSpacing.current.lg14,
                        end = LocalAppSpacing.current.lg,
                    ),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
            ) {
                if (!hubAside) {
                    // 智能体卡片流（Phase B）：每个 (宿主, agent) 实体一张卡，置顶在治理段之上
                    // （决策稿 §2「两段一保留」——本页头/看板骨架原样，卡片流是页头之下的一段）。
                    // 数据全部来自 `hubAgents` 投影（合并 / 排序 / 归因都在那里），这里只画。
                    hubFlow(false)
                }
                WorkspaceBoardView(
                    agentSlice = agentSlice,
                    nodeSlice = nodeSlice,
                    settingsSlice = settingsSlice,
                    agents = agents,
                    selectedAgent = selected,
                    now = now,
                    onSelectAgent = onSelectAgent,
                    onOpenWorkspace = { peerId, wsId, sessionId ->
                        onSelectAgent(peerId)
                        if (wsId != null && onOpenWorkspaceDetail != null) {
                            onOpenWorkspaceDetail(peerId, wsId)
                        } else {
                            localDetailWsId = wsId
                        }
                        if (sessionId != null) {
                            if (onOpenSessionDetail != null) {
                                onOpenSessionDetail(peerId, sessionId)
                            } else {
                                localDetailSessionId = sessionId
                            }
                            onSelectAgentSession(sessionId)
                        }
                        if (wsId != null) {
                            onLoadWorkspaceSessions?.invoke(peerId, wsId)
                        }
                    },
                    onOpenChat = onOpenChat,
                    onFindNodes = onFindNodes,
                    onRefresh = onRefresh,
                    onRespondApproval = onRespondApproval,
                    onRespondChoice = onRespondChoice,
                    showApprovalQueue = showBoardApprovalQueue,
                    inPlaceApprovals = inPlaceApprovals,
                    onBindFirstProject = onBindFirstProject,
                )

                // 底部留白：原先是空 `Column(padding(bottom)) {}` —— 空组合件当间距用，
                // 读代码的人会以为那里少了内容。Spacer 表达的就是间距本身。
                Spacer(Modifier.height(LocalAppSpacing.current.xl))
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // 页头在滚动列**之外**：整幅宽的 `surface` 面 + hairline，与另外四个页签
        // 同一个 [WorkspaceBoardTitleBar] / [LargeTitleBar]。此前标题画在这个带横向
        // padding 的滚动列里，铺的是页面底色 `background`，于是这一页顶部和别的页签
        // 差一整条色带（深色 #232625→#121413），切 tab 时肉眼就是「跳色」。
        //
        // 这条页头只属于手机壳：桌面壳传 showHeader=false，因为它已经有窗口标题栏
        // + 工具栏；两栏形态再叠一条按 Pane 收列居中的大标题，会让它飘在左右两栏
        // 之间、刷新圆钮孤悬右侧，属于双层页头。桌面各 tab 的内容区一律不画页面级
        // 标题，刷新走壳的溢出菜单与快捷键。
        // 单列形态只在看板级画（下钻详情有自己的置顶栏，改前形态）；两栏形态下
        // 左列在页头之下常驻，下钻时只有右列换内容。
        if (showHeader && (hubAside || atBoardLevel)) {
            WorkspaceBoardTitleBar(
                now = now,
                onRefresh = onRefresh,
                onCreateWorkspaceGroup = if (hubAside) null else onCreateWorkspaceGroup,
                agentSlice = agentSlice,
                nodeSlice = nodeSlice,
                settingsSlice = settingsSlice,
            )
        }
        if (hubAside) {
            // 桌面两栏（决策稿 §2）：左列卡片流 + 右列治理段。左列宽度用既有档位
            // [ContentWidths.Sidebar]（与桌面侧栏 / 文件柜左栏同值），不发明新宽度；
            // 窄列收起次级信息走密度参数，内容仍是 [AgentHubSection] 这一份。
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .width(ContentWidths.Sidebar)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = LocalAppSpacing.current.lg,
                            top = LocalAppSpacing.current.lg14,
                            end = LocalAppSpacing.current.lg,
                        ),
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                ) {
                    // 左列窄形态（实参 = compact）。
                    hubFlow(true)
                    // 与全宽正文同一个底部留白（workbench 看板级同款 Spacer）。
                    Spacer(Modifier.height(LocalAppSpacing.current.xl))
                }
                VerticalDivider()
                // 右列主工作台：既有三级链路原样 —— 点左列卡 / 状态行经既有回调链驱动这里。
                workbench(Modifier.weight(1f))
            }
        } else {
            workbench(Modifier)
        }
    }
}
