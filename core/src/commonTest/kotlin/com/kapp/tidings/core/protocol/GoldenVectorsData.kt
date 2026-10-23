// SPDX-License-Identifier: GPL-3.0-only

// [GOLDEN_VECTORS] 由 /tmp/golden-vectors.json 生成（teahouse 0.61.1 真实 codec 产出）。
// 生成方式：在 /tmp/th_up/teahouse-main 跑 gen-golden-vectors.test.ts。
// 不要手改；协议常量变更时重新生成并整体替换。
//
// [OLD_NODE_EXTENSION_VERDICTS] 与 [EXTENSION_VECTORS] 是**本仓维护**的两组：
// 前者的字节来自同一次生成（TS 当时把 `agent-*` 当未知类型，所以结论是「解码通过、known=false」），
// 后者只有在 PPE-1 实现之后才存在，TS 侧生成不出来。改它们时同步改 GoldenVectorTest 的数量断言。
package com.kapp.tidings.core.protocol

internal data class GoldenVector(
    val name: String,
    val note: String,
    val known: Boolean,
    val decodes: Boolean,
    val reason: String?,
    val json: String,
)

internal val GENERATED_FROM: String = """teahouse 0.61.1"""

internal val GOLDEN_VECTORS: List<GoldenVector> = listOf(
    GoldenVector(
        name = "entry",
        note = "握手：完整 profile，无 probeId",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"entry","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"profile":{"nodeId":"22222222-2222-4222-8222-222222222222","nick":"电脑","company":"","dept":"","team":"","avatar":5,"profileRev":3,"host":"MacBook-Pro","platform":"mac","tcpPort":17879,"ver":"0.61.1","caps":["fd1","mrec1"]}}}""",
    ),
    GoldenVector(
        name = "alive",
        note = "应答：完整 profile + 定向 probeId",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"alive","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"profile":{"nodeId":"22222222-2222-4222-8222-222222222222","nick":"电脑","company":"","dept":"","team":"","avatar":5,"profileRev":3,"host":"MacBook-Pro","platform":"mac","tcpPort":17879,"ver":"0.61.1","caps":["fd1","mrec1"]},"probeId":"33333333-3333-4333-8333-333333333333"}}""",
    ),
    GoldenVector(
        name = "exit",
        note = "主动下线",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"exit","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"profile":{"nodeId":"22222222-2222-4222-8222-222222222222","nick":"电脑","company":"","dept":"","team":"","avatar":5,"profileRev":3,"host":"MacBook-Pro","platform":"mac","tcpPort":17879,"ver":"0.61.1","caps":["fd1","mrec1"]}}}""",
    ),
    GoldenVector(
        name = "presence",
        note = "心跳：seq + profileRev",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"presence","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"seq":7,"profileRev":3}}""",
    ),
    GoldenVector(
        name = "msg-text",
        note = "单聊短文本",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"msg","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"kind":"text","text":"hi"}}""",
    ),
    GoldenVector(
        name = "msg-text-resend",
        note = "离线补发：resend=true 且 id/ts 保持原值",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"msg","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"kind":"text","text":"昨晚的构建结果","resend":true}}""",
    ),
    GoldenVector(
        name = "msg-group-text",
        note = "群文本：mentions + replyTo + 严格字段白名单",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"msg","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"kind":"group-text","text":"@你 这个端口被占用了","groupId":"grp-1","groupRev":12,"mentions":["22222222-2222-4222-8222-222222222222"],"replyTo":"33333333-3333-4333-8333-333333333333"}}""",
    ),
    GoldenVector(
        name = "ack",
        note = "可靠帧回执",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"ack","id":"33333333-3333-4333-8333-333333333333","from":"22222222-2222-4222-8222-222222222222","ts":1700000000000,"payload":{"ackFor":"33333333-3333-4333-8333-333333333333"}}""",
    ),
    GoldenVector(
        name = "neg-group-text-extra-field",
        note = "group-text 多一个未知字段 → 整帧丢弃（hasOnlyKeys）",
        known = false,
        decodes = false,
        reason = "bad-payload:msg",
        json = """{"v":1,"type":"msg","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"kind":"group-text","text":"x","groupId":"g","groupRev":1,"type":"extra"}}""",
    ),
    GoldenVector(
        name = "neg-version",
        note = "v !== 1 → version",
        known = false,
        decodes = false,
        reason = "version",
        json = """{"v":2,"type":"msg","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"kind":"text","text":"x"}}""",
    ),
    GoldenVector(
        name = "neg-not-object",
        note = "顶层不是对象 → not-object",
        known = false,
        decodes = false,
        reason = "not-object",
        json = """[1,2,3]""",
    ),
    GoldenVector(
        name = "neg-bad-json",
        note = "JSON 解析失败 → bad-json",
        known = false,
        decodes = false,
        reason = "bad-json",
        json = """{not json""",
    ),
    GoldenVector(
        name = "neg-no-payload",
        note = "payload 缺失 → no-payload",
        known = false,
        decodes = false,
        reason = "no-payload",
        json = """{"v":1,"type":"msg","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000}""",
    ),
    GoldenVector(
        name = "neg-oversize",
        note = "超过 UDP_MAX_INBOUND(4096) → oversize",
        known = false,
        decodes = false,
        reason = "oversize",
        json = """{"v":1,"type":"msg","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"kind":"text","text":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}""",
    ),
)

/**
 * 老节点（TS 0.61.1）对 `agent-*` 的**结论记录**：`known=false`、不抛错、静默忽略（PPE-1 §1）。
 *
 * 它们**不是 parity 向量**：本端实现这两个类型之后，同一批字节的结论必然不同
 * （见 [EXTENSION_VECTORS] 里那两条 `neg-agent-*`）。留着它们的价值是把容忍契约钉成数据 ——
 * 「老节点不认识新类型时不会崩、也不会回错」这句话有出处，而不是一句注释。
 */
internal val OLD_NODE_EXTENSION_VERDICTS: List<GoldenVector> = listOf(
    GoldenVector(
        name = "agent-event-old-node",
        note = "老节点视角：agent-event 解码通过但 known=false → 静默忽略",
        known = false,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-event","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"sessionId":"s-1","seq":1}}""",
    ),
    GoldenVector(
        name = "agent-session-old-node",
        note = "老节点视角：agent-session 解码通过但 known=false（payload 只校验「是对象」）",
        known = false,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-session","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"project":"pantry"}}""",
    ),
)

/**
 * PPE-1 扩展向量：`agent-session` / `agent-event` / `agent-artifact` 实现之后**本仓维护**的结论。
 *
 * 已实现类型（`agent-session` / `agent-event` / `agent-artifact`）同时是出站编码的字节护栏
 * （见 `GoldenVectorTest.typedPayloadsMatchGoldenBytes`）；
 * `agent-unknown-extension` 钉的是「仍未实现的扩展类型照样解码通过、known=false」，
 * 把容忍契约继续守成数据；两条 `neg-agent-*` 钉的是 §5 的**字段白名单** ——
 * 老节点能收下的那些形状，本端实现后必须拒收。
 */
internal val EXTENSION_VECTORS: List<GoldenVector> = listOf(
    GoldenVector(
        name = "agent-session-page",
        note = "PPE-1 增补 D8：设备向宿主要一页会话清单（cap agp1），分页字段齐全",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-session-page","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"wsId":"ws-a1b2c3d4e5f6","offset":0,"limit":20,"at":1700000000000}}""",
    ),
    GoldenVector(
        name = "agent-session",
        note = "PPE-1 §6.1：会话宣告（cap agn1），完整形状",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-session","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"sessionId":"s-1a97a6f3","alias":"refactor runner","agentKind":"codex","state":"busy"}}""",
    ),
    GoldenVector(
        name = "agent-session-activity",
        note = "PPE-1 增补（会话排序 + 可见性）：活动时刻 `updatedAt` 与可见性 `origin`/`blank` 都是 " +
            "**尾部**新键 —— 老接收端靠 tolerantKeys 容忍它、不丢帧；新接收端拿 updatedAt 按 DSH 的 " +
            "活动倒序排序，拿 origin/blank 复刻 DSH 工作区浏览器的 sessionVisible()",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-session","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"sessionId":"s-1a97a6f3","alias":"refactor runner","agentKind":"codex","state":"busy","startedAt":1700000000000,"agentId":"dsh-main","wsId":"ws-a1b2c3d4","wsTitle":"teahouse-mobile","wsPath":"/src/teahouse","updatedAt":1700000000000,"origin":"subagent","blank":false}}""",
    ),
    GoldenVector(
        name = "agent-event",
        note = "PPE-1 §6.2：会话事件（cap agn1），带 cursor 与 summary",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-event","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"sessionId":"s-1a97a6f3","cursor":42,"eventType":"tool.started","summary":"跑测试"}}""",
    ),
    GoldenVector(
        name = "agent-artifact",
        note = "PPE-1 §6.7：产物元数据（cap agn1），完整形状；本端已实现并接收（只收元数据，不自动下载）",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-artifact","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"sessionId":"s-1","artifactId":"a-1","fileId":"f-1","name":"report.md","bytes":2048,"mime":"text/markdown"}}""",
    ),
    GoldenVector(
        name = "agent-unknown-extension",
        note = "PPE-1 容忍契约：本端**未实现**的扩展类型（如未来的 `agent-batch`）照样解码通过、known=false、静默忽略、不崩",
        known = false,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-batch","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"sessionId":"s-1","items":[]}}""",
    ),
    GoldenVector(
        name = "neg-agent-session-bad-shape",
        note = "老节点那份 {\"project\":\"pantry\"} 形状：本端实现后缺 sessionId + 多 project → 整帧拒收",
        known = true,
        decodes = false,
        reason = "bad-payload:agent-session",
        json = """{"v":1,"type":"agent-session","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"project":"pantry"}}""",
    ),
    GoldenVector(
        name = "neg-agent-event-bad-shape",
        note = "老节点那份 {\"sessionId\":\"s-1\",\"seq\":1} 形状：缺 cursor/eventType + 多 seq → 整帧拒收",
        known = true,
        decodes = false,
        reason = "bad-payload:agent-event",
        json = """{"v":1,"type":"agent-event","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"sessionId":"s-1","seq":1}}""",
    ),
    // ── PPE-1 增补 D3 / D7（2026-10-14）：四条出站字节护栏。TS 侧没有这些类型，
    //    向量由本仓维护；键序 = Payloads.kt 声明序，改动会在这里判红。
    GoldenVector(
        name = "agent-workspace",
        note = "增补 D3：工作区宣告（cap aws1），完整形状（尽力而为、不在 RELIABLE）",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-workspace","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"wsId":"ws-a1b2c3d4","wsTitle":"teahouse-mobile","wsPath":"/src/teahouse","agentId":"dsh-cli","agentKind":"DSH","online":true,"sessionCount":2,"at":1700000000000,"order":3}}""",
    ),
    GoldenVector(
        name = "agent-grant-request",
        note = "增补 D7：设备申请看某个 agent（cap agr1）",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-grant-request","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"agentKey":"dsh-main","reason":"想在手机上看","at":1700000000000}}""",
    ),
    GoldenVector(
        name = "agent-grant",
        note = "增补 D7：宿主的授权决定（approved / denied / revoked 三档共用此帧）",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-grant","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"state":"approved","scope":"agent","agentKey":"dsh-main","at":1700000000000}}""",
    ),
    GoldenVector(
        name = "agent-grant-list",
        note = "增补 D7：授权清单条目（一帧一条）",
        known = true,
        decodes = true,
        reason = null,
        json = """{"v":1,"type":"agent-grant-list","id":"33333333-3333-4333-8333-333333333333","from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"scope":"all","total":1,"at":1700000000000}}""",
    ),
)
