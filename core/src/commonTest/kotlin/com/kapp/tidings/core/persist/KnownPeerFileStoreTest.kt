// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.persist

import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.use
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 已知对端落盘（task-80 ①）。
 *
 * 真机缺陷：手机 kill 一次之后，节点表里那台 Mac 只剩 `node-a1b2c3d4`。
 * 那个名字来自配对时造的**合成名**，已经写进消息历史；重启后 `PeerRegistry` 是空的，
 * 于是只能显示历史里那个占位名 —— 头像、平台、能力位全丢了。
 *
 * 这里守的是「身份跨重启还在」这件事本身，以及它的两条边界：
 * 不假装在线、不过期太早。
 */
class KnownPeerFileStoreTest {

    /** commonTest 不能碰 java.*（iOS 目标也要编译），用共享 fs 原语写坏文件。 */
    private fun writeTextFile(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        openRandomAccess(path, forWrite = true).use { it.append(bytes, bytes.size) }
    }

    private fun profile(nodeId: String, nick: String) = Profile(
        nodeId = nodeId,
        nick = nick,
        company = "",
        dept = "",
        team = "",
        avatar = -1,
        profileRev = 0,
        host = "MacBook-Pro",
        platform = "mac",
        tcpPort = 17879,
        ver = "0.61.1",
        caps = listOf("lnk1"),
    )

    @Test
    fun aKnownPeerSurvivesARestart() {
        val dir = createTempDir("relay-known-peers")
        try {
            val path = dir + "/known-peers.json"
            // 时钟钉死：`lastSeen` 用**现在**，否则默认 90 天 TTL 会把 2023 年的记录当过期裁掉
            // （这条测试第一次就是这么红的 —— 它其实先证明了 TTL 是生效的）。
            val now = com.kapp.tidings.core.nowMillis()
            val store = FileKnownPeerStore(path, clock = { now })
            store.save(
                listOf(
                    KnownPeer(
                        profile = profile("node-a", "demo-mac-97"),
                        ip = "2001:db8::1",
                        udpPort = 17878,
                        lastSeen = now,
                    ),
                ),
            )

            // 「重启」：新建一个 store 读同一个文件。
            val reopened = FileKnownPeerStore(path, clock = { now })
            val loaded = reopened.load()
            assertEquals(1, loaded.size, "重启后必须还认得这台设备")
            assertEquals("demo-mac-97", loaded[0].profile.nick, "昵称必须还在（否则节点表只剩 node-xxx）")
            assertEquals("mac", loaded[0].profile.platform)
            assertEquals("2001:db8::1", loaded[0].ip)
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun entriesOlderThanTheTtlAreDropped() {
        val dir = createTempDir("relay-known-peers-ttl")
        try {
            val path = dir + "/known-peers.json"
            val now = 10_000_000L
            // 写入一条「很久以前见过」的记录：TTL 1000，它已经超了 10 倍。
            FileKnownPeerStore(path, ttlMillis = 1_000L, clock = { now }).save(
                listOf(KnownPeer(profile("node-old", "旧设备"), "1.2.3.4", 17878, now - 10_000L)),
            )
            val reopened = FileKnownPeerStore(path, ttlMillis = 1_000L, clock = { now })
            assertTrue(reopened.load().isEmpty(), "90 天/注入的 TTL 之外的对端不该永远占着名录")
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun aCorruptFileDegradesToEmptyInsteadOfCrashing() {
        val dir = createTempDir("relay-known-peers-bad")
        try {
            val path = dir + "/known-peers.json"
            writeTextFile(path, "{not json")
            // 读不出来就该当空表 —— 丢的是"记得它是谁"，不是应用能不能启动。
            assertTrue(FileKnownPeerStore(path).load().isEmpty())
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun anEmptyStoreIsJustEmpty() {
        val dir = createTempDir("relay-known-peers-none")
        try {
            assertTrue(FileKnownPeerStore(dir + "/does-not-exist.json").load().isEmpty())
        } finally {
            removeRecursively(dir)
        }
    }
}