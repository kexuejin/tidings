// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.Limits

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 团队执行档存储的钉子（团队执行方案 §3.2/§3.4/§3.5）。
 *
 * 落盘纪律与 `DelegationTeamStore` 逐字同源（那边已有三态/0600/原子的钉子）；
 * 这里钉的是**本档自己的**规则：主键 `(目录, 领队)`（不含群：一支队进几个群共用一份）、
 * v2 折叠迁移、保存校验矩阵（恰好 1 worker / 异厂商 / 模型预算 / 档 ⊆ 声明）、
 * remove（团队解散生命周期）、悬空读时判过期。
 */
class TeamExecutionStoreTest {

    private fun tmpFile(): File = Files.createTempFile("team-exec", ".json").toFile()

    private fun member(
        agentId: String,
        kind: String = TeamExecutionKinds.REVIEWER,
        driverKey: String = "claude",
        models: List<String> = emptyList(),
    ) = TeamExecutionMemberSpec(agentId, kind, driverKey, models)

    private fun profile(
        dirId: String = "ws-1",
        leader: String = "dsh-lead",
        updatedAt: Long = 0,
        members: List<TeamExecutionMemberSpec>,
    ): TeamExecutionProfile = TeamExecutionProfile(
        dirId = dirId, leaderAgentId = leader,
        updatedAt = updatedAt, members = members,
    )

    // ------------------------------------------------------------ 保存校验矩阵（§3.4）

    @Test
    fun aWellFormedProfileSavesAndReadsBack() {
        val f = tmpFile()
        val store = TeamExecutionStore(f)
        val p = profile(members = listOf(
            member("m-coder", TeamExecutionKinds.WORKER, "codex"),
            member("m-reviewer", driverKey = "claude"),
        ))
        assertIs<TeamExecutionSaveVerdict.Saved>(store.save(p, declaredMemberIds = setOf("m-coder", "m-reviewer")))
        // 新实例读同一份文件（mtime 失效路径）——落盘是真的。
        assertEquals(p.members, TeamExecutionStore(f).profileFor("ws-1", "dsh-lead")?.members)
    }

    @Test
    fun exactlyOneWorkerIsRequired() {
        val store = TeamExecutionStore(tmpFile())
        val two = profile(members = listOf(
            member("m-a", TeamExecutionKinds.WORKER, "codex"),
            member("m-b", TeamExecutionKinds.WORKER, "claude"),
        ))
        val rejected = assertIs<TeamExecutionSaveVerdict.Rejected>(store.save(two))
        assertTrue(rejected.reasons.any { "exactly one worker" in it }, rejected.reasons.toString())

        val zero = profile(members = listOf(member("m-r", driverKey = "claude")))
        val rejected2 = assertIs<TeamExecutionSaveVerdict.Rejected>(store.save(zero))
        assertTrue(rejected2.reasons.any { "exactly one worker" in it })
    }

    /** reviewer 与 worker **异厂商**是硬规则（§3.4.3）；reviewer 之间可同厂商。 */
    @Test
    fun reviewerVendorMustDifferFromWorker() {
        val store = TeamExecutionStore(tmpFile())
        val same = profile(members = listOf(
            member("m-coder", TeamExecutionKinds.WORKER, "codex"),
            member("m-r1", driverKey = "codex"),
        ))
        val rejected = assertIs<TeamExecutionSaveVerdict.Rejected>(store.save(same))
        assertTrue(rejected.reasons.any { "different vendor" in it }, rejected.reasons.toString())

        val okSameReviewers = profile(members = listOf(
            member("m-coder", TeamExecutionKinds.WORKER, "codex"),
            member("m-r1", driverKey = "claude"),
            member("m-r2", driverKey = "claude"),
        ))
        assertIs<TeamExecutionSaveVerdict.Saved>(store.save(okSameReviewers))
    }

    @Test
    fun unknownDriverKeyIsRejected() {
        val store = TeamExecutionStore(tmpFile())
        val bad = profile(members = listOf(member("m-coder", TeamExecutionKinds.WORKER, "does-not-exist")))
        val rejected = assertIs<TeamExecutionSaveVerdict.Rejected>(store.save(bad))
        assertTrue(rejected.reasons.any { "unknown driverKey" in it })
    }

    /** 模型预算（§3.4.4）：≤ 3 个、每个非空 ≤ AGENT_MODEL_ID。 */
    @Test
    fun modelBudgetIsEnforced() {
        val store = TeamExecutionStore(tmpFile())
        val tooMany = profile(members = listOf(
            member("m-coder", TeamExecutionKinds.WORKER, "codex"),
            member("m-r", driverKey = "claude", models = List(Limits.AGENT_MODELS_MAX + 1) { "m$it" }),
        ))
        assertTrue(
            assertIs<TeamExecutionSaveVerdict.Rejected>(store.save(tooMany)).reasons.any { "too many models" in it },
        )
        val tooLong = profile(members = listOf(
            member("m-coder", TeamExecutionKinds.WORKER, "codex"),
            member("m-r", driverKey = "claude", models = listOf("x".repeat(Limits.AGENT_MODEL_ID + 1))),
        ))
        assertTrue(
            assertIs<TeamExecutionSaveVerdict.Rejected>(store.save(tooLong)).reasons.any { "illegal model id" in it },
        )
    }

    /** 档 ⊆ 声明（§3.4.6）：凭空加人 = 拒绝；声明里有档里没有 = 读取侧按未配置处理。 */
    @Test
    fun profileMembersMustBeWithinTheDeclaredRoster() {
        val store = TeamExecutionStore(tmpFile())
        val ghost = profile(members = listOf(
            member("m-coder", TeamExecutionKinds.WORKER, "codex"),
            member("m-ghost", driverKey = "claude"),
        ))
        val rejected = assertIs<TeamExecutionSaveVerdict.Rejected>(
            store.save(ghost, declaredMemberIds = setOf("m-coder")),
        )
        assertTrue(rejected.reasons.any { "not in the declared roster" in it })
        // declaredMemberIds = null（调用方没有声明可比）⇒ 跳过这条校验。
        assertIs<TeamExecutionSaveVerdict.Saved>(store.save(ghost, declaredMemberIds = null))
    }

    // ------------------------------------------------------------ 三态与生命周期

    @Test
    fun missingCorruptAndUnknownSchemaAreAllUntrusted() {
        val missing = TeamExecutionStore(tmpFile())
        assertNull(missing.profileFor("ws-1", "dsh-lead"))

        val corrupt = tmpFile()
        corrupt.writeText("{ not json")
        assertNull(TeamExecutionStore(corrupt).profileFor("ws-1", "dsh-lead"))

        val unknown = tmpFile()
        unknown.writeText("""{"schemaVersion":99,"teams":[]}""")
        assertNull(TeamExecutionStore(unknown).profileFor("ws-1", "dsh-lead"))
    }

    /** remove（§3.5 团队解散生命周期）：同主键条目消失，其它条目不受影响。 */
    @Test
    fun removeDeletesOnlyTheMatchingSeat() {
        val f = tmpFile()
        val store = TeamExecutionStore(f)
        assertTrue(
            store.save(
                profile(dirId = "ws-1", members = listOf(member("m-coder", TeamExecutionKinds.WORKER, "codex"))),
            ) is TeamExecutionSaveVerdict.Saved,
        )
        assertTrue(
            store.save(
                profile(dirId = "ws-2", leader = "dsh-lead-2", members = listOf(member("m-coder2", TeamExecutionKinds.WORKER, "codex"))),
            ) is TeamExecutionSaveVerdict.Saved,
        )
        assertTrue(store.remove("ws-1", "dsh-lead"))
        assertNull(store.profileFor("ws-1", "dsh-lead"))
        assertFalse(store.remove("ws-1", "dsh-lead"), "条目已不存在 = false（正常）")
        assertTrue(store.profileFor("ws-2", "dsh-lead-2") != null, "别的席位不受影响")
    }

    // ------------------------------------------------------------ 主键 = (目录, 团队)

    /**
     * **一支队进两个群只需要配一份**：键里没有 groupId，所以第二群读得到第一群配的那份。
     *
     * 这是这轮换主键的直接理由：原先键带 groupId，用户在 A 群配完，B 群的 `@团队`
     * 读的是另一条 ⇒ blocked 卡说「没配过」，而用户明明刚配过。
     */
    @Test
    fun oneTeamInTwoGroupsSharesASingleProfile() {
        val f = tmpFile()
        val store = TeamExecutionStore(f)
        assertIs<TeamExecutionSaveVerdict.Saved>(
            store.save(profile(dirId = "dir-x", members = listOf(member("m-coder", TeamExecutionKinds.WORKER, "codex")))),
        )
        // 第二个群（另一个 groupId）指向同一目录 ⇒ 同一份档，不是「没配过」。
        assertEquals(
            "m-coder",
            TeamExecutionStore(f).profileFor("dir-x", "dsh-lead")?.worker?.agentId,
            "同目录下的另一个群必须读到同一份档",
        )
        // 落盘也只剩一条（不是两条只差 groupId 的记录）。
        assertEquals(1, Regex("\"dirId\"").findAll(f.readText()).count(), "同键只许留一条")
    }

    /** 目录不同 ⇒ 两份档（同一支队在两个项目里可以有不同的驱动与模型）。 */
    @Test
    fun differentDirectoriesKeepSeparateProfiles() {
        val store = TeamExecutionStore(tmpFile())
        store.save(profile(dirId = "dir-x", members = listOf(member("m-coder", TeamExecutionKinds.WORKER, "codex"))))
        store.save(profile(dirId = "dir-y", members = listOf(member("m-coder", TeamExecutionKinds.WORKER, "claude"))))
        assertEquals("codex", store.profileFor("dir-x", "dsh-lead")?.worker?.driverKey)
        assertEquals("claude", store.profileFor("dir-y", "dsh-lead")?.worker?.driverKey)
    }

    /**
     * v2 文件（键里还有 groupId）读时**折叠**成一份，保留 `updatedAt` 最新那条。
     *
     * 不折叠的后果与「同键留两条」同一个：`profileFor` 只返回第一条，用户最后改的那次
     * 可能正好排在后面 ⇒ 配了没生效。
     */
    @Test
    fun groupScopedV2EntriesCollapseToTheLatestOne() {
        val f = tmpFile()
        f.writeText(
            """
            {"schemaVersion":2,"teams":[
              {"groupId":"g1","dirId":"dir-x","leaderAgentId":"dsh-lead","updatedAt":100,
               "members":[{"agentId":"m-coder","kind":"worker","driverKey":"codex"}]},
              {"groupId":"g2","dirId":"dir-x","leaderAgentId":"dsh-lead","updatedAt":300,
               "members":[{"agentId":"m-coder","kind":"worker","driverKey":"claude"}]},
              {"groupId":"g1","dirId":"dir-y","leaderAgentId":"dsh-lead","updatedAt":200,
               "members":[{"agentId":"m-coder","kind":"worker","driverKey":"codex"}]}
            ]}
            """.trimIndent(),
        )
        val store = TeamExecutionStore(f)
        // 同一 (dir-x, dsh-lead) 的两条合成一条，取 300 那次（claude）。
        assertEquals("claude", store.profileFor("dir-x", "dsh-lead")?.worker?.driverKey)
        assertEquals(300L, store.profileFor("dir-x", "dsh-lead")?.updatedAt)
        // 别的目录不受折叠影响。
        assertEquals("codex", store.profileFor("dir-y", "dsh-lead")?.worker?.driverKey)
    }
}
