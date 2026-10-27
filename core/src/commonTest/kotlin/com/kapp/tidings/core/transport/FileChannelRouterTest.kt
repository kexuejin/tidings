// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.Sha256
import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.testing.createTempDir
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 路由器：多文件 transfer 的**帧内寻址**（线格式不变）+ 收块落盘 + ACK 回传 + 推文件。
 *
 * 用 `"<transferId>#<fileId>"` 当帧里的键，是为了让"一个 transfer 多个文件"能路由，
 * 同时**不动已有帧格式**（上一个提交已经发了 TDCH 的字段布局）。
 */
class FileChannelRouterTest {

    private fun shaOf(bytes: ByteArray): String {
        val s = Sha256()
        s.update(bytes)
        return s.hexDigest()
    }

    @Test
    fun routesTwoFilesOfOneTransferAndAcksEach() = runTest {
        val dir = createTempDir("relay-router-two")
        val a = ByteArray(70_000) { (it % 251).toByte() }
        val b = ByteArray(40_000) { (it % 97).toByte() }
        val sent = mutableListOf<ByteArray>()
        val router = FileChannelRouter(sendToPeer = { _, bytes -> sent.add(bytes); true })

        assertTrue(router.registerInbound("peer", "t1", "a", ChunkFileTarget("a.bin", a.size.toLong(), shaOf(a)), dir))
        assertTrue(router.registerInbound("peer", "t1", "b", ChunkFileTarget("b.bin", b.size.toLong(), shaOf(b)), dir))

        // 交错送入两个文件的块，模拟真实交错到达。
        val chunk = SecureChunkReceiver.CHUNK
        fun framesOf(fileId: String, bytes: ByteArray): List<ByteArray> =
            (0 until bytes.size step chunk).map { off ->
                FileChunkFrame.encode("t1#$fileId", off.toLong(), bytes.copyOfRange(off, minOf(off + chunk, bytes.size)))
            }
        val fa = framesOf("a", a)
        val fb = framesOf("b", b)
        var i = 0
        while (i < maxOf(fa.size, fb.size)) {
            if (i < fa.size) router.onBinaryFrame(fa[i])
            if (i < fb.size) router.onBinaryFrame(fb[i])
            i++
        }
        assertTrue(fileExists(dir + "/a.bin"), "文件 a 必须转正")
        assertTrue(fileExists(dir + "/b.bin"), "文件 b 必须转正")
        assertEquals(a.size.toLong(), fileSize(dir + "/a.bin"))
        assertEquals(b.size.toLong(), fileSize(dir + "/b.bin"))
        // 每个文件都必须收到过 ACK（帧里的键含 fileId，说明路由没串）。
        val ackKeys = sent.mapNotNull { FileChunkAck.decode(it)?.transferId }.toSet()
        assertTrue(ackKeys.contains("t1#a") && ackKeys.contains("t1#b"), "两个文件各自要有 ACK: $ackKeys")
    }

    @Test
    fun unknownTransferFramesAreDroppedQuietly() = runTest {
        val dir = createTempDir("relay-router-unknown")
        val router = FileChannelRouter(sendToPeer = { _, _ -> true })
        val frame = FileChunkFrame.encode("ghost#x", 0, ByteArray(1024))
        assertTrue(router.onBinaryFrame(frame), "已知是数据帧,只是没人认领 —— 仍算已处理")
        assertFalse(fileExists(dir + "/x.bin"))
    }

    @Test
    fun outgoingFileUsesAcksToAdvance() = runTest {
        val payload = ByteArray(200_000) { it.toByte() }
        val sentFrames = mutableListOf<ByteArray>()
        lateinit var routerRef: FileChannelRouter
        // 模拟一个"收到就回连续前缀 ACK"的对端:把 ACK 喂回同一个路由器的出站确认表。
        val router = FileChannelRouter(
            sendToPeer = { _, bytes ->
                sentFrames.add(bytes)
                FileChunkFrame.decode(bytes)?.let { frame ->
                    routerRef.onBinaryFrame(
                        FileChunkAck.encode(frame.transferId, frame.offset + frame.payload.size),
                    )
                }
                true
            },
        ).also { routerRef = it }
        val sent = router.sendFile("peer", "t2", "c", ByteArrayChunkSource(payload))
        assertEquals(payload.size.toLong(), sent)
        assertTrue(sentFrames.isNotEmpty())
        // 首帧键必须带 fileId,对端才能路由回来。
        val first = sentFrames.first()
        assertFalse(FileChunkFrame.decode(first) == null, "必须是合法数据帧")
        assertTrue(FileChunkFrame.decode(first)!!.transferId.startsWith("t2#c"))
    }

    @Test
    fun nonChunkNonAckFrameIsNotSwallowed() = runTest {
        val router = FileChannelRouter(sendToPeer = { _, _ -> true })
        val envelopeLike = """{"v":1,"type":"msg","id":"m","from":"p","ts":1,"payload":{}}""".toByteArray()
        assertFalse(router.onBinaryFrame(envelopeLike), "不像 TDCH/TDCA 的帧必须交回上层")
    }
}
