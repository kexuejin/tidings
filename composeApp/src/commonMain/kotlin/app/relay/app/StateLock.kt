package app.relay.app

/**
 * 保护 [RelayController] 里的可变会话状态（`threads` / `unread` / `pending` / `pendingRecalls`）。
 *
 * **为什么需要它**：控制器跑在 `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 上
 * （见 `androidApp/src/main/kotlin/RelayApplication.kt` 的 `scope`），而 `Dispatchers.Default`
 * 是**多线程池**。`observe()` 里每 2 秒一次的 `refreshPeers()` 会对 `threads` 做
 * `mapValues`，同一时刻 `restoreMessages()` / `appendMessage()` 可能在别的 worker 上
 * `getOrPut` / `+=`（都在 `RelayControllerMessagesComponent` 里）。
 * 一边遍历一边改结构就是 `java.util.ConcurrentModificationException` ——  * 那次「启动后 104ms 即崩溃」（`FATAL EXCEPTION: DefaultDispatcher-worker-5`
 * 落在 `RelayController.refreshPeers`）正是它。
 *
 * 注意 UI 线程的 `publish()` 同样会遍历 `threads`，所以**入站消息与界面刷新之间**
 * 也在同一把锁的保护范围内 —— 这不是一个只在启动时才会踩到的竞态。
 *
 * **为什么不用 `kotlinx.coroutines.sync.Mutex`**：`withLock` 是 `suspend` 的，而这些状态
 * 被 UI 线程与非 suspend 的回调（`refreshPeers` / `publish` / `appendMessage` …）同时读写，
 * 包不起来。控制器里已有的 `persistMutex` 只护得住持久化，正因为那条路径本来就是 suspend。
 *
 * **为什么不用 `synchronized` / `ReentrantLock`**：它们只在 JVM 上有，写进 commonMain 会让
 * iOS 目标编译失败（同 `core/src/commonMain/kotlin/app/relay/core/Platform.kt:24` 里
 * `ioDispatcher` 的理由）。这里照抄本模块既有的 `expect fun createMessageStore(...)`
 * 写法，把平台实现留给 `androidMain` / `jvmMain` / `iosMain`。
 *
 * **必须可重入**：`appendMessage` 持锁时会调到 `refreshPeers()` 与 `persistThreads()`，
 * 它们自己也取这把锁；`applyRecallFrom` 在遍历 `threads` 的循环里调 `applyRecall`。
 *
 * **持锁期间不得挂起**：这把锁是阻塞锁，只包非 suspend 的临界区。`requeueRestored`
 * 那类需要在循环里 `await` 的路径，要先把快照取出来、放锁之后再挂起。
 */
internal interface StateLock {
    fun lock()
    fun unlock()
}

/** 平台实现：JVM/Android 用 `ReentrantLock`，iOS 用 `NSRecursiveLock`（都必须是可重入的）。 */
internal expect fun createStateLock(): StateLock

/** [StateLock] 的 `use` 风格包装：`stateLock.withLock { … }`，抛异常也会解锁。 */
internal inline fun <T> StateLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
