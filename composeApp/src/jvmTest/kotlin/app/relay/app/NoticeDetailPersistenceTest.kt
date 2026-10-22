// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.MessageUi
import app.relay.app.model.NoticeDetail
import app.relay.app.model.NoticeKind
import app.relay.db.RelayDatabase
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **「谁把谁加/移进群」这件事必须落库，且能原样读回。**
 *
 * ## 为什么值得单独一个测试
 *
 * 通知原先只有一个 `NoticeKind` 枚举，文案是「群成员有变化」—— 不说是谁、把谁加/移了。
 * 用户只能自己去成员表里比对；同类产品（茶话间）写的是「张三邀请李四加入群聊」。
 *
 * 带上细节要动三处：`NoticeDetail` 模型、`notice_detail` 列（**含 `.sqm`**）、渲染。
 * 其中最容易假绿的是**落库**：`MessageUi` 上有字段、界面实时看得到，一重启就没了
 * —— 与 `agent_session_id` 当年那次一模一样。所以这条测的是**从库里读回来**。
 */
class NoticeDetailPersistenceTest {

    private fun newStore(): Pair<RelayDatabase, SqlDelightMessageStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RelayDatabase.Schema.create(driver)
        val db = RelayDatabase(driver)
        return db to SqlDelightMessageStore(db)
    }

    private fun notice(id: String, detail: NoticeDetail?) = MessageUi(
        id = id,
        peerId = "group:g-1",
        mine = false,
        text = "",
        at = 1_000L,
        notice = NoticeKind.GroupMembersChanged,
        noticeDetail = detail,
    )

    /** 「A 邀请 B」这条事实，重启后仍然在。 */
    @Test
    fun theActorAndAddedMembersSurviveAReload() {
        val (_, store) = newStore()
        store.save(mapOf("group:g-1" to listOf(
            notice("m-1", NoticeDetail(actor = "node-actor", added = listOf("node-b", "node-c"))),
        )))

        val back = store.load().getValue("group:g-1").single()
        val detail = assertNotNull(back.noticeDetail, "细节必须落库并读回，不能只活在内存里")
        assertEquals("node-actor", detail.actor)
        assertEquals(listOf("node-b", "node-c"), detail.added, "成员顺序要保持：那是当时看到的顺序")
        assertTrue(detail.removed.isEmpty())
    }

    /** 移人那条走 `removed`，与加人分开。 */
    @Test
    fun theRemovedMembersSurviveAReload() {
        val (_, store) = newStore()
        store.save(mapOf("group:g-1" to listOf(
            notice("m-2", NoticeDetail(actor = "node-actor", removed = listOf("node-old"))),
        )))
        val detail = assertNotNull(store.load().getValue("group:g-1").single().noticeDetail)
        assertEquals(listOf("node-old"), detail.removed)
        assertTrue(detail.added.isEmpty())
    }

    /**
     * **没有细节的通知照旧可读**（老库记录）。
     *
     * 这一列是后加的 ⇒ 升级前的那些通知读回来是 null。渲染端必须退回中性文案，
     * 而不是因为这些记录没有细节就把整条通知画没了。
     */
    @Test
    fun aNoticeWithoutDetailStaysReadable() {
        val (_, store) = newStore()
        store.save(mapOf("group:g-1" to listOf(notice("m-3", detail = null))))
        val back = store.load().getValue("group:g-1").single()
        assertEquals(NoticeKind.GroupMembersChanged, back.notice, "通知本身不能因为没细节而丢")
        assertNull(back.noticeDetail)
    }

    /** 空细节不写进库：`{}` 与 null 在语义上是同一件事，存两种形态只会让读取端多一个分支。 */
    @Test
    fun anEmptyDetailIsStoredAsNull() {
        val (_, store) = newStore()
        store.save(mapOf("group:g-1" to listOf(notice("m-4", NoticeDetail()))))
        assertNull(
            store.load().getValue("group:g-1").single().noticeDetail,
            "空细节应当落成 null，而不是一个空对象",
        )
    }
}
