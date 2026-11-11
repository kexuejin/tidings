// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentSessionStates
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.isTeam
import com.kapp.tenon.core.protocol.sameAgent
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.AgentArtifactUi
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.domain.model.visibleInDsh

/**
 * 智能体中心卡片流（Phase B1）的一张实体卡。
 *
 * ## 实体键 = `(hostNodeId, agentId)`
 *
 * 与群席位同一口径（`agentId` 走 `sameAgent` 归一、大小写不敏感）：一台设备上
 * `dsh-029bbbf3`（会话帧自报）与 `DSH`（席位入驻时兜底到 kind）是**同一个** agent，
 * 一张卡。与 `sameAgent` 自己 KDoc 里写明的那样，前缀到 `-` 之前命中即并 ——
 * 同 head 的实例在这张卡上会被并起来；这与群席位「`DSH` 入驻」的现实精度一致，
 * 不是本投影的发明。
 *
 * ## 一张卡聚合三路事实（Phase B 决策稿 §3）
 *
 * | 路 | 来源 | 取什么 |
 * |---|---|---|
 * | 工作区行 | `agentWorkspaces` 宣告 + 会话自报的 `wsId` | 项目目录数、最近活动 |
 * | 群席位 | `GroupMeta.virtualAgents[]`（宿主相同且 `sameAgent`） | 服务群数、单兵/团队角色 |
 * | 会话/事件 | `agentSessions` / `agentEvents` 按 peerId | 执行态、最新事件文案 |
 *
 * **不做的合并**（决策稿 §3 写死）：宿主不同的同 kind agent 不并卡；群组员
 * （`TeamMemberInfo`）不生成独立卡 —— 对外单一窗口，组员数挂在 [isTeam] / [teamSize] 上。
 */
public data class AgentHubUi(
    /** 这张卡指向哪台宿主设备（下钻 / 1:1 入口的第一个键）。 */
    val hostNodeId: String,
    /**
     * 宿主在节点表里的显示名（备注 > 昵称 > …，`PeerUi.displayName` 的口径）。
     *
     * null = 节点表里还不认识这台宿主（只有席位声明在）：卡上就不画设备名，
     * **不拿 [hostNodeId] 顶替** —— 身份键不是名字（与 `AgentSessionUi.displayTitle`
     * 不退回 sessionId 的同一条纪律）。
     */
    val hostName: String?,
    /**
     * 归一后的 agent 身份键（`agentId ?? agentKind` 的聚类代表，优先取自 `agentId` 来源）。
     * null = 这个实体没有任何身份上报（「未上报类型」），**不是空串** —— 空串会被当成
     * 一个真实取值，null 才如实表示"没收到"（与 [com.kapp.tenon.domain.model.WorkspaceUnitUi.agentKind] 同纪律）。
     */
    val agentKey: String?,
    /**
     * 显示名：席位名（用户在群里 @ 的那个 token）优先，其次身份键。null = 连身份键都没有，
     * 由 UI 翻成本地化的「未上报类型」（模型层不出文案）。
     */
    val displayName: String?,
    /** 是否团队形态（任一匹配席位 `isTeam`；事实口径，声明缺失时 `teamMembers` 非空也算）。 */
    val isTeam: Boolean,
    /** 团队人数 = 领队 1 + 组员数；单兵恒为 0（标签由 [isTeam] 决定，这个数只在团队态有义）。 */
    val teamSize: Int,
    /** 服务群数：席位（宿主 + 身份都匹配）落在几个群里（去重）。 */
    val groupCount: Int,
    /** 项目目录数：该实体名下去重后的工作区数（宣告 ∪ 会话自报，合成 wsId 不计）。 */
    val workspaceCount: Int,
    /** 宿主在线态（席位在、宿主没发现过 = 离线，如实）。 */
    val online: Boolean,
    /**
     * 待审批总数（`isPendingApproval` 唯一判据；归因得到本实体的那些）。
     *
     * 与 [rows] 里的 PENDING 行同数 —— 卡片角标读这里，状态行读行，一个事实一个来源。
     */
    val pendingCount: Int,
    /** Urgent-First 状态行（决策稿 §4：审批 > 执行中 > 最近）。见 [AgentHubStatusRow]。 */
    val rows: List<AgentHubStatusRow>,
    /** 点卡下钻的主项目目录（最近活动的那一个）；null = 名下还没有任何项目目录。 */
    val primaryWsId: String?,
    /** [primaryWsId] 的展示名；null = 未命名（UI 翻译，不拿 wsId 顶替）。 */
    val primaryWsTitle: String?,
    /** 该实体最近一次活动（会话/事件/宣告的最近时刻）；0 = 没有任何活动。排序用。 */
    val lastActivityAt: Long,
    /**
     * 该实体名下**最近收到的一件产物**（UX 规范 §2.5②）；null = 还没有任何产物。
     *
     * ## 为什么不叫「最近一次成功的交付」
     *
     * `agent-artifact` 帧里**没有成败位** —— 只有文件名 / 大小 / 摘要 / 哈希。接收侧没有
     * 「这次交付成功了」的独立证据，唯一能如实说的是「最近收到过一件产物」。字段名与
     * 文案都不替协议编一个它没有的语义（与 [AgentHubStatusRow] 不拼句子同一条纪律）。
     *
     * ## 归因只有一把尺子
     *
     * 走 `sessionClusterBySessionId` —— 与会话、审批**同一张会话→聚类表**。产物自己
     * 不带 agent 身份（只有 `peerId` + `sessionId`），在这里另写一套 `agentId` 归一会
     * 让同一个产物在卡片与详情里落到两个实体上。
     *
     * 看不见的会话（子代理静默 / 空壳，[visibleInDsh] 之外）里的产物不进这里 ——
     * 与卡片流「不显示用户看不到的会话」同一条可见性纪律。
     */
    val latestArtifact: AgentHubArtifactUi? = null,
)

/**
 * 卡片底部「最近交付」标签的原始事实（UX 规范 §2.5②）。
 *
 * **只有元数据，没有二进制** —— 与 `AgentArtifactUi` 同一取舍：产物本体靠 `fileId` 指的
 * 那条 `file-msg`，点标签落到产物所属的会话，不在这里拉文件。
 *
 * 文案与措辞归 UI（模型层不出文案）；这里只如实给「叫什么、哪条会话、什么时候收到」。
 */
public data class AgentHubArtifactUi(
    /** 对端给的原文件名（不裁剪、不改写）。 */
    val name: String,
    /** 产物所属会话 —— 点标签要落到的那一条。 */
    val sessionId: String,
    /** 本端收到这一帧的时刻（标签上的相对时刻与排序都读它）。 */
    val at: Long,
)

/** 状态行的档位：决策稿 §4 的 Urgent-First —— P1 审批 > P2 执行中 > P3 最近。 */
public enum class AgentHubRowKind { PENDING, BUSY, RECENT }

/**
 * 卡上一条状态行。一张卡最多三条，按 Urgent-First 排好（构造处保证顺序，UI 照画）。
 *
 * 文案字段全部是**原始事实**（计数、群名、对端状态串、事件摘要），不在这里拼句子 ——
 * 模型层不出文案，本地化与措辞归 UI（与 `WorkspaceUnitActiveUi` 同一纪律）。
 */
public data class AgentHubStatusRow(
    val kind: AgentHubRowKind,
    /**
     * PENDING：待审批条数（归因到本实体的）。
     *
     * 群名归因不了的（直通宿主私聊、或工作区没有绑定任何群）= [pendingGroupNames] 为空，
     * 按决策稿 §4 显示**全局计数不写群名** —— 不编造归属。
     */
    val pendingCount: Int = 0,
    /** PENDING：可归因的群名（去重、按首次出现序）。空 = 归因不了，不写群名。 */
    val pendingGroupNames: List<String> = emptyList(),
    /** BUSY 恒为 `AgentSessionStates.BUSY`；RECENT = 最近会话的态（僵死 busy 已按看板口径折回 idle）。 */
    val state: String? = null,
    /** BUSY：正在执行的那条的最新事件文案；RECENT：最近会话的最新事件文案。null = 没有可说的事件。 */
    val summary: String? = null,
    /** RECENT：最近活动时刻（epoch ms，本端口径）。0 = 没有。 */
    val at: Long = 0,
    /** 这一行指向的会话（下钻 / 打开聊天用）；null = 不指向具体会话。 */
    val sessionId: String? = null,
    /** 这一行指向的项目目录；null = 未归属。 */
    val wsId: String? = null,
)

/**
 * 智能体详情页（Phase B4）的**实体视角**投影结果。
 *
 * 与 [AgentHubUi]（卡片，只能放几行）同键、同归并规则，但把整页要画的段落一次给齐：
 * 身份、组员清单、服务群行、项目目录行、归因审批、会话列表。纯数据，模型层不出文案
 * （null 的语义逐字段写明，UI 负责翻成本地化措辞）。
 */
public data class AgentHubDetailUi(
    /** 实体宿主（详情页 1:1 / 下钻的第一个键）。 */
    val hostNodeId: String,
    /** 宿主显示名；null = 节点表不认识（不拿 nodeId 顶替，同 [AgentHubUi.hostName]）。 */
    val hostName: String?,
    /** 归一后的身份键；null = 没有任何身份上报（「未上报类型」，同 [AgentHubUi.agentKey]）。 */
    val agentKey: String?,
    /** 显示名（席位名优先）；null = 连身份键都没有，UI 翻译。 */
    val displayName: String?,
    /** 是否团队形态（与卡片同判据）。 */
    val isTeam: Boolean,
    /** 团队人数 = 领队 1 + 组员数；单兵恒为 0。 */
    val teamSize: Int,
    /** 宿主在线态（席位在、宿主没发现过 = 离线，如实）。 */
    val online: Boolean,
    /**
     * 组员清单（[isTeam] 时非空；单兵恒为空）。
     *
     * 取**人数最多的那份团队席位**的组员（与 [teamSize] 同一份事实），按 agentId 去重 ——
     * 同一支队伍在多个群里重复声明时，清单不翻倍。
     */
    val teamMembers: List<AgentHubTeamMemberUi>,
    /** 服务群行（每群：席位执行点状态 + 该群待批数）。见 [AgentHubGroupRow]。 */
    val servingGroups: List<AgentHubGroupRow>,
    /** 项目目录行（宣告 ∪ 会话自报，合成 wsId 不计；最近活动在前）。见 [AgentHubWorkspaceRow]。 */
    val workspaces: List<AgentHubWorkspaceRow>,
    /**
     * 归因到本实体的**全部**审批（原始 `AgentApprovalUi` 原样透传，不滤状态）。
     *
     * 为什么不给「滤好的 pending」：`AgentApprovalsSection` 的处置留痕（已决 2 秒退场）
     * 需要看到「pending → 已决」的变化，只喂 pending 会让留痕变成死代码 —— 与工作区详情
     * 的喂法一致（那侧走 `rememberLingeringApprovalRows`）。过期与历史的取舍是**展示层**
     * 的规则，投影不替 UI 决定。
     */
    val approvals: List<AgentApprovalUi>,
    /**
     * 会话列表（`visibleInDsh` 口径 + 按 DSH 同一把「最新活动」尺排序），最多
     * [AGENT_HUB_DETAIL_SESSION_LIMIT] 条 —— 详情页是「最近在跑什么」的入口，不是台账。
     */
    val sessions: List<AgentSessionUi>,
)

/** 详情页身份段的**一条组员**（`TeamMemberInfo` 的展示投影：模型层不出文案）。 */
public data class AgentHubTeamMemberUi(
    val agentId: String,
    val name: String,
    /** 组员角色（声明原样透传；空 = 声明里没写）。 */
    val role: String,
)

/**
 * 详情页服务群段的**一个群**行。
 *
 * 执行点状态是纯席位事实：[defaultWsId] null = 该群没把任何项目目录绑给这个席位
 * （「未暴露」）；非 null = 已暴露，[resolvedWsTitle] 是它与 `workspaceOfGroup` 同源
 * 解析出的目录名（命中宣告才有值，null = 未命名 / 宣告还没到 —— UI 翻译，不拿 id 顶替）。
 */
public data class AgentHubGroupRow(
    val groupId: String,
    /** 群名（展示）。 */
    val groupName: String,
    /** 该群绑给本实体的执行点（席位 `defaultWsId`）；null = 未暴露。 */
    val defaultWsId: String?,
    /** [defaultWsId] 命中的宣告目录名；null = 未命名或宣告未到。 */
    val resolvedWsTitle: String?,
    /** 这个群里归因到本实体的待审批数（`isPendingApproval` 唯一判据）。 */
    val pendingCount: Int,
)

/**
 * 详情页项目目录段的**一个目录**行（点击进既有工作区详情）。
 *
 * [online] 是宿主自报的「此刻在不在这个目录里活着」；null = 没报（**不等于** false，
 * 沉默 ≠ 离线 —— UI 侧与 `WorkspaceUi.online` 的既有消费同一处理）。
 */
public data class AgentHubWorkspaceRow(
    val wsId: String,
    /** 目录展示名；null = 未命名（UI 翻译，不拿 wsId 顶替）。 */
    val wsTitle: String?,
    /** 宿主自报的目录内活跃态；null = 没报。 */
    val online: Boolean?,
    /** 本端可见的该目录会话数（`visibleInDsh` 口径，本端自己数的优先）。 */
    val sessionCount: Int,
)

/**
 * 详情页会话段的条数上限：「top 若干」（决策稿 §3a）—— 详情页回答「这个 agent 最近在
 * 跑什么」，完整台账在工作区详情 / 会话详情里。
 */
public const val AGENT_HUB_DETAIL_SESSION_LIMIT: Int = 10

/**
 * 智能体中心卡片流的投影（Phase B 决策稿 §3/§4）：输入三个切片，输出按 Urgent-First
 * 排好的实体卡列表。纯函数、无副作用；UI 只消费投影，不再自己 join。
 *
 * ## 卡序
 *
 * 待审批多者在前 → 有执行中者在前 → 最近活动新者在前 → 宿主 / 身份键钉住稳定序。
 * 卡内的状态行同一条 Urgent-First（审批 > 执行中 > 最近），两层同一把尺子。
 *
 * ## 审批到卡与到群的两次归因（都是**现有字段**，零协议零 DB）
 *
 * 1. **到实体**：审批帧里只有 `peerId`（宿主）+ `sessionId` —— 会话键 join
 *    `agentSessions`，落到哪个实体的会话集就归哪张卡（与看板行的 join 同一纪律；
 *    join 不上的审批不落到任何卡上，看板组头的 `unattributedApprovals` 仍然报它）。
 * 2. **到群**：会话自报的 `wsId` → [groupBoundToWorkspace]（席位声明的单源判据）
 *    → **再校验**返回的群确实有本实体的席位（宿主相同且 `sameAgent`）。
 *    两道校验都过才写群名：群把 wsId 绑给了**别的宿主**的席位时，把这条审批写成
 *    那个群的就是编造。
 */
public fun hubAgents(
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    now: Long,
): List<AgentHubUi> {
    val onlineByHost = hubHostOnlineStates(agentSlice, nodeSlice, settingsSlice)
    if (onlineByHost.isEmpty()) return emptyList()

    val approvals = agentSlice.agentApprovals.values
    val cards = mutableListOf<AgentHubUi>()
    for ((hostNodeId, hostOnline) in onlineByHost) {
        val index = hostIndexOf(hostNodeId, agentSlice, settingsSlice.settings.groups)
        for (cluster in index.clusters) {
            val entity = assembleEntity(hostNodeId, cluster, index, agentSlice, approvals, settingsSlice, now)
            cards += entity.toCard(hostNodeId, hostOnline, agentSlice, nodeSlice, settingsSlice, now)
        }
    }

    return cards.sortedWith(
        compareByDescending<AgentHubUi> { it.pendingCount }
            .thenByDescending { card -> card.rows.any { it.kind == AgentHubRowKind.BUSY } }
            .thenByDescending { it.lastActivityAt }
            .thenBy { it.hostNodeId }
            .thenBy { it.agentKey.orEmpty() },
    )
}

/**
 * 智能体详情页的实体视角投影（Phase B4，决策稿 §3a）：给定实体键
 * `(hostNodeId, agentId)`，输出该实体的整页数据。
 *
 * ## 与卡片流同一份事实
 *
 * 实体的发现、聚类（`sameAgent` 归一）、席位匹配、审批归因全部复用 `hubAgents` 的
 * 同一套装配（[hostIndexOf] / [assembleEntity]）—— 卡片上写的和详情里展开的是**同一个
 * 实体的同一份事实**，不存在「卡片说 A、详情说 B」的第二种可能。
 *
 * ## 返回 null 的两种情形（都如实，不编）
 *
 * - `hostNodeId` 不在任何编制来源里（节点表 / 席位声明都没有）；
 * - 宿主在，但没有任何聚类匹配 [agentId]（实体已撤销；空串按「未上报类型」那一档匹配，
 *   因为导航路由里只能传字符串 —— 与卡片的 null 身份键是同一个实体）。
 *
 * 其余入参语义与 [hubAgents] 相同。
 */
public fun agentHubDetail(
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    hostNodeId: String,
    agentId: String,
    now: Long,
): AgentHubDetailUi? {
    val groups = settingsSlice.settings.groups
    val hostOnline = hubHostOnlineStates(agentSlice, nodeSlice, settingsSlice)[hostNodeId] ?: return null
    val index = hostIndexOf(hostNodeId, agentSlice, groups)
    val cluster = index.clusters.firstOrNull { it.matches(agentId.takeIf { it.isNotBlank() }) } ?: return null
    val entity = assembleEntity(
        hostNodeId = hostNodeId,
        cluster = cluster,
        index = index,
        agentSlice = agentSlice,
        approvals = agentSlice.agentApprovals.values,
        settingsSlice = settingsSlice,
        now = now,
    )

    // 组员清单：取人数最多的那份团队席位（与 teamSize 同一份事实），按 agentId 去重 ——
    // 同一支队伍在多个群里重复声明时，清单不翻倍。
    val teamMembers = entity.seats.filter { it.isTeam }
        .maxByOrNull { it.teamMembers.size }
        ?.teamMembers.orEmpty()
        .distinctBy { it.agentId }
        .map { AgentHubTeamMemberUi(agentId = it.agentId, name = it.name, role = it.role) }

    // 服务群行：每群找本实体在该群的席位，读它的执行点声明。目录名与
    // `workspaceOfGroup` 同源：席位声明的 wsId → `agentWorkspaces` 里同一键的宣告
    // （那里按「任一席位的声明」取第一个命中，这里按本席位自己的声明 —— 同一张表，
    // 同一次查找，不存在第二份解析规则）。
    val servingGroups = entity.entityGroups.map { group ->
        val seat = index.hostSeats.firstOrNull { (candidate, seatGroup) ->
            seatGroup.groupId == group.groupId && entity.seats.any { it == candidate }
        }?.first
        val declaredWsId = seat?.defaultWsId?.takeIf { it.isNotBlank() }
        val resolved = declaredWsId?.let { wsId ->
            agentSlice.agentWorkspaces.values.firstOrNull { it.wsId == wsId }
        }
        AgentHubGroupRow(
            groupId = group.groupId,
            groupName = group.name,
            defaultWsId = declaredWsId,
            resolvedWsTitle = resolved?.wsTitle,
            pendingCount = entity.pendingCountByGroup[group.groupId] ?: 0,
        )
    }

    val workspaces = entity.wsInfos.values
        .sortedWith(compareByDescending<HubWsInfo> { it.lastAt }.thenBy { it.wsId })
        .map { info ->
            val declared = entity.cluster.workspaces.firstOrNull { it.wsId == info.wsId }
            AgentHubWorkspaceRow(
                wsId = info.wsId,
                wsTitle = info.title,
                online = declared?.online,
                sessionCount = entity.cluster.sessions.count { it.wsId == info.wsId },
            )
        }

    val sessions = entity.cluster.sessions
        .sortedByLatestActivity { lastEventAtOf(agentSlice, hostNodeId, it) }
        .take(AGENT_HUB_DETAIL_SESSION_LIMIT)

    return AgentHubDetailUi(
        hostNodeId = hostNodeId,
        hostName = peer(hostNodeId, nodeSlice, settingsSlice)?.displayName,
        agentKey = entity.canonicalKey,
        displayName = entity.displayName,
        isTeam = entity.isTeam,
        teamSize = entity.teamSize,
        online = hostOnline,
        teamMembers = teamMembers,
        servingGroups = servingGroups,
        workspaces = workspaces,
        approvals = entity.attributedApprovals,
        sessions = sessions,
    )
}

/**
 * 编制里有哪些宿主、各自在不在线：`hubAgents` 与 `agentHubDetail` 共用的**唯一**来源。
 *
 * 席位宿主哪怕此刻不在线、节点表里也没有，也要入账：它是一份**编制声明**，
 * 「组织里还有这台数字员工」这件事不因当前看不见而消失（离线如实标注）。
 */
private fun hubHostOnlineStates(
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
): LinkedHashMap<String, Boolean> {
    val selfNodeId = settingsSlice.settings.nodeId
    val onlineByHost = LinkedHashMap<String, Boolean>()
    agentPeers(agentSlice, nodeSlice, settingsSlice).forEach { peer ->
        onlineByHost[peer.nodeId] = peer.online || peer.nodeId == selfNodeId
    }
    settingsSlice.settings.groups.forEach { group ->
        group.virtualAgents.orEmpty().forEach { seat ->
            onlineByHost.putIfAbsent(seat.hostNodeId, seat.hostNodeId == selfNodeId)
        }
    }
    return onlineByHost
}

/** 一个宿主的装配索引：聚类 + 会话→聚类映射 + 本宿主全部席位。卡片流与详情页共用。 */
private class HostIndex(
    val clusters: List<HubCluster>,
    val sessionClusterBySessionId: Map<String, HubCluster>,
    val hostSessions: List<AgentSessionUi>,
    val hostSeats: List<Pair<VirtualAgentMember, GroupMeta>>,
    /**
     * 每个聚类**最近收到**的那件产物（UX 规范 §2.5②）。
     *
     * 在这里一次算好而不是出卡时再扫：产物表是全局的（所有宿主），逐卡过滤等于
     * O(产物 × 实体)；而「哪件产物属于哪个聚类」这件事只依赖会话→聚类表，
     * 天然是装配期的信息，不是卡片期才有的。
     */
    val latestArtifactByCluster: Map<HubCluster, AgentArtifactUi>,
)

/**
 * 装一个宿主的聚类（`hubAgents` 原内联段的提取，行为逐字保持）：可见会话与宣告按
 * `sameAgent` 聚成实体，身份在聚类里没有任何落点的席位自己成实体。
 */
private fun hostIndexOf(
    hostNodeId: String,
    agentSlice: AgentSlice,
    groups: List<GroupMeta>,
): HostIndex {
    // 可见性过滤与看板同一份（[visibleInDsh]）：子代理静默会话与空壳不进卡片流，
    // 否则「正在执行」会为一条用户永远看不到的子代理会话亮起来。
    val hostSessions = agentSlice.agentSessions.values
        .filter { it.peerId == hostNodeId }.visibleInDsh()
    val hostDeclared = agentSlice.agentWorkspaces.values.filter {
        it.peerId == hostNodeId && !isSyntheticWsId(it.wsId)
    }

    val clusters = mutableListOf<HubCluster>()
    val sessionClusterBySessionId = HashMap<String, HubCluster>()
    fun clusterOf(key: String?): HubCluster {
        val hit = clusters.firstOrNull { it.matches(key) }
        if (hit != null) return hit
        val created = HubCluster()
        clusters += created
        return created
    }
    hostSessions.forEach { session ->
        val agentId = session.agentId?.takeIf { it.isNotBlank() }
        val kind = session.agentKind?.takeIf { it.isNotBlank() }
        val cluster = clusterOf(agentId ?: kind)
        cluster.addIdentity(agentId ?: kind, fromAgentId = agentId != null)
        if (agentId != null && kind != null) cluster.addIdentity(kind, fromAgentId = false)
        cluster.sessions += session
        sessionClusterBySessionId[session.sessionId] = cluster
    }
    hostDeclared.forEach { ws ->
        val cluster = clusterOf(ws.agentKey)
        cluster.addIdentity(ws.agentKey, fromAgentId = ws.agentId != null)
        cluster.workspaces += ws
    }

    // ---- 本宿主的席位（含群）：身份在聚类里没有任何落点的席位，自己成实体 ----
    val hostSeats = groups.flatMap { group ->
        group.virtualAgents.orEmpty()
            .filter { it.hostNodeId == hostNodeId }
            .map { seat -> seat to group }
    }
    hostSeats.forEach { (seat, _) ->
        val seatKey = seat.agentId.takeIf { it.isNotBlank() }
        if (clusters.none { it.matches(seatKey) }) {
            clusterOf(seatKey).addIdentity(seatKey, fromAgentId = true)
        }
    }

    // ---- 最近一件产物（§2.5②）：归因只走上面那张「会话→聚类」表 ----
    //
    // 产物帧自带的是 `peerId` + `sessionId`，**没有 agent 身份**。所以这里不解析 agentId、
    // 不做第二次 sameAgent 归一 —— 拿会话去查上面刚建好的那张表，与审批归因逐字同一把尺子。
    // 查不到 = 那条会话不在可见集里（子代理静默 / 空壳）：产物不进卡片，与可见性纪律一致。
    val latestArtifactByCluster = HashMap<HubCluster, AgentArtifactUi>()
    agentSlice.agentArtifacts.values
        .filter { it.peerId == hostNodeId }
        .forEach { artifact ->
            val cluster = sessionClusterBySessionId[artifact.sessionId] ?: return@forEach
            val current = latestArtifactByCluster[cluster]
            // 「最近」按**本端收到的时刻**（`at`）比，不按文件名或会话顺序猜。
            if (current == null || artifact.at > current.at) latestArtifactByCluster[cluster] = artifact
        }

    return HostIndex(clusters, sessionClusterBySessionId, hostSessions, hostSeats, latestArtifactByCluster)
}

/** 一个实体卡在装配期间的聚类：身份来源 + 名下会话 + 名下宣告。 */
private class HubCluster {
    /**
     * 观察到的身份来源；`fromAgentId` 区分「自报的 agentId」与「兜底的 agentKind」。
     * null 身份如实入账（"对端什么都没报"是聚类的一种），它只与另一个 null 并。
     */
    val identities = mutableListOf<HubIdentity>()

    val sessions = mutableListOf<AgentSessionUi>()
    val workspaces = mutableListOf<WorkspaceUi>()

    /** [key] 属不属于这个聚类（`sameAgent` 容忍精度差异；null 只与 null 并）。 */
    fun matches(key: String?): Boolean = when (key) {
        null -> identities.any { it.key == null }
        else -> identities.any { it.key != null && sameAgent(key, null, it.key) }
    }

    fun addIdentity(key: String?, fromAgentId: Boolean) {
        // null 键没有精度来源可言（agentId 非空时键就不会是 null），一律记为兜底档。
        val source = if (key == null) false else fromAgentId
        if (identities.none { it.key == key && it.fromAgentId == source }) {
            identities += HubIdentity(key, source)
        }
    }
}

/** 一条身份来源：键 + 它是不是来自显式 `agentId`（决定聚类代表与显示名的优先级）。 */
private data class HubIdentity(val key: String?, val fromAgentId: Boolean)

/** 装配期的一个项目目录条目：去重后的 wsId + 标题 + 最近活动。 */
private data class HubWsInfo(
    val wsId: String,
    val title: String?,
    val lastAt: Long,
)

/** 一个实体装配完成后的全部事实：卡片（`hubAgents`）与详情页（`agentHubDetail`）各取所需。 */
private class HubEntity(
    val cluster: HubCluster,
    /** 聚类代表：优先取自 `agentId` 来源（最精确），其次任意身份键；null 如实保留。 */
    val canonicalKey: String?,
    /** 本实体的席位（宿主已在键里，只比身份）。 */
    val seats: List<VirtualAgentMember>,
    /** 本实体落进的群（按 groupId 去重）。 */
    val entityGroups: List<GroupMeta>,
    val displayName: String?,
    val isTeam: Boolean,
    val teamSize: Int,
    val wsInfos: LinkedHashMap<String, HubWsInfo>,
    /** 归因到本实体的全部审批（未滤状态；消费方各自按需再滤）。 */
    val attributedApprovals: List<AgentApprovalUi>,
    /** 待批总数与可归因群名（卡片 P1 行用；`isPendingApproval` 唯一判据）。 */
    val pendingCount: Int,
    val pendingGroupNames: LinkedHashSet<String>,
    /** 每个群的待批数（详情页服务群行用；键 = groupId，只含本实体的群）。 */
    val pendingCountByGroup: Map<String, Int>,
    /** 本实体名下最近收到的一件产物（§2.5②）；null = 还没有任何产物。 */
    val latestArtifact: AgentHubArtifactUi?,
)

/**
 * 把一个聚类装配成实体事实（`hubAgents` 原内联段的提取，计数行为逐字保持）：
 * 聚类代表、席位匹配、显示名 / 团队形态、项目目录并集、审批两次归因。
 */
private fun assembleEntity(
    hostNodeId: String,
    cluster: HubCluster,
    index: HostIndex,
    agentSlice: AgentSlice,
    approvals: Collection<AgentApprovalUi>,
    settingsSlice: SettingsSlice,
    now: Long,
): HubEntity {
    val canonicalKey = cluster.identities.firstOrNull { it.key != null && it.fromAgentId }?.key
        ?: cluster.identities.firstOrNull { it.key != null }?.key

    // 席位匹配：宿主已在键里，这里只比身份（sameAgent，精度差异容忍 —— 决策稿 §3）。
    val seats = index.hostSeats.map { it.first }.filter { seat ->
        canonicalKey != null && sameAgent(seat.agentId, null, canonicalKey)
    }
    val entityGroups = index.hostSeats.filter { (seat, _) ->
        canonicalKey != null && sameAgent(seat.agentId, null, canonicalKey)
    }.map { it.second }.distinctBy { it.groupId }

    val displayName = seats.firstOrNull { it.name.isNotBlank() }?.name ?: canonicalKey
    val isTeam = seats.any { it.isTeam }
    val teamSize = seats.filter { it.isTeam }
        .maxOfOrNull { 1 + it.teamMembers.size } ?: 0

    // ---- 项目目录：会话自报的 wsId ∪ 宣告，按 wsId 去重；每目录留最近活动与标题 ----
    val wsInfos = LinkedHashMap<String, HubWsInfo>()
    cluster.sessions.forEach { session ->
        val wsId = session.wsId?.takeIf { it.isNotBlank() } ?: return@forEach
        if (isSyntheticWsId(wsId)) return@forEach
        val lastAt = session.latestActivityAt(lastEventAtOf(agentSlice, hostNodeId, session))
        val existing = wsInfos[wsId]
        wsInfos[wsId] = HubWsInfo(
            wsId = wsId,
            // 标题：同目录先到先得（与看板行的合并纪律同口径），会话侧优先于宣告侧。
            title = existing?.title ?: session.wsTitle?.takeIf { it.isNotBlank() },
            lastAt = maxOf(existing?.lastAt ?: 0L, lastAt),
        )
    }
    cluster.workspaces.forEach { ws ->
        val wsId = ws.wsId.takeIf { it.isNotBlank() } ?: return@forEach
        val existing = wsInfos[wsId]
        wsInfos[wsId] = HubWsInfo(
            wsId = wsId,
            title = existing?.title ?: ws.wsTitle?.takeIf { it.isNotBlank() },
            lastAt = maxOf(existing?.lastAt ?: 0L, ws.at),
        )
    }

    // ---- 审批两次归因（见 hubAgents KDoc）：先到实体，再经 wsId + 席位校验到群 ----
    val attributed = mutableListOf<AgentApprovalUi>()
    var pendingCount = 0
    val pendingGroupNames = LinkedHashSet<String>()
    val pendingCountByGroup = HashMap<String, Int>()
    approvals.forEach { approval ->
        if (approval.peerId != hostNodeId) return@forEach
        if (index.sessionClusterBySessionId[approval.sessionId] !== cluster) return@forEach
        attributed += approval
        // 过期的不算待批：[isPendingApproval] 是全仓唯一判据（页头 / 段头 / 这里同一把尺子）。
        if (!isPendingApproval(approval, now)) return@forEach
        pendingCount += 1
        val wsId = index.hostSessions.firstOrNull { it.sessionId == approval.sessionId }
            ?.wsId?.takeIf { it.isNotBlank() } ?: return@forEach
        val bound = groupBoundToWorkspace(wsId, settingsSlice.settings.groups) ?: return@forEach
        if (entityGroups.any { it.groupId == bound.groupId }) {
            pendingGroupNames += bound.name
            pendingCountByGroup.merge(bound.groupId, 1, Int::plus)
        }
    }

    return HubEntity(
        cluster = cluster,
        canonicalKey = canonicalKey,
        seats = seats,
        entityGroups = entityGroups,
        displayName = displayName,
        isTeam = isTeam,
        teamSize = teamSize,
        wsInfos = wsInfos,
        attributedApprovals = attributed,
        pendingCount = pendingCount,
        pendingGroupNames = pendingGroupNames,
        pendingCountByGroup = pendingCountByGroup,
        // 产物在装配期已按「会话→聚类」归好类，这里只把那条 `AgentArtifactUi` 收窄成
        // 卡片要用的三个事实 —— 不做第二次归因，也不在 UI 层再筛一遍。
        latestArtifact = index.latestArtifactByCluster[cluster]?.let { artifact ->
            AgentHubArtifactUi(
                name = artifact.name,
                sessionId = artifact.sessionId,
                at = artifact.at,
            )
        },
    )
}

/** 实体事实 → 卡片：Urgent-First 状态行（审批 > 执行中 > 最近）在这里拼。 */
private fun HubEntity.toCard(
    hostNodeId: String,
    hostOnline: Boolean,
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    now: Long,
): AgentHubUi {
    // ---- P2 执行中：非僵死 busy（与看板 pickActiveSession 同一条 staleness 判据）----
    val busySession = cluster.sessions
        .filter {
            it.state == AgentSessionStates.BUSY &&
                !isStaleBusy(it.latestActivityAt(lastEventAtOf(agentSlice, hostNodeId, it)), now)
        }
        .maxByOrNull { it.latestActivityAt(lastEventAtOf(agentSlice, hostNodeId, it)) }

    // ---- P3 最近兜底：会话/事件与宣告并取最近；态做僵死 busy 折回（与看板同口径）----
    val lastActivityAt = maxOf(
        cluster.sessions.maxOfOrNull { it.latestActivityAt(lastEventAtOf(agentSlice, hostNodeId, it)) } ?: 0L,
        cluster.workspaces.maxOfOrNull { it.at } ?: 0L,
    )
    val recentSession = cluster.sessions.maxByOrNull { it.latestActivityAt(lastEventAtOf(agentSlice, hostNodeId, it)) }
    val recentState = recentSession?.let { session ->
        val lastAt = session.latestActivityAt(lastEventAtOf(agentSlice, hostNodeId, session))
        if (session.state == AgentSessionStates.BUSY && isStaleBusy(lastAt, now)) {
            AgentSessionStates.IDLE
        } else {
            session.state
        }
    }

    fun summaryOf(session: AgentSessionUi): String? = latestEventSummaryOf(agentSlice, hostNodeId, session)

    val rows = buildList {
        if (pendingCount > 0) {
            add(
                AgentHubStatusRow(
                    kind = AgentHubRowKind.PENDING,
                    pendingCount = pendingCount,
                    pendingGroupNames = pendingGroupNames.toList(),
                ),
            )
        }
        if (busySession != null) {
            add(
                AgentHubStatusRow(
                    kind = AgentHubRowKind.BUSY,
                    state = AgentSessionStates.BUSY,
                    summary = summaryOf(busySession),
                    sessionId = busySession.sessionId,
                    wsId = busySession.wsId?.takeIf { it.isNotBlank() },
                ),
            )
        }
        if (lastActivityAt > 0L) {
            add(
                AgentHubStatusRow(
                    kind = AgentHubRowKind.RECENT,
                    state = recentState,
                    summary = recentSession?.let { summaryOf(it) },
                    at = lastActivityAt,
                    sessionId = recentSession?.sessionId,
                    wsId = recentSession?.wsId?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    val primary = wsInfos.values.maxByOrNull { it.lastAt }
    return AgentHubUi(
        hostNodeId = hostNodeId,
        hostName = peer(hostNodeId, nodeSlice, settingsSlice)?.displayName,
        agentKey = canonicalKey,
        displayName = displayName,
        isTeam = isTeam,
        teamSize = teamSize,
        groupCount = entityGroups.size,
        workspaceCount = wsInfos.size,
        online = hostOnline,
        pendingCount = pendingCount,
        rows = rows,
        primaryWsId = primary?.wsId,
        primaryWsTitle = primary?.title,
        lastActivityAt = lastActivityAt,
        latestArtifact = latestArtifact,
    )
}

/** 这条会话最新事件的到达时刻（事件流为空 = 0）。装配共用的一把尺。 */
private fun lastEventAtOf(agentSlice: AgentSlice, hostNodeId: String, session: AgentSessionUi): Long =
    agentSlice.agentEvents[AgentSessionKey(hostNodeId, session.sessionId)].orEmpty().maxOfOrNull { it.at } ?: 0L

/** 这条会话的最新事件文案（summary 优先，其次 body，其次会话 title）。装配共用的一把尺。 */
private fun latestEventSummaryOf(agentSlice: AgentSlice, hostNodeId: String, session: AgentSessionUi): String? {
    val event = agentSlice.agentEvents[AgentSessionKey(hostNodeId, session.sessionId)].orEmpty().lastOrNull()
    return event?.summary ?: event?.body ?: session.title
}
