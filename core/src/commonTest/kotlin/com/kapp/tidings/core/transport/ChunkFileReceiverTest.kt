// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.Sha256
import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.testing.createTempDir
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文件落盘的三条契约（与 legacy `PullSession` 逐字一致，不许有第二套语义）：
 *  1. 写 `<final>.part`，转正才改名；
 *  2. 长度不符 ⇒ `size-mismatch` 且**删掉 part**；
 *  3. sha 不符 ⇒ `hash-mismatch` 且删掉 part；
 *  4. 全部通过 ⇒ 转到最终名，**不覆盖**已有同名文件。
 */
class ChunkFileReceiverTest {

    private fun shaOf(bytes: ByteArray): String {
        val sha = Sha256()
        sha.update(bytes)
        return sha.hexDigest()
    }

    @Test
    fun writesPartThenRenamesAfterVerification() = runTest {
        val dir = createTempDir("relay-chunk-recv")
        val payload = ByteArray(4321) { (it % 251).toByte() }
        val receiver = ChunkFileReceiver(
            ChunkFileTarget("a.bin", payload.size.toLong(), shaOf(payload)),
            dir,
        )
        assertTrue(receiver.valid)
        receiver.append(payload.copyOfRange(0, 1000))
        receiver.append(payload.copyOfRange(1000, payload.size))
        assertEquals(payload.size.toLong(), receiver.writtenBytes())
        val final = receiver.finish()
        assertTrue(fileExists(final))
        assertEquals(payload.size.toLong(), fileSize(final))
        assertFalse(fileExists(pathJoin(dir, "a.bin.part")), "转正后不许留下 part")
    }

    @Test
    fun sizeMismatchDeletesPart() = runTest {
        val dir = createTempDir("relay-chunk-size")
        val receiver = ChunkFileReceiver(ChunkFileTarget("b.bin", 100, null), dir)
        receiver.append(ByteArray(50))
        val e = assertFailsWith<PullException> { receiver.finish() }
        assertEquals("size-mismatch", e.reason)
        assertFalse(fileExists(pathJoin(dir, "b.bin.part")), "校验失败必须删 part,不留半成品")
    }

    @Test
    fun hashMismatchDeletesPart() = runTest {
        val dir = createTempDir("relay-chunk-hash")
        val payload = ByteArray(64) { 7 }
        val receiver = ChunkFileReceiver(
            ChunkFileTarget("c.bin", payload.size.toLong(), "00".repeat(32)),
            dir,
        )
        receiver.append(payload)
        val e = assertFailsWith<PullException> { receiver.finish() }
        assertEquals("hash-mismatch", e.reason)
        assertFalse(fileExists(pathJoin(dir, "c.bin.part")))
    }

    @Test
    fun pathEscapeIsRejectedUpFront() = runTest {
        val dir = createTempDir("relay-chunk-escape")
        val receiver = ChunkFileReceiver(ChunkFileTarget("../../etc/passwd", 1, null), dir)
        assertFalse(receiver.valid, "越界相对路径必须在解析阶段就判失败")
        assertEquals(0L, receiver.writtenBytes())
    }

    @Test
    fun existingTargetFileIsNotOverwritten() = runTest {
        val dir = createTempDir("relay-chunk-dedupe")
        val existing = pathJoin(dir, "d.bin")
        val handle = openRandomAccess(existing, forWrite = true)
        handle.append(ByteArray(3) { 1 }, 3)
        handle.close()

        val payload = ByteArray(16) { 2 }
        val receiver = ChunkFileReceiver(ChunkFileTarget("d.bin", payload.size.toLong(), shaOf(payload)), dir)
        receiver.append(payload)
        val finalPath = receiver.finish()
        assertTrue(finalPath != existing, "已有文件不许被覆盖(必须去重命名)")
        assertEquals(3L, fileSize(existing), "原文件内容必须原样保留")
        assertEquals(16L, fileSize(finalPath))
        deleteFile(finalPath)
    }
}
