// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 责任链的判据全在这里：**交接靠提交、摘要不灌日志、失败即停机、rev 单调**。
 *
 * 这一层不碰进程与 git（全注入），所以判据能写成普通断言 —— 真机跑一次要 30 分钟，
 * 不能让「棒次顺序」这种确定性事实也去排那个队。
 */
class StageChainTest {

    private fun stage(role: String, key: String, instruction: String = "做 $role 的活") =
        ChainStage(role = role, agentKey = key, instruction = instruction)

    // ---------------------------------------------------------------- 摘要

    @Test
    fun aBlankSummaryStaysBlankInsteadOfBecomingAnEmptyString() {
        assertEquals(null, StageChain.summarize("   \n\t "), "空白摘要要回 null，不是空串（下游要能区分「没说」和「说了空」）")
        assertEquals(null, StageChain.summarize(null))
    }

    @Test
    fun aLongSummaryIsCutAtTheBudgetWithAnHonestMarker() {
        val long = "改".repeat(500)
        val out = assertNotNull(StageChain.summarize(long))
        assertEquals(StageChain.MAX_SUMMARY, out.length, "摘要必须压在 200 字预算内，实际 ${out.length}")
        assertTrue(out.endsWith(StageChain.TRUNCATION_MARKER), "截断必须留标注，不能让下游以为看到了全文：$out")
    }

    @Test
    fun foldedWhitespaceKeepsTheSummaryReadable() {
        // CLI 的收尾说明常一行一个字，折行不折叠就截成一串空行。
        val raw = "第一行\r\n\r\n  第二行\t\t第三行"
        assertEquals("第一行 第二行 第三行", StageChain.summarize(raw))
    }

    @Test
    fun theHandoffSummaryIsWhatTheCliSaidNotItsJsonEnvelope() {
        // codex 的信封（真机抓到的形态）。
        val codex = """
            {"type":"thread.started","thread_id":"01a1"}
            {"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"已把缓存键改成 cityId"}}
            {"type":"turn.completed","usage":{"input_tokens":27813}}
        """.trimIndent()
        assertEquals("已把缓存键改成 cityId", StageChain.saidText(codex))

        // agy 的流式增量：多行 text_delta 要拼起来，而不是只留最后一行。
        val agy = """
            {"event":"step_update","step_update":{"state":"ACTIVE","tool_name":"read_file"}}
            {"event":"step_update","step_update":{"text_delta":"缓存键仍用原始 query"}}
            {"event":"step_update","step_update":{"text_delta":"，判定 FAIL"}}
        """.trimIndent()
        val said = assertNotNull(StageChain.saidText(agy))
        assertTrue(said.startsWith("缓存键仍用原始 query"), "要保留先后顺序：$said")
        assertTrue(said.contains("判定 FAIL"))
        assertFalse(said.contains("step_update"), "信封字段不许漏进摘要：$said")
        assertFalse(said.contains("read_file"), "工具调用名不算它「说的话」：$said")
    }

    @Test
    fun aPlainTextCliFallsBackToItsOwnWords() {
        assertEquals("looks fine to me", StageChain.saidText("looks fine to me"))
        assertEquals(null, StageChain.saidText("   "))
    }

    // ---------------------------------------------------------------- prompt

    @Test
    fun aStagePromptCarriesTheBaseCommitAndIsBoundedNoMatterHowBigTheSummaryWas() {
        // 交接纪律的判据是「prompt 有上界」：即便上游给了一段 5000 字的原文，
        // 经过 summarize 之后 prompt 也必须仍然是小体量（§7.7 的 200 字预算）。
        val seed = StageSeed(
            baseSha = "941598aa",
            repoPath = "/tmp/repo",
            previousSummary = StageChain.summarize("上一棒把缓存键改成城市身份\n" + "日志行\n".repeat(1000)),
            previousArtifacts = listOf("/tmp/artifacts/patch.diff"),
        )
        val prompt = StageChain.promptOf(stage("开发", "codex"), seed)

        assertTrue(prompt.contains("941598aa"), "下一棒必须知道基线提交（交接靠提交，不靠上下文）")
        assertTrue(prompt.contains("上一棒把缓存键改成城市身份"), "摘要要传下去")
        assertTrue(prompt.contains("/tmp/artifacts/patch.diff"), "产物指针要传下去")
        assertTrue(prompt.length < 2000, "prompt 要有上界，不能被上游原文灌大：实际 ${prompt.length} 字")
    }

    @Test
    fun theFirstStageGetsNoHandoffSectionAtAll() {
        val prompt = StageChain.promptOf(stage("原型", "agy"), StageSeed("abc1234", "/tmp/repo"))
        assertFalse(prompt.contains("上一棒交接"), "第一棒没有上一棒，不该出现空的交接小节")
        assertTrue(prompt.contains("abc1234"))
    }

    // ---------------------------------------------------------------- 运行

    @Test
    fun eachStageStartsFromTheCommitThePreviousOneLanded() = runBlocking {
        val prompts = mutableListOf<String>()
        val landed = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        var sha = "base0000"
        val runner = StageChainRunner(
            headSha = { "base0000" },
            runStage = { stage, prompt, _ ->
                prompts += prompt
                StageRun(ok = true, sessionId = "s-" + stage.role, patchPath = "/tmp/" + stage.role + ".diff",
                    summary = stage.role + " 完成")
            },
            land = { _, run, message ->
                landed += message
                sha = "sha-" + run.sessionId
                sha
            },
            report = { seen += it },
        )
        val stages = listOf(stage("原型", "agy"), stage("开发", "codex"), stage("验收", "claude"))
        val results = runner.run("c-1", "/tmp/repo", "天气应用", stages)

        assertEquals(3, results.size)
        assertTrue(prompts[0].contains("base0000"), "第一棒从基线开跑")
        assertTrue(prompts[1].contains("sha-s-原型"), "第二棒的起点必须是第一棒落下的提交，实际：\n${prompts[1]}")
        assertTrue(prompts[2].contains("sha-s-开发"), "第三棒的起点必须是第二棒落下的提交")
        assertTrue(prompts[1].contains("原型 完成"), "第二棒要看到第一棒的摘要")
        assertEquals(3, landed.size, "每棒都要落一次（哪怕只读的棒也要走 land 去判有没有改动）")
        assertTrue(landed[1].startsWith("chain(c-1) 2/3 开发:"), "提交信息要能一眼看出棒次：${landed[1]}")
        assertEquals("review_ready", seen.last().state)
        assertEquals(100, seen.last().percent)
        assertEquals(listOf("done", "done", "done"), seen.last().stages.map { it.second })
    }

    @Test
    fun aFailedStageStopsTheChainInsteadOfPassingAnEmptyDiffDownstream() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val runner = StageChainRunner(
            headSha = { "base0000" },
            runStage = { stage, _, _ ->
                ran += stage.role
                if (stage.role == "开发") StageRun(ok = false, error = "cli exited with code 1")
                else StageRun(ok = true, sessionId = "s-" + stage.role, summary = "ok")
            },
            land = { _, run, _ -> "sha-" + run.sessionId },
            report = { seen += it },
        )
        val results = runner.run("c-2", "/tmp/repo", "天气应用",
            listOf(stage("原型", "agy"), stage("开发", "codex"), stage("验收", "claude")))

        assertEquals(listOf("原型", "开发"), ran, "第二棒失败后第三棒绝不能开跑（假全绿的来源）")
        assertEquals(2, results.size)
        assertEquals("blocked", seen.last().state)
        assertTrue(seen.last().banner!!.contains("第 2 棒失败"), "卡面要如实说是第几棒、为什么停：${seen.last().banner}")
        assertEquals(listOf("done", "failed", "pending"), seen.last().stages.map { it.second })
    }

    @Test
    fun aStageThatChangesNothingKeepsTheSameBaseForTheNextStage() = runBlocking {
        val prompts = mutableListOf<String>()
        val runner = StageChainRunner(
            headSha = { "base0000" },
            runStage = { stage, prompt, _ ->
                prompts += prompt
                // 审查棒只读：没有 patch，也就没有提交。
                StageRun(ok = true, sessionId = "s-" + stage.role,
                    patchPath = if (stage.role == "审查") null else "/tmp/x.diff", summary = stage.role)
            },
            land = { _, run, _ -> if (run.patchPath == null) null else "sha-" + run.sessionId },
            report = {},
        )
        val results = runner.run("c-3", "/tmp/repo", "天气应用",
            listOf(stage("开发", "codex"), stage("审查", "claude")))

        assertEquals(2, results.size)
        assertEquals(null, results[1].sha, "没有改动的棒不该凭空产生一个提交")
        assertEquals(2, prompts.size)
        // 审查棒没有改动是正常结局：链条不该因此判失败。
        assertTrue(results[1].ok)
    }

    @Test
    fun theCardRevOnlyEverGoesUp() = runBlocking {
        val revs = mutableListOf<Int>()
        val runner = StageChainRunner(
            headSha = { "base0000" },
            runStage = { stage, _, _ -> StageRun(ok = true, sessionId = "s", summary = stage.role) },
            land = { _, _, _ -> "sha-next" },
            report = { revs += it.rev },
        )
        runner.run("c-4", "/tmp/repo", "t", listOf(stage("原型", "agy"), stage("开发", "codex")))
        assertEquals(revs.sorted(), revs, "rev 必须单调递增（原位刷新的前提）：$revs")
        assertEquals(revs.distinct().size, revs.size, "同一卡的 rev 不许重复：$revs")
    }

    @Test
    fun aRepoWithoutHeadIsReportedInsteadOfRunningBlind() = runBlocking {
        var ran = false
        val seen = mutableListOf<ChainProgress>()
        val runner = StageChainRunner(
            headSha = { null },
            runStage = { _, _, _ -> ran = true; StageRun(ok = true) },
            land = { _, _, _ -> null },
            report = { seen += it },
        )
        val out = runner.run("c-5", "/tmp/not-a-repo", "t", listOf(stage("原型", "agy")))
        assertEquals(emptyList(), out, "读不到基线就不该开跑")
        assertFalse(ran, "没有基线的链一棒都不能跑")
        assertEquals("blocked", seen.last().state)
        assertTrue(seen.last().banner!!.contains("HEAD"))
    }

    @Test
    fun aChainWithoutStagesIsRejectedLoudly() {
        val runner = StageChainRunner(headSha = { "x" }, runStage = { _, _, _ -> StageRun(true) }, land = { _, _, _ -> null })
        val thrown = runCatching { runBlocking { runner.run("c-6", "/tmp/repo", "t", emptyList()) } }
        assertTrue(thrown.isFailure, "空链是调用方的形状错误，不能静默跑完报成功")
    }

    // ---------------------------------------------------------------- 链卡形状

    private fun progress(state: String, percent: Int = 40) = ChainProgress(
        chainId = "cabc",
        rev = 7,
        state = state,
        title = "天气应用三棒",
        currentRole = "开发",
        stages = listOf("原型" to "done", "开发" to "running", "验收" to "pending"),
        percent = percent,
        banner = "开发 开跑",
    )

    @Test
    fun theChainCardUsesExactlyTheFieldNamesThePhoneParses() {
        // 手机端 parseAgentCard 只认 cardId/rev/type/items/progress/banner；
        // 写错一个键的后果是「群里出现一张空白卡」，两端都不报错。
        val card = StageChain.cardOf(progress("executing"))
        assertEquals("chain-cabc", card["cardId"]?.toString()?.trim('"'), "一链一卡：cardId 必须由 chainId 推出来")
        assertEquals("7", card["rev"]?.toString(), "rev 要跟着进度走（原位刷新的前提）")
        assertEquals("task-board", card["type"]?.toString()?.trim('"'),
            "type 必须是手机注册表里已有的那类（task-board = Progress + StepList + ButtonGroup）")
        assertEquals("40", card["progress"]?.toString())

        val items = card["items"]?.toString().orEmpty()
        assertTrue(items.contains("\"status\":\"done\""), "每棒状态要能看出来：$items")
        assertTrue(items.contains("\"status\":\"running\""), "进行中的那棒要标 running：$items")
    }

    @Test
    fun aBlockedChainSaysErrorAndAFinishedOneSaysSuccess() {
        assertTrue(
            StageChain.cardOf(progress("blocked"))["banner"]!!.toString().contains("\"tone\":\"error\""),
            "停机必须用 error 色，不能用中性色把事故画成进展",
        )
        assertTrue(StageChain.cardOf(progress("review_ready"))["banner"]!!.toString().contains("\"tone\":\"success\""))
    }

    @Test
    fun aPrototypeCardCarriesOptionsArtifactsAndApprovalAction() {
        val p = ChainProgress(
            chainId = "proto1",
            rev = 2,
            state = "prototype",
            title = "Weather Prototype",
            options = listOf("Layout A" to true, "Layout B" to false),
            artifacts = listOf("prototype.html" to 4096L),
            banner = "Prototype ready",
        )
        val card = StageChain.cardOf(p)
        val json = card.toString()
        assertTrue(json.contains("\"actionId\":\"approve-prototype\""), "Must carry approve-prototype action")
        assertTrue(json.contains("Layout A"), "Must carry layout options")
        assertTrue(json.contains("prototype.html"), "Must carry prototype artifact")
        assertTrue(json.contains("text/html"), "Must specify html mime")
    }

    // ---------------------------------------------------------------- 自然语言解析

    @Test
    fun parseStagesExtractsRolesAndExplicitKeys() {
        val input = "原型(agy): 设计界面草稿; 开发(codex): 实现功能逻辑; 验收: 跑回归测试"
        val stages = StageChain.parseStages(input, defaultKey = "claude")
        assertEquals(3, stages.size)
        assertEquals("原型", stages[0].role)
        assertEquals("agy", stages[0].agentKey)
        assertEquals("设计界面草稿", stages[0].instruction)

        assertEquals("开发", stages[1].role)
        assertEquals("codex", stages[1].agentKey)
        assertEquals("实现功能逻辑", stages[1].instruction)

        assertEquals("验收", stages[2].role)
        assertEquals("claude", stages[2].agentKey) // 回退 defaultKey
        assertEquals("跑回归测试", stages[2].instruction)
    }

    @Test
    fun parseStagesIgnoresSingleTaskOrNormalText() {
        val single = "开发: 只干这一件事"
        assertEquals(0, StageChain.parseStages(single).size, "少于2棒不作为链条处理（避免单任务误切入）")

        val normal = "请帮我修改一下登录界面的文案并提交 PR"
        assertEquals(0, StageChain.parseStages(normal).size)
    }

    // ---------------------------------------------------------------- 原型产物抽取

    @Test
    fun theCardDeclaresItsOwnStateInsteadOfLettingTheClientGuess() {
        val json = StageChain.cardOf(
            ChainProgress(
                chainId = "t1",
                rev = 2,
                state = "prototype",
                title = "原型",
                stages = listOf("Codex" to "prototype"),
                percent = 50,
            ),
        )
        assertEquals("prototype", (json["state"] as kotlinx.serialization.json.JsonPrimitive).content)
        // `cardOf` 是**原样转发** stages 的：调用方给什么棒级状态就写什么。所以这里
        // 断言的是「state 单独一个字段存在、不需要客户端反推」，不是「两处必须一样」——
        // 见 `stageStatusIsTranslatedFromTheCardStateInsteadOfCopied` 那条。
        assertEquals(
            "prototype",
            (json["items"] as kotlinx.serialization.json.JsonArray)
                .first().let { (it as kotlinx.serialization.json.JsonObject)["status"] }
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
    }

    /**
     * 卡级 `state` 与棒级 `item.status` 是**两套词表**，必须翻译，不能照抄。
     *
     * 照抄的后果（D6，真机踩过）：
     * - `prototype` / `executing` / `merged` / `aborted` 不在棒级词表里，渲染层
     *   `parseAgentCard` 一律退成 `pending` —— 卡上写着「在做原型」，唯一那一步却显示未开始；
     * - 翻面成 `merged` 之后卡变成 `state=merged` + `item=review_ready`，自相矛盾。
     */
    @Test
    fun stageStatusIsTranslatedFromTheCardStateInsteadOfCopied() {
        val expected = mapOf(
            "executing" to "running",
            "working" to "running",
            "prototype" to "running",
            "queued" to "running",
            "review_ready" to "done",
            "merged" to "done",
            "done" to "done",
            "blocked" to "blocked",
            // 中止没有自己的棒级词：这棒确实没跑完，`failed` 是唯一不说谎的选项。
            "aborted" to "failed",
            "pending" to "pending",
        )
        expected.forEach { (state, status) ->
            assertEquals(status, StageChain.stageStatusOf(state), "state=$state 的棒级状态")
        }
        // 反过来钉住：棒级词表**不含**任何卡级词 —— 谁再照抄一次，这条就会红。
        val cardLevel = listOf("review_ready", "merged", "prototype", "executing", "aborted")
        cardLevel.forEach { state ->
            val status = StageChain.stageStatusOf(state)
            assertTrue(
                status in setOf("pending", "running", "done", "failed", "blocked"),
                "stageStatusOf($state) 给出了棒级词表外的 \"$status\"",
            )
        }
    }

    @Test
    fun itemsAfterMergeOnlyAdvancesTheOnesWaitingForTheMerge() {
        fun items(vararg pairs: Pair<String, String>): JsonArray = JsonArray(
            pairs.map { (id, status) ->
                buildJsonObject {
                    put("id", id)
                    put("label", id)
                    put("status", status)
                }
            },
        )

        val before = items("claude" to "review_ready", "codex" to "done", "qa" to "pending", "ops" to "failed")
        val after = StageChain.itemsAfterMerge(before) as JsonArray
        assertEquals(
            listOf("done", "done", "pending", "failed"),
            after.map { ((it as JsonObject)["status"] as JsonPrimitive).content },
            "只有等合流的那棒推进到 done；没跑的和跑挂的不许被写成完成",
        )
        // 其它键一个都不能丢：assignee 之类的展示信息在真实卡上就在同一个对象里。
        assertEquals(
            "claude",
            ((after.first() as JsonObject)["label"] as JsonPrimitive).content,
        )
        // 不是数组就原样退回，别把卡面其它形状弄坏。
        assertEquals(null, StageChain.itemsAfterMerge(null))
    }

    @Test
    fun extractFileFromPatchRebuildsTheNewSideOfANewFile() {
        val patch = kotlin.io.path.createTempFile("proto", ".diff").toFile()
        patch.deleteOnExit()
        patch.writeText(
            """
            diff --git a/prototype.html b/prototype.html
            new file mode 100644
            index 0000000..1111111
            --- /dev/null
            +++ b/prototype.html
            @@ -0,0 +1,3 @@
            +<html>
            +<body>hi</body>
            +</html>
            diff --git a/other.txt b/other.txt
            --- a/other.txt
            +++ b/other.txt
            @@ -1 +1 @@
            -old
            +new
            """.trimIndent() + "\n",
        )
        val html = StageChain.extractFileFromPatch(patch, "prototype.html")
        assertEquals("<html>\n<body>hi</body>\n</html>\n", html, "新文件要按 + 行原样还原，且不能把别的文件混进来")
    }

    @Test
    fun extractFileFromPatchKeepsContextLinesOfAModifiedFile() {
        val patch = kotlin.io.path.createTempFile("proto-mod", ".diff").toFile()
        patch.deleteOnExit()
        patch.writeText(
            """
            diff --git a/prototype.html b/prototype.html
            --- a/prototype.html
            +++ b/prototype.html
            @@ -1,2 +1,2 @@
             <html>
            -<body>light</body>
            +<body>dark</body>
             </html>
            """.trimIndent() + "\n",
        )
        val html = StageChain.extractFileFromPatch(patch, "prototype.html")
        assertEquals(
            "<html>\n<body>dark</body>\n</html>\n",
            html,
            "上下文行属于新侧：只取 + 行会把改过的文件抽成残缺片段",
        )
    }

    @Test
    fun extractFileFromPatchReturnsNullWhenTheFileIsNotInThePatch() {
        val patch = kotlin.io.path.createTempFile("proto-none", ".diff").toFile()
        patch.deleteOnExit()
        patch.writeText("diff --git a/x.txt b/x.txt\n--- a/x.txt\n+++ b/x.txt\n@@ -1 +1 @@\n-a\n+b\n")
        assertEquals(null, StageChain.extractFileFromPatch(patch, "prototype.html"), "抽不到就是抽不到，不能返回空串让调用方以为拿到了")
    }

    @Test
    fun extractFileFromPatchRefusesAPartialEditItCannotRebuild() {
        val patch = kotlin.io.path.createTempFile("proto-partial", ".diff").toFile()
        patch.deleteOnExit()
        // 只改中间一行：hunk 从第 40 行开始，上下文之外的内容不在补丁里。
        patch.writeText(
            """
            diff --git a/prototype.html b/prototype.html
            --- a/prototype.html
            +++ b/prototype.html
            @@ -40,3 +40,3 @@
             <div class="a">
            -<span>light</span>
            +<span>dark</span>
             </div>
            """.trimIndent() + "\n",
        )
        assertEquals(
            null,
            StageChain.extractFileFromPatch(patch, "prototype.html"),
            "改过的文件只带 hunk：拼出来的 HTML 是残缺的，打开就白屏 —— 宁可不宣称产物",
        )
    }
}
