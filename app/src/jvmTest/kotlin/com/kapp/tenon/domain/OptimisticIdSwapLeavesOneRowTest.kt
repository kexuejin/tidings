// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tenon.db.RelayDatabase
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 乐观气泡换 id 之后，库里**只能剩一行**（真机 A22：「同一句话出现两遍，而且都是我发的」）。
 *
 * ## 真身
 *
 * 乐观插入先落 `local-<n>-<ts>`，发送路径返回后 `replaceMessage` 把内存里那条就地改成
 * 真实信封 id。内存是一次替换（一条），库却是 upsert：`save()` 明确**不**按"快照里没有"
 * 反推删除（那会删掉窗口外的历史），于是旧 id 那行永久留下 —— 重启后同一句话出现两遍，
 * 两遍都是 `mine = true`。
 *
 * 手机库里就是这么躺着的：`local-5-…` 与 `da0f2339` 同文本、同为 `mine=1`。
 *
 * 反向验证：去掉 `replaceMessage` 里那段 `deleteMessage(previousId)`，本用例变红
 * （库里两行都在）。
 */
class OptimisticIdSwapLeavesOneRowTest {

    private val peer = "peer-a"
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: SqlDelightMessageStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RelayDatabase.Schema.create(driver)
        store = SqlDelightMessageStore(RelayDatabase(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun 换id后库里只剩真实id那一行() = runBlocking {
        val controller = controller()

        controller.appendMessage(
            MessageUi(id = "local-1-1794112975868", peerId = peer, mine = true, text = "在吗", at = 1_000L),
        )
        controller.replaceMessage(
            peer,
            "local-1-1794112975868",
            MessageUi(id = "env-real-1", peerId = peer, mine = true, text = "在吗", at = 1_000L),
        )

        val ids = awaitRows { rows -> rows.any { it.id == "env-real-1" } }.map { it.id }
        assertEquals(listOf("env-real-1"), ids, "换 id 是「同一条消息改名」，库里不该留下旧 id 那一行")
        assertTrue(
            ids.none { it.startsWith("local-") },
            "临时 id 的行必须被删掉 —— 否则重启后同一句话出现两遍（都算「我发的」）",
        )
    }

    private suspend fun awaitRows(predicate: (List<MessageUi>) -> Boolean): List<MessageUi> {
        var rows: List<MessageUi> = emptyList()
        withTimeout(5_000) {
            while (true) {
                rows = store.load()[peer].orEmpty()
                if (predicate(rows)) return@withTimeout
                delay(10)
            }
        }
        return rows
    }

    private fun CoroutineScope.controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(peer),
        scope = this,
        version = "relay-test",
        messageStore = store,
    )

    private class MemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
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
