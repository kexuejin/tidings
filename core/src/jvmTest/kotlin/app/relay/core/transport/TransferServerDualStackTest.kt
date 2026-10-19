// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.fs.openRandomAccess
import app.relay.core.fs.pathJoin
import app.relay.core.testing.createTempDir
import app.relay.core.testing.removeRecursively
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * 默认绑定必须是**双栈**（task-63 真机根因）。
 *
 * 要求：**不给** `bindAddress` 时，文件传输监听要能在 **IPv6 环回**上被连上（蜂窝侧手机手里是
 * Mac 的公网 IPv6，只能走 v6 进来）。
 *
 * ## 它**不是**「17879 在蜂窝上超时」的解释（别误读）
 *
 * 我当初的假设是「固定绑 `0.0.0.0` ⇒ IPv4 only ⇒ 蜂窝侧 v6 打不通」，并写了条反面 canary
 * 断言「显式绑 `0.0.0.0` 时 `::1` 连不上」。**canary 当场变红**：ktor 绑 `0.0.0.0` 的监听
 * **能**被 `::1` 连上（而 Python 绑 `0.0.0.0` 时 `nc -6 ::1` 不通 —— 差异在 ktor 的 bind 实现）。
 * ⇒ 那个假设**被证伪**，`TransferServer` 的绑定地址已改回原样，17879 的 rc=124 另有原因。
 *
 * 这条留着当**属性守卫**：以后谁把监听绑到某个具体 IPv4 地址上（或换成 v4-only 的 API），
 * 它会立刻红 —— 那个改动确实会让蜂窝侧连不进来。
 */
class TransferServerDualStackTest {

    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        dirs.forEach { removeRecursively(it) }
        dirs.clear()
    }

    @Test
    fun aDefaultBoundServerIsReachableOverIpv6() = runBlocking {
        val dir = createTempDir("relay-dualstack").also { dirs += it }
        val src = pathJoin(dir, "src.bin")
        val payload = ByteArray(8_192) { (it % 251).toByte() }
        val handle = openRandomAccess(src, forWrite = true)
        try {
            handle.append(payload, payload.size)
            handle.flush()
        } finally {
            handle.close()
        }
        val dstDir = pathJoin(dir, "dst")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = OutgoingFile("f1", src, payload.size.toLong())
            val server = TransferServer(
                scope = scope,
                lookup = object : OutgoingLookup {
                    override fun resolve(transferId: String, fileId: String): OutgoingFile? =
                        if (fileId == "f1") file else null
                },
                // 故意**不给** bindAddress：走的就是生产默认路径。
            )
            assertTrue(server.start(0), "服务端绑定失败")
            val port = server.boundPort ?: error("已启动却没有 boundPort")

            pullTransfer(
                PullOptions(
                    host = "::1",
                    port = port,
                    selfId = "self-1",
                    transferId = "t-dualstack",
                    files = listOf(IncomingFilePlan("f1", "src.bin", payload.size.toLong())),
                    saveDir = dstDir,
                ),
            )
            assertContentEquals(
                payload,
                readAll(pathJoin(dstDir, "src.bin")),
                "IPv6 环回上也必须能拉完（修之前这里会连不上 17879 那类 IPv4-only 监听）",
            )
        } finally {
            scope.cancel()
        }
    }

    private fun readAll(path: String): ByteArray {
        val handle = openRandomAccess(path, forWrite = false)
        try {
            val size = handle.size.toInt()
            val out = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val n = handle.read(out, offset.toLong(), size - offset)
                if (n <= 0) break
                offset += n
            }
            return if (offset == size) out else out.copyOf(offset)
        } finally {
            handle.close()
        }
    }
}
