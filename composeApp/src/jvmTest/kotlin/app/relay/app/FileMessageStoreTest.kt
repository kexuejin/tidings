// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.platform.AppPlatform
import app.relay.platform.DataDirectoryProvider
import app.relay.platform.InMemoryAppPlatform
import app.relay.platform.MdnsAdvertiser
import app.relay.platform.MdnsBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

import kotlin.test.assertTrue
import app.relay.app.model.DeliveryState
import app.relay.app.model.FileCardUi
import app.relay.app.model.MessageUi
import app.relay.app.model.TransferState

/**
 * 落盘的**真实实现**：文件版 `MessageStore` 与跨重启恢复。
 *
 * 前面 [MessagePersistenceTest] 守的是"什么时候写"，用的是记数的替身；
 * 这里守的是"写下去的东西本身":真文件、真 JSON、真解码，以及
 * 「杀掉进程再打开，历史还在」这句承诺。
 */
class FileMessageStoreTest {

    private lateinit var directory: File
    private lateinit var platform: AppPlatform
    private val scope = CoroutineScope(Job())

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("relay-message-store-test").toFile()
        platform = InMemoryAppPlatform(
            dataDirectory = object : DataDirectoryProvider {
                override fun dataDirectory(): String = directory.absolutePath
                override fun cacheDirectory(): String = File(directory, "cache").absolutePath
            },
            mdnsAdvertiser = SilentAdvertiser,
            mdnsBrowser = SilentBrowser,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun historyWrittenByOneStoreComesBackFromAFreshOne() {
        createMessageStore(platform).save(
            mapOf(
                "peer-a" to listOf(
                    MessageUi(id = "m1", peerId = "peer-a", mine = false, text = "在吗", at = 100),
                    MessageUi(id = "m2", peerId = "peer-a", mine = true, text = "在", at = 200),
                ),
            ),
        )

        // 换一个 store 实例= 换一个进程视角；文件还在，后面的历史就该还在。
        val reloaded = createMessageStore(platform).load()

        assertEquals(listOf("在吗", "在"), reloaded["peer-a"]?.map { it.text })
        assertEquals(false, reloaded["peer-a"]?.first()?.mine, "谁说的话也要记住")
        assertEquals(200L, reloaded["peer-a"]?.last()?.at, "时间戳决定顺序，丢了就排不对")
    }

    @Test
    fun whatIsStoredIsHonestAboutPendingAndFileTransfers() {
        createMessageStore(platform).save(
            mapOf(
                "peer-b" to listOf(
                    MessageUi(id = "m1", peerId = "peer-b", mine = true, text = "", at = 1, delivery = DeliveryState.Queued),
                    MessageUi(id = "m2", peerId = "peer-b", mine = true, text = "", at = 2, delivery = DeliveryState.Failed),
                    // 真的带一张文件卡片：落盘时必须被剥掉，否则会复活一张死卡片
                    MessageUi(
                        id = "m3",
                        peerId = "peer-b",
                        mine = true,
                        text = "",
                        at = 3,
                        delivery = DeliveryState.Sent,
                        file = FileCardUi(
                            transferId = "t-1",
                            name = "报告.pdf",
                            size = 2048,
                            done = 2048,
                            state = TransferState.Completed,
                            savedPaths = listOf("/data/user/0/app.relay/files/报告.pdf"),
                        ),
                    ),
                ),
            ),
        )

        val reloaded = createMessageStore(platform).load()["peer-b"].orEmpty()

        // 这一层只管"忠实记录发生过什么"，不管重启之后该怎么办——策略在恢复那一刻
        // 由 toRestored() 决定。所以 Queued 在磁盘上就是 Queued，不在这里被抹平。
        assertEquals(DeliveryState.Queued, reloaded.first { it.id == "m1" }.delivery, "排队事实要如实落盘，别在这一层被抹平")
        assertEquals(DeliveryState.Failed, reloaded.first { it.id == "m2" }.delivery, "失败是既成事实，要留着")
        assertTrue(reloaded.all { it.file == null }, "文件卡片不落盘：路径可能已失效，传输管理器也不认旧的 transferId")
        // 更硬的一条：磁盘上的字节里根本不该出现文件名或路径，否则磁盘上留着死卡片，
        // 以后哪次改了策略就会把它当成真实历史读回来。
        val onDisk = File(directory, "messages.json").readText()
        assertTrue(onDisk.contains("\"Queued\""), "排队事实要真的写到字节里，而不是读回来才凭空补上")
        assertFalse(onDisk.contains("报告.pdf"), "落盘的 JSON 里不该有文件名")
        assertFalse(onDisk.contains("savedPaths"), "落盘的 JSON 里不该有保存路径")
        assertFalse(onDisk.contains("transferId"), "落盘的 JSON 里不该有传输编号")
    }

    @Test
    fun 删除之后镜像不再包含那条消息() {
        // messages.json 是"库的导出"，不是只写一次的备份：删除必须也反映到它上面。
        // 否则 SQLite 一旦为空（重装/库损坏），load() 会拿这份陈旧镜像做一次性迁移，
        // 把用户删过的消息复活。
        val store = createMessageStore(platform)
        val keep = MessageUi(id = "k1", peerId = "peer-g", mine = false, text = "这条要留下来", at = 2)
        val gone = MessageUi(id = "g1", peerId = "peer-g", mine = false, text = "这条要被删掉", at = 1)
        store.save(mapOf("peer-g" to listOf(gone, keep)))
        val file = File(directory, "messages.json")
        assertTrue(file.readText().contains("这条要被删掉"), "先确认镜像里确实有它")

        // 真实顺序与 handleDeleteMessage 一致：先显式删库，再落一次快照。
        store.deleteMessage("g1")
        store.save(mapOf("peer-g" to listOf(keep)))
        assertFalse(file.readText().contains("这条要被删掉"), "删掉的消息不能继续留在导出里")
        assertTrue(file.readText().contains("这条要留下来"), "没删的那条仍要在导出里")
    }

    @Test
    fun clearRemovesTheFileInsteadOfLeavingAnEmptyGhost() {
        createMessageStore(platform).save(
            mapOf("peer-c" to listOf(MessageUi(id = "m1", peerId = "peer-c", mine = false, text = "留个纪念", at = 1))),
        )
        val file = File(directory, "messages.json")
        assertTrue(file.isFile, "先确认确实写了文件")

        createMessageStore(platform).clear()

        assertFalse(file.exists(), "「清空聊天记录」不可撤销，删干净才算数")
        assertEquals(emptyMap(), createMessageStore(platform).load())
    }

    @Test
    fun aTruncatedFileIsTreatedAsNoHistoryRatherThanCrashing() {
        File(directory, "messages.json").writeText("{\"version\":1,\"threads\":{\"peer-d\":[{\"id\"") // 写到一半

        assertEquals(emptyMap(), createMessageStore(platform).load(), "存储坏了不该拦住聊天")
    }

    @Test
    fun restartingWithTheSwitchOnBringsTheOldThreadBack() = runBlocking {
        createMessageStore(platform).save(
            mapOf("peer-e" to listOf(MessageUi(id = "m1", peerId = "peer-e", mine = false, text = "上次说到哪了", at = 5))),
        )

        val controller = controller(persistMessages = true)
        try {
            controller.start()
            awaitUntil("历史回到会话里") { controller.state.value.messages["peer-e"]?.isNotEmpty() == true }
            assertEquals("上次说到哪了", controller.state.value.messages["peer-e"]?.single()?.text)
        } finally {
            controller.stop()
        }
    }

    @Test
    fun restartingWithTheSwitchOffLeavesTheFileAlone() = runBlocking {
        createMessageStore(platform).save(
            mapOf("peer-f" to listOf(MessageUi(id = "m1", peerId = "peer-f", mine = false, text = "不该被读出来", at = 5))),
        )

        val controller = controller(persistMessages = false)
        try {
            controller.start()
            delay(200) // 读盘是后台异步的：这里等一会儿，才能证明它真的没去读
            assertEquals(
                emptyList(),
                controller.state.value.messages["peer-f"].orEmpty(),
                "关着的时候一个字节都不该读 —— 这是「默认行为零变化」的另一半",
            )
        } finally {
            controller.stop()
        }
    }

    @Test
    fun aLegacySettingsFileWithoutTheSwitchKeyStillBringsHistoryBack() = runBlocking {
        // 老库升级的端到端：settings.json 里**没有** persistMessages 这个键
        // （旧默认值是 false，encodeDefaults=false 下根本不会写进文件），而磁盘上还有当年
        // 开过开关时留下的 messages.json。迁移把默认补成开启之后，这份历史必须读得回来 ——
        // 否则"离线也能看到历史会话"只兑现了策略、没兑现结果。
        createMessageStore(platform).save(
            mapOf(
                "peer-legacy" to listOf(
                    MessageUi(id = "m1", peerId = "peer-legacy", mine = false, text = "老库的历史", at = 5),
                ),
            ),
        )

        val controller = RelayController(
            platform = platform,
            store = object : SettingsStore {
                override fun load(): RelaySettings? = SettingsStore.json.decodeFromString(
                    RelaySettings.serializer(),
                    """{ "nodeId": "node-1", "udpPort": 0, "tcpPort": 0 }""",
                )

                override fun save(settings: RelaySettings) = Unit
            },
            scope = scope,
            version = "relay-test",
            messageStore = createMessageStore(platform),
        )
        try {
            controller.start()
            awaitUntil("老库升级后也能读回历史") {
                controller.state.value.messages["peer-legacy"]?.isNotEmpty() == true
            }
            assertEquals(true, controller.persistMessages, "迁移后默认落盘")
        } finally {
            controller.stop()
        }
    }

    private fun controller(persistMessages: Boolean) = RelayController(
        platform = platform,
        store = FixedSettingsStore(persistMessages),
        scope = scope,
        version = "relay-test",
        messageStore = createMessageStore(platform),
    )

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) =
        withTimeout(5_000) {
            while (!condition()) {
                delay(10)
            }
        }

    private class FixedSettingsStore(private val persistMessages: Boolean) : SettingsStore {
        override fun load(): RelaySettings? = RelaySettings(
            nodeId = "node-1",
            udpPort = 0,
            tcpPort = 0,
            persistMessages = persistMessages,
            // 决定位必须显式给：只有"已经决定过"的 false 才不会被
            // migrateRelaySettings 当成老库而重新打开（见那个函数的 KDoc）。
            persistMessagesDecided = true,
        )

        override fun save(settings: RelaySettings) = Unit
    }

    private object SilentAdvertiser : MdnsAdvertiser {
        override suspend fun start(serviceName: String, port: Int): Boolean = true
        override fun stop() = Unit
    }

    private object SilentBrowser : MdnsBrowser {
        override fun startBrowsing(
            onServiceFound: (String, String, Int) -> Unit,
            onServiceLost: (String) -> Unit,
        ) = Unit

        override fun stopBrowsing() = Unit
    }
}
