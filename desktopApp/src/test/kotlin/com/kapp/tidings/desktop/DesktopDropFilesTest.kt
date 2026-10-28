// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import com.kapp.tidings.domain.DesktopPaneActions
import com.kapp.tidings.ui.desktop.DesktopPreviewActions
import com.kapp.tidings.core.transport.LocalFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **拖进来的目录必须被展开成文件列表，而不是当成一个「文件」发出去。**
 *
 * ## 为什么这条值得单独测
 *
 * 不展开时的表现是**静默失败**：目录被塞进 `LocalFile(path=目录, size=目录项大小)`，
 * 对端收到一个打不开的东西。界面上没有报错，只是「传过去了但打不开」。
 *
 * ## 对照的是茶话间的 `walkDir`
 *
 * 规则一致的部分：递归、只收普通文件、`根/子/文件` 相对路径、空目录单独计数。
 * **故意不同的一处**：空目录这里只计数并跳过（发送侧不设置 `isDir`，硬塞进去会让服务端
 * 把它当文件打开而报错），由调用方如实告知用户。
 */
class DesktopDropFilesTest {

    private val roots = mutableListOf<File>()

    private fun tempDir(tag: String): File {
        val d = File.createTempFile("drop-$tag", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        roots += d
        return d
    }

    private fun write(dir: File, rel: String, bytes: Int = 3): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes) { 7 })
        return f
    }

    @AfterTest
    fun cleanup() {
        roots.forEach { runCatching { it.deleteRecursively() } }
    }

    /** 目录被展开：相对路径带上根目录名，接收端才能还原目录树。 */
    @Test
    fun aDroppedFolderIsExpandedIntoFilesWithRelativePaths() {
        val root = tempDir("expand")
        val folder = File(root, "相册").apply { mkdirs() }
        write(folder, "a.jpg", 5)
        write(folder, "子目录/b.jpg", 7)

        val payload = expandDropped(listOf(folder))

        assertTrue(payload.isFolder, "拖的是目录 ⇒ 按文件夹传")
        assertEquals("相册", payload.folderRootName, "单一路径用目录自己的名字")
        assertEquals(
            listOf("相册/a.jpg", "相册/子目录/b.jpg"),
            payload.files.map { it.name }.sorted(),
            "相对路径必须是 根/子/文件，且递归进子目录",
        )
        assertEquals(5L + 7L, payload.files.sumOf { it.size }, "大小按真实文件算")
        assertFalse(payload.overflow)
    }

    /** 普通文件不受影响：不走文件夹通道。 */
    @Test
    fun plainFilesAreNotTreatedAsAFolder() {
        val root = tempDir("plain")
        val f = write(root, "note.txt", 11)

        val payload = expandDropped(listOf(f))

        assertFalse(payload.isFolder, "普通文件不该被标成文件夹")
        assertEquals(null, payload.folderRootName)
        assertEquals(listOf("note.txt"), payload.files.map { it.name })
    }

    /**
     * **空目录只计数、不进文件列表。**
     *
     * 这是与茶话间故意不同的一处：发送侧不设置 `isDir`，把目录塞进去会让服务端把它当
     * 文件打开而失败。跳过之后由调用方如实告知，而不是假装传成功。
     */
    @Test
    fun emptyFoldersAreCountedButNotSent() {
        val root = tempDir("empty")
        val folder = File(root, "外层").apply { mkdirs() }
        write(folder, "有内容.txt", 1)
        File(folder, "空子目录").mkdirs()

        val payload = expandDropped(listOf(folder))

        assertEquals(1, payload.files.size, "只发真文件")
        assertEquals(1, payload.skippedEmptyDirs, "空的子目录要计数（好让调用方如实告知）")
    }

    /**
     * **超过上限就停下并标记 overflow，不静默截断后照发。**
     *
     * 截断后照发等于悄悄少传一半文件 —— 那比明确拒绝坏得多。
     */
    @Test
    fun exceedingTheLimitStopsAndFlagsOverflow() {
        val root = tempDir("limit")
        val folder = File(root, "很多").apply { mkdirs() }
        repeat(5) { write(folder, "f$it.txt", 1) }

        val payload = expandDropped(listOf(folder), limit = 3)

        assertTrue(payload.overflow, "超限必须标记出来")
        assertEquals(3, payload.files.size, "在界限处停下")
    }

    /** 拖进来多个东西且有目录 ⇒ 走文件夹通道，名字用「第一个 等 N 个文件」。 */
    @Test
    fun mixedDropsUseTheFolderChannelWithASummaryName() {
        val root = tempDir("mixed")
        val folder = File(root, "照片").apply { mkdirs() }
        write(folder, "x.png", 2)
        val single = write(root, "readme.md", 4)

        val payload = expandDropped(listOf(folder, single))

        assertTrue(payload.isFolder)
        // 多条路径的显示名（「第一个 等 N 个文件」）由 core 拼，这里**故意不传** ——
        // 否则同一句话会有两个出处，而且桌面层拼它会把中文写死进 cjkLiteralGate 的范围。
        assertEquals(null, payload.folderRootName)
        assertEquals(2, payload.files.size)
    }

    /** 下载旁车 `.sha256` 不该再被当成附件发一遍（与拖入的既有过滤保持一致）。 */
    @Test
    fun checksumSidecarsAreSkipped() {
        val root = tempDir("sha")
        val real = write(root, "a.bin", 9)
        write(root, "a.bin.sha256", 64)

        val payload = expandDropped(listOf(real, File(root, "a.bin.sha256")))

        assertEquals(listOf("a.bin"), payload.files.map { it.name })
    }

    private class FakeDesktopPaneActions : DesktopPaneActions by DesktopPreviewActions {
        var sentPeerId: String? = null
        var sentFiles: List<LocalFile> = emptyList()
        var isFolder: Boolean = false
        var folderRootName: String? = null

        override fun sendFiles(
            peerId: String,
            files: List<LocalFile>,
            isFolder: Boolean,
            folderRootName: String?,
        ) {
            sentPeerId = peerId
            sentFiles = files
            this.isFolder = isFolder
            this.folderRootName = folderRootName
        }
    }

    private val testTexts = DropTexts(
        pickConversation = "pick",
        tooMany = "tooMany",
        emptyDirsSkipped = "emptyDirsSkipped",
    )

    /** 选中会话时拖入普通文件：暂存到附件区，不立即发出 offer。 */
    @Test
    fun whenPeerSelectedAndFilesDroppedTheyAreStagedNotSent() {
        val root = tempDir("stage")
        val f = write(root, "photo.jpg", 100)
        val actions = FakeDesktopPaneActions()
        var staged = listOf<LocalFile>()

        handleDroppedFiles(
            controller = actions,
            peerId = "peer-1",
            dropped = listOf(f),
            notify = {},
            texts = testTexts,
            onStageFiles = { staged = it },
        )

        assertEquals(1, staged.size, "文件必须交给暂存回调")
        assertEquals("photo.jpg", staged.first().name)
        assertEquals(null, actions.sentPeerId, "暂存后不得直接发送 offer")
    }

    /** 选中会话时拖入文件夹：继续走文件夹传输通道直发，不落暂存区。 */
    @Test
    fun whenPeerSelectedAndFolderDroppedItIsSentNotStaged() {
        val root = tempDir("folder-send")
        val folder = File(root, "docs").apply { mkdirs() }
        write(folder, "doc1.txt", 10)
        val actions = FakeDesktopPaneActions()
        var staged = listOf<LocalFile>()

        handleDroppedFiles(
            controller = actions,
            peerId = "peer-1",
            dropped = listOf(folder),
            notify = {},
            texts = testTexts,
            onStageFiles = { staged = it },
        )

        assertTrue(staged.isEmpty(), "文件夹不得落入普通附件暂存区")
        assertEquals("peer-1", actions.sentPeerId, "文件夹直接发给选中对端")
        assertTrue(actions.isFolder, "必须标明为文件夹传输")
    }

    /** 未选中会话时拖入文件：触发目标选择器，不暂存也不直发。 */
    @Test
    fun whenPeerNotSelectedDropTriggersOnPickTarget() {
        val root = tempDir("no-peer")
        val f = write(root, "file.pdf", 20)
        val actions = FakeDesktopPaneActions()
        var pickedPayload: DroppedPayload? = null
        var staged = listOf<LocalFile>()

        handleDroppedFiles(
            controller = actions,
            peerId = null,
            dropped = listOf(f),
            notify = {},
            texts = testTexts,
            onPickTarget = { pickedPayload = it },
            onStageFiles = { staged = it },
        )

        assertTrue(staged.isEmpty(), "未选会话时不得暂存")
        assertEquals(null, actions.sentPeerId, "未选会话时不得发送")
        assertEquals(1, pickedPayload?.files?.size, "必须交给 onPickTarget 弹窗选人")
    }

    /** 未接入暂存回调时：安全回退为直发。 */
    @Test
    fun whenOnStageFilesNotProvidedFallsBackToSend() {
        val root = tempDir("fallback")
        val f = write(root, "fallback.txt", 15)
        val actions = FakeDesktopPaneActions()

        handleDroppedFiles(
            controller = actions,
            peerId = "peer-2",
            dropped = listOf(f),
            notify = {},
            texts = testTexts,
            onStageFiles = null,
        )

        assertEquals("peer-2", actions.sentPeerId, "无暂存回调时回退直发")
        assertFalse(actions.isFolder)
        assertEquals(1, actions.sentFiles.size)
    }
}
