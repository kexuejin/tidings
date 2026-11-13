// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.agent.testutil.Await
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.core.session.NodeRuntime
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具面路由（P3）：`GET/POST /v1/agent/tools`。
 *
 * 每条判据对应一个会**静默出错**的形态：
 *
 * 1. 目录读得到，且带 `brief` 与参数形状（agent 先读它才知道能调什么、参数怎么拼）；
 * 2. 不认识的动作名 ⇒ 400 且列出可用名字（否则 agent 只能瞎猜）；
 * 3. 没有档案面（headless 单进程）⇒ 503 如实说缺，不假装能读能写；
 * 4. 目标档案不存在 ⇒ 404，**在问机主之前**就拦掉（问一件注定做不成的事是浪费人的注意力）；
 * 5. `auto` 档 ⇒ 直接写，回执带前后值（员工与团队两条）；
 * 6. `request` 档 ⇒ 真的发一张审批卡出去，机主不答 = **超时即拒绝**，一个字都不写；
 * 7. **只读动作不过授权闸**：`team.roster.read` 在 request 档下也必须当场回数据，
 *    否则「先读后写」的工作流每一步都要人点一次同意。
 *
 * #6 是这一组的重点：它同时钉住「卡真的发了」（桌面收到 `agent-approval` 帧）与
 * 「没答就等于没批」（fail-closed）。
 */
class RosterToolRouteTest {

    private val loopback = "127.0.0.1"
    private lateinit var scope: CoroutineScope
    private lateinit var agent: NodeRuntime
    private lateinit var desktop: NodeRuntime
    private lateinit var plane: ControlPlane
    private lateinit var fake: FakeRosterPort
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val desktopFrames = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
    private val json = Json { ignoreUnknownKeys = true }

    private val toolFile: File = File.createTempFile("roster-tool-approvals-", ".json").apply { delete() }
    private val policyDir: File = kotlin.io.path.createTempDirectory("roster-tool-policy").toFile()

    private class FakeRosterPort(
        var rows: MutableList<RosterEmployeeSnapshot>,
        var teamRows: MutableList<RosterTeamSnapshot> = mutableListOf(),
    ) : RosterWritePort {
        var updates: MutableList<Triple<String?, String, String?>> = mutableListOf()
        var created: MutableList<RosterTeamDraft> = mutableListOf()
        var teamUpdates: MutableList<Pair<String?, String?>> = mutableListOf()

        override fun employees(): List<RosterEmployeeSnapshot> = rows.toList()

        override fun updateEmployee(
            agentKey: String?,
            employeeId: String,
            name: String?,
            duty: String?,
        ): RosterWriteResult {
            val before = rows.firstOrNull { it.employeeId == employeeId }
                ?: return RosterWriteResult.Denied(RosterDenial.UNKNOWN_EMPLOYEE, employeeId)
            updates += Triple(agentKey, employeeId, name)
            val after = before.copy(name = name ?: before.name, duty = duty ?: before.duty)
            rows = rows.map { if (it.employeeId == employeeId) after else it }.toMutableList()
            return RosterWriteResult.Applied(before, after)
        }

        override fun teams(): List<RosterTeamSnapshot> = teamRows.toList()

        override fun createTeam(agentKey: String?, draft: RosterTeamDraft): RosterWriteResult {
            created += draft
            val snapshot = RosterTeamSnapshot(
                teamId = "tm-new",
                name = draft.name,
                leaderAgentId = draft.leaderAgentId,
                members = draft.members,
                groupIds = draft.groupIds,
                rev = 0,
            )
            teamRows = (teamRows + snapshot).toMutableList()
            return RosterWriteResult.TeamApplied(before = null, after = snapshot)
        }

        override fun updateTeam(
            agentKey: String?,
            teamId: String,
            name: String?,
            members: List<RosterMember>?,
        ): RosterWriteResult {
            val before = teamRows.firstOrNull { it.teamId == teamId }
                ?: return RosterWriteResult.Denied(RosterDenial.UNKNOWN_TEAM, teamId)
            teamUpdates += agentKey to name
            val after = before.copy(
                name = name ?: before.name,
                members = members ?: before.members,
                rev = before.rev + 1,
            )
            teamRows = teamRows.map { if (it.teamId == teamId) after else it }.toMutableList()
            return RosterWriteResult.TeamApplied(before, after)
        }
    }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        desktop = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = Profile.mobile(nodeId = newId(), nick = "手机侧", host = "phone", ver = "0.61.1"),
            udpPort = 0,
            bindHost = loopback,
        )
        agent = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = Profile.mobile(nodeId = newId(), nick = "宿主", host = "host", ver = "relay-0.1.0"),
            udpPort = 0,
            bindHost = loopback,
        )
        runBlocking {
            desktop.start(scope)
            agent.start(scope)
        }
        scope.launch {
            desktop.inbound.collect { env -> desktopFrames += Codec.wireJson.parseToJsonElement(Codec.encode(env)).jsonObject }
        }
        fake = FakeRosterPort(
            mutableListOf(
                RosterEmployeeSnapshot(employeeId = "emp-1", agentId = "dsh", name = "前端小王", duty = "coder"),
            ),
            mutableListOf(
                RosterTeamSnapshot(
                    teamId = "tm-1",
                    name = "开发一组",
                    leaderAgentId = "dsh-lead",
                    members = listOf(RosterMember("m-1", "coder", "编码")),
                    groupIds = listOf("g1"),
                    rev = 2,
                ),
            ),
        )
        plane = ControlPlane(
            agent,
            httpPort = 0,
            token = "",
            loadProbe = { null },
            sessions = AgentSessionRegistry(),
            toolApprovals = AgentToolApprovalStore(File(toolFile.absolutePath + "." + System.nanoTime())),
            rosterWritePolicy = RosterWritePolicy(File(policyDir, "roster-write.json")),
            // 600ms：既能测到「超时 = 拒绝」，又不用真等两分钟。
            rosterApprovalTtlMs = 600,
        )
        plane.rosterWriter = fake
        plane.start()
        // 回环上的两个节点不会自己撞见彼此（广播在测试传输上不跑）：显式宣告一次，
        // 与 `AgentChannelGateTest` 同一条做法。审批要「有一台在线且声明 agq1 的设备」。
        runBlocking { desktop.announceTo(loopback, agent.boundPort) }
        plane.observeInbound(scope)
    }

    @AfterTest
    fun tearDown() {
        plane.stop()
        runBlocking {
            desktop.stop()
            agent.stop()
        }
        scope.cancel()
        policyDir.deleteRecursively()
    }

    private fun get(path: String): Pair<Int, JsonObject> {
        val res = client.send(
            HttpRequest.newBuilder(URI("http://$loopback:${plane.boundPort}$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return res.statusCode() to json.parseToJsonElement(res.body()).jsonObject
    }

    private fun post(path: String, body: String): Pair<Int, JsonObject> {
        val res = client.send(
            HttpRequest.newBuilder(URI("http://$loopback:${plane.boundPort}$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return res.statusCode() to json.parseToJsonElement(res.body()).jsonObject
    }

    @Test
    fun theCatalogTellsAnAgentWhatItCanCall() {
        val (code, body) = get("/v1/agent/tools")
        assertEquals(200, code)
        assertEquals("request", body["tier"]?.jsonPrimitive?.contentOrNull, "默认档必须是 request")
        assertEquals("ready", body["writer"]?.jsonPrimitive?.contentOrNull)

        val brief = body["brief"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(RosterTools.TEAM_READ in brief, "目录要自带用法简报（agent 读一次就知道怎么用）：" + brief)

        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, tools.size, "四个动作：employee.update / team.roster.read / team.create / team.update")
        val create = tools.first { it["name"]?.jsonPrimitive?.contentOrNull == RosterTools.TEAM_CREATE }
        assertEquals(true, create["mutates"]?.jsonPrimitive?.contentOrNull!!.toBoolean())
        val members = create["args"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]?.jsonPrimitive?.contentOrNull == "members" }
        assertEquals("member_list", members["shape"]?.jsonPrimitive?.contentOrNull, "数组参数要标形状")

        val read = tools.first { it["name"]?.jsonPrimitive?.contentOrNull == RosterTools.TEAM_READ }
        assertEquals(false, read["mutates"]?.jsonPrimitive?.contentOrNull!!.toBoolean())
    }

    @Test
    fun anUnknownToolIsRejectedWithTheAvailableNames() {
        val (code, body) = post("/v1/agent/tools", """{"tool":"team.delete"}""")
        assertEquals(400, code)
        val message = body["message"]?.jsonPrimitive?.contentOrNull ?: body.toString()
        assertTrue(RosterTools.EMPLOYEE_UPDATE in message, "400 要列出可用动作名：" + message)
        assertTrue(RosterTools.TEAM_CREATE in message, "同上：" + message)
    }

    @Test
    fun withoutAWriterTheRouteSaysSoInsteadOfPretending() {
        plane.rosterWriter = null
        try {
            val (code, body) = post(
                "/v1/agent/tools",
                """{"tool":"employee.update","args":{"employeeId":"emp-1","name":"x"}}""",
            )
            assertEquals(503, code, "没有档案面就该 503，而不是 200 什么都不做")
            assertEquals("no-writer", body["error"]?.jsonPrimitive?.contentOrNull)

            val (readCode, _) = post("/v1/agent/tools", """{"tool":"team.roster.read"}""")
            assertEquals(503, readCode, "读也一样：读不到就说读不到，不返回空表")
        } finally {
            plane.rosterWriter = fake
        }
    }

    @Test
    fun anUnknownEmployeeIs404BeforeAnyApprovalIsRequested() {
        val (code, body) = post(
            "/v1/agent/tools",
            """{"tool":"employee.update","args":{"employeeId":"emp-missing","name":"x"}}""",
        )
        assertEquals(404, code)
        assertTrue(
            (body["message"]?.jsonPrimitive?.contentOrNull ?: "").contains("emp-missing"),
            "要点名是哪一份档案：" + body,
        )
        assertTrue(fake.updates.isEmpty(), "问机主之前就该拦掉")
    }

    @Test
    fun anUnknownTeamIs404BeforeAnyApprovalIsRequested() {
        val (code, body) = post(
            "/v1/agent/tools",
            """{"tool":"team.update","args":{"teamId":"tm-missing","name":"x"}}""",
        )
        assertEquals(404, code)
        assertEquals("unknown-team", body["error"]?.jsonPrimitive?.contentOrNull)
        assertTrue(fake.teamUpdates.isEmpty(), "团队不存在这件事与「机主同不同意」无关")
    }

    @Test
    fun readingTheRosterNeverWaitsForApproval() {
        // request 档（默认）下读也必须当场回：否则「先读后写」每一步都要人点一次同意。
        val (code, body) = post("/v1/agent/tools", """{"tool":"team.roster.read"}""")
        assertEquals(200, code, body.toString())
        assertEquals("ok", body["status"]?.jsonPrimitive?.contentOrNull)
        val teams = body["teams"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, teams.size)
        assertEquals("tm-1", teams.single()["teamId"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            listOf("g1"),
            teams.single()["groupIds"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
            "读出来要带落点（在哪些群），否则 agent 以为队伍已经能接活",
        )
        assertTrue(
            desktopFrames.none { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_APPROVAL },
            "只读动作不该弹卡",
        )
    }

    @Test
    fun readingOneTeamThatDoesNotExistIs404() {
        val (code, body) = post("/v1/agent/tools", """{"tool":"team.roster.read","args":{"teamId":"tm-x"}}""")
        assertEquals(404, code)
        assertEquals("unknown-team", body["error"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun autoTierWritesAndReturnsBeforeAndAfter() {
        assertTrue(plane.rosterWritePolicy.setTier(RosterWritePolicy.Tiers.AUTO))
        val (code, body) = post(
            "/v1/agent/tools",
            """{"tool":"employee.update","agentKey":"dsh","args":{"employeeId":"emp-1","name":"后端小王"}}""",
        )
        assertEquals(200, code, body.toString())
        assertEquals("applied", body["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("前端小王", body["before"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals("后端小王", body["after"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        assertNull(body["approvalId"]?.jsonPrimitive?.contentOrNull, "auto 档没有审批，别编一个 id")
        assertEquals(listOf("前端小王" to "后端小王"), fake.updates.map { "前端小王" to it.third })
    }

    @Test
    fun autoTierCreatesATeamAndReturnsTheSnapshot() {
        assertTrue(plane.rosterWritePolicy.setTier(RosterWritePolicy.Tiers.AUTO))
        val (code, body) = post(
            "/v1/agent/tools",
            """
            {"tool":"team.create","agentKey":"dsh","args":{
              "name":"开发二组","leaderAgentId":"dsh-lead",
              "members":[{"agentId":"m-2","name":"coder2","role":"编码"}],
              "groupIds":[]}}
            """.trimIndent(),
        )
        assertEquals(200, code, body.toString())
        assertEquals("applied", body["status"]?.jsonPrimitive?.contentOrNull)
        assertNull(body["before"]?.jsonPrimitive?.contentOrNull, "新建没有 before（别拿 after 冒充）")
        val after = body["after"]!!.jsonObject
        assertEquals("开发二组", after["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tm-new", after["teamId"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, fake.created.size)
        assertEquals(emptyList<String>(), fake.created.single().groupIds, "空 groupIds = 只建档")
    }

    @Test
    fun requestTierAsksTheDeviceAndTimesOutToDeny() {
        // 先等到有一台能收审批的在线设备（agq1）—— 否则这一条会退化成 503。
        Await.value("桌面上线并声明审批能力") {
            agent.peerList().firstOrNull { it.online && it.profile.caps.contains(MsgTypes.CAP_AGENT_QUEUE) }
        }
        val (code, body) = post(
            "/v1/agent/tools",
            """{"tool":"employee.update","agentKey":"dsh","args":{"employeeId":"emp-1","name":"后端小王"}}""",
        )
        assertEquals(403, code, "机主没答 = 拒绝：" + body)
        assertEquals("denied", body["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            (body["reason"]?.jsonPrimitive?.contentOrNull ?: "").contains("超时"),
            "超时与「被拒绝」要分开说：" + body,
        )

        // 卡真的发出去了（不是本地假装问过）。
        val approval = Await.value("桌面收到 agent-approval") {
            desktopFrames.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_APPROVAL }
        }
        assertTrue(
            (approval.toString()).contains("employee.update"),
            "卡片正文要写清是什么动作：" + approval,
        )
        assertTrue(fake.updates.isEmpty(), "没批就不许写")
        assertEquals("前端小王", fake.rows.single().name)
    }

    @Test
    fun requestTierTeamCreateShowsTheRosterOnTheCard() {
        Await.value("桌面上线并声明审批能力") {
            agent.peerList().firstOrNull { it.online && it.profile.caps.contains(MsgTypes.CAP_AGENT_QUEUE) }
        }
        val (code, _) = post(
            "/v1/agent/tools",
            """
            {"tool":"team.create","agentKey":"dsh","args":{
              "name":"开发二组","leaderAgentId":"dsh-lead",
              "members":[{"agentId":"m-2","name":"coder2","role":"编码"}]}}
            """.trimIndent(),
        )
        assertEquals(403, code, "超时按拒绝")
        val approval = Await.value("桌面收到 agent-approval") {
            desktopFrames.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_APPROVAL }
        }
        val text = approval.toString()
        // 机主批「建一支 3 人队」与批「改个名字」是两种决定：代价要写在卡上。
        assertTrue("开发二组" in text && "coder2" in text, "卡上要有人能看懂的编制：" + text)
        assertTrue(fake.created.isEmpty(), "没批就不许建")
    }
}
