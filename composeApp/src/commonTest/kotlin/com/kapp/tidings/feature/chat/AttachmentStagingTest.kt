// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.core.transport.LocalFile
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentStagingTest {

    @Test
    fun maxStagedAttachmentsConstantIsTen() {
        assertEquals(10, MAX_STAGED_ATTACHMENTS, "附件暂存上限必须为 10")
    }

    @Test
    fun stagedFilesAccumulateAndDeduplicateByPath() {
        val initial = listOf(
            LocalFile(path = "/tmp/a.png", name = "a.png", size = 100),
            LocalFile(path = "/tmp/b.png", name = "b.png", size = 200),
        )
        val incoming = listOf(
            LocalFile(path = "/tmp/b.png", name = "b.png", size = 200), // duplicate
            LocalFile(path = "/tmp/c.png", name = "c.png", size = 300),
        )

        val merged = (initial + incoming).distinctBy { it.path }.take(MAX_STAGED_ATTACHMENTS)

        assertEquals(3, merged.size, "重复路径自动去重")
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png", "/tmp/c.png"), merged.map { it.path })
    }

    @Test
    fun stagedFilesCappedAtMaxLimit() {
        val incoming = (1..15).map {
            LocalFile(path = "/tmp/file$it.txt", name = "file$it.txt", size = it.toLong())
        }

        val staged = incoming.distinctBy { it.path }.take(MAX_STAGED_ATTACHMENTS)

        assertEquals(MAX_STAGED_ATTACHMENTS, staged.size, "超过上限的文件必须被截断到 10 个")
        assertEquals("/tmp/file1.txt", staged.first().path)
        assertEquals("/tmp/file10.txt", staged.last().path)
    }

    @Test
    fun singleFileCanBeRemovedFromStaging() {
        val staged = listOf(
            LocalFile(path = "/tmp/1.txt", name = "1.txt", size = 10),
            LocalFile(path = "/tmp/2.txt", name = "2.txt", size = 20),
            LocalFile(path = "/tmp/3.txt", name = "3.txt", size = 30),
        )

        val toRemove = staged[1]
        val updated = staged.filterNot { it.path == toRemove.path }

        assertEquals(2, updated.size)
        assertEquals(listOf("/tmp/1.txt", "/tmp/3.txt"), updated.map { it.path })
    }

    @Test
    fun draftCoexistsWithStagedFiles() {
        var draft = "正在编辑的草稿"
        var staged = listOf(LocalFile("/tmp/doc.pdf", "doc.pdf", 1024))

        // 增加附件不影响草稿
        staged = (staged + listOf(LocalFile("/tmp/img.png", "img.png", 2048))).distinctBy { it.path }
        assertEquals("正在编辑的草稿", draft, "添加附件不改变草稿内容")
        assertEquals(2, staged.size)

        // 修改草稿不影响附件
        draft = "修改后的草稿内容"
        assertEquals(2, staged.size, "修改草稿不改变暂存附件")

        // 移除附件不影响草稿
        staged = staged.filterNot { it.path == "/tmp/doc.pdf" }
        assertEquals("修改后的草稿内容", draft, "单删附件不改变草稿内容")
        assertEquals(1, staged.size)
    }

    @Test
    fun combinedSendingSemantics() {
        var sentText: String? = null
        var sentFiles: List<LocalFile>? = null

        fun simulateSubmit(
            body: String,
            staged: List<LocalFile>,
            onSend: (String) -> Unit,
            onSendFiles: ((List<LocalFile>) -> Unit)?,
        ) {
            val filesToSend = staged
            if (body.isNotBlank() || filesToSend.isEmpty()) {
                onSend(body)
            }
            if (filesToSend.isNotEmpty()) {
                onSendFiles?.invoke(filesToSend)
            }
        }

        // 1. 图文混发：正文与附件同时触发发送
        simulateSubmit(
            body = "附言说明",
            staged = listOf(LocalFile("/tmp/a.png", "a.png", 100)),
            onSend = { sentText = it },
            onSendFiles = { sentFiles = it },
        )
        assertEquals("附言说明", sentText)
        assertEquals(1, sentFiles?.size)

        // 2. 纯文本发送：只发文本，不触发附件发送
        sentText = null
        sentFiles = null
        simulateSubmit(
            body = "仅文本",
            staged = emptyList(),
            onSend = { sentText = it },
            onSendFiles = { sentFiles = it },
        )
        assertEquals("仅文本", sentText)
        assertEquals(null, sentFiles)

        // 3. 纯附件发送：空白文本不触发文本发送，只触发附件发送
        sentText = null
        sentFiles = null
        simulateSubmit(
            body = "   ",
            staged = listOf(LocalFile("/tmp/b.png", "b.png", 200)),
            onSend = { sentText = it },
            onSendFiles = { sentFiles = it },
        )
        assertEquals(null, sentText, "纯附件发送时不向对方发送空白文本消息")
        assertEquals(1, sentFiles?.size)
    }
}
