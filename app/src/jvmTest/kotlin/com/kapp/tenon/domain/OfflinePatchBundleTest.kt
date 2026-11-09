// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.transport.LocalFile
import com.kapp.tenon.domain.model.TransferState
import com.kapp.tenon.platform.DataDirectoryProvider
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.awaitUntil
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * **§6.3 脱网去中心化 Git 合流：补丁真的走 P2P 文件通道到了对端。**
 *
 * ## 这一条补的是什么
 *
 * 审阅卡上一直写着「产物：patch.diff，N 字节」，而那个文件**从没离开过宿主本机** ——
 * 补丁躺在沙箱收割目录里，手机上那一行只是一段描述。计划书 §6.3 的判据是
 * 「通过 Tidings 本地 P2P 文件传输通道直接投递至移动端」，在此之前这句话没有落点。
 *
 * ## 为什么必须用两个真节点
 *
 * 只看「调用过 `sendAgentArtifacts`」证明不了任何事：报价可能压根没发起
 * （节点没起、群不认识），也可能发了没人收。所以这里同 JVM 起两个 [RelayController]、
 * 各绑真实 UDP/TCP 端口、回环互认，然后断言**对端磁盘上出现了与发送侧逐字相同的字节**。
 *
 * 全程不碰任何外部服务：没有 GitHub、没有 GitLab、没有中继。这正是「脱网」二字的判据 ——
 * 局域网里两台设备就够了。
 *
 * ## 判据分三层，缺一层都能骗人
 *
 * 1. **发起**：`sendAgentArtifacts` 返回 true（报价真的交给了传输层，不是被提前 return 掉）；
 * 2. **到达**：对端会话里出现 `patch.diff` 的文件卡；
 * 3. **字节一致**：卡片 `savedPaths` 指向的文件内容与发送侧逐字相同 —— 只有这一条
 *    能区分「卡到了」与「文件到了」。
 */
class OfflinePatchBundleTest {

    private val scope = CoroutineScope(Job())

    private val root: File = Files.createTempDirectory("tidings-offline-bundle").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun thePatchTravelsOverTheP2pFileChannelAndLandsByteForByte() = runBlocking {
        val portA = freePort()
        val portB = freePort()
        val a = controller("a", udpPort = portA, tcpPort = portA + 1)
        val b = controller("b", udpPort = portB, tcpPort = portB + 1)
        try {
            a.start()
            b.start()
            awaitUntil("A 起来", timeoutMs = 60_000) { a.state.value.network.phase.name == "Running" }
            awaitUntil("B 起来", timeoutMs = 60_000) { b.state.value.network.phase.name == "Running" }
            val selfA = a.state.value.selfNodeId
            val selfB = b.state.value.selfNodeId
            a.addManualPeer("127.0.0.1", portB.toString())
            b.addManualPeer("127.0.0.1", portA.toString())
            awaitUntil("A 看见 B", timeoutMs = 60_000) { a.state.value.peers.any { it.nodeId == selfB } }
            awaitUntil("B 看见 A", timeoutMs = 60_000) { b.state.value.peers.any { it.nodeId == selfA } }

            // 一份**像真补丁**的内容：带换行、带 +/− 行、带非 ASCII 路径。
            // 纯 ASCII 单行的样本测不出编码/换行被改写这类问题。
            val patchText = """
                diff --git a/src/weather.js b/src/weather.js
                --- a/src/weather.js
                +++ b/src/weather.js
                @@ -1,2 +1,3 @@
                -const city = raw
                +const city = normalizeQuery(raw)
                +// 备注：大小写容错
                 module.exports = { city }
            """.trimIndent() + "\n"
            val patch = File(root, "patch.diff").apply { writeText(patchText) }

            // ① 发起：报价必须真的交出去（false = 节点/群状态不对，产物根本没上路）。
            val dispatched = a.sendAgentArtifacts(
                selfB,
                listOf(LocalFile(path = patch.absolutePath, name = "patch.diff", size = patch.length())),
            )
            assertTrue(dispatched, "报价没发起 —— 产物根本没上路（节点没起 / 对端不认识 / 文件超限）")

            // ② 到达：对端会话里必须出现这张文件卡，且走到 Completed。
            awaitUntil("B 收到 patch.diff 并传完") {
                b.state.value.messages[selfA].orEmpty().any { row ->
                    row.file?.name == "patch.diff" && row.file?.state == TransferState.Completed
                }
            }
            val card = assertNotNull(
                b.state.value.messages[selfA].orEmpty()
                    .firstOrNull { it.file?.name == "patch.diff" && it.file?.state == TransferState.Completed }
                    ?.file,
            )
            assertEquals(false, card.mine, "在 B 这一侧它是收到的")
            assertEquals(patch.length(), card.size, "大小要对得上")

            // ③ 字节一致：只有这一条能区分「卡到了」与「文件到了」。
            val savedPath = card.savedPaths.firstOrNull()
            assertNotNull(savedPath, "Completed 必须带 savedPaths —— 否则用户不知道文件在哪")
            val saved = File(savedPath)
            assertTrue(saved.isFile, "落盘文件必须真的存在：$savedPath")
            assertEquals(patchText, saved.readText(), "补丁内容必须逐字到达（换行与中文注释都不许被改写）")

            println(
                "[offline-bundle] patch.diff " + patch.length() + " B 经 P2P 落盘到 " + savedPath +
                    "（全程无外部服务）",
            )
        } finally {
            a.stop()
            b.stop()
            // **等它们真的停下再走。** 本类跑在整包 700+ 用例的同一个 JVM 里，紧接着还有别的
            // 双节点用例要绑端口；`stop()` 是异步的，不等它落地就退出，后面的节点会抢到
            // 一个「刚被释放」的端口，症状是那边 `start()` 后 phase 永远不到 Running。
            runCatching {
                awaitUntil("A 停下") { a.state.value.network.phase.name != "Running" }
                awaitUntil("B 停下") { b.state.value.network.phase.name != "Running" }
            }
        }
    }

    /** 两个节点各自的私有目录：共享 `/tmp/relay-preview` 会让两边的下载目录撞在一起。 */
    private fun controller(name: String, udpPort: Int, tcpPort: Int): RelayController {
        val dir = File(root, name).apply { mkdirs() }
        return RelayController(
            platform = InMemoryAppPlatform(
                dataDirectory = object : DataDirectoryProvider {
                    override fun dataDirectory(): String = dir.absolutePath
                    override fun cacheDirectory(): String = File(dir, "cache").apply { mkdirs() }.absolutePath
                },
            ),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = udpPort,
                    tcpPort = tcpPort,
                    persistMessages = false,
                    persistMessagesDecided = true,
                ),
            ),
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
