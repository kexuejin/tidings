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
    fun persistIsOffByDefaultAndTheStoreIsNeverTouched() = runTest {
        val store = RecordingMessageStore()
        val controller = controller(store)

        // 默认就是关的：不声明也不该有任何写盘动作，行为与这个功能落地之前完全一致。
        assertEquals(false, controller.persistMessages, "默认必须是不落盘，否则等于替用户改了主意")

        controller.clearMessages("peer-never-talks")
        controller.updatePersistMessages(false)

        assertNothingHappens { store.awaitAnyCall() }
        assertEquals(0, store.saveCount, "save 调用数")
        assertEquals(0, store.clearCount, "clear 调用数")
    }

    @Test
    fun turningPersistOnWritesImmediatelyAndOnlyOnce() = runTest {
        val store = RecordingMessageStore()
        val controller = controller(store)

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
        val controller = controller(store)

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
        val controller = controller(store)

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
        val settings = RecordingSettingsStore()
        val controller = controller(messages, settings)
        // 构造时若没有设置文件会先存一份首次运行设置，那是启动行为、不属于这个功能，
        // 所以按"切换前后的增量"来数，不把启动那次算进来。
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
        val saved = RecordingSettingsStore().apply {
            current = RelaySettings(nodeId = "node-1", persistMessages = true)
        }

        val controller = controller(RecordingMessageStore(), saved)

        assertEquals(true, controller.persistMessages, "开关必须来自设置，而不是每次启动都从关开始")
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