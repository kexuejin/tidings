// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 残片出口的护栏：`leftoverParts()` 与 `discardPartFile()` 之间那**一层坐标系偏移**。
 *
 * 这里的坑已经踩过一次：`TransferManager.discardPart(path)` 收的是**正式文件名**
 * （`x`），它自己补 `.part`；而 `leftoverParts()` 交出来的 `PartFile.path` 是**磁盘上
 * 那个已经带 `.part` 的名字**（`x.part`）。把后者直接喂给前者，后缀会叠成
 * `.part.part`，于是"清理残片"一个字节都没删掉，界面还回一个漂亮的数字。
 *
 * 所以测试分三件事：读得到、按对坐标删得掉、按错坐标**删不掉**（最后这条是反向
 * 护栏 —— 它钉住"两个坐标系确实不同"这个事实，免得以后有人把 `discardPartial`
 * 改成自动去后缀，把 `existingPartSize` 那条路一起弄坏）。
 *
 * 为什么放在 `jvmTest`：要真的在磁盘上造 `.part` 文件，而且 `start()` 会先扫一遍，
 * 顺序错了就什么都读不到。
 */
class NodeRuntimeLeftoverPartsTest {

    private val dirs = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        dirs.forEach { removeRecursively(it) }
        dirs.clear()
    }

    @Test
    fun leftoverPartsSeesWhatTheSweepCouldNotDelete() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = newDir()
        val runtime = newNode(dir)
        try {
            runtime.start(scope)
            // 必须在 start() 之后造：start() 会扫一遍，把没有在途传输认领的 .part 当场删掉。
            write(dir, "photo.png.part", 5)
            write(dir, "archive.zip.part", 7)
            write(dir, "notes.txt", 3)

            val parts = runtime.leftoverParts().sortedBy { it.path }
            assertEquals(2, parts.size, "只认 .part，普通文件不算残片")
            // 目录遍历顺序不是契约，只钉住「这两个大小都认出来了」。
            assertEquals(listOf(5L, 7L), parts.map { it.size }.sorted())
            assertTrue(parts.all { it.path.endsWith(".part") }, "交出来的是磁盘上的名字")
            assertTrue(parts.none { it.path.endsWith("notes.txt") })
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    @Test
    fun discardPartFileRemovesTheFileAndTheCountDrops() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = newDir()
        val runtime = newNode(dir)
        try {
            runtime.start(scope)
            write(dir, "half.bin.part", 11)
            val part = runtime.leftoverParts().single()

            // partialSize 收的是正式文件名（不带 .part），和 PartFile.path 差一层。
            assertEquals(11L, runtime.partialSize(part.path.removeSuffix(".part")))

            runtime.discardPartFile(part.path)

            assertFalse(File(part.path).exists(), "按磁盘路径删，文件必须真的没了")
            assertTrue(runtime.leftoverParts().isEmpty(), "删完再数就是 0，不是嘴上说 0")
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    @Test
    fun feedingADiskPathToDiscardPartialDeletesNothing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = newDir()
        val runtime = newNode(dir)
        try {
            runtime.start(scope)
            write(dir, "stale.img.part", 4)
            val part = runtime.leftoverParts().single()

            // 反向护栏：错坐标（磁盘路径喂给收正式名的那一个）会去找 stale.img.part.part。
            runtime.discardPartial(part.path)
            assertTrue(
                File(part.path).exists(),
                "错坐标必须删不掉 —— 能删掉说明两个坐标系被谁悄悄合并了",
            )
            assertEquals(1, runtime.leftoverParts().size)

            // 换回对的那一个，同一个文件立刻就没了。
            runtime.discardPartFile(part.path)
            assertFalse(File(part.path).exists())
            assertTrue(runtime.leftoverParts().isEmpty())
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    private fun newDir(): String = createTempDir("relay-leftover-").also { dirs += it }

    private fun write(dir: String, name: String, bytes: Int) {
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes) { 0x2A })
        assertTrue(file.length() == bytes.toLong(), "前提：$name 真的落了 $bytes 字节")
    }

    private fun newNode(downloadDir: String) = NodeRuntime(
        profile = Profile.mobile(
            nodeId = newId(),
            nick = "leftover-parts-test",
            host = "localhost",
            tcpPort = 0,
            ver = "test",
        ),
        udpPort = 0,
        tcpPort = 0,
        downloadDir = { downloadDir },
    )
}
