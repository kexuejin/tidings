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
 * 这里钉的是**本档自己的**规则：主键三元组、保存校验矩阵（恰好 1 worker /
 * 异厂商 / 模型预算 / 档 ⊆ 声明）、remove（团队解散生命周期）、悬空读时判过期。
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
        groupId: String = "g1",
        wsId: String = "ws-1",
        leader: String = "dsh-lead",
        members: List<TeamExecutionMemberSpec>,
    ): TeamExecutionProfile = TeamExecutionProfile(
        groupId = groupId, dirId = wsId, leaderAgentId = leader,
        updatedAt = 0, members = members,
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
        assertEquals(p.members, TeamExecutionStore(f).profileFor("g1", "ws-1", "dsh-lead")?.members)
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
        assertNull(missing.profileFor("g1", "ws-1", "dsh-lead"))

        val corrupt = tmpFile()
        corrupt.writeText("{ not json")
        assertNull(TeamExecutionStore(corrupt).profileFor("g1", "ws-1", "dsh-lead"))

        val unknown = tmpFile()
        unknown.writeText("""{"schemaVersion":99,"teams":[]}""")
        assertNull(TeamExecutionStore(unknown).profileFor("g1", "ws-1", "dsh-lead"))
    }

    /** remove（§3.5 团队解散生命周期）：同主键条目消失，其它条目不受影响。 */
    @Test
    fun removeDeletesOnlyTheMatchingSeat() {
        val f = tmpFile()
        val store = TeamExecutionStore(f)
        assertTrue(
            store.save(
                profile(wsId = "ws-1", members = listOf(member("m-coder", TeamExecutionKinds.WORKER, "codex"))),
            ) is TeamExecutionSaveVerdict.Saved,
        )
        assertTrue(
            store.save(
                profile(wsId = "ws-2", leader = "dsh-lead-2", members = listOf(member("m-coder2", TeamExecutionKinds.WORKER, "codex"))),
            ) is TeamExecutionSaveVerdict.Saved,
        )
        assertTrue(store.remove("g1", "ws-1", "dsh-lead"))
        assertNull(store.profileFor("g1", "ws-1", "dsh-lead"))
        assertFalse(store.remove("g1", "ws-1", "dsh-lead"), "条目已不存在 = false（正常）")
        assertTrue(store.profileFor("g1", "ws-2", "dsh-lead-2") != null, "别的席位不受影响")
    }
}
