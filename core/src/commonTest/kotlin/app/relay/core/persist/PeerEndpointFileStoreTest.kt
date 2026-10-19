// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.persist

import app.relay.core.fs.pathJoin
import app.relay.core.testing.createTempDir
import app.relay.core.testing.removeRecursively
import app.relay.core.transport.PeerEndpoint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 候选端点的落盘：跨重启还在，坏文件不拦路。
 *
 * 为什么必须落盘：手机换到移动网之后要用**公网**候选地址发心跳/消息，而"配对那一刻"
 * 可能已经过去很久（甚至跨了好几次重启）—— 只在内存里留一份，重启就回到"只有一个地址"。
 */
class PeerEndpointFileStoreTest {

    private val root = createTempDir("relay-peer-endpoints")
    private fun p(name: String): String = pathJoin(root, name)

    @AfterTest
    fun clean() {
        removeRecursively(root)
    }

    @Test
    fun endpointsSurviveAReopen() {
        val store = FilePeerEndpointStore(p("peer-endpoints.json"))
        store.save(
            mapOf(
                "node-a" to listOf(PeerEndpoint("2001:db8::1", 17878), PeerEndpoint("192.168.2.9", 17878)),
                "node-b" to listOf(PeerEndpoint("10.0.0.9", 17878)),
            ),
        )

        val reopened = FilePeerEndpointStore(p("peer-endpoints.json"))

        assertEquals(listOf("2001:db8::1", "192.168.2.9"), reopened.load()["node-a"]?.map { it.host })
        assertEquals(1, reopened.load()["node-b"]?.size)
    }

    @Test
    fun aMissingFileIsAnEmptyTableNotAnError() {
        assertTrue(FilePeerEndpointStore(p("never-written.json")).load().isEmpty())
    }

    @Test
    fun aCorruptFileDegradesToEmptyInsteadOfCrashing() {
        val handle = app.relay.core.fs.openRandomAccess(p("broken.json"), forWrite = true)
        val bytes = "{ not json".encodeToByteArray()
        handle.append(bytes, bytes.size)
        handle.close()
        assertTrue(FilePeerEndpointStore(p("broken.json")).load().isEmpty(), "坏文件当空表，绝不拦路")
    }
    // ---- TTL 清理（task-24）----

    private val day = 24L * 60 * 60 * 1000
    private val ttl = 30 * day

    /** 注入时钟的 store：TTL 测试要能确定性地跨天，不能等真实时间。 */
    private fun storeAt(now: Long, name: String = "ttl.json") =
        FilePeerEndpointStore(p(name), ttlMillis = ttl, clock = { now })

    private fun endpointsOf(vararg hosts: String) = hosts.map { PeerEndpoint(it, 17878) }

    private fun onDiskText(name: String): String =
        app.relay.core.fs.readFileBytes(p(name))?.decodeToString().orEmpty()

    @Test
    fun aJustPairedDeviceSurvivesUntilTheTtl() {
        storeAt(0L).save(mapOf("node-a" to endpointsOf("2001:db8::1")))
        val reopened = storeAt(ttl - 1)
        assertEquals(listOf("2001:db8::1"), reopened.load()["node-a"]?.map { it.host })
    }

    @Test
    fun aStaleEntryIsDroppedOnLoadAndRemovedFromTheFile() {
        storeAt(0L).save(mapOf("node-a" to endpointsOf("2001:db8::1")))
        val reopened = storeAt(ttl + 1)
        assertTrue(reopened.load().isEmpty(), "超过 TTL 的候选不该再被装回 registry")
        assertFalse(
            onDiskText("ttl.json").contains("node-a"),
            "盘上也要真的清掉，而不是只是读的时候看不见",
        )
    }

    @Test
    fun anUnchangedEntryIsNotRefreshedByALaterSave() {
        val name = "unchanged.json"
        storeAt(0L, name).save(mapOf("node-a" to endpointsOf("2001:db8::1")))
        // 第 10 天又保存一次：node-a 的值没变（不该刷新），node-b 是新来的（该盖当下）。
        storeAt(10 * day, name).save(
            mapOf(
                "node-a" to endpointsOf("2001:db8::1"),
                "node-b" to endpointsOf("10.0.0.9"),
            ),
        )
        // 第 31 天：node-a 的戳还停在 0 点 ⇒ 过期；node-b 的戳是第 10 天 ⇒ 还有 9 天。
        assertEquals(
            setOf("node-b"),
            storeAt(31 * day, name).load().keys,
            "值没变就不该被刷新 —— 否则 registry 一直送、它就永远清不掉",
        )
    }

    @Test
    fun aChangedEntryIsRestamped() {
        val name = "changed.json"
        storeAt(0L, name).save(mapOf("node-a" to endpointsOf("2001:db8::1")))
        storeAt(10 * day, name).save(mapOf("node-a" to endpointsOf("2001:db8::2")))
        assertEquals(listOf("2001:db8::2"), storeAt(31 * day, name).load()["node-a"]?.map { it.host })
    }

    @Test
    fun aV1FileWithoutStampsIsKeptAndThenAgesOut() {
        val name = "v1.json"
        // v1 形状：只有 version + endpoints（task-24 之前写下的文件）。
        val handle = app.relay.core.fs.openRandomAccess(p(name), forWrite = true)
        val text = """{"version":1,"endpoints":{"node-a":[{"host":"2001:db8::1","port":17878}]}}"""
        val bytes = text.encodeToByteArray()
        handle.append(bytes, bytes.size)
        handle.close()

        assertEquals(
            listOf("2001:db8::1"),
            storeAt(0L, name).load()["node-a"]?.map { it.host },
            "升级不该丢数据",
        )
        // 首次打开就要把「现在」盖上盘，否则每次启动都重新盖章、永远老不掉。
        assertTrue(onDiskText(name).contains("updatedAt"), "v1 文件首次打开要把时间戳补上盘")
        assertTrue(storeAt(ttl + 1, name).load().isEmpty(), "补上时间戳之后照常按 TTL 老化")
    }
}
