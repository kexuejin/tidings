// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.ArtifactPipe
import com.kapp.tenon.orchestration.DesktopStageChain

import com.kapp.tenon.agent.ChainProgress
import com.kapp.tenon.agent.StageChain
import com.kapp.tenon.core.transport.LocalFile
import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 链的**棒产物投递**判据（卡片进展评审 P1 末条遗留）。
 *
 * 单棒工单原型流早就把 prototype.html 投进群了，但责任链的 onArtifact 只捕
 * patch.diff —— 链的原型棒/方案棒产物（prototype.html、报告 md）只躺在宿主本机，
 * 卡面 artifacts 行恒空，用户手里没有任何能打开的东西。
 *
 * 这里钉三件事：
 * 1. 棒产物被收集并按既有 artifact 通道（purpose=artifact）成批投群；
 * 2. 只有**真的发起过直投**的 (name, bytes) 才上卡，且卡 JSON 里不出现本机绝对路径；
 * 3. meta 超卡预算时整批拒绝、不截断（发送侧不生产注定被对端拒收的帧）。
 */
class DesktopStageChainArtifactsTest {

    private fun harvestDir(): File = kotlin.io.path.createTempDirectory("chain-harvest-").toFile()

    /** 造一份「新建文件」的 unified diff（+ 行就是全文，和沙箱收割出的形状一致）。 */
    private fun newFilePatch(path: String, text: String): String {
        val lines = text.trimEnd('\n').lines()
        return buildString {
            appendLine("diff --git a/$path b/$path")
            appendLine("new file mode 100644")
            appendLine("--- /dev/null")
            appendLine("+++ b/$path")
            appendLine("@@ -0,0 +1,${lines.size} @@")
            lines.forEach { appendLine("+$it") }
        }
    }

    /** 收割层给「改过的文件」存的全文快照（<artifacts>/files/<相对路径>）。 */
    private fun writeSnapshot(dir: File, rel: String, text: String) {
        val file = File(File(dir, "files"), rel)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun patchOf(dir: File): String = File(dir, "patch.diff").path

    @Test
    fun stageArtifactsAreDeliveredInOneBatchAndLandOnTheCard() = runBlocking {
        val harvest = harvestDir()
        File(harvest, "patch.diff").writeText(newFilePatch("report.md", "# 方案\n\n- 甲\n- 乙"))
        // 机器对账 / 日志 / 源码改动都不是「产物」：一个都不许投。
        File(harvest, "report.json").writeText("{\"patchPath\":\"" + File(harvest, "patch.diff").absolutePath + "\"}")
        File(harvest, "task.log").writeText("[sandbox] finished")
        writeSnapshot(harvest, "src/Main.kt", "fun main() {}")
        writeSnapshot(harvest, "empty.md", "")
        writeSnapshot(harvest, "prototype.html", "<html><body>hi</body></html>")

        val collected = DesktopStageChain.collectStageArtifacts(patchOf(harvest))
        assertEquals(
            listOf("prototype.html", "report.md"),
            collected.map { it.name },
            "只收能打开的文本产物：改过文件的全文快照 + patch 里新建的 html/md；源码/对账/日志/空文件都不算",
        )
        val html = collected.first { it.name == "prototype.html" }
        assertEquals("<html><body>hi</body></html>".length.toLong(), html.bytes, "卡面字节数取真实落盘长度")

        // 假 controller：钉「投递调用」本身，以及投出去的是真实存在的全文文件。
        val batches = mutableListOf<List<LocalFile>>()
        val delivered = DesktopStageChain.deliverStageArtifacts(collected) { files ->
            batches += files
            true
        }
        assertEquals(1, batches.size, "一棒产物应作为一批走一次既有 artifact 通道")
        assertEquals(listOf("prototype.html", "report.md"), batches.single().map { it.name })
        assertTrue(batches.single().all { File(it.path).isFile }, "投出去的 LocalFile 必须是真实存在的全文文件")
        assertEquals(
            listOf("prototype.html" to html.bytes, "report.md" to collected.last().bytes),
            delivered,
            "卡面产物清单 = 真正投出去的文件（name + bytes）",
        )

        // artifacts 上卡：cardOf 渲染 name/bytes；本机绝对路径一个字节都不许进卡 JSON。
        val card = StageChain.cardOf(
            ChainProgress(
                chainId = "c-art",
                rev = 3,
                state = "executing",
                title = "原型链",
                stages = listOf("原型" to "done"),
                artifacts = delivered,
            ),
        )
        val artifacts = card["artifacts"] as JsonArray
        assertEquals(
            listOf("prototype.html", "report.md"),
            artifacts.map { ((it as JsonObject)["name"] as JsonPrimitive).content },
        )
        assertFalse(card.toString().contains(harvest.absolutePath), "本机 harvest 绝对路径进了协议帧：$card")
        assertFalse(card.toString().contains("/tmp/"), "本机临时路径进了协议帧：$card")
    }

    @Test
    fun artifactsStayOffTheCardWhenTheChannelIsNotDispatched() = runBlocking {
        val harvest = harvestDir()
        File(harvest, "patch.diff").writeText("")
        writeSnapshot(harvest, "prototype.html", "<html></html>")
        val collected = DesktopStageChain.collectStageArtifacts(patchOf(harvest))
        assertTrue(collected.isNotEmpty())

        var called = false
        // 假 controller 报「报价没发起」（节点没起 / 群不认识）：卡上不许留打不开的产物行。
        val delivered = DesktopStageChain.deliverStageArtifacts(collected) {
            called = true
            false
        }
        assertTrue(called, "投递通道必须被真的调用过，否则这条测试区分不了「没投」和「没接」")
        assertTrue(delivered.isEmpty(), "没投出去的产物不许上卡")
        val card = StageChain.cardOf(
            ChainProgress(
                chainId = "c-nod",
                rev = 1,
                state = "executing",
                title = "t",
                stages = listOf("原型" to "running"),
                artifacts = delivered,
            ),
        )
        assertFalse(card.containsKey("artifacts"), "空产物不该产出一段空的 artifacts 数组")
    }

    @Test
    fun oversizedArtifactMetaIsRefusedInsteadOfTruncated() = runBlocking {
        val harvest = harvestDir()
        File(harvest, "patch.diff").writeText("")
        val filesRoot = File(harvest, "files").apply { mkdirs() }
        repeat(400) { i -> File(filesRoot, "x".repeat(120) + "-$i.md").writeText("x") }

        val collected = DesktopStageChain.collectStageArtifacts(patchOf(harvest))
        assertEquals(400, collected.size)
        val metaBytes = ArtifactPipe.requestsCardBytes(
            collected.map { ArtifactPipe.PipeRequest(it.name, it.path, it.bytes) },
        )
        assertTrue(metaBytes > ArtifactPipe.CARD_BYTES, "夹具要真的超预算，实际 $metaBytes B")

        var called = false
        val delivered = DesktopStageChain.deliverStageArtifacts(collected) {
            called = true
            true
        }
        assertFalse(called, "meta 超卡预算必须整批拒绝（不截断后再投）——发送侧不生产注定被拒的帧")
        assertTrue(delivered.isEmpty())
    }

    /**
     * 接线守卫：判据在、投递在、但 publish 重打包时丢字段是这一层的真实失效形态
     * （details 就在真机 rev6 上整段消失过）。产物投递的调用点也只在 runOneStage 里。
     */
    @Test
    fun thePublishRepackAndTheArtifactWiringStayInPlace() {
        val source = GuardSourceScan.stripComments(
            GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt"),
        )
        val publish = GuardSourceScan.bracedBlockAfter(source, "suspend fun publish(")
        assertTrue(publish.contains("artifacts = artifacts"), "publish 重打包 ChainProgress 时漏了 artifacts —— 卡面产物行会整段消失")
        assertTrue(publish.contains("details = details"), "publish 重打包时漏了 details（真机 rev6 的旧缺陷）")
        assertTrue(publish.contains("checks = pendingChecks"), "publish 重打包时漏了 checks")
        assertTrue(publish.contains("assignees = stages.map"), "publish 重打包时漏了 assignees")

        val stageRun = GuardSourceScan.bracedBlockAfter(source, "): StageRun {")
        assertTrue(
            stageRun.contains("deliverStageArtifacts(collectStageArtifacts(patchPath))"),
            "runOneStage 没有把 harvest 产物接进投递 —— 链原型棒/方案棒的产物又只会躺在宿主本机",
        )
        assertTrue(
            stageRun.contains("controller.sendAgentArtifacts("),
            "产物没有走既有 artifact 通道（sendAgentArtifacts）—— 手搓私有通路会绕开免确认与传输台账",
        )
        assertTrue(stageRun.contains("onArtifacts(delivered)"), "投递结果没有上报 —— artifacts 上不了卡")
    }
}
