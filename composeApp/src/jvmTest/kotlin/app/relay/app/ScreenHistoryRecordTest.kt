// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.relay.core.protocol.ScreenPhase
import app.relay.core.protocol.ScreenRole
import app.relay.db.RelayDatabase
import app.relay.platform.InMemoryAppPlatform
import app.relay.ui.components.ScreenCardCase
import app.relay.ui.components.screenCardCase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import app.relay.app.model.MessageUi
import app.relay.app.model.ScreenRecordUi
import app.relay.app.model.SCREEN_RECORD_VERSION
import app.relay.app.model.screenHistoryMessageId

/**
 * 屏幕协助历史卡（原型 `m-screencard`）的存储、迁移与启动卫生。
 *
 * 这一屏的缺口原本写在**本地历史记录**上（不是 wire —— `ScreenPayload.kt` 的
 * `ScreenSessionState` 一直与茶话间 `ScreenState` 逐个字段对应）。所以用例也围着
 * "记录这一半"打，三个方向各一条：
 *
 * 1. **落盘与读回**：卡片是"这件事发生过"的证据，重启后必须还在（对比文件卡：
 *    `toStorable()` 刻意把 `file` 折成 null，屏幕记录**不折**）。
 * 2. **老库升级**：`screen_json` 是后加的列，老用户的库里没有它 —— 没有 `1.sqm`
 *    的症状不是"少一张卡"，是打开库那一刻就 `no such column: screen_json`。
 * 3. **启动卫生**：进程被杀掉时留下一条 `phase: active` 的记录，重启后不能一直画
 *    "协助中"（那个会话早就没了）。对齐茶话间 `msg-repo.ts:interruptScreenRecords()`。
 */
class ScreenHistoryRecordTest {

    private lateinit var driver: JdbcSqliteDriver

    @AfterTest
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
    }

    private fun newStore(): SqlDelightMessageStore {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RelayDatabase.Schema.create(driver)
        return SqlDelightMessageStore(RelayDatabase(driver))
    }

    /** 进行中的记录：viewer 侧，正在协助。 */
    private fun activeRecord(sessionId: String = "s-1") = ScreenRecordUi(
        v = SCREEN_RECORD_VERSION,
        role = ScreenRole.Viewer,
        phase = ScreenPhase.Active,
        requestedAt = 1_000L,
        startedAt = 1_200L,
    )

    @Test
    fun activeScreenRecordSurvivesStoreRoundTrip() {
        val store = newStore()
        val record = activeRecord()
        store.save(
            mapOf(
                "peer-1" to listOf(
                    MessageUi(id = "m1", peerId = "peer-1", mine = false, text = "在吗", at = 900),
                    MessageUi(
                        id = screenHistoryMessageId("peer-1", "s-1"),
                        peerId = "peer-1",
                        mine = true,
                        text = "屏幕协助进行中",
                        at = record.requestedAt,
                        screen = record,
                    ),
                ),
            ),
        )

        val loaded = store.load().getValue("peer-1")
        assertEquals(2, loaded.size)
        val card = loaded.single { it.screen != null }
        assertEquals(record, card.screen, "进行中的记录必须逐字段原样读回")
        assertEquals("peer-1", card.peerId)
        assertTrue(card.mine, "viewer 侧的记录落在\"我发的\"那一侧（茶话间 isMine: role === 'viewer'）")
        assertEquals(record.requestedAt, card.at, "时间锚点是请求那一刻，不是结束那一刻")

        // 同一张表里的文件消息仍然按老规矩折掉 `file` —— 别把新列的加入顺手改了旧行为。
        assertNull(loaded.single { it.id == "m1" }.file)
    }

    @Test
    fun terminalScreenRecordSurvivesStoreRoundTrip() {
        val store = newStore()
        val record = ScreenRecordUi(
            v = SCREEN_RECORD_VERSION,
            role = ScreenRole.Sharer,
            phase = ScreenPhase.Error,
            reason = "disconnected",
            requestedAt = 5_000L,
            startedAt = 5_100L,
            endedAt = 5_400L,
            durationMs = 300L,
        )
        store.save(
            mapOf(
                "peer-2" to listOf(
                    MessageUi(
                        id = screenHistoryMessageId("peer-2", "s-2"),
                        peerId = "peer-2",
                        mine = false,
                        text = "屏幕连接已断开",
                        at = record.requestedAt,
                        screen = record,
                    ),
                ),
            ),
        )

        val card = store.load().getValue("peer-2").single()
        assertEquals(record, card.screen)
        assertEquals("disconnected", card.screen?.reason)
        assertEquals(300L, card.screen?.durationMs)
        // Sharer 侧的记录落在"对方发的"那一侧，与 viewer 侧相反。
        assertTrue(!card.mine)
    }

    /**
     * 没有开始过的那一档（被拒绝/占线）：`startedAt` 与 `durationMs` 都必须是 null ——
     * 茶话间副行因此写「仅查看」而不是「时长」。
     */
    @Test
    fun neverStartedScreenRecordRoundTrips() {
        val store = newStore()
        val record = ScreenRecordUi(
            v = SCREEN_RECORD_VERSION,
            role = ScreenRole.Viewer,
            phase = ScreenPhase.Error,
            reason = "declined",
            requestedAt = 5_000L,
            endedAt = 5_400L,
        )
        store.save(
            mapOf(
                "peer-3" to listOf(
                    MessageUi(
                        id = screenHistoryMessageId("peer-3", "s-3"),
                        peerId = "peer-3",
                        mine = true,
                        text = "对方拒绝",
                        at = record.requestedAt,
                        screen = record,
                    ),
                ),
            ),
        )

        val restored = store.load().getValue("peer-3").single().screen
        assertEquals(record, restored)
        assertNull(restored?.startedAt)
        assertNull(restored?.durationMs)
    }

    /**
     * 老库（`user_version = 1`，表里没有 `screen_json`）升到当前版本。
     *
     * 这里手写的是**旧版** `MessageEntity`：刻意不引生成的 schema，因为要验的就是
     * "一个在加列之前就存在的库能不能被迁移开"。用生成的 `create()` 建库会让这条用例
     * 永远绿 —— 那样测的是新库，不是迁移。
     */
    @Test
    fun migrationFromVersionOneAddsScreenColumn() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE MessageEntity (
                id TEXT NOT NULL PRIMARY KEY,
                conv_id TEXT NOT NULL,
                peer_id TEXT NOT NULL,
                sender_id TEXT,
                mine INTEGER NOT NULL,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                delivery TEXT,
                outgoing_envelope TEXT,
                notice TEXT,
                file_json TEXT
            )
            """.trimIndent(),
            0,
        )
        // 加列之前就存在的一行：迁移不能把它弄丢。
        driver.execute(
            null,
            "INSERT INTO MessageEntity (id, conv_id, peer_id, mine, text, timestamp) " +
                "VALUES ('old-1', 'peer-1', 'peer-1', 0, '加列之前的消息', 10)",
            0,
        )
        driver.execute(null, "PRAGMA user_version = 1", 0)

        RelayDatabase.Schema.migrate(driver, 1, RelayDatabase.Schema.version)
        // 这条**故意**写死数字：它是一个提醒 —— 每加一个 `.sqm` 都要回来把它 +1，
        // 顺便逼人想一遍"老库上这一级迁移会不会丢东西"。（`2.sqm` 加的就是那四张 agent 表。）
        assertEquals(14L, RelayDatabase.Schema.version, "迁移文件数 + 1 —— 加一个 .sqm 就得同步改这条")

        val database = RelayDatabase(driver)
        val old = database.relayDatabaseQueries.selectMessagesByConv("peer-1").executeAsList().single()
        assertEquals("加列之前的消息", old.text)
        assertNull(old.screen_json, "老行的新列是 NULL ⇒ 读回来 screen == null，不需要回填")

        // 新列真的可写可读（不是"迁移没报错"就算过）。
        database.relayDatabaseQueries.insertMessage(
            id = "new-1",
            conv_id = "peer-1",
            peer_id = "peer-1",
            sender_id = null,
            mine = 1,
            text = "屏幕协助进行中",
            timestamp = 20,
            delivery = null,
            notice = null,
            notice_detail = null,
            file_json = null,
            screen_json = MessageStore.json.encodeToString(activeRecord()),
            agent_session_id = null,
            agent_session_title = null,
        )
        val fresh = database.relayDatabaseQueries.selectMessagesByConv("peer-1").executeAsList()
            .single { it.id == "new-1" }
        assertEquals(activeRecord(), fresh.screen_json?.let { parseScreenRecordUi(it) })
    }

    /**
     * 启动卫生：上一次运行留下的、没走完的会话必须被标成"已中断"，且**不补造时长**。
     *
     * 不补 `endedAt`/`durationMs` 是茶话间 `interruptScreenRecords()` 的逐字行为
     * （注释：「离线时段不补计协助时长」）—— 卡片副行因此写「时长 未知」而不是编一个数。
     */
    @Test
    fun startupHygieneMarksUnfinishedRecordsInterrupted() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = RelayController(
                platform = InMemoryAppPlatform(),
                store = MemorySettingsStore(),
                scope = scope,
                version = "relay-test",
            )
            val unfinished = screenRecordMessage(
                peerId = "peer-1",
                sessionId = "s-1",
                record = activeRecord(),
                text = "屏幕协助进行中",
            )
            val finished = screenRecordMessage(
                peerId = "peer-2",
                sessionId = "s-2",
                record = ScreenRecordUi(
                    v = SCREEN_RECORD_VERSION,
                    role = ScreenRole.Sharer,
                    phase = ScreenPhase.Ended,
                    reason = "user",
                    requestedAt = 3_000L,
                    endedAt = 4_000L,
                    durationMs = 1_000L,
                ),
                text = "屏幕协助已结束",
            )
            controller.appendMessage(unfinished)
            controller.appendMessage(finished)

            runBlocking { controller.screenHistoryCtl.handleInterruptUnfinishedScreenRecords() }

            val swept = controller.core.threads.getValue("peer-1").single().screen!!
            assertEquals(ScreenPhase.Ended, swept.phase, "没走完的会话重启后不能还画着\"协助中\"")
            assertEquals(SCREEN_INTERRUPTED_REASON, swept.reason)
            assertNull(swept.endedAt, "不补造结束时间")
            assertNull(swept.durationMs, "不补造时长")
            assertTrue(swept.isTerminal)

            // 已经结束的那条一个字节都不许动：它本来就带真实时长。
            val untouched = controller.core.threads.getValue("peer-2").single().screen!!
            assertEquals("user", untouched.reason)
            assertEquals(1_000L, untouched.durationMs)

            // 幂等：再跑一次不会把 reason 从 interrupted 改成别的，也不会重复改写。
            runBlocking { controller.screenHistoryCtl.handleInterruptUnfinishedScreenRecords() }
            assertEquals(SCREEN_INTERRUPTED_REASON, controller.core.threads.getValue("peer-1").single().screen!!.reason)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun parseRejectsMalformedRecords() {
        assertNull(parseScreenRecordUi(""), "空串")
        assertNull(parseScreenRecordUi("不是 JSON"), "不是 JSON")
        assertNull(parseScreenRecordUi("""{"v":2,"role":"Viewer","phase":"Active","requestedAt":1,"startedAt":2}"""), "版本不对")
        assertNull(parseScreenRecordUi("""{"v":1,"role":"Nobody","phase":"Active","requestedAt":1}"""), "角色不在枚举里")
        assertNull(parseScreenRecordUi("""{"v":1,"role":"Viewer","phase":"Nope","requestedAt":1}"""), "相位不在枚举里")
        assertNull(parseScreenRecordUi("""{"v":1,"role":"Viewer","phase":"Active","requestedAt":0}"""), "请求时间必须是正数")
        assertNull(parseScreenRecordUi("""{"v":1,"role":"Viewer","phase":"Active","requestedAt":1}"""), "active 必须有 startedAt")
        assertNull(
            parseScreenRecordUi("""{"v":1,"role":"Viewer","phase":"Ended","reason":"user","requestedAt":1}"""),
            "正常结束必须有 endedAt",
        )
        assertNull(
            parseScreenRecordUi(
                """{"v":1,"role":"Viewer","phase":"Ended","reason":"user","requestedAt":1,"endedAt":3,"durationMs":1}""",
            ),
            "有 durationMs 就必须有 startedAt（时长是\"从开始算起\"）",
        )
        assertNull(parseScreenRecordUi("x".repeat(SCREEN_RECORD_MAX_CHARS + 1)), "超长直接拒，不去解析")

        // 正例：`interrupted` 是唯一允许没有 `endedAt` 的终态 —— 启动卫生写出来的就是它，
        // 这条读不回来会让「重启后那张卡」整个消失。
        val interrupted = parseScreenRecordUi(
            """{"v":1,"role":"Viewer","phase":"Ended","reason":"interrupted","requestedAt":1}""",
        )
        assertEquals(SCREEN_INTERRUPTED_REASON, interrupted?.reason)
        assertNull(interrupted?.endedAt)
        assertNull(interrupted?.durationMs)
    }

    /**
     * 版本闸必须是**真的**闸：写出去的 JSON 里要带 `v`，缺 `v` 的 JSON 要整条作废。
     *
     * 2026-10-12 独立验证者实测到的洞：`MessageStore.json` 没开 `encodeDefaults`，而 `v` 原先
     * 带默认值 ⇒ 编码时被丢掉、解析时又被默认值补成 1 ⇒ `v != SCREEN_RECORD_VERSION` 那道闸
     * 对自家数据**永远为真**，缺 `v` 的坏数据也会被放行。上面那批坏数据用例每条都手写了
     * `"v":1`，恰好照不到这一条。修法是给 `v` 去掉默认值（见 `ScreenRecordUi` 的字段注释）。
     */
    @Test
    fun encodedRecordCarriesVersionAndMissingVersionIsRejected() {
        val encoded = MessageStore.json.encodeToString(activeRecord())
        assertTrue("\"v\":1" in encoded, "写出去的 JSON 必须带版本号，实得：$encoded")

        // 缺 `v` 的 JSON：`v` 现在是必填字段 ⇒ 反序列化抛 MissingFieldException ⇒ 整条作废。
        assertNull(
            parseScreenRecordUi("""{"role":"Viewer","phase":"Active","requestedAt":1,"startedAt":2}"""),
            "缺 v 的记录必须被拒（否则版本闸形同虚设）",
        )
    }

    /**
     * 状态词表逐档核对（原型 annot：`remote-view-text.ts` 的 16 种，其中 `declined`/`busy`
     * 按角色分叉，所以枚举是 19 档）。分叉那两档是最容易写反的 —— 拒绝者自己不该看到
     * 「对方拒绝」，所以这里把两侧都钉住。
     */
    @Test
    fun statusWordMappingCoversEveryCase() {
        fun record(
            phase: ScreenPhase,
            reason: String? = null,
            role: ScreenRole = ScreenRole.Viewer,
            startedAt: Long? = null,
        ) = ScreenRecordUi(
            v = SCREEN_RECORD_VERSION,
            role = role,
            phase = phase,
            reason = reason,
            requestedAt = 1L,
            startedAt = startedAt,
        )

        assertEquals(ScreenCardCase.Requesting, screenCardCase(record(ScreenPhase.Requesting)))
        assertEquals(ScreenCardCase.AwaitingConsent, screenCardCase(record(ScreenPhase.AwaitingConsent)))
        assertEquals(ScreenCardCase.Connecting, screenCardCase(record(ScreenPhase.Preparing)))
        assertEquals(ScreenCardCase.Connecting, screenCardCase(record(ScreenPhase.Connecting)))
        assertEquals(ScreenCardCase.Active, screenCardCase(record(ScreenPhase.Active, startedAt = 2L)))

        // 同一个原因码在两侧语义相反：本侧发起的拒绝不能告诉用户"对方拒绝"。
        assertEquals(
            ScreenCardCase.DeclinedSelf,
            screenCardCase(record(ScreenPhase.Ended, "declined", role = ScreenRole.Sharer)),
        )
        assertEquals(
            ScreenCardCase.DeclinedPeer,
            screenCardCase(record(ScreenPhase.Ended, "declined", role = ScreenRole.Viewer)),
        )
        assertEquals(
            ScreenCardCase.BusySelf,
            screenCardCase(record(ScreenPhase.Error, "busy", role = ScreenRole.Sharer)),
        )
        assertEquals(
            ScreenCardCase.BusyPeer,
            screenCardCase(record(ScreenPhase.Error, "busy", role = ScreenRole.Viewer)),
        )

        assertEquals(ScreenCardCase.Unsupported, screenCardCase(record(ScreenPhase.Error, "unsupported")))
        assertEquals(ScreenCardCase.PermissionDenied, screenCardCase(record(ScreenPhase.Error, "permission-denied")))
        assertEquals(ScreenCardCase.CaptureStopped, screenCardCase(record(ScreenPhase.Error, "capture-failed")))
        assertEquals(ScreenCardCase.CaptureStopped, screenCardCase(record(ScreenPhase.Error, "capture-ended")))
        assertEquals(ScreenCardCase.Timeout, screenCardCase(record(ScreenPhase.Error, "timeout")))
        assertEquals(ScreenCardCase.Disconnected, screenCardCase(record(ScreenPhase.Error, "disconnected")))
        assertEquals(ScreenCardCase.ProtocolError, screenCardCase(record(ScreenPhase.Error, "protocol-error")))
        assertEquals(ScreenCardCase.Locked, screenCardCase(record(ScreenPhase.Ended, "locked")))
        assertEquals(ScreenCardCase.Suspended, screenCardCase(record(ScreenPhase.Ended, "suspended")))
        assertEquals(ScreenCardCase.Interrupted, screenCardCase(record(ScreenPhase.Ended, SCREEN_INTERRUPTED_REASON)))

        // 兜底：没有原因码时按"有没有开始过"分叉 —— 开始了就是正常结束，没开始就是取消。
        assertEquals(ScreenCardCase.Ended, screenCardCase(record(ScreenPhase.Ended, null, startedAt = 2L)))
        assertEquals(ScreenCardCase.Canceled, screenCardCase(record(ScreenPhase.Ended, null, startedAt = null)))
    }

    /**
     * 屏幕协助记录**不进正文检索**。
     *
     * 卡片的 `text` 是机器生成的状态长句（`screen_card_text_*`），不是谁写的字。放它进检索
     * 会让搜「屏幕」把每一场协助都捞出来、用户真正写的那句话反被埋掉。参考实现茶话间同样
     * 排除：`search-selftest.ts:27` 带 `AND m.kind IN ('text','pk')`，而屏幕记录落库时
     * `kind = 'system'`（`src/main/services/chat.ts:104-125`）。本端没有 `kind` 列，靠
     * `screen_json IS NULL` 认 —— 六条正文检索都加了这一句（三条 FTS + 三条 LIKE 兜底）。
     *
     * 这个用例必须**两条路径都钉**：JVM 上的框架 SQLite 有 fts5，走的是 FTS 那三条；
     * Android 上没有 fts5，走的是 LIKE 那三条。只测一条等于把另一半交给运气。
     */
    @Test
    fun screenRecordIsExcludedFromSearch() {
        val store = newStore()
        val record = activeRecord()
        store.save(
            mapOf(
                "peer-1" to listOf(
                    // 正文**故意与卡片同一句话**：这样"卡片没被滤掉"时这条查询会同时命中两行，
                    // 断言才真的在测那道过滤，而不是在测"查询词恰好没出现在普通消息里"。
                    MessageUi(id = "m-plain", peerId = "peer-1", mine = false, text = "屏幕协助进行中", at = 100),
                    MessageUi(
                        id = screenHistoryMessageId("peer-1", "s-1"),
                        peerId = "peer-1",
                        mine = true,
                        text = "屏幕协助进行中",
                        at = record.requestedAt,
                        screen = record,
                    ),
                ),
            ),
        )

        // FTS 路径：查询串 >= 3 字符且本机有 fts5 时 `search()` 走它。
        val ftsHits = store.search("屏幕协助", 50).map { it.id }
        assertEquals(listOf("m-plain"), ftsHits, "FTS 路径不许命中卡片")

        // LIKE 兜底路径：查询串 < 3 字符（单个词短于 trigram 的最小 token）或本机没有 fts5
        // （Android 框架 SQLite 就是）时走它 —— 生产上这是常走的那条。
        val likeHits = store.search("屏幕", 50).map { it.id }
        assertEquals(listOf("m-plain"), likeHits, "LIKE 路径不许命中卡片")
    }

    /**
     * 追加消息按 `at` 落位，位置不随重启改变。
     *
     * `restoreMessages()` 读回来时会 `sortBy { it.at }`，而无脑追加只在"新消息就是最新的"
     * 时与它一致。屏幕协助历史卡的 `at` 取的是 `requestedAt`，常早于流里后来到的消息 ——
     * 2026-10-12 独立验证者实测到同一条记录在内存里是 [200,100]、重启后变 [100,200]。
     */
    @Test
    fun outOfOrderAppendKeepsThreadSorted() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = RelayController(
                platform = InMemoryAppPlatform(),
                store = MemorySettingsStore(),
                scope = scope,
                version = "relay-test",
            )
            runBlocking {
                controller.appendMessage(MessageUi(id = "later", peerId = "peer-1", mine = false, text = "后来的", at = 200))
                controller.appendMessage(MessageUi(id = "earlier", peerId = "peer-1", mine = false, text = "早的", at = 100))
            }
            assertEquals(
                listOf("earlier", "later"),
                controller.core.threads.getValue("peer-1").map { it.id },
                "早到的消息要排在前面，与重启后的 sortBy 一致",
            )

            // 常见路径不受影响：`at` 最大时仍然落在末尾（`.lastOrNull()` 取预览的假设不能破）。
            runBlocking {
                controller.appendMessage(MessageUi(id = "newest", peerId = "peer-1", mine = false, text = "最新", at = 300))
            }
            assertEquals("newest", controller.core.threads.getValue("peer-1").last().id)
        } finally {
            scope.cancel()
        }
    }

    /** 与 `RelayControllerStateLockTest` 里那份同形：够启动、不落盘。 */
    private class MemorySettingsStore : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = "abc12345-0000-4000-8000-000000000000",
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }
}
