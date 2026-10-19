// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.platform.InMemoryAppPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.relay.app.model.MessageUi

/**
 * 「消息落盘」开关的行为边界。
 *
 * 这几条都是**界面在替用户许诺**的事：聊天页写着「本地未落盘，重启会丢失」，
 * 设置里给了一个开关。所以关着必须真的什么都不写、开着的瞬间必须真的写下去 ——
 * 而不是"等到下一条消息才生效"，那会让开关前后的历史对不上，界面上也看不出区别。
 *
 * 测不到"具体哪几条消息被写下去"：[RelayController.onInbound] 是私有的，
 * 往内存里塞消息只能起真实节点（真 socket），那是集成测试的活。
 * 这里守住的是**时序**——写与不写、什么时候写、写几次。
 *
 * 另一半是**默认值与迁移**（真机反馈 #2）：默认改成开启，且老库只迁移一次；
 * 用户显式关掉之后任何一次启动都不能被迁移重新打开。这两条都在这里。
 *
 * 边界：真正"重启后从磁盘读回消息"的端到端在 `FileMessageStoreTest`（它用真文件），
 * 这里用替身 store 只验证控制器的时序与设置文件的内容。
 */
class MessagePersistenceTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(store: MessageStore) = controller(store, RecordingSettingsStore())

    private fun controller(store: MessageStore, settings: SettingsStore) = RelayController(
        platform = InMemoryAppPlatform(),
        store = settings,
        scope = scope,
        messageStore = store,
    )

    /**
     * 一个"用户已经把开关显式关掉"的设置替身。
     *
     * `persistMessagesDecided = true` 必须一起给：新格式里"显式关闭"就是这两个字段的组合，
     * 只有值不给决定位的话，构造期的 [migrateRelaySettings] 会把它当成老库而重新打开 ——
     * 那正是这个迁移要避免的事。
     */
    private fun explicitOff(): RecordingSettingsStore = RecordingSettingsStore().apply {
        current = RelaySettings(
            nodeId = "node-1",
            persistMessages = false,
            persistMessagesDecided = true,
        )
    }

    /**
     * 真的等一段时间，而不是用 [kotlinx.coroutines.test] 的虚拟时间。
     *
     * 落盘里的 `withContext(ioDispatcher)` 跑在真实调度器上，而 `runTest` 的测试调度器
     * 会把虚拟时间一股脑推到超时 —— 于是"等写盘"变成"等虚拟时间耗尽"，必超时。
     * 切到 [Dispatchers.Default] 再计时，量的才是墙上的时间。
     */
    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(2_000) { block() } }

    /**
     * 反向断言：等一小段，[block] 一次都不该返回。
     *
     * 这种"等不到才算过"的测试天然只能证明"在这段时间里没发生"，
     * 所以时间是短的（300ms）—— 够跑完一次落盘的 IO，又不至于让测试变慢。
     */
    private suspend fun <T> assertNothingHappens(block: suspend () -> T) {
        assertNull(
            withContext(Dispatchers.Default) { withTimeoutOrNull(300) { block() } },
            "这段时间里不该有任何存储调用",
        )
    }

    @Test
    fun persistIsOnByDefaultSoAnOfflineLaunchStillHasHistory() = runTest {
        // 真机反馈 #2：默认不落盘 ⇒ 冷启动时消息表是空的，会话列表只能等局域网发现。
        // 默认改成"存"之后，离线打开也先读到历史。
        val store = RecordingMessageStore()
        val controller = controller(store)

        assertEquals(true, controller.persistMessages, "默认必须落盘，否则离线冷启动看不到历史会话")

        // 另一半承诺没变：用户关掉之后必须一个字节都不碰（连读都不读）。
        controller.updatePersistMessages(false)
        store.arm()
        controller.clearMessages("peer-never-talks")
        controller.clearAllMessages()

        assertNothingHappens { store.awaitAnyCall() }
        assertEquals(0, store.saveCount, "关着时 save 调用数")
        assertEquals(0, store.clearCount, "关着时 clear 调用数")
    }

    @Test
    fun turningPersistOnWritesImmediatelyAndOnlyOnce() = runTest {
        val store = RecordingMessageStore()
        val controller = controller(store, explicitOff())

        // 开的那一刻就必须写一次：不这样做的话，用户在开关打开前聊的那些消息
        // 永远存不进去，而界面上看不出任何区别。
        controller.updatePersistMessages(true)
        val first = awaitReal { store.awaitSave() }
        assertEquals(emptyMap(), first, "这一条守的是时序；内存里没有消息时落的就是空会话表")
        assertEquals(1, store.saveCount, "打开开关应当且只应当写一次")

        // 同样的值再设一次不写：Compose 重组会让同一个回调反复触发，
        // 每次都落盘等于每次都往磁盘上砸一份一模一样的内容。
        controller.updatePersistMessages(true)
        store.arm()
        assertNothingHappens { store.awaitAnyCall() }
        assertEquals(1, store.saveCount, "重复设成同一个值不该再写")
    }

    @Test
    fun turningPersistOffStopsWritingButKeepsWhatIsAlreadyThere() = runTest {
        val store = RecordingMessageStore()
        val controller = controller(store, explicitOff())

        controller.updatePersistMessages(true)
        awaitReal { store.awaitSave() }
        store.arm() // 丢弃"开关打开"那一次的记录，只看关掉之后发生了什么

        controller.updatePersistMessages(false)
        controller.clearMessages("peer-a")
        controller.clearAllMessages()

        assertNothingHappens { store.awaitAnyCall() }
        assertEquals(1, store.saveCount, "关掉之后不该再写")
        assertEquals(0, store.clearCount, "关掉之后不该清存储")
    }

    @Test
    fun clearAllAsksTheStoreToDropItsFilesRatherThanSaveAnEmptyMap() = runTest {
        val store = RecordingMessageStore()
        val controller = controller(store, explicitOff())

        controller.updatePersistMessages(true)
        awaitReal { store.awaitSave() }
        val savesBefore = store.saveCount

        controller.clearAllMessages()

        awaitReal { store.awaitClear() }
        // 刻意不调 save(emptyMap())：存储实现对"删干净"可能有额外约定
        // （删文件、清密钥、回收空间），把这件事说清楚比塞一个空 map 过去可靠。
        assertEquals(savesBefore, store.saveCount, "清空不该再写一次 save")
        assertTrue(store.clearCount == 1, "clear 次数 ${store.clearCount}")
    }

    @Test
    fun theSwitchItselfIsSavedSoItSurvivesARestart() = runTest {
        val messages = RecordingMessageStore()
        // 从一个"已经显式关掉"的文件开始：这样两次切换都是真的变化，
        // 数得清"只有值变了才写设置"。构造期（迁移/补默认名）的写盘不算进这个功能。
        val settings = explicitOff()
        val controller = controller(messages, settings)
        val savesBefore = settings.saveCount

        // 只存到内存里的话，重启后开关会自己弹回「关」—— 用户明明开过，
        // 下次进来却看到历史全没了，界面上没有任何地方解释这件事。
        controller.updatePersistMessages(true)
        assertEquals(true, settings.current?.persistMessages, "开关的值必须跟着设置一起存下来")

        // 关掉同样要落盘：否则"关"是进程级的临时状态，一重启就自己回来了。
        controller.updatePersistMessages(false)
        assertEquals(false, settings.current?.persistMessages, "关掉也要存")
        assertEquals(savesBefore + 2, settings.saveCount, "只有真的变了才存 —— 同值不该反复写设置")

        controller.updatePersistMessages(false)
        assertEquals(savesBefore + 2, settings.saveCount, "重复设成同一个值不该再写一次设置")
    }

    @Test
    fun aControllerRebuiltFromSavedSettingsStartsWithTheSwitchWhereTheUserLeftIt() = runTest {
        val on = RecordingSettingsStore().apply {
            current = RelaySettings(
                nodeId = "node-1",
                persistMessages = true,
                persistMessagesDecided = true,
            )
        }
        val off = explicitOff()

        assertEquals(
            true,
            controller(RecordingMessageStore(), on).persistMessages,
            "开着就要一直开着，不能每次启动都从关开始",
        )
        assertEquals(
            false,
            controller(RecordingMessageStore(), off).persistMessages,
            "关着就要一直关着，不能被默认值或迁移改写",
        )
    }

    @Test
    fun legacySettingsFileWithoutTheKeyMigratesToPersistOn() = runTest {
        // 旧 settings.json 的实况（design/runtime-evidence/persist-04-android-on-disk.txt:33）：
        // 这个键只在为 true 时才写（encodeDefaults = false），所以从没设过的老用户文件里
        // 根本没有它。这是"从没设过"那一档，必须迁移到开启。
        val legacy = SettingsStore.json.decodeFromString(
            RelaySettings.serializer(),
            """{ "nodeId": "node-legacy" }""",
        )
        assertTrue(legacy.persistMessages, "解码后先落到新默认值：开启")
        assertFalse(legacy.persistMessagesDecided, "旧文件里没有决定位")

        val settings = RecordingSettingsStore().apply { current = legacy }
        val controller = controller(RecordingMessageStore(), settings)

        assertEquals(true, controller.persistMessages, "老用户升级后默认落盘，离线也看得到历史")
        assertEquals(true, settings.current?.persistMessagesDecided, "迁移必须把决定位写回文件")
        assertEquals(1, settings.saveCount, "一次性迁移只写这一次")
    }

    @Test
    fun theMigrationRunsOnceAndAnExplicitOffSurvivesEveryLaterLaunch() = runTest {
        val legacy = SettingsStore.json.decodeFromString(
            RelaySettings.serializer(),
            """{ "nodeId": "node-legacy" }""",
        )
        val settings = RecordingSettingsStore().apply { current = legacy }

        // 第一次启动：迁移并写回。
        controller(RecordingMessageStore(), settings)
        assertEquals(1, settings.saveCount, "迁移写一次")

        // 用户在新版本里关掉它（这一下会写第二次）。
        controller(RecordingMessageStore(), settings).updatePersistMessages(false)
        assertEquals(2, settings.saveCount, "显式关掉要落盘")

        // 第三次启动：决定位已经在文件里，迁移不能再把开关打开。
        val third = controller(RecordingMessageStore(), settings)
        assertEquals(false, third.persistMessages, "显式关闭必须跨启动留住")
        assertEquals(2, settings.saveCount, "已经决定过就不该再写迁移")
    }

    @Test
    fun theNewFormatCanAlwaysRecordAnExplicitOff() = runTest {
        // 这条守的是"可判别"本身：新默认值是 true ⇒ false ≠ 默认值 ⇒ 一定会写进文件。
        // 谁要是把默认值改回 false，或者打开 encodeDefaults，这条会红 ——
        // 那两种改法都会让"用户关掉了"再次变成不可判别。
        val written = SettingsStore.json.encodeToString(
            RelaySettings.serializer(),
            RelaySettings(nodeId = "node-1", persistMessages = false, persistMessagesDecided = true),
        )
        assertTrue(written.contains("\"persistMessages\": false"), "显式关闭必须真的落进文件：$written")
        assertTrue(written.contains("\"persistMessagesDecided\": true"), "决定位也要落进文件：$written")
    }

    @Test
    fun anExplicitOffWrittenByTheNewFormatIsHonouredAfterADecode() = runTest {
        val raw = SettingsStore.json.encodeToString(
            RelaySettings.serializer(),
            RelaySettings(nodeId = "node-1", persistMessages = false, persistMessagesDecided = true),
        )
        val decoded = SettingsStore.json.decodeFromString(RelaySettings.serializer(), raw)
        val controller = controller(
            RecordingMessageStore(),
            RecordingSettingsStore().apply { current = decoded },
        )

        assertEquals(false, controller.persistMessages, "跨一次编码/解码，显式关闭不能退化")
    }

    /**
     * 设置侧的替身：既是"设置文件"，又能被测试读回来看存了什么。
     *
     * 不复用控制器默认注入的那个匿名 `SettingsStore` 是因为这里必须能断言
     * "开关的值真的落到了设置里"——那正是这个功能最容易漏的一环。
     */
    private class RecordingSettingsStore(
        var current: RelaySettings? = null,
    ) : SettingsStore {
        var saveCount = 0
            private set

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            saveCount++
            current = settings
        }
    }

    /**
     * 记录调用次数，并把每次调用的结果交给测试。
     *
     * [arm] 用来丢掉已记录的信号：断言"从这一刻起一次都没调"时，
     * 之前那一次已完成的 save 会让等待立刻返回，看上去像真的又写了一次。
     */
    private class RecordingMessageStore : MessageStore {
        private var saveSignal = CompletableDeferred<Map<String, List<MessageUi>>>()
        private var clearSignal = CompletableDeferred<Unit>()

        // 故意不加 @Volatile：commonTest 里解析不到 kotlin.concurrent.Volatile
        // （Kotlin 1.9 才把它挪进 common），而这里跨线程的可见性由
        // withTimeout 的挂起边界提供 —— 测试与 IO 分属两个调度器，交替的
        // await/计数读之间必然有过一次真实的挂起与恢复。
        var saveCount = 0

        var clearCount = 0

        override fun load(): Map<String, List<MessageUi>> = emptyMap()

        override fun save(threads: Map<String, List<MessageUi>>) {
            saveCount++
            saveSignal.complete(threads)
        }

        override fun clear() {
            clearCount++
            clearSignal.complete(Unit)
        }

        fun arm() {
            saveSignal = CompletableDeferred()
            clearSignal = CompletableDeferred()
        }

        suspend fun awaitSave(): Map<String, List<MessageUi>> = saveSignal.await()

        suspend fun awaitClear() = clearSignal.await()

        /** 任何一个调用发生就返回；调用 `withTimeout` 包它即得"等一小段就放弃"。 */
        suspend fun awaitAnyCall(): String = select {
            saveSignal.onAwait { "save" }
            clearSignal.onAwait { "clear" }
        }
    }
}