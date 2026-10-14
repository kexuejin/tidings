// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgKinds
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.newId
import app.relay.core.nowMillis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 控制器里几个纯函数：它们都不碰 IO，但都决定用户看到什么。
 *
 * [RelayController.textOf] 决定一条入站帧要不要变成气泡；
 * [RelayController.mentionsSelf] 决定一条群消息在「仅被 @ 时提醒」下要不要响铃；
 * [RelayController.isPlausibleHost] 决定"手动加入"是否被拒；
 * [RelayController.bindFailureMessage] 决定端口被占时用户读到的是中文还是 BindException。
 */
class RelayControllerHelpersTest {

    private fun envelope(type: String, payload: JsonObject): Envelope =
        Envelope(v = 1, type = type, id = newId(), from = "11111111-1111-4111-8111-111111111111", ts = nowMillis(), payload = payload)

    // ---- textOf ----

    @Test
    fun textKindYieldsTheText() {
        val env = envelope(
            MsgTypes.MSG,
            buildJsonObject {
                put("kind", JsonPrimitive(MsgKinds.TEXT))
                put("text", JsonPrimitive("六条门禁全绿"))
            },
        )
        assertEquals("六条门禁全绿", RelayController.textOf(env))
    }

    @Test
    fun absentKindIsTreatedAsPlainText() {
        // 标准的 text 帧可以省略 kind —— 省略时就是普通文本。
        val env = envelope(MsgTypes.MSG, buildJsonObject { put("text", JsonPrimitive("你好")) })
        assertEquals("你好", RelayController.textOf(env))
    }

    @Test
    fun groupTextIsABubble() {
        // 群消息和私聊消息共用一个 type，只有 kind 分开。之前 textOf 只认
        // TEXT，group-text 于是被当成"不是气泡"丢掉 —— 而协议层早就放行了。
        assertEquals(
            "群里说的话也得显示",
            RelayController.textOf(
                envelope(
                    MsgTypes.MSG,
                    buildJsonObject {
                        put("kind", JsonPrimitive(MsgKinds.GROUP_TEXT))
                        put("text", JsonPrimitive("群里说的话也得显示"))
                    },
                ),
            ),
        )
    }

    @Test
    fun nonTextKindsAreNotBubbles() {
        for (kind in listOf(MsgKinds.RECALL, MsgKinds.NUDGE, MsgKinds.PK)) {
            val env = envelope(
                MsgTypes.MSG,
                buildJsonObject {
                    put("kind", JsonPrimitive(kind))
                    put("text", JsonPrimitive("这段不该显示"))
                },
            )
            assertNull(RelayController.textOf(env), "kind=$kind 本期不显示，必须返回 null")
        }
    }

    @Test
    fun malformedPayloadsYieldNullInsteadOfThrowing() {
        assertNull(RelayController.textOf(envelope(MsgTypes.MSG, buildJsonObject { })), "没有 text 字段")
        assertNull(
            RelayController.textOf(
                envelope(MsgTypes.MSG, buildJsonObject { put("text", JsonPrimitive(42)) }),
            ),
            "text 不是字符串",
        )
        assertNull(
            RelayController.textOf(
                envelope(MsgTypes.MSG, buildJsonObject { put("text", JsonNull) }),
            ),
            "text 是 null",
        )
        // 载荷不是对象（协议上不该发生，但线上什么都可能来）时不能崩。
        assertNull(RelayController.textOf(Envelope(v = 1, type = MsgTypes.MSG, id = newId(), from = "x", ts = 0L, payload = JsonPrimitive("裸字符串"))))
    }

    // ---- mentionsSelf ----

    private val self = "11111111-1111-4111-8111-111111111111"

    private fun groupWithMentions(vararg ids: String) = envelope(
        MsgTypes.MSG,
        buildJsonObject {
            put("kind", JsonPrimitive(MsgKinds.GROUP_TEXT))
            put("text", JsonPrimitive("今晚的评审改到八点"))
            put("mentions", JsonArray(ids.map { JsonPrimitive(it) }))
        },
    )

    @Test
    fun mentionsSelfFindsMeInTheGroupMentionList() {
        assertTrue(RelayController.mentionsSelf(groupWithMentions(self), self))
        assertTrue(
            RelayController.mentionsSelf(groupWithMentions("someone-else", self), self),
            "名单里有别人也有我，一样算点到我",
        )
    }

    @Test
    fun mentionsSelfIsFalseWhenSomebodyElseIsNamed() {
        assertFalse(RelayController.mentionsSelf(groupWithMentions("someone-else"), self))
    }

    /**
     * 载荷里没有 `mentions` 字段 = **没有任何人被 @**，不是"不知道"。
     *
     * 老端本来就不带这个字段；在"仅被 @ 时提醒"下，一条没点名的群消息就应该静默进列表。
     * 反过来把它当成"可能提到了我"而响铃，那个开关就等于没接。
     */
    @Test
    fun aGroupMessageWithoutMentionsNamesNobody() {
        val env = envelope(
            MsgTypes.MSG,
            buildJsonObject {
                put("kind", JsonPrimitive(MsgKinds.GROUP_TEXT))
                put("text", JsonPrimitive("群里随便聊两句"))
            },
        )
        assertFalse(RelayController.mentionsSelf(env, self))
    }

    /**
     * 群文本是 `msg` 的一个 kind，**不是** `MsgTypes.GROUP` 那种报文。
     *
     * 这条测试是补一个真机上才暴露的缺陷：判据曾经写成 `envelope.type == MsgTypes.GROUP`，
     * 而桌面端发来的群消息 type 是 `msg`，于是判据恒为 false —— 「仅在有人 @ 我时提醒」
     * 对群消息完全不起作用，会话列表也永远不标「有人@我」。
     * 上面几个用例当时全绿，因为夹具自己就把 type 写成了 `GROUP`：夹具与判据一起错。
     */
    @Test
    fun groupTextIsRecognisedByKindNotByType() {
        assertTrue(
            RelayController.isGroupText(
                envelope(
                    MsgTypes.MSG,
                    buildJsonObject {
                        put("kind", JsonPrimitive(MsgKinds.GROUP_TEXT))
                        put("text", JsonPrimitive("群里说的话"))
                    },
                ),
            ),
        )
        // 群**元数据**报文（`group{op:"info"}`）不是群文本：它没有 text，也不该带 mentions。
        assertFalse(
            RelayController.isGroupText(
                envelope(MsgTypes.GROUP, buildJsonObject { put("op", JsonPrimitive("info")) }),
            ),
            "group 是元数据报文，不能当群消息",
        )
        assertFalse(
            RelayController.isGroupText(
                envelope(
                    MsgTypes.MSG,
                    buildJsonObject {
                        put("kind", JsonPrimitive(MsgKinds.TEXT))
                        put("text", JsonPrimitive("私聊"))
                    },
                ),
            ),
            "私聊不是群文本",
        )
        // kind 省略 = text（协议允许），不是群文本。
        assertFalse(
            RelayController.isGroupText(
                envelope(MsgTypes.MSG, buildJsonObject { put("text", JsonPrimitive("你好")) }),
            ),
        )
    }

    /** `"@all"` 是协议保留值：任何群成员都能点所有人，收端要当"点到了我"。 */
    @Test
    fun mentionAllCountsAsMentioningMe() {
        assertTrue(RelayController.mentionsSelf(groupWithMentions(RelayController.MENTION_ALL), self))
        assertTrue(
            RelayController.mentionsSelf(groupWithMentions("someone-else", RelayController.MENTION_ALL), self),
            "名单里还有别人时也一样",
        )
        // 名字里含 @all 但不是完整 token 的，不算（协议要求整 token 匹配）。
        assertFalse(RelayController.mentionsSelf(groupWithMentions("@all-others"), self))
    }

    /** 一对一没有 @ 这个概念：即使载荷里凑巧带了 mentions，也不算"点到我"。 */
    @Test
    fun mentionsNeverApplyToOneToOneMessages() {
        val env = envelope(
            MsgTypes.MSG,
            buildJsonObject {
                put("kind", JsonPrimitive(MsgKinds.TEXT))
                put("text", JsonPrimitive("在吗"))
                put("mentions", JsonArray(listOf(JsonPrimitive(self))))
            },
        )
        assertFalse(RelayController.mentionsSelf(env, self))
    }

    /** 畸形载荷不能崩，也不能把"读不出来"当成"提到了我"。 */
    @Test
    fun malformedMentionsAreTreatedAsNobody() {
        assertFalse(
            RelayController.mentionsSelf(
                envelope(MsgTypes.GROUP, buildJsonObject { put("mentions", JsonPrimitive(self)) }),
                self,
            ),
            "mentions 不是数组",
        )
        assertFalse(
            RelayController.mentionsSelf(
                envelope(MsgTypes.GROUP, buildJsonObject { put("mentions", JsonArray(listOf(JsonPrimitive(7)))) }),
                self,
            ),
            "mentions 里不是字符串",
        )
        assertFalse(
            RelayController.mentionsSelf(
                Envelope(
                    v = 1,
                    type = MsgTypes.GROUP,
                    id = newId(),
                    from = "x",
                    ts = 0L,
                    payload = JsonPrimitive("裸字符串"),
                ),
                self,
            ),
            "载荷不是对象",
        )
    }

    // ---- isPlausibleHost ----

    @Test
    fun plausibleHostsAreAccepted() {
        for (host in listOf("192.168.1.5", "10.0.0.9", "mac.local", "书房的电脑", "fe80::1", "a")) {
            assertTrue(RelayController.isPlausibleHost(host), "应接受：$host")
        }
    }

    @Test
    fun emptyWhitespaceAndSymbolOnlyHostsAreRejected() {
        for (host in listOf("", "   ", "a b", "192.168.1.5 ", "..", "---", ":", "/")) {
            assertFalse(RelayController.isPlausibleHost(host), "应拒绝：'$host'")
        }
        assertFalse(RelayController.isPlausibleHost("a".repeat(254)), "超过 253 字符应拒绝")
        assertTrue(RelayController.isPlausibleHost("a".repeat(253)), "253 字符是边界，应接受")
    }

    // ---- splitHostPort ----

    /**
     * 输入框的提示写着「支持 IPv4 或 ip:port」，所以 `ip:port` 必须真的拆开。
     *
     * 真机取证（2026-10-07 Pixel 6）：`255.255.255.255:17899` 曾被整串当成**主机名**存进
     * `settings.json`，端口字段缺失、退化成 17878，之后每次广播都解析失败且没有任何报错 ——
     * 用户看到的是一个永远连不上的节点。
     */
    @Test
    fun aHostWithAnInlinePortIsSplit() {
        assertEquals("192.168.1.5" to "17899", RelayController.splitHostPort("192.168.1.5:17899", ""))
        assertEquals("192.168.1.5" to "17899", RelayController.splitHostPort("  192.168.1.5:17899  ", "  "))
        assertEquals("192.168.1.5" to "17899", RelayController.splitHostPort("192.168.1.5:17899", "17899"))
    }

    @Test
    fun aHostWithoutAPortKeepsThePortField() {
        assertEquals("192.168.1.5" to "", RelayController.splitHostPort("192.168.1.5", ""))
        assertEquals("192.168.1.5" to "17878", RelayController.splitHostPort("192.168.1.5", "17878"))
    }

    /**
     * IPv6 字面量里冒号很多，不能当 `host:port` 拆 —— `isPlausibleHost` 明确接受 `fe80::1`，
     * 拆了就会把最后一段当成端口。
     */
    @Test
    fun anIpv6LiteralIsNotSplit() {
        assertEquals("fe80::1" to "", RelayController.splitHostPort("fe80::1", ""))
        assertEquals("fe80::1" to "17878", RelayController.splitHostPort("fe80::1", "17878"))
    }

    /** 两边都写了端口是歧义，交给 UI 报错，不替用户猜一个。 */
    @Test
    fun twoPortsAreRefusedRatherThanGuessed() {
        assertNull(RelayController.splitHostPort("192.168.1.5:17899", "17878"))
    }

    @Test
    fun aMalformedInlinePortIsRefused() {
        for (bad in listOf("192.168.1.5:abc", "192.168.1.5:0", "192.168.1.5:65536", "192.168.1.5:", "192.168.1.5:-1")) {
            assertNull(RelayController.splitHostPort(bad, ""), "应拒绝：'$bad'")
        }
    }

    // ---- bindFailureMessage ----

    @Test
    fun bindFailureCarriesPortAndTheUnderlyingReason() {
        val message = RelayController.bindFailureMessage(17_878, IllegalStateException("Address already in use"))
        val expected = BannerMessage.BindFailed(port = 17_878, reason = "Address already in use")
        assertEquals(expected, message)
    }

    /**
     * 异常没有 message 时，`reason` 是 **null**，不是异常类名。
     *
     * 这条以前断言的是 `reason = "SilentError"`（`error::class.simpleName` 兜底）——
     * 那不是在描述需求，是在把当时的实现抄了一遍：把一个类名摆到横幅上，对用户既不是
     * 原因也不是出路。现在交给 UI 层取 `error_unknown`，所以这里断言"没编原因"。
     */
    @Test
    fun bindFailureLeavesTheReasonNullWhenTheExceptionIsSilent() {
        val message = RelayController.bindFailureMessage(17_879, SilentError())
        assertEquals(BannerMessage.BindFailed(port = 17_879, reason = null), message)
    }

    /** 空串和空白串同样是"问不出原因"：横幅上不能出现一行空白。 */
    @Test
    fun bindFailureTreatsBlankMessagesAsNoReason() {
        assertEquals(
            BannerMessage.BindFailed(port = 17_880, reason = null),
            RelayController.bindFailureMessage(17_880, IllegalStateException("   ")),
        )
    }
}

/** 一个不携带 message 的异常：验证兜底是"没拿到原因"而不是类名。 */
private class SilentError : Throwable()
