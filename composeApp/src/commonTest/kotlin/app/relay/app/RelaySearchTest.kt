// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.relay.app.model.FileCardUi
import app.relay.app.model.PeerUi

/**
 * 检索的**规则**部分：谁算命中、哪一条算哪一档。
 *
 * 与存储层那两条 SQL 路径的测试分开（后者在 `jvmTest/.../SqlDelightMessageStoreFtsTest.kt`，
 * 因为它要真的起一个 SQLite）：这里只验判据，不起库、不起节点。
 *
 * 为什么这些判据值得单独钉住：它们错了**不会崩**。搜「图片」搜出一堆 pdf、
 * 联系人按主机名搜出别人搜不到的人，界面上都只是"结果有点怪"——
 * 截图门禁看不见（画面里有一列结果，行高也对），真机手测也看不见
 * （手测的人只搜自己知道的那个词，恰好命中了）。
 */
class RelaySearchTest {

    private fun peer(
        nodeId: String = "n-1",
        nick: String = "老周",
        company: String = "",
        dept: String = "",
        team: String = "",
        host: String = "",
        ip: String = "",
        caps: List<String> = emptyList(),
        online: Boolean = false,
    ) = PeerUi(
        nodeId = nodeId,
        nick = nick,
        company = company,
        dept = dept,
        team = team,
        host = host,
        ip = ip,
        caps = caps,
        online = online,
    )

    private fun card(name: String, purpose: String? = null) =
        FileCardUi(transferId = "t-1", name = name, size = 1L, purpose = purpose)

    // ---------------------------------------------------------------- 联系人段

    @Test
    fun 联系人检索不含主机名() {
        // 主机名是本机给对端起的外号，两台机器可能各叫各的：用它检索会让
        // 同一个人在两台设备上搜出不同结果。IP 是双方共见的，所以 IP 要能搜。
        val zhou = peer(host = "zhou-macbook-pro", ip = "192.168.1.31")

        assertTrue(matchContacts(listOf(zhou), "192.168.1").isNotEmpty(), "IP 要能搜到")
        assertTrue(
            matchContacts(listOf(zhou), "zhou-macbook").isEmpty(),
            "主机名不能搜到 —— 计划 2026-10-08-relay-prototype-gap-plan.md:111 明写了这条",
        )
    }

    @Test
    fun 联系人检索覆盖昵称公司部门团队() {
        val zhou = peer(nick = "老周", company = "捎话科技", dept = "客户端", team = "协议组")

        for (q in listOf("老周", "捎话", "客户端", "协议组")) {
            assertEquals(1, matchContacts(listOf(zhou), q).size, "「$q」应该搜到老周")
        }
    }

    @Test
    fun 联系人检索忽略大小写并且空词不命中() {
        val lin = peer(nick = "Lin", company = "ACME")

        assertEquals(1, matchContacts(listOf(lin), "lin").size)
        assertEquals(1, matchContacts(listOf(lin), "acme").size)
        assertTrue(matchContacts(listOf(lin), "   ").isEmpty(), "只有空格等于没搜")
    }

    @Test
    fun 联系人段上限是20() {
        // 上限是原型的数字（GlobalSearchResult.CONTACT_LIMIT）。它存在的意义是
        // 一个 200 人的局域网里，搜「a」不会把三段里的第一段撑成 200 行。
        val many = (1..30).map { peer(nodeId = "n-$it", nick = "同事$it") }

        assertEquals(20, matchContacts(many, "同事").size)
    }

    @Test
    fun 联系人段的标题与副标题走displayName与subtitle() {
        val zhou = peer(nick = "", host = "zhou-pc", ip = "10.0.0.5", company = "捎话", dept = "客户端")

        val hit = matchContacts(listOf(zhou), "10.0.0.5").single()

        // 昵称为空时 displayName 退回 host —— 绝不显示空标题。
        assertEquals("zhou-pc", hit.title)
        // 有公司部门时 subtitle 是它们，不是 IP。
        assertEquals("捎话 · 客户端", hit.subtitle)
    }

    @Test
    fun 联系人段区分人与agent() {
        val bot = peer(nodeId = "n-bot", nick = "构建机器人", caps = listOf(MsgTypes.CAP_AGENT))

        val hit = matchContacts(listOf(bot), "机器人").single()

        assertTrue(hit.isAgent, "带 CAP_AGENT 的节点在检索结果里也要标出来")
    }

    // ---------------------------------------------------------------- 会话内：类型档

    @Test
    fun 图片档只收图片文件档只收非图片() {
        val image = card("shot.png")
        val pdf = card("需求文档.pdf")
        val voice = card("voice.m4a")

        assertTrue(HistorySearchKind.Image.matches(image))
        assertFalse(HistorySearchKind.Image.matches(pdf))
        assertFalse(HistorySearchKind.Image.matches(voice))
        assertFalse(HistorySearchKind.Image.matches(null), "没有附件的消息不属于图片档")

        assertTrue(HistorySearchKind.File.matches(pdf))
        assertTrue(HistorySearchKind.File.matches(voice), "语音也是「文件」这一档 —— 原型只有三个 chip")
        // 一张图不能同时属于「图片」和「文件」两档，否则用户切到哪一档都看见它、会以为筛错了。
        assertFalse(HistorySearchKind.File.matches(image))
        assertFalse(HistorySearchKind.File.matches(null))
    }

    @Test
    fun 全部档收下一切() {
        assertTrue(HistorySearchKind.All.matches(null), "没有附件的纯文本消息在「全部」里")
        assertTrue(HistorySearchKind.All.matches(card("a.pdf")))
        assertTrue(HistorySearchKind.All.matches(card("a.png")))
    }

    @Test
    fun 类型档与气泡的呈现同源() {
        // 对端声明了 purpose=image 但文件名是 .bin：气泡里画缩略图（mediaKindOf 认声明），
        // 那它在「图片」档里也必须找得到 —— 同一个文件在两个地方有两种身份是最难查的一类 bug。
        val declared = card("scan.bin", purpose = "image")

        assertTrue(HistorySearchKind.Image.matches(declared))
        assertEquals(MediaKind.Image, mediaKindOf(declared), "两边必须同源")
    }

    // ---------------------------------------------------------------- 会话内：日期档

    @Test
    fun 日期是闭区间且两端可各自缺省() {
        val f = HistorySearchFilter(fromAt = 100L, toAt = 200L)

        assertFalse(f.accepts(99L), "下界是闭的")
        assertTrue(f.accepts(100L))
        assertTrue(f.accepts(200L))
        assertFalse(f.accepts(201L), "上界也是闭的")

        // 原型那个「清除」只清日期（ChatPane.vue:734-748），所以"只有关键词没有日期"是常态。
        assertTrue(HistorySearchFilter.None.accepts(0L))
        assertTrue(HistorySearchFilter(fromAt = 100L).accepts(Long.MAX_VALUE))
        assertTrue(HistorySearchFilter(toAt = 100L).accepts(0L))
    }

    @Test
    fun 没有条件时isEmpty为真() {
        assertTrue(HistorySearchFilter.None.isEmpty)
        assertFalse(HistorySearchFilter(kind = HistorySearchKind.Image).isEmpty)
        assertFalse(HistorySearchFilter(fromAt = 1L).isEmpty)
    }

    // ---------------------------------------------------------------- 全局结果的三段

    @Test
    fun 三块都空才算空结果而空关键词不算() {
        val nothing = GlobalSearchResult(query = "捎话")
        assertTrue(nothing.isEmpty)

        // 空关键词是"还没开始搜"，界面上是初始态不是无结果态，两者文案不同。
        // 这一条由 isEmpty 表达不出来（它只看三块），所以由调用方保证：
        // searchGlobal 对空词返回的也是空三块，但界面按"没输入"渲染。
        assertTrue(GlobalSearchResult(query = "").isEmpty)

        val oneContact = GlobalSearchResult(
            query = "捎话",
            contacts = listOf(
                GlobalSearchContactHit(nodeId = "n", title = "老周", subtitle = "", isAgent = false, online = true),
            ),
        )
        assertFalse(oneContact.isEmpty, "只要有一块非空就不是无结果")
    }

    @Test
    fun 三段上限是原型的数字() {
        // 这三个数画在原型注释里（「联系人（≤20）、聊天记录（按最新命中倒序，≤10 组）、文件（≤20）」），
        // 钉住是为了不让某次重构把它们变成"顺手写个 50"。
        assertEquals(20, GlobalSearchResult.CONTACT_LIMIT)
        assertEquals(10, GlobalSearchResult.CHAT_LIMIT)
        assertEquals(20, GlobalSearchResult.FILE_LIMIT)
    }

    @Test
    fun 会话内上限是50因为原型把它画在界面上() {
        // 原型结果区底部那句终态是「已列出这 4 条 · LIKE 最多取 50 条」——
        // 用户看得见这个数，所以它不能只在存储层当默认值、界面另写一个。
        assertEquals(50L, HISTORY_SEARCH_LIMIT)
    }
}
