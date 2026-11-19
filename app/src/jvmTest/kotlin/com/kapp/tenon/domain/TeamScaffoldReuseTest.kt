// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentMemberName

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AI 建队的**复用契约**（方案 §3.4.1 / §7 P1b-4b 的域层面）：LLM 的 JSON 里写 `use`
 * 就是「用我现有的人」，解析层把复用行落成 `agentId = employeeId`、名册 token、档案底座；
 * `use` 未命中**不新造**（丢行计数）、指到领队/同人 = 找到了但重复（单独计数）；
 * 回落模板同样先进池（按 duty 挑），**领队不许 `leaderKeys.first()`**（洞 B）。
 */
class TeamScaffoldReuseTest {

    private fun candidate(
        agentKey: String,
        token: String,
        duty: String,
        driverKey: String,
        model: String? = null,
        online: Boolean? = null,
    ) = TeamScaffoldCandidate(
        agentKey = agentKey, label = "label-$token", token = token, duty = duty,
        driverKey = driverKey, model = model, online = online,
    )

    private val roster = TeamScaffoldRoster(
        listOf(
            candidate("emp-a", "laozhou", "coder", "dsh", model = "deepseek-chat"),
            candidate("emp-b", "xiaoli", "tester", "claude"),
            // 专任领队：领队不能同时出现在成员里（use 指到领队 = 冲突，见下面那条用例）。
            candidate("emp-lead", "leadp", "reviewer", "codex"),
        ),
    )
    private val drivers = setOf("dsh", "claude")
    private val defaults = TeamScaffoldDefaults(teamTitle = "默认队", role = "职责", roles = listOf("编码", "测试", "审查"))
    private val vendorOf = { key: String ->
        when (key) {
            "dsh" -> "deepseek"
            "claude" -> "anthropic"
            else -> null
        }
    }

    @Test
    fun aUseRowReusesTheEmployeeExactly() {
        val raw = """
            {"teamName":"重用队","leader":"emp-lead","members":[
                {"use":"laozhou","kind":"worker","prompt":"干活人设"},
                {"name":"fresh-reviewer","role":"审查","driver":"claude","kind":"reviewer","prompt":"审查人设"}
            ]}
        """.trimIndent()
        val draft = TeamScaffoldParser.parse(
            raw, roster, drivers, "dsh", "claude", defaults.teamTitle, defaults.role, vendorOf,
        )
        assertNotNull(draft, "use 命中 + 新建行混合的方案应可解析")
        assertEquals(0, draft.missedUseRows)
        assertEquals(0, draft.conflictUseRows)
        val reuse = draft.spec.members.single { it.reused }
        // 方案 §3.4.1 落法三条：agentId = employeeId（引用不是快照）、名册行写 token、
        // driverKey 直接读档案（不反解）。
        assertEquals("emp-a", reuse.agentId, "复用行的 agentId 必须是 employeeId")
        assertEquals("laozhou", reuse.name, "名册行写档案 token，不写档案中文名")
        assertEquals("dsh", reuse.driverKey, "复用行保持档案声明的底座")
        assertEquals("干活人设", reuse.prompt, "use 行自带的人设随行")
        assertEquals(TeamExecutionKindsUi.WORKER, reuse.kind, "恰 1 worker 由 use 行的 kind 决定")
    }

    @Test
    fun aMissedUseRowIsDroppedAndCountedNotInvented() {
        val raw = """
            {"teamName":"t","leader":"emp-lead","members":[
                {"use":"ghost-nobody","kind":"reviewer"},
                {"name":"w1","role":"编码","driver":"dsh","kind":"worker"}
            ]}
        """.trimIndent()
        val draft = TeamScaffoldParser.parse(
            raw, roster, drivers, "dsh", "claude", defaults.teamTitle, defaults.role, vendorOf,
        )
        assertNotNull(draft)
        assertEquals(1, draft.missedUseRows, "`use` 未命中必须丢行并计数")
        assertTrue(
            draft.spec.members.none { it.reused },
            "未命中绝不新造：不会为 ghost 造一行",
        )
    }

    @Test
    fun usePointingAtLeaderOrTheSamePersonTwiceIsCountedAsConflict() {
        val raw = """
            {"teamName":"t","leader":"emp-lead","members":[
                {"use":"emp-lead","kind":"reviewer"},
                {"use":"laozhou","kind":"worker"},
                {"use":"emp-a","kind":"reviewer"},
                {"name":"w1","role":"审查","driver":"claude","kind":"reviewer"}
            ]}
        """.trimIndent()
        val draft = TeamScaffoldParser.parse(
            raw, roster, drivers, "dsh", "claude", defaults.teamTitle, defaults.role, vendorOf,
        )
        assertNotNull(draft)
        assertEquals(2, draft.conflictUseRows, "领队本人 / 同一员工两次：找到了但重复，单独计数（§4-14）")
        assertEquals(0, draft.missedUseRows)
    }

    @Test
    fun fallbackTemplateReusesPoolByDutyAndTheLeaderFollowsDuty() {
        // 池的第一行 duty=tester —— 旧实现（leaderKeys.first()）会领队选它；
        // 新契约领队按 duty 匹配：coder 槽优先，所以领队必须是 emp-c。
        val shifted = TeamScaffoldRoster(
            listOf(
                candidate("emp-t", "testerp", "tester", "claude"),
                candidate("emp-c", "coderp", "coder", "dsh"),
            ),
        )
        val spec = TeamScaffoldFallback.template(
            need = "把登录修好",
            roster = shifted,
            workerDriver = "dsh",
            reviewerDriver = "claude",
            defaults = defaults,
            vendorOf = vendorOf,
        )
        assertNotNull(spec, "池里有人可领队 ⇒ 模板必须给出方案")
        assertEquals("emp-c", spec.leaderAgentId, "回落模板领队按 duty 挑，不许 leaders.first()（洞 B）")
        val reused = spec.members.filter { it.reused }
        assertTrue(reused.isNotEmpty(), "回落模板的槽位也先进池：按 duty 匹配复用")
        assertTrue(
            reused.any { it.agentId == "emp-t" },
            "tester 槽复用池里 duty=tester 的人",
        )
        // 名册行的名字必须是 token（slug 化），不能是 label（中文/显示名）。
        assertTrue(reused.all { it.name == AgentMemberName.slug(it.name) }, "复用行名册名 = token")
    }

    @Test
    fun aSameVendorReusedReviewerEscapesToTheFallbackDriver() {
        // emp-b（tester）的底座改成与 worker 同厂商 → keepReviewersOffWorkerDriver 换兜底键；
        // 兜底键也同厂商时如实保留（让保存期说话），这里断言的是「换」的那一支。
        val sameVendorRoster = TeamScaffoldRoster(
            listOf(
                candidate("emp-a", "laozhou", "coder", "dsh"),
                candidate("emp-b", "xiaoli", "tester", "dsh"), // 与 worker 同厂商
                candidate("emp-lead", "leadp", "reviewer", "codex"),
            ),
        )
        val raw = """
            {"teamName":"t","leader":"emp-lead","members":[
                {"use":"laozhou","kind":"worker"},
                {"use":"xiaoli","kind":"reviewer"}
            ]}
        """.trimIndent()
        val draft = TeamScaffoldParser.parse(
            raw, sameVendorRoster, drivers, "dsh", "claude", defaults.teamTitle, defaults.role, vendorOf,
        )
        assertNotNull(draft)
        val reviewer = draft.spec.members.single { it.kind == TeamExecutionKindsUi.REVIEWER }
        assertEquals("claude", reviewer.driverKey, "复用 reviewer 与 worker 同厂商 ⇒ 换异厂商兜底键")
        assertTrue(reviewer.reused, "换的是底座，不是「换掉复用」这件事")
    }

    @Test
    fun anEmptyPoolYieldsNoLeadersSoNothingCanLead() {
        val empty = TeamScaffoldRoster(emptyList())
        assertTrue(empty.leaders.isEmpty(), "空池无可领队者 —— 生成层据此返回 null（洞 B 契约）")
    }
}
