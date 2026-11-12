// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.DeliveryState
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.NoticeKind
import com.kapp.tenon.domain.model.NoticeDetail
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.resolveVirtualAgentKey
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.GroupMerge
import com.kapp.tenon.core.protocol.GroupMergeDecision
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.withLocalExtras
import com.kapp.tenon.core.protocol.GroupOps
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.Protocol
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.core.transport.SendOutcome
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.declarationFor
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentMemberName
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.TeamRosterValidation
import com.kapp.tenon.core.protocol.isTeam

/**
 * 群聊：建群、入退群、改群资料、发群公告，以及群消息的入站处理。
 *
 * 切出来的理由：这一族的演化动力与其它 7 个分片互不相干。过去它们都是
 * `internal fun RelayController.` 的顶层扩展，凑在一起约 3000 行；改其中一族要在
 * 另外六族里找它依赖了什么。
 *
 * @param host 会话域的窄缝（[ConversationDomainHost]）。**不是** `RelayController`：本族
 *   碰不到别的域的组件，跨域信封路由只能走端口（`host.agent` / `host.transfer` / `host.node`）；
 *   `core` / `stateLock` 是 Phase 3 第三步要清的已知共享耦合（见接口 KDoc），这里是显式记账。
 *   逐个 `host.` 前缀是为了让依赖面一眼可见；只对本族真正用到的名字加前缀 ——
 *   同名的函数形参会遮蔽宿主成员（`state` 就是一例），给它加前缀会直接产出语法垃圾。
 */
internal class RelayControllerGroupsComponent(
    private val host: ConversationDomainHost,
) {

/**
 * 这个群有没有 agent 声明（= 界面语境里的「工作区群」）。
 *
 * 债#2：旧的 `isWorkspaceGroup` 拿群简介/群名对工作区路径、id、标题做**子串匹配** ——
 * 简介被人编辑过判定就翻转，普通群提到路径会被误判。声明是唯一事实：
 * `virtualAgents` 非空 ⇒ 群里有 agent，回复它不必再 @ 一遍。
 */
fun groupHasDeclaredAgents(groupId: String): Boolean {
    val meta = host.state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return false
    return !meta.virtualAgents.isNullOrEmpty()
}

fun handleSyncWorkspaceGroups() {

    val settings = host.state.value.settings
    var changed = false
    val updated = settings.groups.map { group ->
        if (group.description.isNullOrBlank()) {
            // 绑定看**声明**（债#2：子串猜测已删）：简介回填的是声明里默认工作区的
            // 展示名。声明没有可解析的默认工作区（没声明 / defaultWsId 为空 /
            // 本机工作区表里没有那一行）就不填 —— 空着比编一个强。
            val declaredWsId = group.virtualAgents.orEmpty().firstNotNullOfOrNull { it.defaultWsId }
            val matchedWs = declaredWsId
                ?.let { id -> host.state.value.agentWorkspaces.values.firstOrNull { it.wsId == id } }
            if (matchedWs != null) {
                changed = true
                // **不写文件系统路径**：群简介是给群成员看的，本机目录路径
                // 这种本机路径对别人既无意义、也暴露了本机目录结构（真机反馈 #4）。
                // 用工作区展示名，没有名字就不填 —— 空着比填一个实现细节好。
                group.copy(description = matchedWs.wsTitle.orEmpty())
            } else {
                group
            }
        } else {
            group
        }
    }
    if (changed) {
        host.handlePersist(settings.copy(groups = updated))
    }
    // 启动补一次（幂等）：群可能是上一次运行里由手机建的，那时宿主还没装配目录端口，
    // 入站那条路已经走完 —— 不在启动时补，它要等到对方下一次广播才被兑现。
    host.adoptRequestedGroupDirectories()
}

/**
 * [RelayController] 的群组域处理扩展。
 *
 * 覆盖建群、邀请/移出成员、群文本发送、GroupNeed/GroupInfo 协议协商与同步。
 */

fun handleCreateGroup(name: String, memberIds: List<String>, description: String = ""): String? =
    handleCreateGroupInternal(
        name = name,
        memberIds = memberIds,
        description = description,
        workspace = null,
        // 普通建群（会话列表那颗按钮）保持原判据：一个都选不出来，那确实不是群聊。
        allowEmptyMembers = false,
    )

/**
 * 给一个**工作区**建协同群：允许此刻还没有任何其它设备，并把工作区上的 agent 声明进群。
 *
 * ## 为什么这条入口不吃 [handleCreateGroup] 那道「至少再来一个人」
 *
 * 真机反馈：「新建工作区的群无法快速建群，一直提示要至少一个人」。现场就是这一形态 ——
 * 用户点「一键建群 / 共享」，而建群对话框里**根本没有可勾的人**（成员来源只有"当前在线
 * 设备"，对话框本身也不是成员选择器），于是控制器拒收、对话框已经关掉、屏幕上只剩一句
 * 「至少再选一个人」。用户被指着一个他此刻做不到的动作。
 *
 * 两种群不是一回事：
 * - 普通群 = 「拉几个人聊天」，成员得先存在；
 * - 工作区群 = 「给这个项目开一条协同通道」，**先把群建出来，再用分享码/群信息把设备
 *   拉进来**（对话框里「已在协同群中」那张卡片给的正是「进入群聊 + 分享码」）。
 *   反过来要求"必须先有在线设备"，等于要求用户先完成他本来要靠这个群才能做的事。
 *
 * 所以这里允许 0 个人类成员，但会尽量把工作区上的 agent 声明进 `virtualAgents`
 * （见 [virtualAgentMemberFor]）：群里因此至少还有第二方，且 @ 它能真的驱动执行。
 * 连 agent 身份都拿不到（工作区没上报 agentId/agentKind，或它属于远端设备）时，
 * 群仍可建出来 —— 它此刻的意义是那条协同通道，成员按 [handleAddGroupMembers] 补。
 *
 * @param wsId 已宣告的工作区 id；null = 「关联本地全新目录」分支（还没有工作区行）。
 */
fun handleCreateWorkspaceGroup(
    name: String,
    wsId: String?,
    memberIds: List<String>,
    description: String = "",
): String? {
    val self = host.state.value.selfNodeId
    // A22-R5：绑定群创建时的工作区行必须按 wsId 匹配**任意宿主宣告的行** ——
    // 手机建绑定群时，工作区是对端桌面宣告的（peerId = 对端），此前的
    // `peerId == self` 过滤永远匹配不上 ⇒ workspace 恒 null ⇒ 席位恒空 ⇒
    // 该群的 @agent 派单永远 NO_WAKE（真机两轮立案）。wsId 本身唯一，匹配无歧义。
    val workspace = wsId
        ?.takeIf { it.isNotBlank() }
        ?.let { id -> host.state.value.agentWorkspaces.values.firstOrNull { it.wsId == id } }
    return handleCreateGroupInternal(
        name = name,
        memberIds = memberIds,
        description = description,
        workspace = workspace,
        allowEmptyMembers = true,
    )
}

private fun handleCreateGroupInternal(
    name: String,
    memberIds: List<String>,
    description: String,
    workspace: WorkspaceUi?,
    allowEmptyMembers: Boolean,
): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        host.warn(BannerMessage.GroupNameRequired)
        return null
    }
    if (trimmed.length > Limits.GROUP_NAME) {
        host.warn(BannerMessage.GroupNameTooLong(Limits.GROUP_NAME))
        return null
    }
    val self = host.state.value.selfNodeId
    val others = memberIds.map { it.trim() }.filter { it.isNotEmpty() && it != self }.distinct()
    val seat = workspace?.let { virtualAgentMemberFor(it) }
    if (others.isEmpty() && !allowEmptyMembers) {
        host.warn(BannerMessage.GroupNeedsMember)
        return null
    }
    // 简介回填：调用方显式传了就用它；没传而工作区在手，用工作区**展示名**
    // （债#2：按群名子串反猜工作区的兜底已删 —— 绑定看声明，不猜）。
    val autoDesc = if (description.isNotBlank()) description.trim() else workspace?.wsTitle.orEmpty()
    val created = GroupMeta.create(
        groupId = newId(),
        name = trimmed,
        members = others,
        selfId = self,
        updatedTs = nowMillis(),
        description = autoDesc,
    )
    // agent 声明与群一起落库（rev = 1）：别人收到的第一份 `group.info` 里就带着它，
    // 不必再等一次额外的「入驻」往返 —— 那条路要成员在线才发得出去，而这里恰恰
    // 可能一个成员都没有。
    val meta = if (seat == null) created else created.copy(virtualAgents = listOf(seat))
    host.handlePersist(host.state.value.settings.copy(groups = host.state.value.settings.groups + meta))
    // 建群这条路径也要把 rev 登记进 need 游标：我们**刚刚**有这个群的元数据了。
    // 漏了它会怎样？建完群收到的第一条同 rev 群消息会回一发 `op:need` —— 去问一份
    // 自己手上就有的成员表。对端若还回 host.info，就是白跑一个来回；若不回，游标一直不前进，
    // 则**每条**群消息都带上这一问（对端在线却从不回 host.info 的情形）。
    // 另外三条写元数据的路径（加人/移人/收到 host.info）都登记了，只有这里是漏的。
    host.core.groupNeeds.noteKnown(meta.groupId, meta.rev)

    // 显式传进来的那个工作区是唯一来源（调用方已经知道是哪一行，无需反猜）。
    val targetWs = workspace

    if (targetWs != null) {
        // `wsId` 在 WorkspaceUi 上是非空的（见 AgentUi 的声明），所以这里不用再兜到描述串 ——
        // 那个兜底是死代码，编译器会报「Elvis 恒取左操作数」。
        val wsName = targetWs.wsTitle ?: targetWs.wsId
        // 展示与署名用的 kind：工作区自报优先；工作区没报时用声明的身份键
        // （seat 存在 ⇒ resolveVirtualAgentKey 已兜过 agentId/agentKind 之一）。
        // 两者都拿不到 ⇒ 没有可署名的身份（债#6：无内置默认种类），不发开场白。
        val agentKind = targetWs.agentKind?.takeIf { it.isNotBlank() } ?: seat?.agentId
        if (agentKind != null) {
        // agent 的身份是 **(设备, agent)**，不是裸 kind —— 否则两台设备上同名 agent
        // 在 senderId 里完全一样，会被合并成同一个人（见 AgentSenderId 的 KDoc）。
        // 开场白的署名必须与群里那条声明**同一个人**：声明用的是兜底后的身份键
        // （agentId 空时退到 agentKind），这里若还传裸 `agentId`，同一个 agent 会在成员
        // 列表里叫一个名字、在气泡上叫另一个。
        val agentSenderId = AgentSenderId.of(self, seat?.agentId ?: targetWs.agentId, agentKind)
        host.scope.launch {
            // 开场白与状态播报都经桥取（见 [RelayUiTexts]）：措辞在 strings.xml，
            // 「什么时候说」由这里判 —— 两者不该互相依赖。
            val greeting = runCatching {
                host.uiTexts.workspaceGreeting(agentKind, wsName)
            }.getOrNull().orEmpty()
            host.handleAppendMessage(
                MessageUi(
                    id = host.localMessageId(),
                    peerId = ConvId.group(meta.groupId),
                    mine = false,
                    text = greeting,
                    at = nowMillis(),
                    delivery = null,
                    senderId = agentSenderId,
                ),
            )
            // 同步工作区当前活跃会话状态到群聊
            val activeSession = host.state.value.agentSessions.values.firstOrNull {
                it.wsId == targetWs.wsId && !it.isArchived && it.state == "busy"
            } ?: host.state.value.agentSessions.values.firstOrNull {
                it.wsId == targetWs.wsId && !it.isArchived && it.title?.isNotBlank() == true
            }
            if (activeSession != null) {
                val sessionTitle = activeSession.title ?: activeSession.alias ?: activeSession.sessionId
                val brief = runCatching {
                    host.uiTexts.workspaceSessionBrief(sessionTitle, activeSession.state == "busy")
                }.getOrNull().orEmpty()
                host.handleAppendMessage(
                    MessageUi(
                        id = host.localMessageId(),
                        peerId = ConvId.group(meta.groupId),
                        mine = false,
                        text = brief,
                        at = nowMillis() + 10,
                        delivery = null,
                        senderId = agentSenderId,
                    ),
                )
            }
        }
    }
    }

    // 「正在通知 0 位成员」读起来像失败。工作区群允许先建后拉，所以 0 人时换一句
    // 能指路的：群已经建好，设备等会儿再加。
    val createdBanner = if (others.isEmpty()) {
        BannerMessage.GroupCreatedWithoutMembers(meta.name)
    } else {
        BannerMessage.GroupCreated(meta.name, others.size)
    }
    val rt = host.runtime
    if (rt == null) {
        host.info(createdBanner)
        host.publish()
        return meta.groupId
    }
    host.scope.launch {
        for (member in others) {
            runCatching { rt.sendGroupInfo(member, host.groupMetaWithNicks(meta)) }
        }
        host.refreshPeers()
    }
    host.info(createdBanner)
    host.publish()
    return meta.groupId
}

/**
 * 工作区上的 agent 在群里的那一条声明（`virtualAgents`）。
 *
 * 身份键的兜底顺序与「入驻」（[RelayController.handleAddGroupAgent]）**同一套**
 * —— 真机数据里工作区的 `agentId` 常为空，只认它会让「建群时把 agent 拉进来」在多数
 * 工作区上静默不生效。名字走 `AgentMemberName.base`，与入驻同源：两处各算一遍，
 * 同一个 agent 在群里就会有两个名字。
 *
 * ## 为什么建群路径保留这条内联声明（而入驻由 [RelayController.handleAddGroupAgent] 接管）
 *
 * 手机端（无目录端口）快速建群时，工作区可能由**对端**宣告（A22-R5）—— 入驻在
 * 本机找不到那一行，会静默不生效；这里的内联声明让群里至少还有第二方。桌面壳在
 * 建群成功后仍会走一遍入驻 + 指针绑定（建群即选项目目录，D14）—— 入驻找到这条
 * 既有席位就沿用名字、只重写投影，不会产生第二个 @token。
 *
 * @return null = 这个工作区拿不到任何 agent 身份（agentId / agentKind 都没上报）。
 */
private fun virtualAgentMemberFor(ws: WorkspaceUi): VirtualAgentMember? {
    val agentId = resolveVirtualAgentKey(null, ws.agentId, ws.agentKind) ?: return null
    // 新席位：previous = null ⇒ declarationFor 按默认值开（单兵、空组员）。
    // 构造点收口在 declarationFor（见它的 KDoc）——这里不再手写 VirtualAgentMember。
    // hostNodeId 挂 **agent 真正运行的那台宿主**（A22-R5：工作区宣告方），
    // 不是本机 —— 手机上建的绑定群，agent 在桌面。本机自建的桌面流程
    // 里 ws.peerId == self，两种情形下这一行都对。
    return declarationFor(
        agentId = agentId,
        hostNodeId = ws.peerId,
        // D13：席位名 = kind + 职责，不再含目录（换目录不改名）。新席位默认单兵职责。
        name = AgentMemberName.base(ws.agentKind, agentId, AgentDuties.DEFAULT_SINGLE),
        defaultWsId = ws.wsId,
        previous = null,
    )
}


fun handleAddGroupMembers(groupId: String, newMemberIds: List<String>) {
    val meta = host.state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host.state.value.selfNodeId
    if (!meta.hasMember(self)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    val toAdd = newMemberIds.map { it.trim() }.filter { it.isNotEmpty() && !meta.hasMember(it) }.distinct()
    if (toAdd.isEmpty()) return

    val updatedMeta = meta.copy(
        members = meta.members + toAdd,
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host.state.value.settings
    val updatedGroups = settings.groups.map { if (it.groupId == groupId) updatedMeta else it }
    host.handlePersist(settings.copy(groups = updatedGroups))
    host.core.groupNeeds.noteKnown(updatedMeta.groupId, updatedMeta.rev)
    handleNotice(
        ConvId.group(groupId),
        NoticeKind.GroupMembersChanged,
        // 「谁把谁加进来了」—— 只说「群成员有变化」时用户得自己去成员表比对。
        NoticeDetail(actor = self, added = toAdd),
    )
    host.info(BannerMessage.GroupMembersAdded(toAdd.size))
    host.publish()

    val rt = host.runtime ?: return
    val recipients = updatedMeta.recipientsExcluding(self)
    host.scope.launch {
        for (member in recipients) {
            runCatching { rt.sendGroupInfo(member, host.groupMetaWithNicks(updatedMeta)) }
        }
        host.refreshPeers()
    }
}


fun handleRemoveGroupMember(groupId: String, memberId: String) {
    val meta = host.state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host.state.value.selfNodeId
    if (!meta.hasMember(self)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    val isOwner = meta.ownerId == self || (meta.ownerId == null && meta.creatorId == self)
    val isAdmin = meta.adminIds?.contains(self) == true
    if (!isOwner && !isAdmin) {
        host.warn(BannerMessage.GroupNoPermission)
        return
    }
    if (memberId == meta.ownerId || (meta.ownerId == null && memberId == meta.creatorId)) {
        host.warn(BannerMessage.GroupNoPermission)
        return
    }
    if (!meta.hasMember(memberId)) return

    val oldMembers = meta.members
    val updatedMeta = meta.copy(
        members = meta.members.filter { it != memberId },
        adminIds = meta.adminIds?.filter { it != memberId },
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host.state.value.settings
    val updatedGroups = settings.groups.map { if (it.groupId == groupId) updatedMeta else it }
    host.handlePersist(settings.copy(groups = updatedGroups))
    host.core.groupNeeds.noteKnown(updatedMeta.groupId, updatedMeta.rev)
    handleNotice(
        ConvId.group(groupId),
        NoticeKind.GroupMembersChanged,
        NoticeDetail(actor = self, removed = listOf(memberId)),
    )
    host.info(BannerMessage.GroupMemberRemoved)
    host.publish()

    val rt = host.runtime ?: return
    val notifyList = oldMembers.filter { it != self }
    host.scope.launch {
        for (m in notifyList) {
            runCatching { rt.sendGroupInfo(m, host.groupMetaWithNicks(updatedMeta)) }
        }
        host.refreshPeers()
    }
}


fun handleLeaveGroup(groupId: String) {
    val meta = host.state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host.state.value.selfNodeId
    if (!meta.hasMember(self)) {
        // **我已经不在这个群里了**（被移出 / 被解散）—— 此时仍然要能把这个群删掉。
        //
        // 这是一条真实的死角，不是漏网之鱼：`GroupMerge.Removed` 的既有设计是**保留**
        // 元数据（成员表里没有自己 = 对端回答「我为什么发不出话」的唯一依据），于是被移出
        // 的人手里长期留着一份「我在里面」的群元数据；而这里原来直接 `return`，
        // 那颗「退出群聊」按下去什么都不发生、连提示都没有 —— 用户被卡在一个
        // 删不掉、也发不出话的群上。
        //
        // 没有别人可通知（我不在成员表里，发出去的帧对端只会当垃圾），所以这一支
        // 只清本机、回一句如实的话。用 `GroupLeft` 那条既有措辞（「你已经被移出这个群」）
        // 而不是「已退出」—— 后者会撒谎说我主动退了。
        val settings = host.state.value.settings
        host.handlePersist(settings.copy(groups = settings.groups.filter { it.groupId != groupId }))
        host.info(BannerMessage.GroupLeft)
        host.publish()
        return
    }
    val oldMembers = meta.members
    val nextMembers = meta.members.filter { it != self }
    val updatedMeta = meta.copy(
        members = nextMembers,
        adminIds = meta.adminIds?.filter { it != self },
        ownerId = if (meta.ownerId == self) nextMembers.firstOrNull() else meta.ownerId,
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host.state.value.settings
    val updatedGroups = settings.groups.filter { it.groupId != groupId }
    host.handlePersist(settings.copy(groups = updatedGroups))
    host.core.groupNeeds.noteKnown(updatedMeta.groupId, updatedMeta.rev)
    handleNotice(ConvId.group(groupId), NoticeKind.GroupMembersChanged)
    host.info(BannerMessage.GroupLeft)
    host.publish()

    val rt = host.runtime ?: return
    val notifyList = oldMembers.filter { it != self }
    host.scope.launch {
        for (m in notifyList) {
            runCatching { rt.sendGroupInfo(m, host.groupMetaWithNicks(updatedMeta)) }
        }
        host.refreshPeers()
    }
}


/**
 * 群主**解散**这个群：对所有成员生效，不是「我退出去」。
 *
 * ## 协议上怎么做（不新增帧）
 *
 * 把成员表裁到 `[自己]`、rev+1，然后给**原来的每个成员**发一份 `group.info`。
 * 对端收到后按既有判据处理：自己不在 `members` 里 ⇒ [GroupMerge] 判 Removed，
 * 本机删除该群并提示「你已被移出群」。也就是说「解散」= 一次性的全员移出，
 * 一个字节的新协议都不需要 —— 而新增一个 `dissolved` 标志会让旧端整帧拒收
 * （AGENTS §2.2：加可选键等于要求两端同版本），代价远大于收益。
 *
 * ## 与「退出群聊」的差别
 *
 * [handleLeaveGroup] 只动自己这一份，群对别人继续存在（群主退出时还会转交群主）。
 * 这里则**先广播、再删本机**：顺序反了的话，本机已经没有这个群，广播就发不出去，
 * 别人会永远留着一个「群主跑路」的群。
 *
 * ## 聊天记录不动
 *
 * 与「退出群聊」同一条口径：历史消息留在本机（用户想清可以再删会话）。
 */
fun handleDissolveGroup(groupId: String) {
    val meta = host.state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host.state.value.selfNodeId
    if (!meta.hasMember(self)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    val isOwner = meta.ownerId == self || (meta.ownerId == null && meta.creatorId == self)
    if (!isOwner) {
        host.warn(BannerMessage.GroupNoPermission)
        return
    }
    val oldMembers = meta.members
    // 只剩自己：对端据此判 Removed；ownerId 必须仍属于 members（否则整帧拒收）。
    val dissolved = meta.copy(
        members = listOf(self),
        adminIds = emptyList(),
        ownerId = self,
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host.state.value.settings
    host.handlePersist(settings.copy(groups = settings.groups.filter { it.groupId != groupId }))
    host.core.groupNeeds.noteKnown(dissolved.groupId, dissolved.rev)
    handleNotice(ConvId.group(groupId), NoticeKind.GroupMembersChanged)
    host.info(BannerMessage.GroupDisbanded(meta.name))
    host.publish()

    val rt = host.runtime ?: return
    // 广播要在删除本机元数据**之后**发仍然有效：收件人列表与载荷都取自上面那份快照。
    val notifyList = oldMembers.filter { it != self }
    host.scope.launch {
        val payload = host.groupMetaWithNicks(dissolved)
        for (m in notifyList) {
            runCatching { rt.sendGroupInfo(m, payload) }
        }
        host.refreshPeers()
    }
}


fun handleUpdateGroupMeta(
    groupId: String,
    description: String?,
    announce: String?,
) {
    val meta = host.state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    val self = host.state.value.selfNodeId
    if (!meta.hasMember(self)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    val isOwner = meta.ownerId == self || (meta.ownerId == null && meta.creatorId == self)
    val isAdmin = meta.adminIds?.contains(self) == true
    if (!isOwner && !isAdmin) {
        host.warn(BannerMessage.GroupNoPermission)
        return
    }

    val trimmedDesc = description?.trim()?.take(Limits.GROUP_DESCRIPTION)
    val trimmedAnn = announce?.trim()?.take(Limits.GROUP_ANNOUNCE)

    val updatedMeta = meta.copy(
        description = trimmedDesc ?: meta.description,
        announce = trimmedAnn ?: meta.announce,
        rev = meta.rev + 1,
        updatedBy = self,
        updatedTs = nowMillis(),
    )
    val settings = host.state.value.settings
    val updatedGroups = settings.groups.map { if (it.groupId == groupId) updatedMeta else it }
    host.handlePersist(settings.copy(groups = updatedGroups))
    host.core.groupNeeds.noteKnown(updatedMeta.groupId, updatedMeta.rev)
    host.info(BannerMessage.GroupMetaUpdated)
    host.publish()

    val rt = host.runtime ?: return
    val recipients = updatedMeta.recipientsExcluding(self)
    host.scope.launch {
        for (m in recipients) {
            runCatching { rt.sendGroupInfo(m, host.groupMetaWithNicks(updatedMeta)) }
        }
        host.refreshPeers()
    }
}


fun handleSendToGroup(
    groupId: String,
    rawText: String,
    mentions: List<String>? = null,
    replyTo: String? = null,
    /**
     * 人类 → 插件的**单帧原子意图**（方案 §4.2，M1 已上线 `agentIntent` 键）。
     *
     * 由上下文唤起胶囊（§5.2）装填、随正文一次发出 —— 宿主原子化完成
     * 「开会话 + 派单执行」，乐观发送零网络等待。null = 普通群聊（胶囊没唤起，
     * 或者这个壳没接意图通路）。形状由调用方保证是 JSON 对象（协议校验按对象收）。
     */
    agentIntent: JsonObject? = null,
) {
    // require: / +host: 文本语法（T-G2/T-G5，D-031）：派单指令不是聊天内容 ——
    // 标签进 agentIntent.requiredTag、宿主引用进 hostNodeId，本体从正文剥掉。
    // 词法在这里，**语义解析（昵称→nodeId）在 meta 之后**（它才有成员表）。
    val (cleanedText, requireTags) = com.kapp.tenon.core.protocol.IntentTextSyntax.parse(rawText)
    val (hostCleanedText, hostRef) = com.kapp.tenon.core.protocol.IntentTextSyntax.parseHost(cleanedText)
    val body = hostCleanedText.trim()
    if (body.isEmpty()) return
    val meta = host.state.value.settings.groups.firstOrNull { it.groupId == groupId }
    if (meta == null) {
        host.warn(BannerMessage.GroupUnknown)
        return
    }
    if (!meta.hasMember(host.state.value.selfNodeId)) {
        host.warn(BannerMessage.GroupLeft)
        return
    }
    // 定向呼叫的宿主解析（T-G5）：昵称精确匹配优先，nodeId 直等兜底；解析不到
    // 就当普通文本发出去（不猜 —— 猜错宿主比不路由更糟）。
    val effectiveHostNodeId = hostRef?.let { ref ->
        meta.memberNicks?.entries?.firstOrNull { it.value == ref }?.key
            ?: meta.members.firstOrNull { it == ref }
    }
    val effectiveIntent = run {
        var base = agentIntent
        if (requireTags.isNotEmpty()) {
            base = (base ?: kotlinx.serialization.json.buildJsonObject { }).let { prev ->
                kotlinx.serialization.json.buildJsonObject {
                    prev.forEach { (k, v) -> put(k, v) }
                    put("requiredTag", kotlinx.serialization.json.JsonPrimitive(requireTags.joinToString(",")))
                }
            }
        }
        if (effectiveHostNodeId != null) {
            base = (base ?: kotlinx.serialization.json.buildJsonObject { }).let { prev ->
                kotlinx.serialization.json.buildJsonObject {
                    prev.forEach { (k, v) -> put(k, v) }
                    put("hostNodeId", kotlinx.serialization.json.JsonPrimitive(effectiveHostNodeId))
                }
            }
        }
        base
    }
    val bytes = body.encodeToByteArray().size
    if (bytes > Protocol.TEXT_TCP_LIMIT) {
        host.warn(BannerMessage.MessageTooLong(bytes, Protocol.TEXT_TCP_LIMIT))
        return
    }
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.scope.launch {
        // 本地行 id **从一开始就等于信封 id**：重发要按原 id 重放（换 id = 对端多一条），
        // 重启时的排队对账也按信封 id 匹配历史行 —— 合成 id 会让这两件事永远对不上。
        val envelopeId = newId()
        val outcome = sendGroupTextOptimistic(groupId, body, replyTo, envelopeId) {
            rt.sendGroupText(
                text = body,
                groupId = groupId,
                groupRev = meta.rev,
                members = meta.members,
                mentions = mentions,
                replyTo = replyTo,
                envelopeId = envelopeId,
                agentIntent = effectiveIntent,
            )
        } ?: return@launch
        val repliedToIsAgent = replyTo != null && RelayController.isAgentAuthored(
            host.state.value.messages[ConvId.group(groupId)].orEmpty().firstOrNull { it.id == replyTo },
        )
        // 触发 ≠ 本机执行：远端 agent 在场时这条消息已经按群协议送达那台设备，
        // 本机不跑、也不代替它回话（见 [RelayController.agentPromptRoute]）。
        val routeFacts = host.agentRouteFactsOf(groupId)
        // **先解析目标，再决定执不执行**（团队执行方案 §4.2）：@ 的是席位还是组员？
        // 组员 ⇒ 目标宿主就是该席位宿主（targetHostIsSelf 路由，§4.5）。
        val self = host.state.value.selfNodeId
        val promptTarget = resolvePromptTarget(body, meta, routeFacts.agentKinds)
        val targetHostIsSelf = when (promptTarget) {
            is PromptTarget.Seat -> promptTarget.hostNodeId == self
            is PromptTarget.Member -> promptTarget.hostNodeId == self
            is PromptTarget.Team -> promptTarget.hostNodeId == self
            else -> null
        }
        val route = RelayController.agentPromptRoute(
            text = body,
            groupId = groupId,
            isWorkspaceGroup = groupHasDeclaredAgents(groupId),
            repliedToIsAgent = repliedToIsAgent,
            localAgentPresent = routeFacts.localAgentPresent,
            remoteAgentPresent = routeFacts.remoteAgentPresent,
            agentKinds = routeFacts.agentKinds,
            targetHostIsSelf = targetHostIsSelf,
        )
        // §7.2：未唤醒且群里有 agent ⇒ 记一条 NO_WAKE（判据在 logNoWakeIfNeeded 里）。
        RelayController.logNoWakeIfNeeded(
            host.diagnosticLogSink, route, routeFacts.localAgentPresent, routeFacts.remoteAgentPresent, groupId,
        )
        // **歧义不执行**（§4.2）：同帧点了多个目标（席位+组员 / 两个组员）⇒ 一句可读的
        // 提示，绝不按 firstOrNull 的字母序随机挑一个。发送者刚打完这句话，本地提示
        // 就在 ta 的屏幕上（每个成员的发送侧都跑这一段，各自可见）。
        if (promptTarget is PromptTarget.Ambiguous) {
            handleNotice(
                ConvId.group(groupId),
                NoticeKind.TeamMentionAmbiguous,
            )
        } else if (route == AgentPromptRoute.Local) {
            val prompt = RelayController.extractAgentPrompt(body, routeFacts.agentKinds)
            // 与收方触发点同口径：点名事实从原文取，剥 @ 之后的 prompt 里已经没有它。
            val mentionedAgentKey = RelayController.mentionedAgentKeys(body, routeFacts.agentKinds).firstOrNull()
            host.scope.launch {
                host.onAgentPrompt?.invoke(ConvId.group(groupId), self, prompt, null, mentionedAgentKey)
            }
        }

        // **发送者侧的无权限提示。**
        //
        // 与接收侧那条不是重复：那里是宿主告诉自己「这台设备 @ 了我但没权」，
        // 这里是我告诉**我自己**「我的 @ 不会被它执行」。两边各自本地判定，
        // 不需要把 notice 跨设备同步（notice 是纯本地数据，不进协议）。
        //
        // 为什么必须放在发送侧：用户 @ 完盯着**自己的**屏幕，而那条执行在他人的机器上。
        // 只在他人的机器上留一行字，等于他自己永远看不到为什么没反应 —— 那依然是静默。
        //
        // **带意图的消息跳过这条本地自检**（M4）：意图帧让**宿主**用它自己的台账与执行档
        // 做授权（谁托管谁执行），本机台账对远端宿主没有发言权 —— 真机现场：宿主放行并
        // 跑完任务回了卡片，这条横幅却说「没有执行」，同屏自相矛盾。意图消息的授权与
        // 横幅由入站意图路径一处出（handleInboundAgentIntent），不再两边各说一遍。
        if (agentIntent == null) {
            val selfMsg = host.state.value.selfNodeId
            val missing = host.unathorizedAgentMention(body, groupId, selfMsg)
            if (missing != null) {
                host.handleAppendMessage(
                    MessageUi(
                        id = host.localMessageId(),
                        peerId = ConvId.group(groupId),
                        mine = false,
                        text = "",
                        at = nowMillis(),
                        notice = NoticeKind.AgentNotAuthorized,
                        noticeDetail = NoticeDetail(actor = missing),
                    ),
                )
            }
        }
    }
}


/**
 * 群文本的乐观插入：**先让气泡出现**，再等发送路径返回，然后按临时 id 就地改写。
 *
 * 与私聊那条路（见 `RelayControllerMessagesComponent.handleSend`）同形，理由也同一条：
 * 群扇出最坏要等一个慢成员的退避 + TCP 兜底，那段时间界面原本**什么都不显示** ——
 * 用户以为没点上而重复发送。先插一个 [DeliveryState.Sending] 气泡，结果回来后就地改写它，
 * 全程只有一条。
 *
 * ## 本地 id 必须**等于信封 id**
 *
 * 以前这里用 `group-local-…` 合成 id。代价是「重发」只能按合成 id 重建信封 —— 对端按
 * `(id,from)` 去重，换一个 id 就是**又一条消息**（真机桌面库读数：同一句话出现
 * `f93a27cf…` 与 `0137bff5…` 两条）；启动时的排队对账（按信封 id 匹配历史行）也永远对不上。
 * 现在调用方先生成信封 id（[envelopeId]）传进来，本地行从第一帧起就用它。
 *
 * ## 为什么终态仍恒为 [DeliveryState.Sent]（不按 [SendOutcome] 折 Queued）
 *
 * 群扇出**没有逐成员回执**：`QUEUED` 只表示「至少有一个成员的那一份还在队列里」，
 * 而另一些成员可能已经 ACK。把它折成「未送达」会让已经收到的人被标错；而重发入口
 * 只挂在 [DeliveryState.Failed] 上，群消息不会因为排队变成 Failed。逐成员回执要改协议，
 * 不在这条修复的范围里。
 *
 * @param envelopeId 这次发送的信封 id；null = 没有信封的场景（预览/单测），退回本地 id。
 * @param send 真正的发送动作（生产路径传 `rt.sendGroupText` 的调用）。抽成形参是为了让
 *   「先插后等」这个**时序**能被闸门式测试钉住，而不是只能断言终态。
 * @return 发送路径的结论；抛异常时为 null（那条气泡已收尾成 [DeliveryState.Failed]）。
 */

internal suspend fun sendGroupTextOptimistic(
    groupId: String,
    body: String,
    replyTo: String?,
    envelopeId: String? = null,
    /**
     * 兜底硬超时（毫秒）。默认 [SEND_HARD_TIMEOUT_MS]。
     *
     * 做成形参而不是直接读常量，是为了让「挂死必须折成失败」这条能用**毫秒级**验证 ——
     * 用真超时测要等 60 秒，那种用例没人会在本地跑，等于没有。
     */
    hardTimeoutMs: Long = SEND_HARD_TIMEOUT_MS,
    /**
     * 这条信封此刻是否**还在待发队列里**。null = 问 core（[NodeRuntime.pendingOutgoingMessages]）。
     *
     * 做成可注入的形参只为一件事：让「超时但已入队 ⇒ Queued」这条能用毫秒级验证 ——
     * 起一个真节点、造一条真队列只为了断言一个分支，那种用例太脆、没人会跑。
     */
    queuedProbe: ((String) -> Boolean)? = null,
    send: suspend () -> SendOutcome,
): SendOutcome? {
    val convId = ConvId.group(groupId)
    val optimistic = MessageUi(
        id = envelopeId ?: host.localMessageId(),
        peerId = convId,
        mine = true,
        text = body,
        at = nowMillis(),
        delivery = DeliveryState.Sending,
        senderId = host.state.value.selfNodeId,
        replyTo = replyTo,
    )
    host.handleAppendMessage(optimistic)
    // **兜底硬超时**：`runCatching` 只接异常，**接不住「永不返回」。**
    //
    // 真机现场：一条群消息停在 `Sending` 转了 5 分半仍是 `Sending`；
    // 而 `DeliveryState.Sending` 的 KDoc 承诺过「不会永远转下去」，且它**没有重发出口**
    // （只有 `Failed` 才挂重发）。所以「永远转」既欺骗又不可恢复。
    //
    // 值取 [GROUP_FANOUT_BUDGET_MS] 的 4 倍：内层预算到点会把未完成的记成
    // `QUEUED` 正常返回，所以**正常路径用不到这条兜底**；能走到这里的只有真正的挂死。
    // 给足 60 秒是为了不误伤慢网（UDP 退避 1/2/4s 再逐端点试 TCP 各 7s，
    // 跨网最坏在 40 秒量级），一旦超时就走下面的 `outcome == null` 分支 ——
    // 而那个分支现在会先问队列：还在队列里就是「已排队」，不是失败。
    val outcome = runCatching {
        withTimeoutOrNull(hardTimeoutMs) { send() }
    }
        .onFailure {
            // 抛了什么必须留痕：这条分支以前只弹一个「发送失败」横幅，异常本身被吞掉 ——
            // 真机上「消息标失败、对端却收到了」因此只能靠两端库对比才看得出来。
            println(
                "[send] group=$groupId id=${optimistic.id.take(8)} threw " +
                    "${it::class.simpleName}: ${it.message}",
            )
            host.warn(
                it.messageOrNull()?.let(BannerMessage::SendFailed) ?: BannerMessage.UnknownError,
            )
        }
        .getOrNull()
    if (outcome == null) {
        // **超时 ≠ 没发出去。**
        //
        // `outcome == null` 只有两种来源：抛了异常，或者 60 秒兜底到点。后者在真机上的
        // 典型形状是「扇出 10 秒预算耗尽 → 重试用尽 → 硬超时」，而那一帧**通常已经送达**
        // （对端库里有这条）。一律标 [DeliveryState.Failed] 会挂出「重发」按钮，用户据此
        // 反复手打，队列越积越长、下一次更慢 —— 一个自我加速的假失败循环。
        //
        // 判据不猜：**问队列**。这条信封还在待发队列里 ⇒ 它真的是「已排队/在补发」，
        // 那是 [DeliveryState.Queued] 的语义；队列里也没有才是不折不扣的失败。
        val stillQueued = (queuedProbe ?: { id ->
            host.runtime?.pendingOutgoingMessages()?.any { it.msgId == id } == true
        }).invoke(optimistic.id)
        val settled = if (stillQueued) DeliveryState.Queued else DeliveryState.Failed
        println(
            "[send] group=$groupId id=${optimistic.id.take(8)} settled=$settled " +
                "queued=$stillQueued timeout=$hardTimeoutMs",
        )
        host.replaceMessage(convId, optimistic.id, optimistic.copy(delivery = settled))
        return null
    }
    // 按**同一个 id** 就地改写终态：id 从插入那一刻就等于信封 id，不需要再换。
    host.replaceMessage(convId, optimistic.id, optimistic.copy(delivery = DeliveryState.Sent))
    return outcome
}


fun handleOnGroupInfo(envelope: Envelope, incoming: GroupMeta) {
    val self = host.state.value.selfNodeId
    val local = host.state.value.settings.groups.firstOrNull { it.groupId == incoming.groupId }
    when (val decision = GroupMerge.decide(local, incoming, self)) {
        is GroupMergeDecision.Apply -> handleApplyGroupMeta(local, decision.meta, envelope.from)

        is GroupMergeDecision.Removed -> {
            handleApplyGroupMeta(local, decision.meta, envelope.from)
            handleNotice(ConvId.group(decision.meta.groupId), NoticeKind.RemovedFromGroup)
        }

        is GroupMergeDecision.Reject -> {
            // **可观测**：群元数据被拒时把原因说出来。
            //
            // 这条链路以前是静默的 —— 真机上「手机上看不到 agent」排查了几轮都没结论，
            // 因为「帧被拒」与「帧没到」在外部看起来完全一样（本地 virtualAgents 都是空）。
            // 一行日志就能把这两种情况分开。
            // 拒绝原因不丢：用 noteRequested 的同一份台账之外的路径记不下来（没有
            // 可用的日志通道），所以这里只保留判据本身；排查时看 GroupMerge 的 why。
            host.core.groupNeeds.noteRequested(incoming.groupId, maxOf(incoming.rev, local?.rev ?: 0))
        }
    }
}


fun handleApplyGroupMeta(local: GroupMeta?, next: GroupMeta, from: String) {
    val convId = ConvId.group(next.groupId)
    val settings = host.state.value.settings
    val replaced = if (local == null) settings.groups + next
    else settings.groups.map { if (it.groupId == next.groupId) next else it }
    host.handlePersist(settings.copy(groups = replaced))
    host.core.groupNeeds.noteKnown(next.groupId, next.rev)
    if (local != null) {
        val kind = if (local.name != next.name) NoticeKind.GroupRenamed
        else NoticeKind.GroupMembersChanged
        // **细节用差集算**，不信任何载荷里自报的「我加了谁」——
        // 那一份可能与实际的成员表不一致，而这里拿到的是落库前的两份真值。
        val before = local.members.toSet()
        val after = next.members.toSet()
        handleNotice(
            convId,
            kind,
            NoticeDetail(
                actor = next.updatedBy,
                added = (after - before).toList(),
                removed = (before - after).toList(),
            ),
        )
    }
    host.refreshPeers()
    // 兑现别处（手机）建群时提的目录请求（UX 规范 §2.6 手机侧）：那份 `group.info` 的席位
    // 声明里已经带着用户点选的 wsId，缺的只是宿主把本机路径找回来、登记、设指针。
    // 没有目录端口 / 指针已设 / 本机席位多义，都在判据里整群跳过。
    host.adoptRequestedGroupDirectories()
    if (from.isEmpty()) return
}


fun handleOnGroupNeed(envelope: Envelope, groupId: String) {
    val meta = host.state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: return
    if (!meta.hasMember(host.state.value.selfNodeId)) return
    val rt = host.runtime ?: return
    host.scope.launch { runCatching { rt.sendGroupInfoReply(envelope.from, meta) } }
}


fun handleSyncGroupMetaIfNeeded(rt: NodeRuntime, envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val groupId = (payload["groupId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return
    // groupRev 在协议里是**数字**：`Codec.intOf` 对字符串一律返回 null，
    // 群文本消息的载荷校验（`CodecMsg.validateMsg` → `GROUP_TEXT_KEYS` 里的 `groupRev`）
    // 就是用它判的，发送侧也写数字（`Codec.groupTextPayload(groupRev = meta.rev)`）。
    // 这里原来写成 `takeIf { it.isString }` ⇒ 真机上的数字 rev 一律读成 0 ⇒
    // `beginRequest(groupId, 0)` 在游标已经登记过本地 rev 后恒为假 ⇒ **need 索取这条路径
    // 在实机上从来没跑过**。2026-10-10 group 夹具 `group-need-on-newer-rev` 逮到它：
    // 手机 ACK 了 rev=2 的群消息（本地 rev=1）却不回 need。
    val rev = Codec.intOf(payload["groupRev"])?.toInt() ?: 0
    // beginRequest = 「该不该问」+「问过就记」，成对做。只判不记的话，对端没回 host.info 时
    // 游标停在原地，下一条同 rev 的群消息会再问一次（见 GroupNeedTracker.beginRequest）。
    if (!host.core.groupNeeds.beginRequest(groupId, rev)) return
    host.scope.launch { runCatching { rt.sendGroupNeed(envelope.from, groupId) } }
}


fun handleOnGroupControl(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val op = (payload["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
    when (op) {
        GroupOps.INFO -> Codec.decodeGroupInfo(envelope.payload)?.let { handleOnGroupInfo(envelope, it) }
        GroupOps.NEED -> Codec.decodeGroupNeed(envelope.payload)?.let { handleOnGroupNeed(envelope, it) }
        else -> Unit
    }
}




fun handleNotice(convId: String, kind: NoticeKind, detail: NoticeDetail? = null) {
    host.handleAppendMessage(
        MessageUi(
            id = host.localMessageId(),
            peerId = convId,
            mine = false,
            text = "",
            at = nowMillis(),
            notice = kind,
            // 细节是可选的：老的落库记录没有它，渲染端会退回那句中性文案。
            noticeDetail = detail?.takeIf { !it.isEmpty },
        ),
    )
}
}

/**
 * 触发路由要的**群内 agent 事实**：有哪些可点名的键、agent 在本机还是远端。
 *
 * - `localAgentPresent` 要求**本机真的挂了执行器**（`RelayController.onAgentPrompt` 非空）
 *   且本机是这个群的 agent 宿主 —— 只凭"群里有 agent"就在本机跑，正是「@dsh 收到本地
 *   mock」那条路；
 * - `remoteAgentPresent` 只看**群成员里的 agent 宿主**，不看消息发送者：本机 agent 的
 *   历史回复（`agent:native`）也在消息里，拿它判"远端在场"会让本机永久让路。
 */
internal class GroupAgentRouteFacts(
    val agentKinds: Set<String>,
    val localAgentPresent: Boolean,
    val remoteAgentPresent: Boolean,
)

internal fun RelayController.agentRouteFactsOf(groupId: String): GroupAgentRouteFacts {
    val st = state.value
    val ctx = st.senderContext(ConvId.group(groupId))
    val hosts = agentHostsInGroup(ctx)
    val self = st.selfNodeId
    return GroupAgentRouteFacts(
        agentKinds = agentKindsInGroup(ctx),
        localAgentPresent = onAgentPrompt != null && hosts.contains(self),
        remoteAgentPresent = hosts.any { it != self },
    )
}


/**
 * 元数据提交的**唯一入口**（团队创建方案 §4.1）：落库 + 逐成员广播。
 *
 * ## 为什么必须是唯一入口
 *
 * 写元数据的路径各写一遍「persist + 逐成员 sendGroupInfo」，第三份抄本迟早漏掉
 * 其中的关键步骤（昵称补齐、声明重建、need 游标登记、发送侧预算自检）—— 每一步
 * 漏了都是真机撞过的事故。工作区授权与团队编制两个入口都只调它；其它五条既有
 * 写路径（建群/加人/移人/退群/改资料）保持原样，但它们要走收口时迁过来即可。
 *
 * 顺序与纪律：
 * 1. **发送侧预算自检**（§4.5）：编码后走 `CodecGroup.validateGroupMeta` 同一条判据，
 *    不过就拒收在落库之前 —— 存进去只会让下一次广播变成毒帧（framing peer-closed
 *    + 队列重放同一帧）。正常路径先过了 [TeamRosterValidation]，这条是 bug 防线。
 * 2. 落库（handlePersist）。
 * 3. need 游标登记（`noteKnown`）：我们现在握有这个 rev 的完整元数据（建群路径
 *    的 KDoc 记过漏登记的下场：每条同 rev 群消息都白跑一个 need 往返）。
 * 4. 广播：发送前必经 [groupMetaWithNicks]（补昵称 + 重建本机声明 —— 声明重建
 *    已经带团队字段回填，见 `virtualAgentsFor`）。
 */
internal fun RelayController.commitGroupMetaUpdate(updated: GroupMeta) {
    if (!com.kapp.tenon.core.protocol.CodecGroup.validateGroupMeta(updated)) {
        warn(BannerMessage.GroupMetaRejected)
        return
    }
    handlePersist(
        _state.value.settings.copy(
            groups = _state.value.settings.groups.map { if (it.groupId == updated.groupId) updated else it },
        ),
    )
    core.groupNeeds.noteKnown(updated.groupId, updated.rev)
    // 先在扩展接收者上算好带昵称的那份，再进 launch：lambda 里的 this 不是它。
    val broadcast = groupMetaWithNicks(updated)
    val self = updated.updatedBy
    scope.launch {
        for (m in updated.members.filter { it != self }) {
            runCatching { runtime?.sendGroupInfo(m, broadcast) }
        }
    }
    publish()
}

/**
 * 保存 / 关闭某个席位的团队编制（团队创建方案 §4.3）。
 *
 * **本函数只是入口适配**：把参数翻译成 [GroupDeclarationCommand.SetTeamRoster] 交给
 * [applyDeclaration]，再把结果映射成横幅。校验（token 池 / 每群一个团队 / 组员上限）、
 * rev 推进、落库与广播都在那条唯一写路径里 —— 三个入口共用一份，不再各写一遍。
 *
 * @param groupId 群 id。
 * @param agentId 目标席位的 agent 身份（席位定位键 = `(groupId, hostNodeId, agentId)`，
 *   本机 host 由 selfNodeId 隐含；大小写不敏感匹配）。
 * @param roster 组员草稿；**null 或空清单 = 关闭团队**（`entityType` 回 single、
 *   组员清空）—— 与入驻/撤回同一节奏：一次保存 = 一次 rev+1、一次广播。
 */
fun RelayController.handleSetGroupTeamDeclaration(
    groupId: String,
    agentId: String,
    roster: List<TeamMemberDraft>?,
) {
    val result = applyDeclaration(
        GroupDeclarationCommand.SetTeamRoster(
            groupId = groupId,
            agentId = agentId,
            roster = roster,
            actor = DeclarationActor.Human(_state.value.selfNodeId),
            origin = DeclarationOrigin.GROUP_INFO,
        ),
    )
    when (result) {
        is DeclarationResult.Applied -> info(BannerMessage.TeamRosterSaved)
        is DeclarationResult.Conflict -> warn(BannerMessage.TeamRosterInvalid)
        is DeclarationResult.Rejected -> when (result.reason) {
            DeclarationRejection.GROUP_UNKNOWN -> warn(BannerMessage.GroupUnknown)
            DeclarationRejection.NOT_MEMBER -> warn(BannerMessage.GroupLeft)
            DeclarationRejection.SEAT_MISSING -> warn(BannerMessage.TeamSeatMissing)
            DeclarationRejection.ROSTER_INVALID, DeclarationRejection.NAME_INVALID ->
                warn(BannerMessage.TeamRosterInvalid)
            // 档案化团队：名册归档案，群内整表替换被拒 —— 必须说出来，否则用户以为保存成功。
            DeclarationRejection.TEAM_PROFILE_BACKED -> warn(BannerMessage.TeamProfileManaged)
            // 这条入口不写档案（`SetTeamRoster` 只改本群席位），档案缺失与否与它无关：
            // 真出现 TEAM_UNKNOWN 是调用方拼错了命令，落横幅只会误导。
            DeclarationRejection.TEAM_UNKNOWN -> Unit
            // 员工档案同理：`SetTeamRoster` 不碰员工引用。
            DeclarationRejection.EMPLOYEE_UNKNOWN,
            DeclarationRejection.EMPLOYEE_EXISTS,
            -> Unit
            // 参数不自洽与落盘被拒：commitGroupMetaUpdate 已经 warn 过（GroupMetaRejected），
            // 这里不再叠一条 —— 两条横幅会被读成两个故障。
            DeclarationRejection.ARGUMENT_INVALID,
            DeclarationRejection.BASE_REV_REQUIRED,
            DeclarationRejection.PERSIST_REJECTED,
            -> Unit
        }
    }
}

/**
 * 发群元数据时**带上本机已知的成员昵称**（`GroupMeta.memberNicks`）。
 *
 * ## 为什么在发送侧补，而不是在元数据生成时写死
 *
 * 昵称不是群的属性，是**本机知识**：同一份 `GroupMeta` 在不同设备上能填出来的成员昵称不一样
 * （谁认识谁取决于各自的发现历史）。写进群元数据并随 `group.info` 同步，正是为了让**跨网、
 * 发现不到对方的设备**也能显示出真名 —— 真机现场（红米走蜂窝）此前只能显示 `f6a7b8c9`
 * 这样的 nodeId 前 8 位，而桌面端四个成员都正常。
 *
 * 这里只填**本机确实知道**的那些；不知道的不编（对方会用自己的知识补，或者退回 nodeId —— 
 * 那是诚实的降级，不是谎）。自己一定在 `members` 里，所以自己的昵称也带上。
 */
internal fun RelayController.groupMetaWithNicks(meta: GroupMeta): GroupMeta {
    val state = _state.value
    val known = buildMap {
        put(state.selfNodeId, state.settings.nick.ifBlank { "" })
        for (peer in state.peers) put(peer.nodeId, peer.displayName)
    }
    val nicks = meta.members.mapNotNull { id ->
        val nick = known[id]?.trim().orEmpty()
        if (nick.isEmpty()) null else id to nick
    }.toMap()
    // agent 的名字来源（PPE-2 的 `virtualAgents`）：这里是**唯一**的写入点。
    //
    // 之前这个字段只有读、没有写 —— 于是 `SenderIdentity` 里那条「显示声明名」的分支
    // 永远取不到值，agent 只能显示成标识符（`dsh` / `native`），用户看到的就是
    // 「一群机器人名字都是驱动类型」。名字只能由**宿主**知道（它才知道自己跑着什么），
    // 所以和昵称一样在发送侧补、随 group.info 同步给其它设备。
    val agents = virtualAgentsFor(meta)
    // **`agents == null` 必须保持原值，不能 copy 成 null。**
    //
    // 真机事故：`virtualAgentsFor` 的 KDoc 写着「返回 null 表示本机没有要声明的，
    // 调用方据此保持 `meta.virtualAgents` 原样（不覆盖别的设备已经声明进来的）」，
    // 而这里无条件 `copy(virtualAgents = agents)` —— 于是**入驻声明被这一行抹掉**。
    //
    // 现场：桌面端 `settings.json` 里明明有 `[dsh-teahouse-mobile]`，但队列里的
    // `group.info` 帧 rev=7 却没有 `virtualAgents` 字段；手机端收到后群里 0 个 agent，
    // 列表空、@ 候选空、气泡署名退回 kind（`dsh`）。用户问「是不是清除数据就好了」——
    // 不是，发出去的帧本身就是坏的，清完重收还是同样缺字段的帧。
    //
    // 两种 null 是不同的事实：`agents != null` = 本机有要声明的（覆盖）；
    // `agents == null` = 本机没有（**不动**，别人的声明还在里面）。
    //
    // 判据本体在 core 的 [withLocalExtras]：这里不再重写一遍。它必须能被单独测 ——
    // 写在这里时只能靠真机撞见，而它已经撞见过两次（昵称空、localAgents 空）。
    return meta.withLocalExtras(nicks, agents)
}

/**
 * 本机为群里这些 agent 声明的身份（`virtualAgents`）—— **P2 之后只携带，不再生成**。
 *
 * ## 为什么自动生成被删除（防复活）
 *
 * 旧模型在这里从本机工作区清单为每个 `(agentId, wsId)` 自动铸席位（「15 个 ws =
 * 15 个可 @ 的对象」），「加 agent = 选目录」正是它喂出来的。新模型（决策稿 D1/D4/D6/D14）：
 * **席位 = `(groupId, hostNodeId, agentId)`，一个 agent 一个席位**，只由显式流程写入
 * （入驻流程 / 指针投影重建）；执行点 = 群项目目录解析出的那个 wsId，写入目标不许
 * per-agent 分叉。把自动生成放回来的每一次尝试，都会在下一次广播把用户没入驻的
 * 目录悄悄加回群里、把指针投影冲掉 —— 那是静默元数据分叉的形状。
 *
 * 投影刷新只走 `handleRebuildGroupSeatProjections`（指针变化 / 重新解析时显式触发）。
 *
 * 返回 null 表示「本机没有要声明的」，调用方据此保持 `meta.virtualAgents` 原样
 * （不覆盖别的设备已经声明进来的）。
 */
private fun RelayController.virtualAgentsFor(meta: GroupMeta): List<VirtualAgentMember>? {
    val self = _state.value.selfNodeId
    val hasSelfSeats = meta.virtualAgents.orEmpty().any { it.hostNodeId == self }
    // 有本机席位 → 原样返回（withLocalExtras 判等后不会产生拷贝）；没有 → null。
    // 两种情形都是「只携带」：广播不再改写任何声明。
    return if (hasSelfSeats) meta.virtualAgents else null
}

/*
 * 群消息发送的**兜底硬超时**。
 *
 * 内层 fan-out 有 10 秒预算（`GROUP_FANOUT_BUDGET_MS`），到点会把未完成的成员记成
 * `QUEUED` 并正常返回 —— 所以正常路径**根本用不到**这条超时。
 *
 * 它是给「send() 因为某个 await 永不完成而挂死」这种形态准备的：那种情况下
 * `runCatching` 接不住（它只处理异常），消息会永远停在 `Sending`，
 * 而 `Sending` 在界面上**没有重发出口**（只有 `Failed` 才挂）。
 *
 * 取 60 秒：UDP 退避 1/2/4s 再逐端点试 TCP 各 7s，跨网最坏在 40 秒量级，
 * 留出余量以免误伤慢网。
 */
internal const val SEND_HARD_TIMEOUT_MS: Long = 60_000L

/**
 * **补发台账：（群 × 对端）→ 已经发到哪个 rev。**
 *
 * ## 为什么需要它（真机事故）
 *
 * 第一版 `rebroadcastGroupMeta` 挂在「在线对端集合」的状态流上，没有任何去重，于是
 * 形成了**自我触发的循环**：补发 → 队列变化 → 对端 `lastSeen` 抖动 → 在线集合变 →
 * 又补发。真机现场：每 15 秒一轮，队列瞬间涨到 286 条 `group.info` 帧，全是同一个 rev。
 *
 * 而那个抖动是真实存在的：`v1/peers` 连查三次，在线集合在
 * `[A,B]` 与 `[A,B,C,D]` 之间来回跳 —— `distinctUntilChanged()` 因此每次放行。
 *
 * ## 判据
 *
 * 记 `(nodeId, groupId) → 已发送的 rev`。同一个 rev 对同一个对端**只发一次**：
 *  · 对端上线 → 补一次 ✅
 *  · 状态抖动（同 rev）→ 已记过 → 跳过 ✅
 *  · 群元数据真的变了（rev 更大）→ 重新补 ✅
 *
 * 与 `GroupNeedTracker` 的「问过就记」是同一条思路，只是方向相反（那边记「我问过谁」，
 * 这边记「我发给谁」）。
 *
 * 只活在内存里：重启后重新补一次是**正确**的（对端确实可能错过了），代价是每端一帧。
 */
private val groupMetaSentRev = mutableMapOf<Pair<String, String>, Int>()

/**
 * 与 [groupMetaSentRev] 配套：**上次发出去的那份带不带 virtualAgents**。
 *
 * 只按 rev 去重时，「发过一次 rev 7」就把这个对端永久标记成已发 —— 即使那次发出去的是
 * **修复前编出来的、缺 virtualAgents 的那一份**。真机现场：红米收到过 100 条 group.info，
 * 一条都不带 agent 声明；而补发台账坚持「rev 7 已经发过了」，不再补。
 *
 * 同一个 rev 的内容**可以升过级**。判据要带上这一位，内容升级就能再发一次。
 */
private val groupMetaSentHadAgents = mutableMapOf<Pair<String, String>, Boolean>()

/**
 * 与 [groupMetaSentHadAgents] 配套：**上次发出去的那份带不带团队**。
 *
 * 团队创建方案 §4.2 的第二维：只按「有没有 agents」去重时，同一个 rev 的内容从
 * 「单兵」升级为「团队」（保存编制后的同 rev 补发场景）会被台账当成「没变」而跳过
 * —— 对端永远看不到团队。与 `groupMetaSentHadAgents` 同款先例：同 rev 的内容
 * 升级要能再发一次。
 */
private val groupMetaSentHadTeams = mutableMapOf<Pair<String, String>, Boolean>()

/**
 * **同一个 rev 对同一个成员只补发一次。**
 *
 * 抽成纯函数是为了能被单测钉住 —— 这条判据是「补发不会变成无限循环」的**唯一**保障。
 * 它一旦破掉，症状是每 15 秒一轮、队列在几分钟内涨到几百帧（真机现场 286 条），
 * 而只看代码是看不出来的。
 *
 * @param alreadySent 台账查询；默认读进程级台账，测试传自己的实现。
 * @return 本轮真正该发的成员。
 */
internal fun freshRecipients(
    targets: List<String>,
    groupId: String,
    rev: Int,
    /** 这一次要发的那份**带不带** agent 声明。 */
    hasAgents: Boolean,
    /** 这一次要发的那份**带不带**团队（isTeam 口径；方案 §4.2 的第二去重维）。 */
    hasTeams: Boolean,
    alreadySent: (String) -> Int? = { member -> groupMetaSentRev[member to groupId] },
    /** 上次发出那份带不带 agent 声明；缺省 false（修复前发出的都不带）。 */
    alreadySentHadAgents: (String) -> Boolean? = { member -> groupMetaSentHadAgents[member to groupId] },
    /** 上次发出那份带不带团队；缺省 false。 */
    alreadySentHadTeams: (String) -> Boolean? = { member -> groupMetaSentHadTeams[member to groupId] },
): List<String> = targets.filter { member ->
    val sameRev = alreadySent(member) == rev
    val sameAgents = (alreadySentHadAgents(member) ?: false) == hasAgents
    val sameTeams = (alreadySentHadTeams(member) ?: false) == hasTeams
    !(sameRev && sameAgents && sameTeams)
}
/**
 * **把本机已知的群元数据发给需要的成员。**
 *
 * 与变更时那次广播的区别：这里带**去重**，可以安全地挂在「对端上线」这类频繁触发点上。
 *
 * 触发点见 `DesktopExtension` 的补发协程（启动 / 对端上线 / 换网）。
 */
fun RelayController.rebroadcastGroupMeta() {
    val st = _state.value
    val self = st.selfNodeId
    val runtime = runtime ?: return
    // **不按「本机认为它在线」筛目标。**
    //
    // 第一版写成 filter { it.online }，理由看似无懈可击：「离线对端的帧会进队列，
    // 队列会在它回来时补发，这里再发一份就是重复」。**那个理由不成立** —— 补发是在
    // 判定它离线的那一刻执行的，帧根本没排进队列，所以它回来时队列里没有这一条：
    // 离线对端永远拿不到。
    //
    // 真机现场（Pixel）：它自己的节点表显示本机在线，而本机 /v1/peers 说它 online=false
    // —— 单向在线。按 online 筛就把一个活着的、能收包的对端排除在外。
    //
    // 去重靠的是 (对端, 群) -> 已发 rev 台账，不是在线判定。
    for (group in st.settings.groups) {
        // 只发自己确实在里面的群：不在群里却有它的元数据（被移出后残留）不该继续外发。
        if (self !in group.members) continue
        val targets = group.members.filter { it != self }
        if (targets.isEmpty()) continue
        val payload = groupMetaWithNicks(group)
        val rev = group.rev
        val hasAgents = !payload.virtualAgents.isNullOrEmpty()
        val hasTeams = payload.virtualAgents.orEmpty().any { it.isTeam }
        val fresh = freshRecipients(targets, group.groupId, rev, hasAgents, hasTeams)
        if (fresh.isEmpty()) continue
        // 先记账再发：发失败也算「试过了」，否则一次失败会让它每轮重试 —— 那正是循环的入口。
        for (member in fresh) {
            groupMetaSentRev[member to group.groupId] = rev
            groupMetaSentHadAgents[member to group.groupId] = hasAgents
            groupMetaSentHadTeams[member to group.groupId] = hasTeams
        }
        scope.launch {
            for (member in fresh) {
                runCatching { runtime.sendGroupInfo(member, payload) }
            }
        }
    }
}
