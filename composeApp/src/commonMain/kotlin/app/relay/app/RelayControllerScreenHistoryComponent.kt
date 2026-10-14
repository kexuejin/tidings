// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.MessageUi
import app.relay.app.model.ScreenRecordUi
import app.relay.app.model.screenHistoryMessageId
import app.relay.app.model.toScreenRecordUi
import app.relay.core.protocol.ScreenPhase
import app.relay.core.protocol.ScreenRole
import app.relay.ui.components.screenCardText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 投屏记录：订阅相位变化 → 每次变化 upsert 成聊天里的一条消息，外加启动卫生检查。
 *
 * 切出来的理由：这是 11 个分片里唯一直接改 [RelayCoreState] 表的，它的并发纪律
 * （锁外算好 → 锁内回写 → 同一临界区里 publish）值得单独成文件 —— 改动时不必在
 * 1500 行里找。[handleInterruptUnfinishedScreenRecords] 尤其要：它每次启动都跑一次，
 * 而它要动的正是那张被崩溃事故反复光顾的 `core.threads`。
 *
 * [screenRecordMessage] 留在文件级、刻意不做成成员：它不需要 host，抽出纯函数
 * 就是为了让截图夹具与产品走**同一条**构造路径，两边都用裸调用。
 */
internal class RelayControllerScreenHistoryComponent(
    private val host: RelayController,
) {

/**
 * 屏幕协助历史卡的**生产者**。
 *
 * 对齐屏幕会话记录规范 `recordScreen(host.state, initial)`：每一次相位/原因变化都把当前记录
 * upsert 成聊天里的一条消息。三端共用一个**确定性的消息 id**
 * （[screenHistoryMessageId] = `screen:<会话键>:<会话 id>`），所以整场会话自始至终只有
 * 一条卡，不会每变一次相位就在聊天里多出一条半截记录。
 *
 * ### 为什么是"观察状态"而不是"在每个状态机分支里调用"
 * `RelayControllerScreen.kt` 里改 `screenSession.session` 的地方有十几处（发起、同意、
 * 采集就绪、TCP ready、对端拒绝、对端结束、超时、断开、本侧拒绝……）。逐处插一行写库
 * 迟早会漏一处 —— 而漏掉的那一处恰好就是"用户看到的最后状态"（比如 TCP ready 那处漏了，
 * 卡片会永远停在"连接中"）。这里改成订阅 `host._state` 里那一个字段：**只要它变了就一定写**，
 * 与分支多少无关。
 *
 * 订阅的是 `screenSession.session` 而不是整个 `screenSession`：帧字节/宽高/序号也在
 * `screenSession` 里，但它们是**画面**不是**记录**（投屏记录数据结构同样只有
 * 角色/相位/原因/三个时间戳）。映射成 [ScreenRecordUi] 之后逐字相同的记录还会被
 * [upsertScreenRecord] 挡掉，所以 fps 切换这种"状态变了但记录没变"的情况不会写库。
 */
fun handleObserveScreenRecords(): Job = host.scope.launch {
    // 「这是不是一场新会话」只能靠 sessionId 判：记录本身不存 sessionId（它在消息 id 里），
    // 而 `recordScreen(host.state, initial)` 的 `initial` 就是"这个 id 之前没有行"。
    var lastSessionId: String? = null
    host._state
        .map { it.screenSession.session }
        .distinctUntilChanged()
        .collect { session ->
            if (session == null) {
                lastSessionId = null
                return@collect
            }
            val record = session.toScreenRecordUi()
            val initial = session.sessionId != lastSessionId
            lastSessionId = session.sessionId
            handleUpsertScreenRecord(
                peerId = session.peerId,
                sessionId = session.sessionId,
                record = record,
                initial = initial,
            )
        }
}

/**
 * 把一条屏幕协助记录写进聊天流（没有就插入，有就就地改那一条）。
 *
 * 会话记录持久化三道卡控：
 * - **没有旧行时只有 `initial` 才允许插入**。`initial == false` 而聊天里找不到这条记录，
 *   说明用户已经把聊天记录删了（或清空了）—— 后续状态变化**不能把它复活**，
 *   否则"清空聊天记录"之后卡片会自己长回来。
 * - **已经结束的记录不再改**。一场会话结束后它就不再是"进行中"的活状态，
 *   再来的迟到状态（比如对端重发的 end）不该把它改回"连接中"。
 * - **逐字相同就不写**。相位没变但别的东西动了（帧率、revision）时不必动库。
 */


/**
 * 把一条屏幕协助记录写进聊天流（没有就插入，有就就地改那一条）。
 *
 * 会话记录持久化三道卡控：
 * - **没有旧行时只有 `initial` 才允许插入**。`initial == false` 而聊天里找不到这条记录，
 *   说明用户已经把聊天记录删了（或清空了）—— 后续状态变化**不能把它复活**，
 *   否则"清空聊天记录"之后卡片会自己长回来。
 * - **已经结束的记录不再改**。一场会话结束后它就不再是"进行中"的活状态，
 *   再来的迟到状态（比如对端重发的 end）不该把它改回"连接中"。
 * - **逐字相同就不写**。相位没变但别的东西动了（帧率、revision）时不必动库。
 */
suspend fun handleUpsertScreenRecord(
    peerId: String,
    sessionId: String,
    record: ScreenRecordUi,
    initial: Boolean,
) {
    val id = screenHistoryMessageId(peerId, sessionId)
    val existing = host.stateLock.withLock { host.core.threads[peerId]?.firstOrNull { it.id == id } }
    if (existing == null && !initial) return
    if (existing != null) {
        if (existing.screen?.isTerminal == true) return
        if (existing.screen == record) return
    }

    // 正文（`MessageUi.text`）只给两处用：会话列表那行预览、以及读屏。卡片本身从
    // `record` 画。取不到资源时留空而不是整条丢掉 —— 历史记录比一句文案重要
    // （先例：`onNudged` 里的 `getString` 也是 `runCatching` 包着的）。
    val text = runCatching { screenCardText(record) }.getOrNull().orEmpty()
    val message = screenRecordMessage(peerId, sessionId, record, text)
    if (existing == null) {
        // `incoming = false`：记录规范明确指定这条记录
        // 「与线上消息发送、帧流和未读计数无关」，它不涨未读。
        host.handleAppendMessage(message, incoming = false)
    } else {
        host.handleReplaceMessage(peerId, message)
    }
}

/**
 * 构造那条消息。抽成纯函数是为了让截图夹具与生产者用**同一条**构造路径 ——
 * 否则夹具里手写的卡片与产品里真写出来的那条迟早会长得不一样。
 */


/**
 * 启动卫生：把**上一次运行**留下的、没走完的会话标成 [SCREEN_INTERRUPTED_REASON]。
 *
 * 对齐异常中断记录规范 `interruptScreenRecords()`（它在应用启动装配时被调一次）：
 * 库里若留着一条 `phase: active` 的记录，重启后卡片会一直画着"协助中"—— 而那个会话
 * 早就随进程一起没了。这不是"补造一个结束时间"，而是"承认它没走完"：
 * `endedAt` 与 `durationMs` 一律留空（规范约定：离线时段不补计协助时长），
 * 卡片副行因此写「时长 未知」，而不是编一个时长出来。
 *
 * 为什么必须在这里做而不是"下次发起时顺手清"：用户可能永远不再发起屏幕协助，
 * 那条假"协助中"就会一直挂在他的聊天记录里。
 */
suspend fun handleInterruptUnfinishedScreenRecords() {
    // 先在锁外算好要写什么（`screenCardText` 是挂起的，持锁期间不能挂起），
    // 再拿一次锁逐个回写 —— 与 `requeueRestored` 是同一条纪律。
    val stale = host.stateLock.withLock {
        host.core.threads.values.flatMap { list -> list.filter { it.screen?.isTerminal == false } }
    }
    if (stale.isEmpty()) return
    val replacements = stale.map { message ->
        val record = message.screen!!.copy(
            phase = ScreenPhase.Ended,
            reason = SCREEN_INTERRUPTED_REASON,
            endedAt = null,
            durationMs = null,
        )
        val text = runCatching { screenCardText(record) }.getOrNull().orEmpty()
        message.copy(text = text, screen = record)
    }
    host.stateLock.withLock {
        for (message in replacements) {
            val list = host.core.threads[message.peerId] ?: continue
            val index = list.indexOfFirst { it.id == message.id }
            if (index >= 0) list[index] = message
        }
    }
    host.publish()
    host.handlePersistThreads()
}
}


/**
 * 构造那条消息。抽成纯函数是为了让截图夹具与生产者用**同一条**构造路径 ——
 * 否则夹具里手写的卡片与产品里真写出来的那条迟早会长得不一样。
 */
internal fun screenRecordMessage(
    peerId: String,
    sessionId: String,
    record: ScreenRecordUi,
    text: String,
): MessageUi = MessageUi(
    id = screenHistoryMessageId(peerId, sessionId),
    peerId = peerId,
    // `isMine: role === 'viewer'`：我看别人的屏幕时这条记在"我发的"那一侧。
    mine = record.role == ScreenRole.Viewer,
    text = text,
    // `ts: requestedAt`：这条记录的时间锚点是"请求那一刻"，不是结束那一刻。
    at = record.requestedAt,
    screen = record,
)

/**
 * 启动卫生：把**上一次运行**留下的、没走完的会话标成 [SCREEN_INTERRUPTED_REASON]。
 *
 * 对齐异常中断记录规范 `interruptScreenRecords()`（它在应用启动装配时被调一次）：
 * 库里若留着一条 `phase: active` 的记录，重启后卡片会一直画着"协助中"—— 而那个会话
 * 早就随进程一起没了。这不是"补造一个结束时间"，而是"承认它没走完"：
 * `endedAt` 与 `durationMs` 一律留空（规范约定：离线时段不补计协助时长），
 * 卡片副行因此写「时长 未知」，而不是编一个时长出来。
 *
 * 为什么必须在这里做而不是"下次发起时顺手清"：用户可能永远不再发起屏幕协助，
 * 那条假"协助中"就会一直挂在他的聊天记录里。
 */
