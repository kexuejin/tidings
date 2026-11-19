// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 「目录已知但不含该模型」这条判据的保存期测试。
 *
 * ## 守的是什么
 *
 * 用户从没见过的模型 id 手填进去，旧实现一路存下来、派单时才由 CLI 失败（更糟的是静默跑
 * 默认模型）。有目录之后，**已知且不含**必须当场拒。
 *
 * ## 反向验证（两条方向相反的坑）
 *
 * 1. 把 `knownModels` 的判据改成「不在表里就拒」⇒ [catalogUnknownDriverStillSaves] 红：
 *    驱动没探到（缺键）时会把一切合法模型拒掉 —— 离线/未登录时整盘不能保存。
 * 2. 把「已知不含则拒」整段删掉 ⇒ [modelOutsideKnownCatalogIsRejected] 红。
 *
 * 两条同时钉住，才能区分「没验到」与「验坏了」。
 */
class TeamExecutionModelCatalogValidationTest {

    private fun tmpFile(): File = Files.createTempFile("team-exec-catalog", ".json").toFile()

    private fun member(
        agentId: String,
        kind: String = TeamExecutionKinds.REVIEWER,
        driverKey: String = "claude",
        models: List<String> = emptyList(),
    ) = TeamExecutionMemberSpec(agentId, kind, driverKey, models)

    private fun profile(members: List<TeamExecutionMemberSpec>) = TeamExecutionProfile(
        dirId = "ws-1", leaderAgentId = "dsh-lead", updatedAt = 0, members = members,
    )

    /** worker=codex / reviewer=claude（异厂商过），reviwer 填模型 —— 校验只看目录。 */
    private fun twoMemberProfile(model: String?) = profile(
        listOf(
            member("m-coder", TeamExecutionKinds.WORKER, "codex"),
            member(
                "m-r",
                driverKey = "claude",
                models = if (model == null) emptyList() else listOf(model),
            ),
        ),
    )

    @Test
    fun modelOutsideKnownCatalogIsRejected() {
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            twoMemberProfile("gpt-4o"),
            knownModels = mapOf("claude" to setOf("claude-opus-4-1", "claude-sonnet-4-5")),
        )
        val rejected = assertIs<TeamExecutionSaveVerdict.Rejected>(verdict)
        assertTrue(
            rejected.reasons.any { "is not in driver 'claude' catalog" in it },
            "目录已知却不含 gpt-4o ⇒ 必须当场拒；实际原因=" + rejected.reasons,
        )
    }

    @Test
    fun modelInsideKnownCatalogSaves() {
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            twoMemberProfile("claude-opus-4-1"),
            knownModels = mapOf("claude" to setOf("claude-opus-4-1", "claude-sonnet-4-5")),
        )
        assertEquals(TeamExecutionSaveVerdict.Saved, verdict)
    }

    @Test
    fun catalogUnknownDriverStillSaves() {
        // 这家没探到（表里缺键）⇒ 放行。把判据写成「不在表里就拒」时本条必红。
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            twoMemberProfile("anything-the-user-typed"),
            knownModels = mapOf("codex" to setOf("gpt-5-codex")),
        )
        assertEquals(TeamExecutionSaveVerdict.Saved, verdict)
    }

    @Test
    fun noCatalogAtAllStillSaves() {
        // 从没探过目录（手机端 / 老装配 / 没打开过编制）⇒ 不放行才是灾难。
        val store = TeamExecutionStore(tmpFile())
        assertEquals(TeamExecutionSaveVerdict.Saved, store.save(twoMemberProfile("gpt-4o")))
    }

    @Test
    fun driverKeyAliasResolvesToCatalogEntry() {
        // 档案键归一后再查目录：`claude-cli` 与 `claude` 是同一家，缺了归一会永远查不到。
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            profile(
                listOf(
                    member("m-coder", TeamExecutionKinds.WORKER, "codex"),
                    member("m-r", driverKey = "claude-cli", models = listOf("claude-opus-4-1")),
                ),
            ),
            knownModels = mapOf("claude" to setOf("claude-opus-4-1")),
        )
        assertEquals(TeamExecutionSaveVerdict.Saved, verdict)
    }
}
