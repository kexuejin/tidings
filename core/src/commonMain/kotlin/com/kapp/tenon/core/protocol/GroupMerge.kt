// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/** 群内角色，对应 TS `groupRoleOf`。`Left` = 不在成员表里。 */
enum class GroupRole { Owner, Admin, Member, Left }

/** 收到一份 `group{op:"info"}` 之后该做什么。 */
sealed interface GroupMergeDecision {
    /** 收下这份元数据（本地没有，或它比本地新且发起者有权改）。 */
    data class Apply(val meta: GroupMeta) : GroupMergeDecision

    /**
     * 发起者把我们移出了群，且他有权这么做。
     *
     * 带上 [meta] 而不是只给一个信号：本地**要把它存下来**。存下来的理由不是"还想留着
     * 这个名字好看"，而是它是唯一能回答"我为什么发不出话"的东西 —— 一份成员表里没有
     * 自己的群，会让发送路径与 `op:need` 应答路径都自动闭嘴（两处都查
     * [GroupMeta.hasMember]），而会话列表还留着群名与历史。删掉它反而更糟：历史还在
     * `messages` 里，会话键 `group:<id>` 会退化成标题里的一串 `group:ab`。
     */
    data class Removed(val meta: GroupMeta) : GroupMergeDecision

    /** 不收。[why] 是给人看的英文短语，写日志/测试断言用，不进界面。 */
    data class Reject(val why: String) : GroupMergeDecision
}

/**
 * 远端群元数据的合并策略（协议 §7.4）。
 *
 * ### 为什么在 core
 * 这是**协议语义**不是界面偏好：`(rev, updatedTs)` 的 LWW、角色矩阵、成对字段的补齐
 * 都由 §7.4 定义，与"哪个平台在跑"无关。放在 core 也让它能同时在 JVM 与 iOS 上跑单测。
 *
 * ### 本仓实现的是**子集**，如实写清
 * TS 侧 `groups.ts:519-651` 的 `canApplyRemoteInfo` 有约 130 行权限矩阵，覆盖群主/管理员/
 * 管理密码/`creatorIp` 来源校验/离组重邀的按步重放。本仓实现下面这条**一句话规则**：
 *
 * > **只有群主与管理员能改群；普通成员唯一能做的是"只增不减地邀请新人"，**
 * > **以及改写自己宿主（`hostNodeId == updatedBy`）的那条声明。**
 *
 * 第二条是 PPE-2 / 团队创建方案 §5.2 的「宿主自声明主权」：声明是自述的展示元数据
 * （agent/团队/工作区白名单），不授予执行权 —— 允许成员声明"自己机器上的 agent"
 * 与既有「机主主权」同向；没有它，成员宿主声明自己的席位/团队会被每个对端整帧拒收。
 * **语义变更的兼容性代价**：旧版本节点的合并逻辑没有这条分支，仍会拒收成员发起的
 * rev+1 —— 团队声明的可见性依赖组内成员版本一致，见方案 §5.3 的混合版本窗口。
 *
 * 外加三条不变量：`sameManagementIdentity`（谁都改不动建群来源与管理密码身份）、
 * `rev` 严格递增、自己不在新成员表里时按"被移出"处理。
 *
 * **明确不实现的**：管理密码（`adminSecretHash`）路径 —— 有密码的群里，一次由密码持有者
 * 发起、但他既不是群主也不是管理员的变更，我们**会拒收**。代价是我们的副本停在旧 rev，
 * 表现为群名/成员表偏旧；`GroupNeedTracker` 保证这不会变成每条群消息一次的 need/info 往返。
 * 另一处放宽：`creatorId` 相等即视为管理员级，**不校验来源 IP**（TS 还要 `sourceIp === creatorIp`）。
 *
 * 为什么不照抄那 130 行：本端**没有群管理界面** —— 不改名、不设管理员、不踢人、不设密码，
 * 所以那条矩阵里大半分支在我们这里永远不会被走到；而照抄意味着把一堆我们无法在真机上
 * 验证的分支写进代码，正是本仓记过的"实现了但没人调用"。等真要做群管理时再补。
 */
object GroupMerge {

    /** 角色判定，对应 TS `groupRoleOf`。 */
    fun roleOf(meta: GroupMeta, nodeId: String): GroupRole {
        if (nodeId !in meta.members) return GroupRole.Left
        if (meta.ownerId == nodeId) return GroupRole.Owner
        if (meta.adminIds?.contains(nodeId) == true) return GroupRole.Admin
        return GroupRole.Member
    }

    /**
     * 规范化一份**已通过** [Codec.validateGroup] 的入站元数据。
     *
     * 三件事，都对应 TS `normalizeGroupMeta` 的必要部分：
     * 1. 成员去重（TS 只对 `adminIds` 查重，`members` 允许重复 —— 重复的成员会让
     *    "逐成员单播"给对方发两遍）；
     * 2. `adminIds` 的 `null` 归一成空列表（本仓用 `null` 表示"旧包没带这个字段"，
     *    但下游只想判空）；
     * 3. **补齐 `ownerId`**：旧包（v0.43 之前）没有群主概念，不补的话 [roleOf] 对所有人
     *    都返回 `Member`，于是这个群在我们这儿**谁都改不动**，名字和成员表会永远停在收到
     *    的第一版。补齐顺序与 TS 一致：`creatorId` → `updatedBy` → 第一个成员。
     */
    fun normalize(meta: GroupMeta): GroupMeta {
        val members = meta.members.filter { it.isNotEmpty() }.distinct()
        val adminIds = meta.adminIds?.filter { it in members && it != meta.ownerId }?.distinct()
        val ownerId = meta.ownerId?.takeIf { it in members }
            ?: meta.creatorId?.takeIf { it in members }
            ?: meta.updatedBy.takeIf { it in members }
            ?: members.firstOrNull()
        return meta.copy(members = members, ownerId = ownerId, adminIds = adminIds ?: emptyList())
    }

    /**
     * 决定怎么处理一份入站 `group{op:"info"}`。
     *
     * [local] 为 null 表示本地没有这个群（被拉进群，或换了台设备）。
     * [selfId] 是本机 nodeId。
     *
     * **两侧都会先 [normalize]**，调用方传原样存下来的旧快照也行。这不是顺手：
     * 只规范化入站那一侧会让比较变得**不对称** —— 旧包本地 `ownerId == null`、入站被补成
     * `ownerId == creatorId`，于是 `rolesChanged` 对一个其实没人动过角色的帧返回 true，
     * 结果是"旧群一旦有人改名就被我们拒收"。这个 bug 在 `aLegacyCreatorWithoutAnOwnerRoleStillCountsAsAManager`
     * 上红过一次。
     */
    fun decide(local: GroupMeta?, incoming: GroupMeta, selfId: String): GroupMergeDecision {
        val next = normalize(incoming)
        val current = local?.let { normalize(it) }

        if (next.rev < 1) return GroupMergeDecision.Reject("rev-below-one")

        // 本地没有：这是"被拉进群"。只收自己确实在里面的群 —— 别人的群不是我们的事，
        // 存下来只会在会话列表里多一个我们永远发不出消息的条目。
        if (current == null) {
            return if (selfId in next.members) GroupMergeDecision.Apply(next)
            else GroupMergeDecision.Reject("not-a-member")
        }

        // 建群来源与管理密码身份不可改：改得动它就等于能冒充群主。
        if (!sameManagementIdentity(current, next)) return GroupMergeDecision.Reject("management-identity")

        // rev 严格递增。相等也拒 —— 同一 rev 的两份不同快照没法排序，先到的那份说了算。
        //
        // **唯一的例外：本地那份缺 `virtualAgents`、而收到的有。**
        //
        // `virtualAgents` 是后加的字段（PPE-2），所以**在这个字段存在之前落库的元数据**
        // 里它必然是空的；而对端那份是同一个 rev、内容却完整。真机现场：手机本地是
        // `rev=7` 且 `virtualAgents` 为空，桌面端发的也是 `rev=7` 但带 1 条声明 ——
        // 持续被这一行判成 `stale-rev` 丢弃，于是手机上群详情没有 agent、输入 `@`
        // 找不到、气泡署名永远是 `dsh`，而且**无论怎么补发都不会好**。
        //
        // 为什么这个例外不会造成「两份快照来回翻转」：条件只在**本地为空**时成立，
        // 一旦接受，本地就不再是空 —— 同一个例外对同一份数据只能生效一次。
        // 而「谁后到谁赢」那种危险语义并没有被放开：本地非空时仍然按先到的算。
        if (next.rev <= current.rev) {
            val localLacksAgents = current.virtualAgents.isNullOrEmpty()
            val incomingHasAgents = !next.virtualAgents.isNullOrEmpty()
            if (next.rev == current.rev && localLacksAgents && incomingHasAgents) {
                // **只补这一个字段，其余一律用本地那份。**
                //
                // 写成 `Apply(next)` 就是**权限绕过**：本分支直接 return，后面那些
                // `actor-not-a-member` / `removal-not-authorised` / 角色矩阵校验**全被跳过**
                // —— 任何人只要发一个「同 rev + 带 virtualAgents」的帧，就能重写整份群
                // 元数据，包括成员表与踢人；而 `virtualAgents` 是任何人都能填的字段。
                //
                // 那是 fail-open，与本仓「验不过就拒」相反。补字段的正确形态是：
                // 拿本地那份当底，只把缺的那一个键换成对方的值。
                return GroupMergeDecision.Apply(current.copy(virtualAgents = next.virtualAgents))
            }
            return GroupMergeDecision.Reject("stale-rev")
        }

        val actor = next.updatedBy
        if (actor !in current.members) return GroupMergeDecision.Reject("actor-not-a-member")
        val actorRole = roleOf(current, actor)
        val legacyCreator = current.creatorId?.isNotEmpty() == true && actor == current.creatorId
        // 管理员级 = 群主 / 管理员 / 旧包的建群者。三者都能改群，但只有群主能改角色矩阵。
        val actorIsManager = actorRole == GroupRole.Owner || actorRole == GroupRole.Admin || legacyCreator

        // 有人把我们移出了群。只有管理员级的人能这么做；否则是伪造的"踢人"。
        if (selfId !in next.members) {
            return if (actorIsManager) GroupMergeDecision.Removed(next)
            else GroupMergeDecision.Reject("removal-not-authorised")
        }

        val added = next.members.filter { it !in current.members }
        val removed = current.members.filter { it !in next.members }
        val rolesChanged = next.ownerId != current.ownerId ||
            next.adminIds.orEmpty() != current.adminIds.orEmpty()
        val nameChanged = next.name != current.name
        val avatarChanged = (next.avatarHash ?: "") != (current.avatarHash ?: "")
        val textChanged = (next.description ?: "") != (current.description ?: "") ||
            (next.announce ?: "") != (current.announce ?: "")

        if (actorRole == GroupRole.Owner) return GroupMergeDecision.Apply(next)

        // 角色矩阵只能由群主改（TS：`adminsChanged` 要求 `actorId === local.ownerId`，
        // 且一次只动一个）。这里更严一点：非群主一次都不许动 —— 我们没有"逐项 diff"的能力，
        // 与其猜"这次动的是不是合法的那一项"，不如不做。
        if (rolesChanged) return GroupMergeDecision.Reject("roles-changed-by-non-owner")

        if (actorIsManager) return GroupMergeDecision.Apply(next)

        // 普通成员：两条路，各只此一条。
        //
        // ① 邀请新人 —— 只增、不减，且不动名字/头像/简介/公告（既有规则）。
        val onlyAdded = added.isNotEmpty() && removed.isEmpty() &&
            !nameChanged && !avatarChanged && !textChanged
        if (onlyAdded) return GroupMergeDecision.Apply(next)

        // ② **宿主自声明主权**（PPE-2 / 团队创建方案 §5.2）：只改写 `hostNodeId == 自己`
        // 的那条声明（含 entityType / teamMembers / 工作区白名单），其余字段一律与本地
        // 完全一致。
        //
        // 以前这里只有 ①，于是成员宿主声明自己的席位/团队（rev+1）会被**每个对端整帧
        // 拒收**，而发送侧显示已保存 —— 「界面允许、收端拒收」的组合比两者单独存在都糟。
        // 它也顺带修了存量缺陷：工作区授权（`handleSetGroupAgentWorkspaces`）由普通成员
        // 保存时走的就是同一条被拒的路。
        //
        // 安全边界：声明是**自述的展示元数据**，不授予执行权（执行名单是独立台账）；
        // 允许成员声明「自己机器上的 agent/团队」与既有「机主主权」一致，且比现状更严
        // —— 现状是管理员可整份改写别人的声明。
        if (memberMayRedeclareSelf(current, next, actor)) {
            return GroupMergeDecision.Apply(next)
        }
        return GroupMergeDecision.Reject("member-may-only-invite")
    }

    /**
     * 普通成员的**自声明主权**判据（方案 §5.2 的规则 1–4）：
     *
     * 1. 除 `virtualAgents` 外的所有字段（成员表/群主/管理员/名字/头像/简介/公告）
     *    必须与本地完全一致 —— 管理身份（creatorIp/creatorId/adminSecretHash/adminHint）
     *    已在 [sameManagementIdentity] 比过，这里不重复；
     * 2. `virtualAgents` 的 null 与空列表归一后按**条目多重集合**比较（顺序不敏感）；
     * 3. `hostNodeId != 发起者` 的每一条声明：两边必须逐字段相同（增、删、改都不行）；
     * 4. `hostNodeId == 发起者` 的声明：允许增、删、改。
     */
    private fun memberMayRedeclareSelf(local: GroupMeta, next: GroupMeta, actor: String): Boolean {
        val fieldsEqual = local.members == next.members &&
            (local.ownerId ?: "") == (next.ownerId ?: "") &&
            (local.adminIds.orEmpty()) == (next.adminIds.orEmpty()) &&
            local.name == next.name &&
            (local.avatarHash ?: "") == (next.avatarHash ?: "") &&
            (local.description ?: "") == (next.description ?: "") &&
            (local.announce ?: "") == (next.announce ?: "")
        if (!fieldsEqual) return false
        // 外来声明的多重集合必须原样：一条不许多、一条不许少、一条不许改。
        val foreignLeft = local.virtualAgents.orEmpty()
            .filter { it.hostNodeId != actor }.map(::canonicalDeclaration).sorted()
        val foreignRight = next.virtualAgents.orEmpty()
            .filter { it.hostNodeId != actor }.map(::canonicalDeclaration).sorted()
        return foreignLeft == foreignRight
    }

    /**
     * 一条声明的**规范形**：按声明序编码成 JSON 串。
     *
     * 编码器 `encodeDefaults = false`：缺省与默认值（`entityType="single"`、空组员清单、
     * 空白名单、null 的 defaultWsId）编码出**同一个串** —— 规则 2 的「null 与空列表归一」
     * 在这里免费拿到，不用再手写归一。顺序不敏感由调用方 sort 保证。
     */
    private fun canonicalDeclaration(member: VirtualAgentMember): String =
        declarationCanonicalJson.encodeToString(VirtualAgentMember.serializer(), member)

    private val declarationCanonicalJson = kotlinx.serialization.json.Json { encodeDefaults = false }

    /**
     * 对应 TS `sameManagementIdentity`：建群来源（`creatorIp`/`creatorId`）与管理密码身份
     * （`adminSecretHash`/`adminHint`）必须与本地完全一致。
     *
     * `adminHint` 也一起比：它虽是展示字段，但让对端改提示语而不同步密码摘要，会让用户
     * 以为密码换了 —— 身份字段要么整组一致，要么整组不动。
     */
    private fun sameManagementIdentity(left: GroupMeta, right: GroupMeta): Boolean =
        (left.creatorIp ?: "") == (right.creatorIp ?: "") &&
            (left.creatorId ?: "") == (right.creatorId ?: "") &&
            (left.adminSecretHash ?: "") == (right.adminSecretHash ?: "") &&
            (left.adminHint ?: "") == (right.adminHint ?: "")
}

/**
 * 记住"已经为哪个群的哪个 rev 要过元数据"，防止 need/info 往返风暴。
 *
 * ### 为什么必须有它
 * 收端看到 `group-text.groupRev > 本地 rev` 就发 `op:need`。而 [GroupMerge] 是**会拒收**的
 * （非管理员改角色、密码路径的变更……）。被拒之后本地 rev 不前进，于是**下一条群消息**
 * 又满足 `groupRev > 本地 rev`，又发一次 need，群主又回一次 info，我们又不收 —— 每收到
 * 一条群消息就白跑一个来回。这不是假想：密码保护的群一旦改名就稳定进入这个循环。
 *
 * 记的是"要过的最高 rev"而不是集合：rev 单调，比它低的一定要过了。
 * 只活在内存里 —— 重启后重新问一次是**正确**的（那时我们确实不知道），代价是一次往返。
 */
class GroupNeedTracker {
    /**
     * 「**问过**哪个 rev」，用来抑制往返风暴（[beginRequest] 与 [noteKnown] 都推进它）。
     *
     * ## 两件容易被混淆的事
     *
     * **① 本地握有完整元数据**（[noteKnown] 的六个调用点：建群 / 加人 / 移人 / 退群 /
     * 改资料 / 收到 info 并合并）。手上有完整元数据时，同 rev 再来问一次只是白跑，
     * 所以推进这里是**对的**。
     *
     * **② 仅从群消息里见过某个 rev**（`handleSyncGroupMetaIfNeeded`）。它**只调
     * [beginRequest]，从不碰 [noteKnown]** —— 收到带 rev 7 的群消息不代表有 rev 7 的
     * 成员表与 `virtualAgents`，所以那种情况下仍该去要。
     *
     * 曾经误以为「收到消息也会推进 noteKnown ⇒ 错过一次广播就永久缺失」，据此把这个
     * map 拆成两份 —— 那会让建群之后每一条同 rev 的群消息都再问一次，正是本类要防的
     * 往返风暴。查证调用点后已还原。
     */
    private val requested = mutableMapOf<String, Int>()

    /** 该不该为 ([groupId], [wantRev]) 发一次 need。 */
    fun shouldRequest(groupId: String, wantRev: Int): Boolean {
        val highest = requested[groupId] ?: return true
        return wantRev > highest
    }

    /** 记下"已经问过 [wantRev]"（连同它之前的都算问过）。 */
    fun noteRequested(groupId: String, wantRev: Int) {
        val highest = requested[groupId]
        if (highest == null || wantRev > highest) requested[groupId] = wantRev
    }

    /**
     * 「现在该发一次 need 吗」+「发出去的同时记下问过了」这两件事**必须成对**。
     *
     * 只问不记的话，对端**没回** info 时游标不前进：下一条同 rev 的群消息又满足
     * `wantRev > highest`，又发一次 need —— 一条消息一帧，就是本类开头那段"往返风暴"
     * 的另一种进入方式（对端在线却从不回 info、或者回包丢了，都算）。
     * 合成一个方法是为了让"问过就记"这件事不可能被漏，也让判据不起节点就能测。
     */
    fun beginRequest(groupId: String, wantRev: Int): Boolean {
        if (!shouldRequest(groupId, wantRev)) return false
        noteRequested(groupId, wantRev)
        return true
    }

    /**
     * 记下「本地**握有**这个群的 rev 的完整元数据」—— 六条写元数据的路径都调它
     * （建群 / 加人 / 移人 / 退群 / 改资料 / 收到 info 并合并）。
     *
     * 因此它推进 [requested] 是正确的：手上有完整元数据时，同 rev 再来问一次只是白跑一趟。
     *
     * **注意它与「从群消息里见过某个 rev」不是一回事**：后者走 [beginRequest]，
     * 从不调用这里 —— 收到带 rev 7 的群消息不代表有 rev 7 的成员表。
     */
    fun noteKnown(groupId: String, rev: Int) {
        val highest = requested[groupId]
        if (highest == null || rev > highest) requested[groupId] = rev
    }

    /** 群被删掉（我们被移出）时忘掉它，重新加入时才能再问。 */
    fun forget(groupId: String) {
        requested.remove(groupId)
    }
}
