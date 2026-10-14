// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.FilePurpose
import app.relay.core.protocol.MsgKinds
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.makeEnvelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.relay.app.model.AgentEventUi
import app.relay.app.model.FileCardUi
import app.relay.app.model.MessageUi
import app.relay.app.model.NoticeKind
import app.relay.app.model.TransferState

/**
 * 撤回与戳一碰的纯规则。
 *
 * 这些数字（5 分钟窗口、60 秒 2 次、15 秒间隔）在协议里是**收发两端共用**的约束，
 * 所以它们值得被钉死在单测里 —— 改错一个数字，界面不会报错，只会悄悄做错事。
 */
class ChatInteractionsTest {

    // ---------- 撤回窗口（发送侧） ----------

    @Test
    fun ourOwnFreshTextCanBeRecalled() {
        assertTrue(canRecall(message(at = NOW - 1_000), NOW))
    }

    @Test
    fun theFiveMinuteWindowIsInclusiveAtItsBoundary() {
        assertTrue(canRecall(message(at = NOW - RECALL_WINDOW_MS), NOW))
        assertFalse(canRecall(message(at = NOW - RECALL_WINDOW_MS - 1), NOW))
    }

    @Test
    fun whatThePeerSaidIsNotOursToRecall() {
        assertFalse(canRecall(message(mine = false), NOW))
    }

    @Test
    fun ourOwnFileWithAProtocolIdCanBeRecalled() {
        assertTrue(canRecall(message(text = "", file = fileCard(msgId = "offer-1")), NOW))
    }

    @Test
    fun aFileWithoutAProtocolIdHasNoRecallEntry() {
        // offer 没带 `msgId` 时对端认不出撤回的是哪条消息：按下去什么都不会发生的按钮
        // 比没有按钮更糟，所以入口在这里就关掉（见 canRecall 的 KDoc）。
        assertFalse(canRecall(message(text = "", file = fileCard()), NOW))
        assertNull(recallTargetIdOf(message(text = "", file = fileCard())))
    }

    /**
     * 媒体消息报的是 **offer 的 `msgId`**，不是本地消息序号。
     *
     * 协议 §3 要求收发两端用同一个 id 把这条消息入库；本地序号（`local-N-ts`）
     * 两端各不相同，报出去对端匹配不到任何东西 —— 撤回会静默失败，用户以为撤掉了。
     */
    @Test
    fun aFileRecallReportsTheOfferMsgIdNotTheLocalMessageId() {
        val card = fileCard(msgId = "offer-1")
        assertEquals(
            "offer-1",
            recallTargetIdOf(message(id = "local-7-1700000000000", text = "", file = card)),
        )
        assertEquals("m-1", recallTargetIdOf(message(id = "m-1")), "文本消息报信封 id")
    }

    @Test
    fun theFiveMinuteWindowAppliesToFilesToo() {
        val card = fileCard(msgId = "offer-1")
        assertFalse(
            canRecall(
                message(text = "", at = NOW - RECALL_WINDOW_MS - 1, file = card),
                NOW,
            ),
        )
    }

    @Test
    fun aSystemNoticeLineIsNotAMessage() {
        assertFalse(canRecall(message(text = "", notice = NoticeKind.RecalledByPeer), NOW))
    }

    @Test
    fun blankTextIsNothingToRecall() {
        assertFalse(canRecall(message(text = "   "), NOW))
    }

    @Test
    fun aTimestampFromTheFutureIsNotInsideAnyWindow() {
        // 两端时钟有偏差时，`now - at` 会是负数。那是"还没发生"，不是"刚发生"。
        assertFalse(canRecall(message(at = NOW + 1_000), NOW))
    }

    // ---------- 媒体撤回（接收侧） ----------

    @Test
    fun anImageIsRecallableEvenAfterItLanded() {
        // 协议：图片可在窗口内撤回并隐藏（已经落盘的图片文件不删，只隐藏消息）。
        assertTrue(acceptsMediaRecall(FilePurpose.IMAGE, TransferState.Completed))
        assertTrue(acceptsMediaRecall(FilePurpose.IMAGE, TransferState.Active))
    }

    @Test
    fun anInFlightFileIsRecallable() {
        assertTrue(acceptsMediaRecall("file", TransferState.Active))
        assertTrue(acceptsMediaRecall("file", TransferState.Offered))
    }

    @Test
    fun aSavedFileIgnoresTheLateRecall() {
        assertFalse(acceptsMediaRecall("file", TransferState.Completed))
    }

    @Test
    fun anOfferWithoutAPurposeIsTreatedAsAPlainFile() {
        assertFalse(acceptsMediaRecall(null, TransferState.Completed))
        assertTrue(acceptsMediaRecall(null, TransferState.Active))
    }

    // ---------- 戳一碰限流 ----------

    @Test
    fun twoNudgesFifteenSecondsApartAreAllowed() {
        val throttle = NudgeThrottle()
        assertTrue(throttle.allow(PEER, NOW))
        assertTrue(throttle.allow(PEER, NOW + 15_000))
        assertFalse(throttle.allow(PEER, NOW + 30_000))
    }

    @Test
    fun aNudgeTooSoonAfterTheLastOneIsRefused() {
        val throttle = NudgeThrottle()
        assertTrue(throttle.allow(PEER, NOW))
        assertFalse(throttle.allow(PEER, NOW + 14_999))
    }

    @Test
    fun aRefusalDoesNotSpendTheBudget() {
        val throttle = NudgeThrottle()
        assertTrue(throttle.allow(PEER, NOW))
        assertFalse(throttle.allow(PEER, NOW + 1_000))
        assertTrue(throttle.allow(PEER, NOW + 15_000))
    }

    @Test
    fun theWindowSlidesSoTheNextNudgeLandsLater() {
        val throttle = NudgeThrottle()
        assertTrue(throttle.allow(PEER, NOW))
        assertTrue(throttle.allow(PEER, NOW + 15_000))
        // 第一次已经滑出 60 秒窗口，于是第三次可以发。
        assertTrue(throttle.allow(PEER, NOW + 60_000))
    }

    @Test
    fun eachPeerHasItsOwnBudget() {
        val throttle = NudgeThrottle()
        assertTrue(throttle.allow("peer-a", NOW))
        assertTrue(throttle.allow("peer-b", NOW))
        assertTrue(throttle.allow("peer-a", NOW + 15_000))
        assertTrue(throttle.allow("peer-b", NOW + 15_000))
    }

    // ---------- 入站撤回指令的解析 ----------

    @Test
    fun aRecallCarriesTheTargetItWantsHidden() {
        assertEquals("m-42", recallTargetOf(recallEnvelope(JsonPrimitive("m-42"))))
    }

    @Test
    fun anOrdinaryMessageIsNotARecall() {
        val envelope = makeEnvelope(
            type = MsgTypes.MSG,
            from = PEER,
            payload = JsonObject(
                mapOf("kind" to JsonPrimitive(MsgKinds.TEXT), "targetId" to JsonPrimitive("m-42")),
            ),
            id = "e1",
            ts = NOW,
        )
        assertNull(recallTargetOf(envelope))
    }

    @Test
    fun aNumericTargetIsNotAMessageId() {
        // `{"targetId":123}` 在宽松解析下会变成字符串 "123"，那就可能命中一条碰巧叫 123 的消息。
        assertNull(recallTargetOf(recallEnvelope(JsonPrimitive(123))))
    }

    @Test
    fun aRecallWithoutATargetIsDropped() {
        val envelope = makeEnvelope(
            type = MsgTypes.MSG,
            from = PEER,
            payload = JsonObject(mapOf("kind" to JsonPrimitive(MsgKinds.RECALL))),
            id = "e1",
            ts = NOW,
        )
        assertNull(recallTargetOf(envelope))
    }

    @Test
    fun aPayloadThatIsNotAnObjectIsNotARecall() {
        val envelope = makeEnvelope(
            type = MsgTypes.MSG,
            from = PEER,
            payload = JsonPrimitive(MsgKinds.RECALL),
            id = "e1",
            ts = NOW,
        )
        assertNull(recallTargetOf(envelope))
    }

    @Test
    fun theKindIsReadableOnItsOwn() {
        val nudge = makeEnvelope(
            type = MsgTypes.MSG,
            from = PEER,
            payload = JsonObject(mapOf("kind" to JsonPrimitive(MsgKinds.NUDGE))),
            id = "e1",
            ts = NOW,
        )
        assertEquals(MsgKinds.NUDGE, msgKindOf(nudge))
        assertEquals(MsgKinds.RECALL, msgKindOf(recallEnvelope(JsonPrimitive("m-42"))))
    }

    // ---------- agent 会话与事件（PPE-1 §6.1 / §6.2） ----------

    @Test
    fun aSessionFrameKeepsWhatTheNextFrameOmits() {
        val first = agentSessionFrom(
            payload(
                "sessionId" to JsonPrimitive("s-1"),
                "alias" to JsonPrimitive("refactor runner"),
                "agentKind" to JsonPrimitive("codex"),
                "state" to JsonPrimitive("busy"),
                "title" to JsonPrimitive("跑测试"),
                "startedAt" to JsonPrimitive(1_700_000_000_000L),
            ),
            peerId = PEER,
            at = NOW,
            prev = null,
        )!!
        assertEquals("refactor runner", first.alias)
        assertEquals("busy", first.state)
        assertTrue(first.announced)

        // 只报状态变更的一帧：协议里其余字段都是可选的，不该把 alias/title 抹掉。
        val second = agentSessionFrom(
            payload("sessionId" to JsonPrimitive("s-1"), "state" to JsonPrimitive("done")),
            peerId = PEER,
            at = NOW + 1_000,
            prev = first,
        )!!
        assertEquals("done", second.state)
        assertEquals("refactor runner", second.alias)
        assertEquals("跑测试", second.title)
        assertEquals(1_700_000_000_000L, second.startedAt)
        assertEquals(NOW + 1_000, second.updatedAt)
    }

    /**
     * 线上尾键 `updatedAt`（宿主侧活动时刻）与模型里的 `updatedAt`（本端收帧时刻）是**两件事**。
     *
     * 这条测试的存在理由就是它俩同名：合并纪律若被抄成"缺键就写 at"，排序会静默退回旧行为，
     * 而旧行为恰好也是"看起来在工作"的那一个（一页 20 条全落在同一毫秒 ⇒ 退化成到达序）。
     */
    @Test
    fun theHostActivityTimeIsKeptSeparateFromTheTimeWeHeardAboutIt() {
        val first = agentSessionFrom(
            payload(
                "sessionId" to JsonPrimitive("s-1"),
                "alias" to JsonPrimitive("refactor runner"),
                // 宿主活动时刻刻意取得与本端收帧时刻不同（也是历史更早）：
                // 两者相等时，任何把 A 写成 B 的缺陷都会通过。
                "updatedAt" to JsonPrimitive(1_600_000_000_000L),
            ),
            peerId = PEER,
            at = NOW,
            prev = null,
        )!!
        assertEquals(1_600_000_000_000L, first.hostUpdatedAt)
        assertEquals(NOW, first.updatedAt, "本端收帧时刻不许被尾键顶替")
        assertEquals(1_600_000_000_000L, first.sortActivityAt, "排序该用宿主活动时刻")

        // 只报状态变更的一帧（不带该尾键）：上一帧的活动时刻必须留着 ——
        // 它回答的是"这条会话到目前最晚动过是什么时候"，是一个**累计量**，
        // 每来一条不带它的帧就清空的话，恰恰是历史分页之后那批帧会做的事。
        val second = agentSessionFrom(
            payload("sessionId" to JsonPrimitive("s-1"), "state" to JsonPrimitive("done")),
            peerId = PEER,
            at = NOW + 1_000,
            prev = first,
        )!!
        assertEquals(1_600_000_000_000L, second.hostUpdatedAt)
        assertEquals(NOW + 1_000, second.updatedAt)

        // 老宿主（从不发这个尾键）：排序退回本端时刻，退化成"我能拿到的最好信息"而不是 0。
        val legacy = agentSessionFrom(
            payload("sessionId" to JsonPrimitive("s-legacy")),
            peerId = PEER,
            at = NOW,
            prev = null,
        )!!
        assertNull(legacy.hostUpdatedAt)
        assertEquals(NOW, legacy.sortActivityAt, "没有宿主时刻就退回本端时刻（时间的下界），不是 0")
    }

    /**
     * 可见性两尾键 `origin` / `blank` 的解码与合并纪律。
     *
     * 合并纪律在这里**特别要紧**：可见性目前只由分页那条路送，而实时宣告链路不会发这两键。
     * 若把"这一帧没带"当成"可见"，一条只报 state=done 的收尾帧就会把已经判定的子代理
     * 会话重新放回界面 —— 而那正是用户看到的那批行。
     */
    @Test
    fun theVisibilityKeysAreDecodedAndSurviveAFrameThatOmitsThem() {
        val first = agentSessionFrom(
            payload(
                "sessionId" to JsonPrimitive("s-sub"),
                "origin" to JsonPrimitive("subagent"),
                "blank" to JsonPrimitive(false),
            ),
            peerId = PEER,
            at = NOW,
            prev = null,
        )!!
        assertEquals("subagent", first.hostOrigin)
        assertEquals(false, first.hostBlank, "blank=false 与 null 是两回事")

        // 收尾帧只带 state：两键必须从 prev 继承，不能被清成 null。
        val closing = agentSessionFrom(
            payload("sessionId" to JsonPrimitive("s-sub"), "state" to JsonPrimitive("done")),
            peerId = PEER,
            at = NOW + 1,
            prev = first,
        )!!
        assertEquals("subagent", closing.hostOrigin, "收尾帧把已判定的 origin 抹没了")
        assertEquals(false, closing.hostBlank)

        // 字符串 "true" 是坏载荷：Codec 的 boolOf 会在入口整帧拒收（validateAgentSession
        // 返回 false），所以线上根本走不到这里；真走到这里也必须**不认**它 ——
        // 认了就等于给一条字节级坏帧开了个语义正确的后门。
        // 结果与 updatedAt/startedAt 同一条既有纪律：键在场但取不出值 → 置空（不是继承 prev）。
        val broken = agentSessionFrom(
            payload("sessionId" to JsonPrimitive("s-sub"), "blank" to JsonPrimitive("true")),
            peerId = PEER,
            at = NOW + 2,
            prev = first,
        )!!
        assertNull(broken.hostBlank, "字符串 \"true\" 不该被当成布尔真，也不该冒充确认可见")

        // 老宿主：两个键都不带 ⇒ null（不知道），不是 false。
        val legacy = agentSessionFrom(
            payload("sessionId" to JsonPrimitive("s-legacy")),
            peerId = PEER,
            at = NOW,
            prev = null,
        )!!
        assertNull(legacy.hostOrigin)
        assertNull(legacy.hostBlank, "没听说不等于「确认可见」，更不等于「藏起来」")
    }

    @Test
    fun anExplicitEmptyStringClearsAFieldInsteadOfKeepingIt() {
        val first = agentSessionFrom(
            payload("sessionId" to JsonPrimitive("s-1"), "alias" to JsonPrimitive("runner")),
            peerId = PEER,
            at = NOW,
            prev = null,
        )!!
        val cleared = agentSessionFrom(
            payload("sessionId" to JsonPrimitive("s-1"), "alias" to JsonPrimitive("")),
            peerId = PEER,
            at = NOW,
            prev = first,
        )!!
        assertNull(cleared.alias)
    }

    @Test
    fun aSessionWithoutAStringIdIsRefused() {
        assertNull(agentSessionFrom(payload(), PEER, NOW, null))
        assertNull(agentSessionFrom(payload("sessionId" to JsonPrimitive(123)), PEER, NOW, null))
    }

    @Test
    fun anEventNeedsAllThreeRequiredFields() {
        val ok = agentEventFrom(
            payload(
                "sessionId" to JsonPrimitive("s-1"),
                "cursor" to JsonPrimitive(42L),
                "eventType" to JsonPrimitive("tool.started"),
            ),
            peerId = PEER,
            at = NOW,
        )!!
        assertEquals(42L, ok.cursor)
        assertEquals("tool.started", ok.eventType)

        assertNull(agentEventFrom(payload("cursor" to JsonPrimitive(42L), "eventType" to JsonPrimitive("x")), PEER, NOW))
        assertNull(agentEventFrom(payload("sessionId" to JsonPrimitive("s-1"), "eventType" to JsonPrimitive("x")), PEER, NOW))
        assertNull(agentEventFrom(payload("sessionId" to JsonPrimitive("s-1"), "cursor" to JsonPrimitive(42L)), PEER, NOW))
    }

    @Test
    fun aCursorSentAsAStringIsNotAnInteger() {
        // `JsonPrimitive.content.toLongOrNull()` 会把 "42" 读成 42，而协议里这是坏载荷。
        assertNull(
            agentEventFrom(
                payload(
                    "sessionId" to JsonPrimitive("s-1"),
                    "cursor" to JsonPrimitive("42"),
                    "eventType" to JsonPrimitive("tool.started"),
                ),
                peerId = PEER,
                at = NOW,
            ),
        )
    }

    @Test
    fun anEventCarriesWhenWeHeardItNotItsCursor() {
        val event = agentEventFrom(
            payload(
                "sessionId" to JsonPrimitive("s-1"),
                "cursor" to JsonPrimitive(9_007_199_254_740_991L),
                "eventType" to JsonPrimitive("tool.started"),
            ),
            peerId = PEER,
            at = NOW,
        )!!
        // cursor 是同一会话内的单调游标，不是时间戳：拿它当时间显示会在时钟偏差下说谎。
        assertEquals(NOW, event.at)
        assertEquals(9_007_199_254_740_991L, event.cursor)
    }

    @Test
    fun aRetransmittedCursorIsDroppedInsteadOfShownTwice() {
        val first = event(cursor = 7L)
        val merged = mergeAgentEvent(listOf(first), first.copy(at = NOW + 5_000), max = 10)
        assertNull(merged)
    }

    @Test
    fun eventsAreOrderedByCursorEvenWhenTheyArriveOutOfOrder() {
        val merged = mergeAgentEvent(
            existing = listOf(event(cursor = 30L), event(cursor = 10L)),
            incoming = event(cursor = 20L),
            max = 10,
        )!!
        assertEquals(listOf(10L, 20L, 30L), merged.map { it.cursor })
    }

    @Test
    fun theEventListIsBoundedAndDropsTheOldest() {
        val merged = mergeAgentEvent(
            existing = listOf(event(cursor = 1L), event(cursor = 2L)),
            incoming = event(cursor = 3L),
            max = 2,
        )!!
        assertEquals(listOf(2L, 3L), merged.map { it.cursor })
    }

    @Test
    fun anUnknownEventTypeIsKeptAsIsInsteadOfRefused() {
        // 取值域故意不封闭：新 eventType 必须放行，否则对端会一直重传到入队。
        val event = agentEventFrom(
            payload(
                "sessionId" to JsonPrimitive("s-1"),
                "cursor" to JsonPrimitive(1L),
                "eventType" to JsonPrimitive("tool.waiting_for_review"),
            ),
            peerId = PEER,
            at = NOW,
        )!!
        assertEquals("tool.waiting_for_review", event.eventType)
    }

    // ---------- 构造 ----------

    private fun payload(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(mapOf(*pairs))

    private fun event(cursor: Long) = AgentEventUi(
        sessionId = "s-1",
        peerId = PEER,
        cursor = cursor,
        eventType = "tool.started",
        at = NOW,
    )

    private fun message(
        id: String = "m-1",
        mine: Boolean = true,
        text: String = "在吗",
        at: Long = NOW,
        file: FileCardUi? = null,
        notice: NoticeKind? = null,
    ) = MessageUi(
        id = id,
        peerId = PEER,
        mine = mine,
        text = text,
        at = at,
        file = file,
        notice = notice,
    )

    private fun fileCard(msgId: String? = null) = FileCardUi(
        transferId = "t-1",
        name = "screenshots.zip",
        size = 11_000_000L,
        state = TransferState.Active,
        msgId = msgId,
    )

    private fun recallEnvelope(targetId: JsonElement) = makeEnvelope(
        type = MsgTypes.MSG,
        from = PEER,
        payload = JsonObject(
            mapOf("kind" to JsonPrimitive(MsgKinds.RECALL), "targetId" to targetId),
        ),
        id = "e-1",
        ts = NOW,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PEER = "peer-a"
    }
}
