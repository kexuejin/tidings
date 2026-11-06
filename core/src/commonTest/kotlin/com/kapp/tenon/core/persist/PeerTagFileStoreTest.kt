// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.persist

import com.kapp.tenon.core.fs.openRandomAccess
import com.kapp.tenon.core.fs.use
import com.kapp.tenon.core.protocol.ChannelTags
import com.kapp.tenon.core.testing.createTempDir
import com.kapp.tenon.core.testing.removeRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 节点标签便条（M7-T4，决策包 §3.6）的判据：
 *
 * 1. **存取幂等**：tag → 重启（重建 store）→ 集合一致；
 * 2. **不过期**：updatedAt 很老也照样活着（与 pins 同一立场 —— 标签是用户亲手打的）；
 * 3. **isWellFormed 拒坏形状**：写侧与 wire 校验（`CodecChannel`）引用同一个谓词
 *    （决策包 §3.3「又一条 #7 防线」）—— 大写 / 越界长度 / 空串都被拒；
 * 4. **解耦判据**（A 案两个坑的钉子）：节点 registry 怎么重写、怎么淘汰都不碰这份文件
 *    —— 标签不随发现状态重写而丢。
 *
 * 反向验证路径：谓词换成「只查非空」⇒ 坏形状用例红；换成随 lastSeen 淘汰 ⇒ 不过期用例红。
 */
class PeerTagFileStoreTest {

    /** commonTest 不能碰 java.*（iOS 目标也要编译），用共享 fs 原语读回文件字节。 */
    private fun readTextFile(path: String): String {
        val buffer = ByteArray(64 * 1024)
        openRandomAccess(path, forWrite = false).use { handle ->
            val n = handle.read(buffer, 0, buffer.size)
            return buffer.copyOf(if (n < 0) 0 else n).decodeToString()
        }
    }

    @Test
    fun tagsSurviveReconstructionIdempotently() {
        val dir = createTempDir("relay-tags")
        try {
            val path = dir + "/peer-tags.json"
            val first = PeerTagFileStore(path, clock = { 1_000L })
            first.tag("node-a", listOf("gpu", "docker-sandbox"))
            first.tag("node-a", listOf("gpu")) // 集合语义：重复打同一个标签 = 幂等。
            first.tag("node-b", listOf("gpu"))
            // 「重启」：新实例读同一份文件。
            val second = PeerTagFileStore(path, clock = { 1_100L })
            assertEquals(
                listOf("node-a", "node-b"),
                second.nodeIdsWithTag("gpu").sorted(),
                "两个节点的标签都活着，重启不丢",
            )
            assertEquals(
                listOf("docker-sandbox", "gpu"),
                second.load().first { it.nodeId == "node-a" }.tags.sorted(),
            )
            second.untag("node-a", listOf("docker-sandbox"))
            assertEquals(
                listOf("gpu"),
                PeerTagFileStore(path).load().first { it.nodeId == "node-a" }.tags,
                "摘标签后只剩剩下的（再重启一次仍一致）",
            )
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun oldTagsAreNeverExpiredAway() {
        val dir = createTempDir("relay-tags")
        try {
            val path = dir + "/peer-tags.json"
            // 打标签时刻是「0」（远过去）；store 不带任何 TTL —— updatedAt 只是记账。
            PeerTagFileStore(path, clock = { 0L }).tag("node-old", listOf("gpu"))
            val second = PeerTagFileStore(path, clock = { 100L * 24 * 60 * 60 * 1000 })
            assertEquals(
                listOf("node-old"),
                second.nodeIdsWithTag("gpu"),
                "90 天不通信不代表用户不想再路由 —— 标签不过期（与 pins 同一立场）",
            )
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun malformedTagsAreRejectedByTheSharedPredicate() {
        val dir = createTempDir("relay-tags")
        try {
            val path = dir + "/peer-tags.json"
            val store = PeerTagFileStore(path, clock = { 1_000L })
            // 与 CodecChannel 同一份谓词：大写 / 越界 / 空串都不合法。
            assertTrue(!ChannelTags.isWellFormed("GPU"))
            assertTrue(!ChannelTags.isWellFormed("a".repeat(33)))
            // model: 前缀族（D-030，方案 §2.5）：冒号只认这一个前缀。
            assertTrue(ChannelTags.isWellFormed("model:claude"), "model: 标签合法")
            assertTrue(ChannelTags.isWellFormed("model:deepseek-v4.1"), "模型版本号含点合法")
            assertTrue(!ChannelTags.isWellFormed("model:"), "空前缀后缀不合法")
            assertTrue(!ChannelTags.isWellFormed("custom:thing"), "任意 namespace 不开放（冒号只认 model:）")
            assertTrue(!ChannelTags.isWellFormed("Model:claude"), "前缀大小写敏感（标签全小写域）")
            assertTrue(!ChannelTags.isWellFormed(""))
            assertEquals(
                emptyList(),
                store.tag("node-a", listOf("GPU")),
                "坏形状被拒：原集合原样返回，不静默改写",
            )
            assertEquals(
                emptyList(),
                store.load().filter { it.nodeId == "node-a" }.flatMap { it.tags },
                "坏标签没有进文件",
            )
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun tagFileIsDecoupledFromPeerRegistryRewrites() {
        val dir = createTempDir("relay-tags")
        try {
            val tagsPath = dir + "/peer-tags.json"
            val knownPeersPath = dir + "/known-peers.json"
            val store = PeerTagFileStore(tagsPath, clock = { 1_000L })
            store.tag("node-a", listOf("gpu"))
            val before = readTextFile(tagsPath)
            // 模拟 KnownPeerFileStore 的整份重写发生在**同一个目录**：它是另一个文件，
            // registry 怎么写都碰不到标签 —— A 案坑①（同文件被 registry save 覆盖）的钉子。
            openRandomAccess(knownPeersPath, forWrite = true).use { handle ->
                val bytes = "any registry rewrite goes here".encodeToByteArray()
                handle.append(bytes, bytes.size)
            }
            assertEquals(before, readTextFile(tagsPath), "registry 重写不碰标签文件（A 案坑①的钉子）")
            // 文件形状：version + entries；坏文件降级空表的容错由 readSnapshot 的
            // runCatching 承担（KnownPeerFileStore 同款）。
            assertEquals("node-a", secondInstanceSeesTag(tagsPath))
        } finally {
            removeRecursively(dir)
        }
    }

    private fun secondInstanceSeesTag(path: String): String =
        PeerTagFileStore(path).nodeIdsWithTag("gpu").single()
}
