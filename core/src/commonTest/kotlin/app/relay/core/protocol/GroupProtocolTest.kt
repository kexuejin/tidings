// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `group` 帧（协议 §7.4）与远端合并策略。
 *
 * 期望值**不是猜的**：校验的每一条都对着 `/tmp/th_up/teahouse-main/src/main/net/codec.ts`
 * 的 `MSG_TYPES.group` 分支逐行读出来（那是真实茶话间），合并规则对着
 * `src/main/services/groups.ts` 的 `canApplyRemoteInfo`。凡本仓**故意更严/更松**的地方，
 * 用例名或注释里写明是哪一条，免得以后有人把差异当成 bug 改回去。
 */
class GroupProtocolTest {

    private companion object {
        const val SELF = "self-node"
        const val OWNER = "owner-node"
        const val ADMIN = "admin-node"
        const val BOB = "bob-node"
        const val EVE = "eve-node"
        const val GROUP = "grp-1"
        const val TS = 1_789_000_000_000L
    }

    private fun meta(
        rev: Int = 1,
        name: String = "项目组",
        members: List<String> = listOf(OWNER, ADMIN, BOB, SELF),
        updatedBy: String = OWNER,
        ownerId: String? = OWNER,
        adminIds: List<String>? = listOf(ADMIN),
        description: String? = null,
        announce: String? = null,
        avatarHash: String? = null,
        creatorIp: String? = null,
        creatorId: String? = OWNER,
        adminSecretHash: String? = null,
        adminHint: String? = null,
        updatedTs: Long = TS,
    ) = GroupMeta(
        groupId = GROUP,
        name = name,
        members = members,
        rev = rev,
        updatedBy = updatedBy,
        updatedTs = updatedTs,
        creatorIp = creatorIp,
        creatorId = creatorId,
        ownerId = ownerId,
        adminIds = adminIds,
        avatarHash = avatarHash,
        adminSecretHash = adminSecretHash,
        adminHint = adminHint,
        description = description,
        announce = announce,
    )

    /** 用**手写 JSON** 构造入站载荷，而不是序列化 [GroupMeta] —— 校验的就是这份手写 JSON。 */
    private fun infoPayload(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("op", GroupOps.INFO)
            putJsonObject("group") {
                put("groupId", GROUP)
                put("name", "项目组")
                putJsonArray("members") { listOf(OWNER, ADMIN, BOB, SELF).forEach { add(JsonPrimitive(it)) } }
                put("rev", 1)
                put("updatedBy", OWNER)
                put("updatedTs", TS)
                block()
            }
        }

    // ------------------------------------------------------------------ 校验

    @Test
    fun aMinimalInfoFrameIsAccepted() {
        assertTrue(Codec.validateGroup(infoPayload { }))
    }

    @Test
    fun aNeedFrameOnlyNeedsAGroupId() {
        assertTrue(
            Codec.validateGroup(
                buildJsonObject {
                    put("op", GroupOps.NEED)
                    put("groupId", GROUP)
                },
            ),
        )
        assertFalse(
            Codec.validateGroup(
                buildJsonObject {
                    put("op", GroupOps.NEED)
                    put("groupId", "")
                },
            ),
            "空 groupId 不是合法标识",
        )
    }

    @Test
    fun anUnknownOpIsRejected() {
        assertFalse(
            Codec.validateGroup(
                buildJsonObject {
                    put("op", "delete")
                    put("groupId", GROUP)
                },
            ),
            "只有 info / need 两个 op；多一个就是别的意思，不能猜",
        )
    }

    @Test
    fun ownerAndAdminIdsMustArriveTogether() {
        assertFalse(
            Codec.validateGroup(infoPayload { put("ownerId", OWNER) }),
            "只给 ownerId 不给 adminIds：TS 侧 hasOwnerId !== hasAdminIds 直接拒",
        )
        assertFalse(
            Codec.validateGroup(infoPayload { putJsonArray("adminIds") { } }),
            "只给 adminIds 不给 ownerId 同样拒",
        )
        assertTrue(
            Codec.validateGroup(
                infoPayload {
                    put("ownerId", OWNER)
                    putJsonArray("adminIds") { }
                },
            ),
        )
    }

    @Test
    fun theOwnerMustBeAMemberAndAdminsMustBeMembersToo() {
        assertFalse(
            Codec.validateGroup(
                infoPayload {
                    put("ownerId", EVE)
                    putJsonArray("adminIds") { }
                },
            ),
            "群主不在成员表里 = 一个没人能管的群",
        )
        assertFalse(
            Codec.validateGroup(
                infoPayload {
                    put("ownerId", OWNER)
                    putJsonArray("adminIds") { add(JsonPrimitive(EVE)) }
                },
            ),
            "管理员必须是成员",
        )
        assertFalse(
            Codec.validateGroup(
                infoPayload {
                    put("ownerId", OWNER)
                    putJsonArray("adminIds") { add(JsonPrimitive(OWNER)) }
                },
            ),
            "群主不该同时出现在管理员列表里（TS: adminIds.includes(ownerId) 即拒）",
        )
    }

    @Test
    fun duplicateAdminsAreRejected() {
        assertFalse(
            Codec.validateGroup(
                infoPayload {
                    put("ownerId", OWNER)
                    putJsonArray("adminIds") {
                        add(JsonPrimitive(ADMIN))
                        add(JsonPrimitive(ADMIN))
                    }
                },
            ),
            "TS 对 adminIds 查重；members 不查重（见 normalize 的去重）",
        )
    }

    @Test
    fun revMustBeAtLeastOneAndUpdatedTsMustBePositive() {
        assertFalse(Codec.validateGroup(infoPayload { put("rev", 0) }), "rev 从 1 起")
        assertFalse(Codec.validateGroup(infoPayload { put("updatedTs", 0) }), "updatedTs 必须 > 0")
    }

    @Test
    fun membersMustBeNonEmptyAndWithinTheGroupCap() {
        assertFalse(
            Codec.validateGroup(infoPayload { putJsonArray("members") { } }),
            "空成员表不是群",
        )
        assertFalse(
            Codec.validateGroup(
                infoPayload {
                    putJsonArray("members") {
                        repeat(GROUP_MAX_MEMBERS + 1) { add(JsonPrimitive("n-$it")) }
                    }
                },
            ),
            "超过 GROUP_MAX_MEMBERS 拒",
        )
    }

    @Test
    fun anAvatarHashMustBeEmptyOrSixtyFourHexChars() {
        assertTrue(Codec.validateGroup(infoPayload { put("avatarHash", "") }), "空串 = 用默认群图标")
        assertTrue(Codec.validateGroup(infoPayload { put("avatarHash", "a".repeat(64)) }))
        assertFalse(Codec.validateGroup(infoPayload { put("avatarHash", "a".repeat(63)) }))
        assertFalse(Codec.validateGroup(infoPayload { put("avatarHash", "Z".repeat(64)) }), "只认小写十六进制")
    }

    @Test
    fun theAdminSecretHashMustBeEmptyOrSixtyFourHexChars() {
        assertTrue(Codec.validateGroup(infoPayload { put("adminSecretHash", "") }))
        assertTrue(Codec.validateGroup(infoPayload { put("adminSecretHash", "0f".repeat(32)) }))
        assertFalse(Codec.validateGroup(infoPayload { put("adminSecretHash", "not-a-hash") }))
    }

    /**
     * **本仓刻意与 TS 一致的一处"不严"**：`group` 帧**没有**字段白名单。
     *
     * 这条必须钉住，否则以后有人"顺手加严"（以为向 `msg` 看齐是好事），
     * 后果是单方面把新版本茶话间踢下线 —— v0.54 的 `description`/`announce` 就是这么加进来的。
     */
    @Test
    fun anUnknownFieldDoesNotKillTheFrame() {
        assertTrue(
            Codec.validateGroup(infoPayload { put("someFutureField", "x") }),
            "group 帧没有 hasOnlyKeys；多一个未知键不该整帧丢弃",
        )
    }

    // ------------------------------------------------------------- 解码与出站

    @Test
    fun anEncodedInfoFrameIsAcceptedByOurOwnValidator() {
        val payload = Codec.groupInfoPayload(meta())
        val o = payload as JsonObject
        assertTrue(Codec.validateGroup(o), "自己编出来的帧必须过得了自己的校验")
        assertEquals(GroupOps.INFO, (o["op"] as JsonPrimitive).content)
    }

    @Test
    fun anEncodedNeedFrameIsAcceptedByOurOwnValidator() {
        val payload = Codec.groupNeedPayload(GROUP) as JsonObject
        assertTrue(Codec.validateGroup(payload))
        assertEquals(GROUP, Codec.decodeGroupNeed(payload))
    }

    @Test
    fun theEncodedFrameOmitsOptionalFieldsInsteadOfWritingNulls() {
        val o = Codec.groupInfoPayload(meta(description = null, announce = null)) as JsonObject
        val group = o["group"] as JsonObject
        assertFalse(group.containsKey("description"), "null 不该上线（explicitNulls = false）")
        assertFalse(group.containsKey("announce"))
        assertTrue(group.containsKey("adminIds"), "空列表是**值**不是缺省，必须出现")
    }

    /**
     * `ownerId` 有值而 `adminIds` 是空列表时，**两个键都必须上线**。
     *
     * 这是本文件里最容易踩的一个坑：如果把 `adminIds` 的默认值写成 `emptyList()`，
     * `encodeDefaults = false` 会把它省略，于是线上出现"只有 ownerId"的帧 ——
     * TS 侧 `hasOwnerId !== hasAdminIds` 会**整帧拒收**，症状是"手机建的群，桌面端收不到"。
     */
    @Test
    fun anEmptyAdminListIsStillWrittenToTheWire() {
        val o = Codec.groupInfoPayload(meta(adminIds = emptyList())) as JsonObject
        val group = o["group"] as JsonObject
        assertTrue(group.containsKey("ownerId"))
        assertTrue(group.containsKey("adminIds"), "空管理员列表必须写出来，否则整帧被 TS 拒收")
    }

    @Test
    fun decodeGroupInfoReadsBackEveryField() {
        val original = meta(
            rev = 7,
            name = "改名了",
            description = "简介",
            announce = "公告",
            avatarHash = "b".repeat(64),
            adminSecretHash = "c".repeat(64),
            adminHint = "提示",
            creatorIp = "192.168.2.82",
        )
        val decoded = Codec.decodeGroupInfo(Codec.groupInfoPayload(original))
        assertEquals(original, decoded, "编出去再解回来必须逐字段相等")
    }

    @Test
    fun decodeGroupInfoReturnsNullOnTheWrongShape() {
        assertNull(Codec.decodeGroupInfo(null))
        assertNull(Codec.decodeGroupInfo(JsonPrimitive("nope")))
        assertNull(Codec.decodeGroupInfo(Codec.groupNeedPayload(GROUP)), "need 帧里没有 group 对象")
    }

    // ------------------------------------------------------------------ 合并

    @Test
    fun aGroupWeAreNotInIsNotStored() {
        val decision = GroupMerge.decide(null, meta(members = listOf(OWNER, BOB)), SELF)
        assertTrue(decision is GroupMergeDecision.Reject, "别人的群不是我们的事：$decision")
    }

    @Test
    fun anInvitationToAGroupWeHaveNeverSeenIsApplied() {
        val decision = GroupMerge.decide(null, meta(), SELF)
        assertTrue(decision is GroupMergeDecision.Apply)
    }

    @Test
    fun aStaleOrEqualRevisionIsRejected() {
        val local = meta(rev = 5)
        assertTrue(GroupMerge.decide(local, meta(rev = 4), SELF) is GroupMergeDecision.Reject)
        assertTrue(
            GroupMerge.decide(local, meta(rev = 5), SELF) is GroupMergeDecision.Reject,
            "同一 rev 的两份快照没法排序，先到的那份说了算",
        )
        assertTrue(GroupMerge.decide(local, meta(rev = 6), SELF) is GroupMergeDecision.Apply)
    }

    @Test
    fun theOwnerMayChangeAnything() {
        val local = meta(rev = 1)
        val renamed = meta(rev = 2, name = "新名字", members = listOf(OWNER, ADMIN, SELF))
        assertTrue(GroupMerge.decide(local, renamed, SELF) is GroupMergeDecision.Apply)
    }

    @Test
    fun anAdminMayChangeTheNameButNotTheRoleMatrix() {
        val local = meta(rev = 1)
        val renamed = meta(rev = 2, name = "管理员改的名", updatedBy = ADMIN)
        assertTrue(GroupMerge.decide(local, renamed, SELF) is GroupMergeDecision.Apply)

        val selfPromoted = meta(rev = 2, updatedBy = ADMIN, adminIds = listOf(ADMIN, BOB))
        val decision = GroupMerge.decide(local, selfPromoted, SELF)
        assertTrue(
            decision is GroupMergeDecision.Reject,
            "管理员给自己人加管理员 = 自授权限，必须拒：$decision",
        )
    }

    @Test
    fun aPlainMemberMayOnlyInviteSomeone() {
        val local = meta(rev = 1)
        val invited = meta(rev = 2, updatedBy = BOB, members = listOf(OWNER, ADMIN, BOB, SELF, EVE))
        assertTrue(GroupMerge.decide(local, invited, SELF) is GroupMergeDecision.Apply)

        val renamedByMember = meta(rev = 2, name = "我说了算", updatedBy = BOB)
        assertTrue(
            GroupMerge.decide(local, renamedByMember, SELF) is GroupMergeDecision.Reject,
            "普通成员不能改名",
        )
    }

    @Test
    fun aMemberCannotRemoveAnyone() {
        val local = meta(rev = 1)
        val kicked = meta(rev = 2, updatedBy = BOB, members = listOf(OWNER, ADMIN, SELF))
        assertTrue(
            GroupMerge.decide(local, kicked, SELF) is GroupMergeDecision.Reject,
            "普通成员踢人 = 夺权",
        )
    }

    @Test
    fun onlyAManagerCanRemoveUsAndThenWeDropTheGroup() {
        val local = meta(rev = 1)
        val kickedByOwner = meta(rev = 2, members = listOf(OWNER, ADMIN, BOB))
        assertTrue(GroupMerge.decide(local, kickedByOwner, SELF) is GroupMergeDecision.Removed)

        val kickedByBob = meta(rev = 2, updatedBy = BOB, members = listOf(OWNER, ADMIN, BOB))
        assertTrue(
            GroupMerge.decide(local, kickedByBob, SELF) is GroupMergeDecision.Reject,
            "普通成员不能把我们踢出去",
        )
    }

    @Test
    fun anActorWhoIsNotAMemberCannotChangeTheGroup() {
        val local = meta(rev = 1)
        val forged = meta(rev = 2, name = "外部改的", updatedBy = EVE)
        assertTrue(GroupMerge.decide(local, forged, SELF) is GroupMergeDecision.Reject)
    }

    /**
     * 建群来源与管理密码身份**谁都改不动** —— 改得动它就等于能冒充群主。
     *
     * 这条是 [GroupMerge] 里少有的"比逐项授权更基础"的检查：它先于角色判定。
     */
    @Test
    fun theManagementIdentityCannotBeChangedByAnyone() {
        val local = meta(rev = 1, adminSecretHash = "d".repeat(64))
        val swappedSecret = meta(rev = 2, adminSecretHash = "e".repeat(64))
        assertTrue(
            GroupMerge.decide(local, swappedSecret, SELF) is GroupMergeDecision.Reject,
            "换掉密码摘要 = 换掉了管理员身份",
        )

        val swappedCreator = meta(rev = 2, creatorId = EVE)
        assertTrue(GroupMerge.decide(local, swappedCreator, SELF) is GroupMergeDecision.Reject)
    }

    /**
     * **本仓明确比 TS 严的一处**：带管理密码的群，一次"由密码持有者发起、但他既不是群主
     * 也不是管理员"的变更会被我们拒收（TS 走 `passwordCompatible` 放行）。
     *
     * 代价是我们的副本停在旧 rev。这条用例把它钉成**已知取舍**而不是 bug：
     * 改这条之前先读 [GroupMerge] 的 KDoc 与 `PROTOCOL-COVERAGE.md`。
     */
    @Test
    fun theAdminPasswordPathIsDeliberatelyNotImplemented() {
        val local = meta(rev = 1, adminSecretHash = "d".repeat(64), adminIds = emptyList())
        val renamedByPasswordHolder = meta(
            rev = 2,
            name = "密码持有者改的",
            updatedBy = BOB,
            adminIds = emptyList(),
        )
        val decision = GroupMerge.decide(local, renamedByPasswordHolder, SELF)
        assertTrue(
            decision is GroupMergeDecision.Reject,
            "本仓不做密码路径 —— 这是写下来的取舍，不是漏掉的：$decision",
        )
    }

    @Test
    fun aLegacyCreatorWithoutAnOwnerRoleStillCountsAsAManager() {
        // 旧包：没有 ownerId / adminIds，只有 creatorId。TS 靠 creatorIp/creatorId 兜底。
        val local = meta(rev = 1, ownerId = null, adminIds = null, creatorId = BOB)
        val renamed = meta(rev = 2, name = "旧群改名", updatedBy = BOB, ownerId = null, adminIds = null, creatorId = BOB)
        assertTrue(GroupMerge.decide(local, renamed, SELF) is GroupMergeDecision.Apply)
    }

    // -------------------------------------------------------------- normalize

    @Test
    fun normalizeDeduplicatesMembers() {
        val normalized = GroupMerge.normalize(meta(members = listOf(OWNER, OWNER, BOB, SELF, BOB)))
        assertEquals(listOf(OWNER, BOB, SELF), normalized.members)
    }

    /**
     * 旧包（v0.43 之前）没有群主概念。不补齐的话 [GroupMerge.roleOf] 对所有人都返回
     * `Member`，于是这个群在我们这儿**谁都改不动**，名字和成员表永远停在第一版。
     */
    @Test
    fun normalizeBackfillsTheOwnerForLegacyGroups() {
        assertEquals(OWNER, GroupMerge.normalize(meta(ownerId = null, adminIds = null)).ownerId)
        assertEquals(
            BOB,
            GroupMerge.normalize(
                meta(ownerId = null, adminIds = null, creatorId = null, updatedBy = BOB),
            ).ownerId,
            "没有 creatorId 时退回 updatedBy",
        )
        assertEquals(
            OWNER,
            GroupMerge.normalize(
                meta(
                    ownerId = EVE,
                    adminIds = emptyList(),
                    members = listOf(OWNER, ADMIN, SELF),
                ),
            ).ownerId,
            "ownerId 不是成员时也要补一个能管的人",
        )
    }

    @Test
    fun normalizeDropsAdminsWhoAreNotMembers() {
        val normalized = GroupMerge.normalize(
            meta(adminIds = listOf(ADMIN, EVE), members = listOf(OWNER, ADMIN, SELF)),
        )
        assertEquals(listOf(ADMIN), normalized.adminIds)
    }

    // ------------------------------------------------------------- need 追踪

    @Test
    fun theFirstRequestForAGroupIsAlwaysSent() {
        assertTrue(GroupNeedTracker().shouldRequest(GROUP, 3))
    }

    /**
     * 这条是 [GroupNeedTracker] 存在的**全部理由**：被 [GroupMerge] 拒收的 rev 不会推进
     * 本地 rev，于是下一条群消息又满足 `groupRev > 本地 rev`。不记"已经问过"的话，
     * 每收到一条群消息就白跑一个 need/info 往返。
     */
    @Test
    fun weDoNotAskTwiceForTheSameRevision() {
        val tracker = GroupNeedTracker()
        tracker.noteRequested(GROUP, 3)
        assertFalse(tracker.shouldRequest(GROUP, 3), "同一个 rev 不再问第二次")
        assertFalse(tracker.shouldRequest(GROUP, 2), "更低的 rev 当然也不问")
        assertTrue(tracker.shouldRequest(GROUP, 4), "真的更新了还是要问")
    }

    @Test
    fun knowingALocalRevisionStopsUsAskingAboutIt() {
        val tracker = GroupNeedTracker()
        tracker.noteKnown(GROUP, 5)
        assertFalse(tracker.shouldRequest(GROUP, 5))
        assertTrue(tracker.shouldRequest(GROUP, 6))
    }

    @Test
    fun differentGroupsHaveIndependentCursors() {
        val tracker = GroupNeedTracker()
        tracker.noteRequested(GROUP, 9)
        assertTrue(tracker.shouldRequest("grp-2", 1))
    }

    @Test
    fun forgettingAGroupLetsUsAskAgainAfterRejoining() {
        val tracker = GroupNeedTracker()
        tracker.noteRequested(GROUP, 9)
        tracker.forget(GROUP)
        assertTrue(tracker.shouldRequest(GROUP, 1), "退出再被拉回来时要能重新问")
    }

    /**
     * `beginRequest` 存在的理由：**问出去**和**记下来**必须是同一个动作。
     *
     * 这条用例守的是"对端没回 info"的那半边：如果发 need 的代码只调 `shouldRequest`、
     * 不调 `noteRequested`，那么对端不回时游标永远停在原地 —— 之后**每一条**同 rev 的
     * 群消息都再发一帧 need。这里逐步走一遍"问、不问、再问"的完整游标。
     */
    @Test
    fun askingRecordsTheRevisionItAskedFor() {
        val tracker = GroupNeedTracker()
        assertTrue(tracker.beginRequest(GROUP, 3), "第一次问")
        assertFalse(tracker.beginRequest(GROUP, 3), "问过了，同 rev 不再问（哪怕一直没回音）")
        assertFalse(tracker.beginRequest(GROUP, 2), "比问过的低，更不问")
        assertTrue(tracker.beginRequest(GROUP, 4), "真的有更新的 rev，还是要问")
        assertFalse(tracker.beginRequest(GROUP, 4), "而且这次也记下了")
    }

    // ------------------------------------------------------------ memberNicks（跨网成员真名）

    /**
     * 群元数据可以带成员昵称，**并且真的能读回来**。
     *
     * 真机缺陷：手机走蜂窝加入群后，成员列表里另外两个人只显示 `f6a7b8c9` 这种 nodeId 前 8 位
     * —— 因为它发现不到他们（不同网段、也从没直连过），而桌面端同一时刻在局域网里认识所有人。
     * 同一个群两端显示不一致，那一端看到的是十六进制。
     */
    @Test
    fun memberNicksRoundTripThroughGroupInfo() {
        val payload = buildJsonObject {
            put("op", GroupOps.INFO)
            putJsonObject("group") {
                put("groupId", GROUP)
                put("name", "项目组")
                putJsonArray("members") { listOf(OWNER, ADMIN, BOB, SELF).forEach { add(JsonPrimitive(it)) } }
                put("rev", 1)
                put("updatedBy", OWNER)
                put("updatedTs", TS)
                putJsonObject("memberNicks") {
                    put(OWNER, "demo-mac-97")
                    put(ADMIN, "panda")
                }
            }
        }
        assertTrue(Codec.validateGroup(payload), "带 memberNicks 的 info 帧必须合法")
        val meta = Codec.decodeGroupInfo(payload)
        assertEquals("demo-mac-97", meta?.memberNicks?.get(OWNER))
        assertEquals("panda", meta?.memberNicks?.get(ADMIN))
    }

    /** 旧端不发这个键 ⇒ null，成员行退回 nodeId 前 8 位（与以前一致，不更差）。 */
    @Test
    fun missingMemberNicksIsNullNotAnError() {
        val payload = infoPayload { }
        assertTrue(Codec.validateGroup(payload))
        assertNull(Codec.decodeGroupInfo(payload)?.memberNicks, "旧端不发 = 没有昵称，不是坏帧")
    }

    /**
     * **键必须属于 members**：不属于的人不能借这个字段混进成员列表（解码侧按 members 过滤）。
     *
     * 这一条是边界而不是洁癖：前端拿到 memberNicks 会把它们显示成"群成员"。
     * 一个越界键就等于让对端往我的成员列表里塞一个不存在的成员。
     */
    @Test
    fun memberNicksKeyOutsideMembersIsRejected() {
        val payload = infoPayload {
            putJsonObject("memberNicks") { put(EVE, "我不是成员") }
        }
        assertFalse(Codec.validateGroup(payload), "键不在 members 里 ⇒ 整帧不合法")
    }

    /** 昵称是纯展示数据：超长/非串一律拒帧，而不是截断成半截名字。 */
    @Test
    fun malformedMemberNicksIsRejected() {
        val tooLong = "x".repeat(Limits.NICK + 1)
        assertFalse(
            Codec.validateGroup(infoPayload {
                putJsonObject("memberNicks") { put(OWNER, tooLong) }
            }),
            "超长昵称不许被截断后接受",
        )
        assertFalse(
            Codec.validateGroup(infoPayload {
                put("memberNicks", "not-an-object")
            }),
            "形状不对就该拒",
        )
    }

    /** 一个昵称都没有时等同"没带"：null，而不是空 map（空 map 会让调用方以为"有信息但全空"）。 */
    @Test
    fun emptyMemberNicksIsNull() {
        val payload = infoPayload { putJsonObject("memberNicks") { } }
        assertTrue(Codec.validateGroup(payload))
        assertNull(Codec.decodeGroupInfo(payload)?.memberNicks)
    }
}
