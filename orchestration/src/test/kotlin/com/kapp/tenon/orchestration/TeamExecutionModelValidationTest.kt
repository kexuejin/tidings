// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 执行档「模型」这一维的**保存期**判据（不变量 5）。
 *
 * 三条都必须当场拒，因为它们都会变成「配了不生效」：
 * 1. 驱动不支持按次指定模型（dsh 的模型写在 profile 配置里，命令行没有旗标）却填了模型；
 * 2. 一条成员填了多个模型（本期只消费首位，没有轮换的消费路径）；
 * 3. 模型 id 非法（既有预算，另一条测试钉着，这里不重复）。
 *
 * 反向探针：把 `TeamExecutionValidation` 里「不支持驱动」那条删掉，`dshPlusModelIsRejected`
 * 必须变红；把「多值」那条删掉，`twoModelsAreRejected` 必须变红。
 */
class TeamExecutionModelValidationTest {

    private fun tmpFile(): File = Files.createTempFile("team-exec-model", ".json").toFile()

    private fun member(
        agentId: String,
        kind: String = TeamExecutionKinds.REVIEWER,
        driverKey: String = "claude",
        models: List<String> = emptyList(),
    ) = TeamExecutionMemberSpec(agentId, kind, driverKey, models)

    private fun profile(members: List<TeamExecutionMemberSpec>) = TeamExecutionProfile(
        dirId = "ws-1", leaderAgentId = "dsh-lead",
        updatedAt = 0, members = members,
    )

    @Test
    fun dshPlusModelIsRejected() {
        val store = TeamExecutionStore(tmpFile())
        // dsh 当 worker、claude 当 reviewer（异厂商门槛过），但给 dsh 填了模型。
        val verdict = store.save(
            profile(
                listOf(
                    member("m-coder", TeamExecutionKinds.WORKER, "dsh", models = listOf("deepseek-v4.1")),
                    member("m-r", driverKey = "claude"),
                ),
            ),
        )
        val rejected = assertIs<TeamExecutionSaveVerdict.Rejected>(verdict)
        assertTrue(
            rejected.reasons.any { "cannot take a model per task" in it },
            "dsh 没有 --model 旗标 ⇒ 必须当场拒，不许静默跑 profile 里的默认模型；实际原因=" +
                rejected.reasons,
        )
    }

    @Test
    fun claudePlusOneModelIsAccepted() {
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            profile(
                listOf(
                    member("m-coder", TeamExecutionKinds.WORKER, "codex", models = listOf("gpt-5-codex")),
                    member("m-r", driverKey = "claude", models = listOf("claude-opus-4-1")),
                ),
            ),
        )
        assertEquals(TeamExecutionSaveVerdict.Saved, verdict, "支持旗标的驱动配一个模型必须能存")
    }

    @Test
    fun twoModelsAreRejected() {
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            profile(
                listOf(
                    member("m-coder", TeamExecutionKinds.WORKER, "codex", models = listOf("gpt-5-codex")),
                    member("m-r", driverKey = "claude", models = listOf("claude-opus-4-1", "claude-sonnet-4-5")),
                ),
            ),
        )
        val rejected = assertIs<TeamExecutionSaveVerdict.Rejected>(verdict)
        assertTrue(
            rejected.reasons.any { "only one model per member is supported" in it },
            "多值没有消费路径 ⇒ 存下来就是死数据；实际原因=" + rejected.reasons,
        )
    }

    @Test
    fun aliasedDriverKeyStillGetsTheModelFlag() {
        val store = TeamExecutionStore(tmpFile())
        // `claude-cli` 是档案的别名写法：归一之后应视为支持模型，不能因为写法不同被误拒。
        val verdict = store.save(
            profile(
                listOf(
                    member("m-coder", TeamExecutionKinds.WORKER, "codex", models = listOf("gpt-5-codex")),
                    member("m-r", driverKey = "claude-cli", models = listOf("claude-opus-4-1")),
                ),
            ),
        )
        assertEquals(TeamExecutionSaveVerdict.Saved, verdict, "别名写法不该被误判成「不支持模型」")
    }

    @Test
    fun emptyModelListIsAlwaysFine() {
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            profile(
                listOf(
                    member("m-coder", TeamExecutionKinds.WORKER, "dsh"),
                    member("m-r", driverKey = "claude"),
                ),
            ),
        )
        assertEquals(TeamExecutionSaveVerdict.Saved, verdict, "不填模型 = 跟随驱动默认，永远合法")
    }
}
