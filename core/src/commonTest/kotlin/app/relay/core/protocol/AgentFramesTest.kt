// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * PPE-1 P0：`agent-session` / `agent-event` 的编解码行为。
 *
 * 规范里没有对应实现的那些帧，`GoldenVectorTest` 用真实 TS 向量守；这里守的是**我们自己**
 * 定下的三条规矩：
 *  1. §5 字段集合白名单 —— 少一个必填键整帧拒收；多一个键的行为**逐帧切换**：
 *     `agent-session` 自增补 D2 起、`agent-event` 自 task-45（附件元数据）起，都是
 *     「必需子集 + 忽略预算内的未知键」（见 [agentSessionToleratesUnknownKeysWithinBudget] 与
 *     [agentEventToleratesUnknownKeysButKeepsTheAttachmentObjectStrict]）；其余 `agent-*` 仍严格；
 *  2. 取值域**故意留宽容** —— 对端将来多一个 `state` / `eventType` 不该让整帧作废
 *     （拒收会让它一直重传到入队，而 `summary` / `title` 本来就够 UI 显示）；
 *  3. §7 `body` 受**通道**预算约束，不受某个字段上限约束 —— 同一帧走 TCP 收得下、走 UDP 会被拒。
 */
class AgentFramesTest {

    private companion object {
        const val ID = "33333333-3333-4333-8333-333333333333"
        const val PEER = "11111111-1111-4111-8111-111111111111"
        const val TS = 1_700_000_000_000L

        /** 手工拼帧，用来构造 `Codec` 的出站构造器**造不出来**的形状（多余键、错类型）。 */
        fun frame(type: String, payloadJson: String): String =
            """{"v":1,"type":"$type","id":"$ID","from":"$PEER","ts":$TS,"payload":$payloadJson}"""

        fun reject(json: String): String {
            val r = Codec.decode(json)
            return (r as? DecodeResult.Rejected)?.reason ?: fail("期望被拒，实际解码通过：$json")
        }

        fun accept(json: String): DecodeResult.Ok =
            Codec.decode(json) as? DecodeResult.Ok ?: fail("期望解码通过，实际被拒：$json")

        fun payloadOf(ok: DecodeResult.Ok): JsonObject = ok.env.payload as? JsonObject
            ?: fail("payload 应当是 JSON 对象")
    }

    // ------------------------------------------------------------------ 正路

    @Test
    fun sessionRoundTrips() {
        val json = Codec.encode(
            makeEnvelope(
                type = MsgTypes.AGENT_SESSION,
                from = PEER,
                payload = Codec.agentSessionPayload(
                    sessionId = "s-1a97a6f3",
                    alias = "refactor runner",
                    agentKind = "codex",
                    state = "busy",
                    title = "重构 runner",
                    startedAt = TS,
                ),
                id = ID,
                ts = TS,
            ),
        )
        val ok = accept(json)
        assertTrue(ok.known, "agent-session 已经是本端已知类型")
        val payload = payloadOf(ok)
        assertEquals("s-1a97a6f3", (payload["sessionId"] as? JsonPrimitive)?.content)
        assertEquals(TS, Codec.intOf(payload["startedAt"]))
        assertEquals(json, Codec.encode(ok.env), "重新编码必须逐字节相同")
    }

    @Test
    fun eventRoundTrips() {
        val json = Codec.encode(
            makeEnvelope(
                type = MsgTypes.AGENT_EVENT,
                from = PEER,
                payload = Codec.agentEventPayload(
                    sessionId = "s-1a97a6f3",
                    cursor = 42,
                    eventType = "tool.started",
                    summary = "跑测试",
                    ref = "run-7",
                ),
                id = ID,
                ts = TS,
            ),
        )
        val ok = accept(json)
        assertTrue(ok.known)
        assertEquals(42L, Codec.intOf(payloadOf(ok)["cursor"]))
        assertEquals(json, Codec.encode(ok.env))
    }

    @Test
    fun optionalFieldsAreOmittedInsteadOfSentAsNull() {
        // encodeDefaults = false + explicitNulls = false：没给的键在线上**整个不出现**。
        val json = Codec.encode(
            makeEnvelope(
                type = MsgTypes.AGENT_SESSION,
                from = PEER,
                payload = Codec.agentSessionPayload(sessionId = "s-1"),
                id = ID,
                ts = TS,
            ),
        )
        assertEquals(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1"}"""), json)
        assertFalse(json.contains("null"), "可选字段不能以 null 形式出现在线上：$json")
    }

    @Test
    fun cursorIsAnEventStreamCursorNotATimestamp() {
        // §8.2：cursor 是事件流游标，与 since=<ts> 是两套语义。用大整数证明它不按 32 位截断。
        val big = 9_007_199_254_740_991L
        val ok = accept(
            frame(
                MsgTypes.AGENT_EVENT,
                """{"sessionId":"s-1","cursor":$big,"eventType":"tool.failed"}""",
            ),
        )
        assertEquals(big, Codec.intOf(payloadOf(ok)["cursor"]))
    }

    // ------------------------------------------------------- §5 字段白名单

    @Test
    fun missingRequiredFieldsAreRefused() {
        assertEquals(
            "bad-payload:agent-session",
            reject(frame(MsgTypes.AGENT_SESSION, """{"alias":"没有会话 id"}""")),
        )
        assertEquals(
            "bad-payload:agent-event",
            reject(frame(MsgTypes.AGENT_EVENT, """{"sessionId":"s-1","eventType":"tool.started"}""")),
        )
        assertEquals(
            "bad-payload:agent-event",
            reject(frame(MsgTypes.AGENT_EVENT, """{"sessionId":"s-1","cursor":1}""")),
        )
    }

    @Test
    fun agentEventToleratesUnknownKeysButKeepsTheAttachmentObjectStrict() {
        // task-45：agent-event 也按 D2 的规矩切成「必需子集 + 忽略预算内的未知键」——
        // 这正是父稿 §2.3 说的「其余帧各自等自己的加键需求再逐帧切换」，附件元数据就是那个需求。
        // 守三件事：① 未知顶层键被忽略；② 预算仍然管着它；③ attachment 对象**自己**仍严格。
        val withUnknown = accept(
            frame(
                MsgTypes.AGENT_EVENT,
                """{"sessionId":"s-1","cursor":1,"eventType":"tool.started","seq":1}""",
            ),
        )
        assertTrue(withUnknown.known, "未知顶层键应被忽略，而不是整帧拒")

        // 预算：8 个未知键仍收，第 9 个拒（宽容是给扩展的，不是给放大的）。
        val eight = (1..8).joinToString(",") { """"k$it":$it""" }
        assertTrue(
            accept(
                frame(
                    MsgTypes.AGENT_EVENT,
                    """{"sessionId":"s-1","cursor":1,"eventType":"tool.started",$eight}""",
                ),
            ).known,
        )
        val nine = (1..9).joinToString(",") { """"k$it":$it""" }
        assertEquals(
            "bad-payload:agent-event",
            reject(
                frame(
                    MsgTypes.AGENT_EVENT,
                    """{"sessionId":"s-1","cursor":1,"eventType":"tool.started",$nine}""",
                ),
            ),
        )

        // 附件对象自己仍是**严格**白名单：多一个键整帧拒（半张卡片比文本占位更糟）。
        assertEquals(
            "bad-payload:agent-event",
            reject(
                frame(
                    MsgTypes.AGENT_EVENT,
                    """{"sessionId":"s-1","cursor":1,"eventType":"tool.started",""" +
                        """"attachment":{"attachmentId":"a","mediaType":"image/png","x":1}}""",
                ),
            ),
        )

        // 必填项仍然必填：宽容只给扩展键，不给漏字段。
        assertEquals(
            "bad-payload:agent-event",
            reject(frame(MsgTypes.AGENT_EVENT, """{"sessionId":"s-1","eventType":"tool.started"}""")),
        )
    }

    @Test
    fun agentSessionToleratesUnknownKeysWithinBudget() {
        // PPE-1 增补 D2（父稿 §2.3）：agent-session 的白名单语义 = 必需子集 + 忽略未知键。
        // 老节点那份 {"project":"pantry"} 缺 sessionId，仍然整帧拒收：
        assertEquals(
            "bad-payload:agent-session",
            reject(frame(MsgTypes.AGENT_SESSION, """{"project":"pantry"}""")),
        )
        // 有 sessionId + 一个未知键 → 收下，未知键不进校验、不报错。
        val ok = accept(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1","project":"pantry"}"""))
        assertTrue(ok.known)
        assertEquals("pantry", (payloadOf(ok)["project"] as? JsonPrimitive)?.content)
        // 预算内多键（8 个）仍收：
        val eight = (1..8).joinToString(",") { """"k$it":$it""" }
        assertTrue(accept(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1",$eight}""")).known)
        // 第 9 个未知键越预算 → 整帧拒收（宽容是给扩展的，不是给放大的）：
        val nine = (1..9).joinToString(",") { """"k$it":$it""" }
        assertEquals(
            "bad-payload:agent-session",
            reject(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1",$nine}""")),
        )
        // 未知键 UTF-8 总长越预算 → 拒收：
        val fat = "x".repeat(Limits.UNKNOWN_KEY_BYTES_MAX)
        assertEquals(
            "bad-payload:agent-session",
            reject(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1","blob":"$fat"}""")),
        )
    }

    @Test
    fun workspaceKeysRoundTripAndValidate() {
        // PPE-1 增补 D1/D6：agentId / wsId / wsTitle / wsPath 四个可选键。
        val json = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_SESSION, PEER,
                Codec.agentSessionPayload(
                    sessionId = "s-ws-1", agentKind = "DSH",
                    agentId = "dsh-main", wsId = "ws-a1b2c3d4",
                    wsTitle = "teahouse-mobile", wsPath = "/workspace/src/teahouse-mobile",
                ),
                ID, TS,
            ),
        )
        val ok = accept(json)
        assertTrue(ok.known)
        val p = payloadOf(ok)
        assertEquals("dsh-main", (p["agentId"] as? JsonPrimitive)?.content)
        assertEquals("ws-a1b2c3d4", (p["wsId"] as? JsonPrimitive)?.content)
        assertEquals(json, Codec.encode(ok.env), "带工作区键的帧重新编码必须逐字节相同")

        // 越界拒收：agentId 超 Limits.AGENT_ID。
        val longId = "a".repeat(Limits.AGENT_ID + 1)
        assertEquals(
            "bad-payload:agent-session",
            reject(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1","agentId":"$longId"}""")),
        )
        // wsTitle 允许空串（未命名是合法取值，与 alias 同规矩）。
        assertTrue(accept(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1","wsId":"w","wsTitle":""}""")).known)
        // 裸 wsPath（wsId 与 wsTitle 双缺）：codec 不丢帧（忽略该键），"忽略"由消费端落实。
        val bare = accept(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1","wsPath":"/tmp/x"}"""))
        assertTrue(bare.known, "父稿 §2.1：双缺时接收端忽略该键并计数，不是丢弃整帧")
    }

    @Test
    fun activityTimeRoundTripsAndValidates() {
        // 会话排序键：`updatedAt` = 宿主侧的 max(createdAt, lastPromptAt)，
        // 与 DSH 会话列表同一口径。**不复用 startedAt**（那个是「开始时间」，
        // 实时帧里填的是帧发出那一刻），见 `AgentSessionPayload.updatedAt` 的 KDoc。
        val json = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_SESSION, PEER,
                Codec.agentSessionPayload(
                    sessionId = "s-1", startedAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_009_999L,
                ),
                ID, TS,
            ),
        )
        val ok = accept(json)
        assertTrue(ok.known)
        assertEquals(1_700_000_009_999L, Codec.intOf(payloadOf(ok)["updatedAt"]))
        assertEquals(json, Codec.encode(ok.env), "带活动时刻的帧重新编码必须逐字节相同")
        // 大整数不按 32 位截断（epoch 毫秒本来就超 Int）。
        assertTrue(json.contains("1700000009999"), json)
        // 给了这个键却不是数字 = 坏载荷，整帧拒收。**不校验大小**：那是对端时钟，
        // 我们无权判对错，能判的只有「它是不是一个数」。
        assertEquals(
            "bad-payload:agent-session",
            reject(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1","updatedAt":"1700000000000"}""")),
        )
    }

    @Test
    fun wrongTypesAreRefused() {
        // 字符串形式的 cursor、负游标、非对象 payload。
        assertEquals(
            "bad-payload:agent-event",
            reject(frame(MsgTypes.AGENT_EVENT, """{"sessionId":"s-1","cursor":"1","eventType":"tool.started"}""")),
        )
        assertEquals(
            "bad-payload:agent-event",
            reject(frame(MsgTypes.AGENT_EVENT, """{"sessionId":"s-1","cursor":-1,"eventType":"tool.started"}""")),
        )
        assertEquals(
            "bad-payload:agent-session",
            reject(frame(MsgTypes.AGENT_SESSION, """[1,2,3]""")),
        )
    }

    @Test
    fun overlongIdentifiersAreRefused() {
        val tooLong = "s" + "0".repeat(Limits.AGENT_SESSION_ID)
        assertEquals(
            "bad-payload:agent-session",
            reject(frame(MsgTypes.AGENT_SESSION, """{"sessionId":"$tooLong"}""")),
        )
        val longType = "e".repeat(Limits.AGENT_EVENT_TYPE + 1)
        assertEquals(
            "bad-payload:agent-event",
            reject(frame(MsgTypes.AGENT_EVENT, """{"sessionId":"s-1","cursor":1,"eventType":"$longType"}""")),
        )
    }

    // --------------------------------------------------- 取值域故意留宽容

    @Test
    fun unknownStateAndEventTypeValuesAreTolerated() {
        // §5 的严格性针对「字段集合」，不针对取值域：将来多一个 state / eventType
        // 不该让整帧作废（拒收的代价是对端重传到入队）。
        val session = accept(
            frame(MsgTypes.AGENT_SESSION, """{"sessionId":"s-1","state":"reviewing","agentKind":"some-new-agent"}"""),
        )
        assertEquals("reviewing", (payloadOf(session)["state"] as? JsonPrimitive)?.content)

        val event = accept(
            frame(MsgTypes.AGENT_EVENT, """{"sessionId":"s-1","cursor":3,"eventType":"tool.progress"}"""),
        )
        assertTrue(event.known)
    }

    @Test
    fun summaryIsBoundedButMayBeEmpty() {
        assertEquals(
            "bad-payload:agent-event",
            reject(
                frame(
                    MsgTypes.AGENT_EVENT,
                    """{"sessionId":"s-1","cursor":1,"eventType":"e","summary":"${"汉".repeat(Limits.AGENT_SUMMARY + 1)}"}""",
                ),
            ),
        )
        val empty = accept(
            frame(MsgTypes.AGENT_EVENT, """{"sessionId":"s-1","cursor":1,"eventType":"e","summary":""}"""),
        )
        assertEquals(
            "",
            (payloadOf(empty)["summary"] as? JsonPrimitive)?.content,
            "空 summary 是合法取值，不是缺字段",
        )
    }

    // ------------------------------------------------------- §7 通道预算

    @Test
    fun aTypicalEventFitsInOneUdpDatagram() {
        val json = Codec.encode(
            makeEnvelope(
                type = MsgTypes.AGENT_EVENT,
                from = PEER,
                payload = Codec.agentEventPayload(
                    sessionId = "s-1a97a6f3",
                    cursor = 7,
                    eventType = "tool.started",
                    summary = "跑测试",
                ),
                id = ID,
                ts = TS,
            ),
        )
        val bytes = json.encodeToByteArray().size
        assertTrue(bytes <= Protocol.TEXT_UDP_LIMIT, "典型事件应当塞进一个 UDP 包，实际 $bytes B")
    }

    @Test
    fun aMaxSummaryEventMustGoOverTcp() {
        // §11 的 budget 用例必须用最坏样本：最长 sessionId + 最长 summary。
        val json = Codec.encode(
            makeEnvelope(
                type = MsgTypes.AGENT_EVENT,
                from = PEER,
                payload = Codec.agentEventPayload(
                    sessionId = "s-" + "0".repeat(Limits.AGENT_SESSION_ID - 2),
                    cursor = 9_007_199_254_740_991L,
                    eventType = "session.started",
                    summary = "汉".repeat(Limits.AGENT_SUMMARY),
                ),
                id = ID,
                ts = TS,
            ),
        )
        val bytes = json.encodeToByteArray().size
        assertTrue(bytes > Protocol.TEXT_UDP_LIMIT, "最坏样本必须超过 UDP 预算，实际 $bytes B")
        assertTrue(bytes <= Protocol.TEXT_TCP_LIMIT, "最坏样本必须落在 TCP 预算内，实际 $bytes B")
    }

    @Test
    fun bodyIsBoundedByTheChannelNotByAFieldLimit() {
        // §6.2：body 超预算必须改走 file-msg。这里钉的是「同一帧 UDP 拒、TCP 收」——
        // 上限来自通道（800 / 4096 字节），不是某个字段常量。
        val body = "a".repeat(900)
        val json = frame(
            MsgTypes.AGENT_EVENT,
            """{"sessionId":"s-1","cursor":1,"eventType":"assistant.text","body":"$body"}""",
        )
        assertEquals("bad-payload:agent-event", reject(json))
        assertTrue(
            Codec.decodeTcp(json) is DecodeResult.Ok,
            "900 字节的 body 在 TCP 通道（4096）里应当收得下",
        )
    }

    @Test
    fun whatWeEmitIsWhatWeAccept() {
        // 出站构造器能造出的每一种形状，自己的入站校验都必须收下（否则是我们自己在打架）。
        val session = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_SESSION, PEER,
                Codec.agentSessionPayload(
                    sessionId = "s-1", alias = "", agentKind = "claude",
                    state = "done", title = "", startedAt = 1L,
                ),
                ID, TS,
            ),
        )
        assertTrue(accept(session).known)

        val event = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_EVENT, PEER,
                Codec.agentEventPayload(
                    sessionId = "s-1", cursor = 0, eventType = "session.started",
                    summary = "", body = "", ref = "r-1",
                ),
                ID, TS,
            ),
        )
        assertTrue(accept(event).known)
    }

    @Test
    fun workspaceAndGrantFramesRoundTripAndValidate() {
        // PPE-1 增补 D3：agent-workspace（cap aws1，尽力而为、不在 RELIABLE）。
        val ws = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_WORKSPACE, PEER,
                Codec.agentWorkspacePayload(
                    wsId = "ws-a1b2c3d4", wsTitle = "teahouse-mobile",
                    wsPath = "/workspace/src/teahouse-mobile",
                    agentId = "dsh-cli", agentKind = "DSH",
                    online = true, sessionCount = 2, at = TS, order = 3,
                ),
                ID, TS,
            ),
        )
        val wsOk = accept(ws)
        assertTrue(wsOk.known)
        assertEquals(ws, Codec.encode(wsOk.env))
        assertTrue(ws.contains("\"online\":true"), "布尔按 JSON 字面量上线")
        assertTrue(accept(ws).env.payload != null)

        // 形状护栏：online 必须是布尔字面量（宿主自报的事实，形状错了就是假数据）。
        assertEquals(
            "bad-payload:agent-workspace",
            reject(frame(MsgTypes.AGENT_WORKSPACE, """{"wsId":"w","online":"true"}""")),
        )
        // 缺必填 wsId 整帧拒收。
        assertEquals(
            "bad-payload:agent-workspace",
            reject(frame(MsgTypes.AGENT_WORKSPACE, """{"wsTitle":"没有 id"}""")),
        )
        // sessionCount 非负。
        assertEquals(
            "bad-payload:agent-workspace",
            reject(frame(MsgTypes.AGENT_WORKSPACE, """{"wsId":"w","sessionCount":-1}""")),
        )
        // order 是注册表持久序（0 起）：负值是假数据，拒帧（与 sessionCount 同规矩）。
        assertEquals(
            "bad-payload:agent-workspace",
            reject(frame(MsgTypes.AGENT_WORKSPACE, """{"wsId":"w","order":-1}""")),
        )
        // 空工作区（只有 wsId 一档）是合法形状。
        assertTrue(accept(frame(MsgTypes.AGENT_WORKSPACE, """{"wsId":"w"}""")).known)

        // PPE-1 增补 D7：三张 grant 帧（cap agr1），state/scope 是封闭取值。
        val req = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_GRANT_REQUEST, PEER,
                Codec.agentGrantRequestPayload(agentKey = "dsh-main", reason = "想在手机上看 DSH 会话", at = TS),
                ID, TS,
            ),
        )
        assertTrue(accept(req).known)
        assertEquals(req, Codec.encode(accept(req).env))

        val grant = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_GRANT, PEER,
                Codec.agentGrantPayload(state = "approved", scope = "agent", agentKey = "dsh-main", at = TS),
                ID, TS,
            ),
        )
        assertTrue(accept(grant).known)
        assertEquals(grant, Codec.encode(accept(grant).env))

        val list = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_GRANT_LIST, PEER,
                Codec.agentGrantListPayload(scope = "all", total = 1, at = TS),
                ID, TS,
            ),
        )
        assertTrue(accept(list).known)
        assertEquals(list, Codec.encode(accept(list).env))

        // 增补：工作区范围（workspace）与访问级别（ro / rw / admin / owner）及委托审批往返
        val wsGrant = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_GRANT, PEER,
                Codec.agentGrantPayload(
                    state = "approved",
                    scope = "workspace",
                    agentKey = "dsh-main",
                    wsId = "ws-relay",
                    access = "admin",
                    isMyDevice = true,
                    delegatedApproval = true,
                    at = TS,
                ),
                ID, TS,
            ),
        )
        assertTrue(accept(wsGrant).known)
        assertEquals(wsGrant, Codec.encode(accept(wsGrant).env))

        // 封闭取值：拼错的 state / scope / access 整帧拒收（与 agent-session 的开放域相反）。
        assertEquals(
            "bad-payload:agent-grant",
            reject(frame(MsgTypes.AGENT_GRANT, """{"state":"granted","scope":"all"}""")),
        )
        assertEquals(
            "bad-payload:agent-grant",
            reject(frame(MsgTypes.AGENT_GRANT, """{"state":"approved","scope":"everything"}""")),
        )
        assertEquals(
            "bad-payload:agent-grant",
            reject(frame(MsgTypes.AGENT_GRANT, """{"state":"approved","scope":"workspace","access":"super-admin"}""")),
        )
        // 撤销 = 同一帧 state=revoked，不另开类型。
        assertTrue(accept(frame(MsgTypes.AGENT_GRANT, """{"state":"revoked","scope":"all"}""")).known)
    }

    @Test
    fun agentP1FramesRoundTripAndValidate() {
        val q = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_QUEUE, PEER,
                Codec.agentQueuePayload("t-1", "s-1", "enqueue", "do refactor", TS),
                ID, TS,
            ),
        )
        val qOk = accept(q)
        assertTrue(qOk.known)
        assertEquals(q, Codec.encode(qOk.env))

        val run = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_RUN, PEER,
                Codec.agentRunPayload("s-1", "r-1", "running", TS, "note"),
                ID, TS,
            ),
        )
        val runOk = accept(run)
        assertTrue(runOk.known)
        assertEquals(run, Codec.encode(runOk.env))

        val app = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_APPROVAL, PEER,
                Codec.agentApprovalPayload("a-1", "s-1", "bash", "execute rm", TS + 60000, "allow,deny"),
                ID, TS,
            ),
        )
        val appOk = accept(app)
        assertTrue(appOk.known)
        assertEquals(app, Codec.encode(appOk.env))

        val cancel = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_CANCEL, PEER,
                Codec.agentCancelPayload("s-1", "r-1", "user stopped"),
                ID, TS,
            ),
        )
        val cancelOk = accept(cancel)
        assertTrue(cancelOk.known)
        assertEquals(cancel, Codec.encode(cancelOk.env))

        val ack = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_ACK, PEER,
                Codec.agentAckPayload("msg-1", "approved", TS, decidedBy = "📱 我的 iPhone 15"),
                ID, TS,
            ),
        )
        val ackOk = accept(ack)
        assertTrue(ackOk.known)
        assertEquals(ack, Codec.encode(ackOk.env))
    }
}
