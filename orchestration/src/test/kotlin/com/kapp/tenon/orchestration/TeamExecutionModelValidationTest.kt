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
    fun dshPlusModelIsAccepted() {
        // **这条曾断言相反的事。** 旧判据是「dsh 没有 --model 旗标 ⇒ 填模型必须拒」；
        // 实测发现 dsh 支持按次指定，走它自己的 `--patch` 配置覆盖（patch 写对时真跑通，
        // 拿到 "OK"；写不存在的 id 当场报 UNKNOWN_MODEL）。所以现在应当**接受**。
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            profile(
                listOf(
                    member("m-coder", TeamExecutionKinds.WORKER, "dsh", models = listOf("deepseek-v4.1-flash")),
                    member("m-r", driverKey = "claude"),
                ),
            ),
        )
        assertEquals(TeamExecutionSaveVerdict.Saved, verdict)
    }

    @Test
    fun zcodePlusModelIsRejected() {
        // 真正没有按次通道的一家：zcode 只能在应用内选模型。
        // 「配了模型、跑的还是默认模型」必须有保存期判据兜住。
        val store = TeamExecutionStore(tmpFile())
        val verdict = store.save(
            profile(
                listOf(
                    member("m-coder", TeamExecutionKinds.WORKER, "zcode", models = listOf("glm-5.3")),
                    member("m-r", driverKey = "claude"),
                ),
            ),
        )
        val rejected = assertIs<TeamExecutionSaveVerdict.Rejected>(verdict)
        assertTrue(
            rejected.reasons.any { "cannot take a model per task" in it },
            "zcode 没有按次通道 ⇒ 必须当场拒，不许静默跑它自己配置里的默认模型；实际原因=" +
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
