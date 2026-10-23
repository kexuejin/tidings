// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.transport.FileDedupStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「消息落盘」开关对去重表的约束。
 *
 * 这个文件守的是三句话，每一句都能单独坏掉：
 * 1. 开关**关着**时一个字节都不写，连读都不读 —— 用户关掉落盘之后，这个应用不该去碰一份
 *    记着"谁、什么时候、发过哪条消息 id"的日志；
 * 2. 开关**开着**时写下去的东西跨实例还在 —— 不然 README 里那条"重启后被重放"就没修；
 * 3. 开关**中途改**要立刻生效 —— [com.kapp.tidings.core.session.NodeRuntime] 的去重表是构造期
 *    定死的，包装存在的唯一理由就是让这个开关不必等下一次重启。
 *
 * 用的是真文件（不是替身）：这里要证的正是"磁盘上有没有那一行"。
 */
class GatedDedupStoreTest {

    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("relay-gated-dedup-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private val path: String get() = File(directory, "dedup.log").absolutePath

    /** 另一个实例看同一份磁盘 —— 这就是"重启"的替身。 */
    private fun reopenFromDisk() = FileDedupStore(path)

    @Test
    fun flagOffWritesNothingToDisk() {
        val store = GatedDedupStore(path) { false }
        store.add("msg-1", "peer-a", NOW)

        assertTrue(store.has("msg-1", "peer-a"), "内存里还是要认得，聊天不能被开关拦住")
        assertFalse(File(path).exists(), "关着的时候不该出现这个文件")
    }

    @Test
    fun flagOffDoesNotEvenReadTheDisk() {
        // 先造一份"上一个开着的时候留下的"日志。
        FileDedupStore(path).add("msg-old", "peer-a", NOW)

        val store = GatedDedupStore(path) { false }
        assertFalse(store.has("msg-old", "peer-a"), "关着时不该去读那份日志")
    }

    @Test
    fun flagOnSurvivesARestart() {
        GatedDedupStore(path) { true }.add("msg-1", "peer-a", NOW)

        assertTrue(reopenFromDisk().has("msg-1", "peer-a"), "开着写下去的东西，重启后必须还认得")
    }

    @Test
    fun turningTheFlagOffStopsWritingButKeepsTheMemoryCopy() {
        var enabled = true
        val store = GatedDedupStore(path) { enabled }
        store.add("before", "peer-a", NOW)

        enabled = false
        store.add("after", "peer-a", NOW)

        assertTrue(store.has("after", "peer-a"), "关掉之后收到的消息照样要去重")
        val reopened = reopenFromDisk()
        assertTrue(reopened.has("before", "peer-a"), "关之前写的要留着")
        assertFalse(reopened.has("after", "peer-a"), "关之后不该再写")
    }

    @Test
    fun turningTheFlagOnStartsWritingImmediately() {
        var enabled = false
        val store = GatedDedupStore(path) { enabled }
        store.add("before", "peer-a", NOW)

        enabled = true
        store.add("after", "peer-a", NOW)

        val reopened = reopenFromDisk()
        assertTrue(reopened.has("after", "peer-a"), "开关一打开就该立刻落盘，不必等重启")
        assertFalse(reopened.has("before", "peer-a"), "开关打开之前的几条不该被追溯落盘")
    }

    @Test
    fun pruneReachesBothTheMemoryCopyAndTheDisk() {
        val store = GatedDedupStore(path) { true }
        store.add("old", "peer-a", NOW - 10_000)
        store.add("fresh", "peer-a", NOW)

        assertTrue(store.prune(NOW, ttl = 5_000) >= 1, "过期的那条要被剪掉")

        val reopened = reopenFromDisk()
        assertFalse(reopened.has("old", "peer-a"), "磁盘上也要真的没了，否则文件只长不缩")
        assertTrue(reopened.has("fresh", "peer-a"))
    }

    private companion object {
        const val NOW = 1_789_000_000_000L
    }
}
