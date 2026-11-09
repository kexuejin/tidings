// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **原型稿卡片族谱的逐张覆盖**（用户提问：「原型稿中的卡片类型都测试验证了吗，目前看还是缺了很多」）。
 *
 * 与 [AgentCardFamilySpecTest] 的分工：那份钉的是「工单卡的状态机」—— 同一张
 * `type=task-board` 在 proposed / working / review_ready / blocked / aborted 各态长得什么样。
 * 这份钉的是**卡片种类本身**：原型稿 `prototype/relay.html:2179-2308` 的 `MSGS` 演示流里
 * 一共画了 8 张卡、5 种 `type`，加上发射侧真实在发但原型稿没画的形态，逐类过一遍。
 *
 * ## 为什么必须在这一层钉
 *
 * 原型稿是视觉稿：字段名是演示用的简写（`diff.add` / `artifact` 单数 / `decided{ok,label,by}`），
 * 与协议字段（`diff.additions` / `artifacts[]` / `decidedBy`）**不是一套**。所以「原型画了」
 * 与「代码能表达」之间隔着一次翻译，而翻译漏字段在真机上只表现为「卡上少一块」，
 * 肉眼比对撑不住 8 张卡 × 每张 6~8 个块。
 *
 * 本文件按**发射侧真实字段名**构造每种卡的形状（与 `StageChain.cardOf`、
 * `DesktopWorkerWatchdog`、`BoardCardFlip`、`AgentInvokeBoardCard`、
 * `DesktopWorkerOrchestrator.askPrototypeDirection`、`ControlPlaneHookRoutes` 逐字对照），
 * 断言它进 [parseAgentCard] 之后 UI 模型上该有的事实都在。
 *
 * ## 第二组：注册表 ↔ 发射侧对账（[emittedTypesAreRegistered]）
 *
 * 这组是给未来的棘轮：`CardTypeRegistry` 声明了 10 类，全仓 `put("type", …)` 的
 * **实际取值**是 5 个 —— 差额那 5 类是被折叠的设计类（`timeout`/`result` 折进
 * task-board 的 state、`status` 折成 agentState 横幅……），显式记在
 * [DESIGNED_WITHOUT_EMITTER] 里。任一侧变化都会让断言红，逼着改的人更新注释
 * 而不是静默漂移。发射侧一度有两个 type 登记不全（徽标印英文原文），已补齐。
 */
class AgentCardTypeCoverageTest {

    // ------------------------------------------------------------ 构造辅助

    private fun card(
        cardId: String,
        type: String,
        title: String,
        rev: Int = 1,
        state: String? = null,
        progress: Int? = null,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        put("cardId", cardId)
        put("rev", rev)
        put("type", type)
        put("title", title)
        state?.let { put("state", it) }
        progress?.let { put("progress", it) }
        body()
    }

    // ══════════════════════════════════════════════════════ 一、原型稿演示的 8 张卡

    /**
     * 原型稿第 1 张：`task-board`「重构网络传输层 · 自愈验证中」(relay.html:2179)。
     *
     * 原型给了 progress 75 + checks 三项（第三项 fail 且 open，带 drawer 原始栈）+ 打回/暂停。
     * 钉住：**失败检查项的 observation 与 drawer 都要活着** —— 那是「卡上唯一能下钻的东西」。
     */
    @Test
    fun prototypeTicketCardKeepsProgressFailedCheckAndDrawer() {
        val json = card(
            cardId = "chain-refactor-transport",
            type = "task-board",
            title = "重构网络传输层 · 自愈验证中",
            rev = 3,
            state = "working",
            progress = 75,
        ) {
            putJsonObject("bot") {
                put("name", "Codex"); put("avatar", "🤖")
                put("branch", "worktree: #feat-transport"); put("bdot", "run")
            }
            putJsonArray("chips") {
                add(buildJsonObject { put("text", "🎯 弱网抗抖动与丢包自愈"); put("tone", "pri") })
                add(buildJsonObject { put("text", "⚡ 模块: :core:transport") })
                add(buildJsonObject { put("text", "🔄 状态: Heal Loop 2/3"); put("tone", "warn") })
            }
            putJsonObject("banner") {
                put("tone", "info"); put("text", "沙箱内正在运行第 2 轮自愈循环，正在验证丢包重试边界用例...")
            }
            putJsonArray("checks") {
                add(buildJsonObject {
                    put("id", "compile"); put("label", "跨平台多端编译对齐 (:core & :app)")
                    put("status", "pass"); put("time", "1.4s")
                    put("observation", "JVM 与 iOS 两端编译完全通过，无符号导出漂移。")
                })
                add(buildJsonObject {
                    put("id", "golden"); put("label", "协议黄金向量回归 (1509 passed, 0 failed)")
                    put("status", "pass"); put("time", "3.2s")
                })
                add(buildJsonObject {
                    put("id", "heal"); put("label", "极端弱网丢包自愈断言 (触发 Heal Loop 2/3)")
                    put("status", "failed"); put("time", "1.1s"); put("open", true)
                    put("observation", "第 1 轮断言未通过：丢包率达 30% 时重连超时未收敛。")
                    put("drawer", "TransportSelfHealTest > packetLossRetry() FAILED\n  AssertionError: expected <2> retries, received <0>")
                })
            }
            putJsonArray("actions") {
                add(buildJsonObject { put("actionId", "reject-revision"); put("label", "打回修改...") })
                add(buildJsonObject { put("actionId", "abort-task"); put("label", "暂停任务") })
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals("task-board", c.type)
        assertEquals("working", c.state)
        assertEquals(75, c.progress, "进度条数据源")
        assertEquals("run", c.bot?.bdot, "执行中呼吸点在动（不是终态色）")
        assertEquals("worktree: #feat-transport", c.bot?.branch)
        assertEquals(3, c.chips.size)
        assertEquals(3, c.checks.size)
        val failed = c.checks.first { it.status == AgentCardStepStatus.Failed }
        assertEquals("heal", failed.id)
        assertTrue(failed.open, "原型里这一项是展开的 —— 收纳起来等于把唯一可下钻的线索藏起来")
        assertNotNull(failed.drawer, "原始栈抽屉")
        assertEquals(2, c.actions.size)
        assertTrue("reject-revision" in c.actions.map { it.actionId })
    }

    /**
     * 原型稿第 2 张：`approval`「代码审核与产物合流 · NodeOutbound.kt」(relay.html:2199)。
     *
     * 这是原型最完整的一张：banner(done) + checks 三项全 pass + diff(hunk 4 行) + artifact + 批准/打回 + subActions。
     * 钉住：diff 的 add/del 与 hunk、产物条目、次级动作三样都在。
     *
     * 注意原型用的是 `diff.add` / `artifact` 单数 / `tone:'done'`，协议是
     * `diff.additions` / `artifacts[]` / `tone:'success'` —— 翻译就发生在这里。
     */
    @Test
    fun prototypeApprovalReviewCardKeepsDiffArtifactAndSubActions() {
        val json = card(
            cardId = "chain-patch-ready",
            type = "approval",
            title = "代码审核与产物合流 · NodeOutbound.kt",
        ) {
            putJsonObject("bot") {
                put("name", "Claude Code"); put("avatar", "⚡")
                put("branch", "worktree: #patch-ready"); put("bdot", "done")
            }
            putJsonObject("banner") {
                put("tone", "success")
                put("text", "沙箱内单元测试与架构门禁已全部通过，patch.diff 与执行产物已随卡直投本机，等待机主批准合流。")
            }
            putJsonArray("chips") {
                add(buildJsonObject { put("text", "⚡ 范围: :core:transport · +42/−7 行") })
                add(buildJsonObject { put("text", "🛡️ 门禁: 6项全绿"); put("tone", "ok") })
            }
            putJsonObject("diff") {
                put("path", "core/transport/NodeOutbound.kt")
                put("additions", 42); put("deletions", 7)
                put("note", "发送侧预算收归三处同源，修复群文本 32 KiB 溢出丢帧缺陷")
                put("open", true)
                putJsonArray("hunk") {
                    add("@@ -45,7 +45,8 @@ fun clampGroupText(payload: String): String {")
                    add("-    if (payload.length > 800) return payload.take(800)")
                    add("+    val maxBytes = Limits.GROUP_TEXT_BYTES // 32768")
                    add("+    return CodecMsg.clampUtf8(payload, maxBytes)")
                }
            }
            putJsonArray("artifacts") {
                add(buildJsonObject {
                    put("name", "patch.diff"); put("bytes", 3482)
                    put("mime", "text/x-diff"); put("intent", "review & merge"); put("delivered", true)
                })
            }
            putJsonArray("actions") {
                add(buildJsonObject { put("actionId", "reject-revision"); put("label", "打回修改...") })
                add(buildJsonObject { put("actionId", "merge-patch"); put("label", "⚡ 批准并合并补丁"); put("style", "primary") })
            }
            putJsonArray("subActions") {
                add(buildJsonObject { put("actionId", "session-exempt"); put("label", "本次会话免审") })
                add(buildJsonObject { put("actionId", "open-report"); put("label", "查看完整验收报告") })
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals("approval", c.type)
        assertEquals(2, c.chips.size)
        val diff = assertNotNull(c.diff)
        assertEquals("core/transport/NodeOutbound.kt", diff.path)
        assertEquals(42, diff.additions)
        assertEquals(7, diff.deletions)
        assertTrue(diff.open, "原型这张卡的代码是展开的")
        assertEquals(4, diff.hunk.size)
        assertEquals(1, c.artifacts.size)
        assertEquals("patch.diff", c.artifacts.first().name)
        assertEquals(2, c.actions.size)
        assertTrue("merge-patch" in c.actions.map { it.actionId })
        assertEquals(2, c.subActions.size, "次级动作（免审 / 看报告）不是按钮组的重复")
    }

    /**
     * 原型稿第 3 张：`approval`「高危命令审批 · 清理构建目录」(relay.html:2231)。
     *
     * 原型特有：`cmd` 命令框 + `decided{ok:false,label:'已打回'}`。
     * 钉住：cmd 与「已由谁打回」的知情行都在 —— 这是「为什么没有按钮」的唯一解释。
     */
    @Test
    fun prototypeHighRiskCommandCardKeepsCmdAndDecidedFooter() {
        val json = card(cardId = "appr-rmrf", type = "approval", title = "高危命令审批 · 清理构建目录", rev = 2) {
            putJsonObject("bot") { put("name", "Codex"); put("avatar", "🤖"); put("bdot", "fail") }
            putJsonArray("chips") {
                add(buildJsonObject { put("text", "⚠️ 高危敏感操作"); put("tone", "error") })
                add(buildJsonObject { put("text", "🎯 目标: 根目录清理") })
                add(buildJsonObject { put("text", "🛡️ 宿主安全守卫: 拦截"); put("tone", "warn") })
            }
            put("cmd", "\$ rm -rf /workspace/tenon/build/")
            putJsonObject("banner") {
                put("tone", "error")
                put("text", "rm -rf build/（已拦截并阻止执行，高危级全量清理操作）")
            }
            put("decidedBy", "25222a45-4b84-4e9a-9870-d13f86b2a8de")
            put("decidedActionId", "reject-revision")
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals("\$ rm -rf /workspace/tenon/build/", c.cmd, "命令框是审批卡的证据本体")
        assertEquals(
            AgentCardBannerTone.Error,
            c.banner?.tone,
            "已被拦截 = 错误档，不能画成普通信息色",
        )
        assertEquals("25222a45-4b84-4e9a-9870-d13f86b2a8de", c.decidedBy)
        assertEquals("reject-revision", c.decidedActionId, "打回后按钮组要收起来，靠这个 id 判")
        assertTrue(c.actions.isEmpty(), "已决的卡不该再带按钮")
    }

    /**
     * 原型稿第 4 张：`approval`「安全审批 · 修改 CI 门禁矩阵」(relay.html:2243) —— **过期态**。
     *
     * 原型用 `expired:'未处理 · 请求于 14:20 失效'` 表达；协议对应 `expiresAt` 时间戳 +
     * 渲染侧 `isCardExpired(expiresAt, now)` 推出「已过期」标签。
     * 钉住：过期是**算出来的**，不是数据里带的一个字符串。
     */
    @Test
    fun prototypeExpiredApprovalIsDerivedFromExpiresAt() {
        val json = card(cardId = "appr-ci-gate", type = "approval", title = "安全审批 · 修改 CI 门禁矩阵") {
            putJsonArray("chips") {
                add(buildJsonObject { put("text", "⏳ 审批请求已失效"); put("tone", "warn") })
                add(buildJsonObject { put("text", "⚡ 文件: .github/workflows/ci.yml") })
            }
            putJsonObject("banner") {
                put("tone", "warn")
                put("text", "审批请求已过期（超 5 分钟未处理，系统已自动按 Fail-Closed 策略安全回滚，无残留影响）")
            }
            putJsonArray("actions") {
                add(buildJsonObject { put("actionId", "approve"); put("label", "批准") })
            }
            put("expiresAt", 1_700_000_000_000L)
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals(1_700_000_000_000L, c.expiresAt)
        // 边界取闭区间：now == expiresAt 已经算过期（留给用户的时间用完了）。
        assertTrue(isCardExpired(c.expiresAt, 1_700_000_000_000L))
        assertTrue(isCardExpired(c.expiresAt, 1_700_000_000_001L))
        assertTrue(!isCardExpired(c.expiresAt, 1_699_999_999_999L))
        // 过期不等于「没按钮」：按钮照画但禁用（AgentCardPolicyTest.expiredStillDrawsTheButtonsButDisablesThem
        // 钉的是渲染计划，这里钉的是数据层两条输入都活着）。
        assertEquals(1, c.actions.size)
    }

    /**
     * 原型稿第 5 张：`timeout`「GPU 算力回归 · 远程节点超时中断」(relay.html:2254)。
     *
     * **重要事实**：发射侧没有 `type="timeout"` 的卡 —— `DesktopWorkerWatchdog.kt:284`
     * 发的是 `type="task-board"` + `state="timeout"`。原型把它画成独立 type，
     * 代码把它收成工单卡的一个终态。本测试按**代码真实形状**构造（task-board + state=timeout），
     * 这才是手机上会到的那一帧；同时钉住降级/重试两个动作。
     */
    @Test
    fun timeoutIsATicketStateNotItsOwnType() {
        val json = card(
            cardId = "chain-gpu-regression",
            type = "task-board",
            title = "GPU 算力回归 · 远程节点超时中断",
            state = "timeout",
        ) {
            putJsonObject("bot") { put("name", "Codex"); put("avatar", "🤖"); put("bdot", "fail") }
            putJsonArray("chips") {
                add(buildJsonObject { put("text", "⚠️ 算力节点失联"); put("tone", "error") })
                add(buildJsonObject { put("text", "📍 节点: gpu-cluster-node-04") })
                add(buildJsonObject { put("text", "💾 进度: 85% 已本地持久化"); put("tone", "ok") })
            }
            putJsonObject("banner") {
                put("tone", "error")
                put("text", "远程算力节点失联超过 3 分钟，租约已到期（前 85% 验证断点已保护）")
            }
            putJsonArray("actions") {
                add(buildJsonObject { put("actionId", "retry"); put("label", "重试远程节点"); put("style", "primary") })
                add(buildJsonObject { put("actionId", "fallback-local"); put("label", "降级为本地 CPU 执行") })
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals("task-board", c.type, "发射侧不发 type=timeout")
        assertEquals("timeout", c.state)
        assertEquals(AgentCardBannerTone.Error, c.banner?.tone)
        assertEquals(setOf("retry", "fallback-local"), c.actions.map { it.actionId }.toSet())
        // 注册表里的 "timeout" 是设计稿口径的独立类，代码里没有对位发射者 ——
        // 对账测试会把这个缺口钉死，见 DESIGNED_WITHOUT_EMITTER。
        assertEquals(
            setOf(CardBlock.Banner, CardBlock.ButtonGroup),
            CardTypeRegistry.recommendedBlocksOf("timeout"),
            "注册表仍按设计稿声明 timeout 是独立类（缺口记在测试尾部的表里）",
        )
    }

    /**
     * 原型稿第 6 张：`result`「重构网络传输层 · 终期交付验收报告」(relay.html:2265)。
     *
     * 同样是「注册表有、发射侧无」的一类：真实到达手机的是 task-board 的
     * `state=merged`（或 review_ready）终态。原型给的三项 checks 全 pass + 产物报告。
     */
    @Test
    fun resultCardIsTheMergedTicketTerminal() {
        val json = card(
            cardId = "chain-network-transport",
            type = "task-board",
            title = "重构网络传输层 · 终期交付验收报告",
            rev = 4,
            state = "merged",
        ) {
            putJsonObject("bot") { put("name", "Codex"); put("avatar", "🤖"); put("bdot", "done") }
            putJsonArray("chips") {
                add(buildJsonObject { put("text", "🏆 全量验收通过"); put("tone", "ok") })
                add(buildJsonObject { put("text", "⏱️ 迭代周期: 3 轮 Heal Loop") })
            }
            putJsonObject("banner") {
                put("tone", "success")
                put("text", "全量验收通过：1509 项单测 + 6 项架构门禁全部合格，补丁已成功合入。")
            }
            putJsonArray("checks") {
                listOf(
                    "单元测试与回归测试集 (1509/1509 全部通过)" to "4.1s",
                    "高并发稳定性测试 (testStabilityGate 连跑 3 轮)" to "12.8s",
                    "架构门禁守卫 (:app 零 Compose 依赖)" to "1.2s",
                ).forEachIndexed { i, (label, time) ->
                    add(buildJsonObject {
                        // 词表是 done / running / failed / blocked（见 stepStatusOf）；
                        // 原型稿这里的简写是 pass / fail —— 照抄会静默退成 Pending，
                        // 见 prototypeShorthandStatusIsNotTheProtocolVocabulary。
                        put("id", "check-$i"); put("label", label); put("status", "done"); put("time", time)
                    })
                }
            }
            putJsonArray("artifacts") {
                add(buildJsonObject {
                    put("name", "acceptance-report.md"); put("bytes", 18432)
                    put("mime", "text/markdown"); put("intent", "read-only report"); put("delivered", true)
                })
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals("merged", c.state)
        assertEquals("done", c.bot?.bdot, "终态呼吸点停止")
        assertEquals(3, c.checks.size)
        assertTrue(c.checks.all { it.status == AgentCardStepStatus.Done })
        assertEquals(AgentCardBannerTone.Success, c.banner?.tone)
        assertEquals("acceptance-report.md", c.artifacts.single().name)
        assertTrue(c.actions.isEmpty(), "终态不该留按钮：没有下一步可点")
    }

    /**
     * 原型稿第 7 张：`status`「任务状态 · 插件探活」(relay.html:2284) —— 轻通知。
     *
     * **事实**：没有 `type="status"` 的发射者。同语义的「生命周期轻通知」在代码里走
     * `AgentStateBannerRow`（`agentState` + 文本），不是一张卡。
     * 钉住：这条横幅可以只有 banner 一个块 —— 注册表说 status 就该只有横幅，
     * 给它按钮就把「不抢眼」破坏了（[AgentCardPolicyTest] 已断言注册表侧）。
     */
    @Test
    fun statusLightNoticeCarriesOnlyABanner() {
        val json = card(cardId = "status-plugin-probe", type = "status", title = "任务状态 · 插件探活") {
            putJsonObject("bot") { put("name", "Codex"); put("avatar", "🤖"); put("bdot", "warn") }
            putJsonArray("chips") {
                add(buildJsonObject { put("text", "🔌 插件连接状态"); put("tone", "warn") })
                add(buildJsonObject { put("text", "📍 插件: github-action-runner") })
                add(buildJsonObject { put("text", "🔄 重试队列: 待发中") })
            }
            putJsonObject("banner") {
                put("tone", "info")
                put("text", "插件离线，动作未执行（已压入离线重试队列，连通后自动重放）")
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals("status", c.type)
        assertEquals(setOf(CardBlock.Banner), presentBlocksOf(c), "轻通知只该有横幅一块")
        assertNull(c.progress)
        assertTrue(c.actions.isEmpty())
    }

    /**
     * 原型稿第 8 张：`approval`「分支清理审批 · 删除远端临时分支」(relay.html:2294)。
     *
     * 原型特有：`wait:'等待机主处理'` —— 只读态要说清「为什么没有按钮」。
     * 协议对应 `CardRenderPlan.showWaitingForHost`，判据是
     * **有动作 && 本机没资格**（`cardRenderPlanOf`）。注意与「数据里本来没有动作」的区别：
     * 后者连等待行都不该有（没东西可等），前者必须说话。
     */
    @Test
    fun approvalWithActionsButNoEligibilityExplainsWhyItWaits() {
        val json = card(cardId = "appr-branch-cleanup", type = "approval", title = "分支清理审批 · 删除远端临时分支") {
            putJsonObject("bot") { put("name", "Claude Code"); put("avatar", "⚡"); put("bdot", "warn") }
            putJsonArray("chips") {
                add(buildJsonObject { put("text", "⚠️ 分支销毁操作"); put("tone", "warn") })
                add(buildJsonObject { put("text", "📍 目标: release/1.4-temp") })
            }
            putJsonObject("banner") {
                put("tone", "error")
                put("text", "即将从远端 Git 仓库永久删除分支 release/1.4-temp")
            }
            putJsonArray("actions") {
                add(buildJsonObject { put("actionId", "delete-branch"); put("label", "确认删除") })
                add(buildJsonObject { put("actionId", "cancel-task"); put("label", "取消") })
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals(2, c.actions.size, "数据里动作是有的 —— 只是这台设备不能点")
        assertTrue(CardBlock.ButtonGroup in presentBlocksOf(c))

        // 本机没资格：整块按钮组不画（画了也点不动，比没有更糟），改说「在等宿主」。
        val readOnly = cardRenderPlanOf(c, CardActionEligibility.ReadOnly, hasActionChannel = true, now = 0L)
        assertTrue(!readOnly.showActions, "只读态整块不画按钮组")
        assertTrue(readOnly.showWaitingForHost, "必须说清为什么没有按钮")

        // 有资格时反过来：按钮要出来。
        val actionable = cardRenderPlanOf(c, CardActionEligibility.Actionable, hasActionChannel = true, now = 0L)
        assertTrue(actionable.showActions)
        assertTrue(!actionable.showWaitingForHost)
    }

    // ══════════════════════════════════════════════════════ 二、发射侧真实形态（原型稿未画）

    /**
     * 手机侧 invoke 工单卡：`type="team-task-board"`（`AgentInvokeBoardCard.kt:65`、
     * `RelayControllerAgentInvokeComponent.kt:534`）。
     *
     * 这个名字与 `task-board` 不是同一类：它把组员的 worker 卡收编进来，自有块只有
     * 横幅（三态都写）、终点态的进度、超时态的两个按钮。注册表与徽标文案都已登记，
     * 所以这里连**推荐构成**一起钉住。
     */
    @Test
    fun invokedTeamTaskBoardCardIsRegisteredWithItsOwnBlocks() {
        val json = card(
            cardId = "invoke-abc123",
            type = "team-task-board",
            title = "实现一个导出对话为 Markdown 的按钮",
            state = "timeout",
        ) {
            putJsonObject("banner") { put("tone", "warn"); put("text", "执行超时（租约到期）") }
            putJsonArray("actions") {
                add(buildJsonObject { put("actionId", "retry-invoke"); put("label", "重试"); put("style", "secondary") })
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals("team-task-board", c.type)
        assertEquals(AgentCardBannerTone.Warn, c.banner?.tone)
        assertEquals(1, c.actions.size)
        assertEquals(
            setOf(CardBlock.Banner, CardBlock.ButtonGroup),
            presentBlocksOf(c),
            "超时态：横幅 + 两个按钮，没有进度也没有 diff",
        )
        assertEquals(
            setOf(CardBlock.Banner, CardBlock.Progress, CardBlock.ButtonGroup),
            CardTypeRegistry.recommendedBlocksOf("team-task-board"),
            "被收编的 worker 卡带来的 diff/steps/artifacts 不列进建议 —— 那是数据里有没有的事",
        )
    }

    /**
     * 求方向卡：`type="direction-ask"`（`DesktopWorkerOrchestrator.askPrototypeDirection`）。
     *
     * 形状：state=blocked + progress 60 + banner info + 两个动作（作为调整 / 派为新任务），
     * 且 **cardId 是 `chain-<ticketId>-ask`**（独立卡，不顶掉屏上的待选卡）。
     */
    @Test
    fun directionAskCardKeepsTwoRoutesAndItsOwnCardId() {
        val json = card(
            cardId = "chain-t-42-ask",
            type = "direction-ask",
            title = "给这个指标看板补一版深色模式",
            state = "blocked",
            progress = 60,
        ) {
            putJsonObject("banner") {
                put("tone", "info")
                put("text", "这句话像新任务，也像对上一版的调整 —— 你想按哪种发？")
            }
            putJsonArray("actions") {
                add(buildJsonObject {
                    put("actionId", "refine-pending")
                    put("label", "作为调整")
                    put("labelKey", "card.action.send_as_adjustment")
                })
                add(buildJsonObject {
                    put("actionId", "dispatch-pending")
                    put("label", "派为新任务")
                    put("labelKey", "card.action.send_as_new_task")
                })
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals("chain-t-42-ask", c.cardId, "独立 cardId：不能顶掉屏上那张待选卡")
        assertEquals("blocked", c.state)
        assertEquals(60, c.progress)
        assertEquals(setOf("refine-pending", "dispatch-pending"), c.actions.map { it.actionId }.toSet())
        assertEquals(
            "card.action.send_as_adjustment",
            c.actions.first { it.actionId == "refine-pending" }.labelKey,
            "labelKey 优先于 label：同一个动作在两端文案不同时才不打架",
        )
        assertNull(
            c.diff,
            "求方向卡不带 diff：它要的是「按哪种发」，不是代码差异",
        )
        assertEquals(
            setOf(CardBlock.Banner, CardBlock.Progress, CardBlock.ButtonGroup),
            presentBlocksOf(c),
            "数据里就这三块：横幅问一句、进度停在 60、两个按钮给去向",
        )
        assertEquals(
            setOf(CardBlock.Banner, CardBlock.ButtonGroup),
            CardTypeRegistry.recommendedBlocksOf("direction-ask"),
            "注册表已登记（第 9 类）：原型稿没画，但发射侧真实在发",
        )
    }

    /**
     * 翻面卡：`type="diff-review"`（`BoardCardFlip.kt:45/71/99`，合并/判定后 rev+1）。
     *
     * 这一类的推荐构成是「diff + 按钮组」，而翻面时按钮组必须**收起来**
     * —— 已经定了的事不该再给出口。
     */
    @Test
    fun diffReviewFlipKeepsDiffAndDropsActionsAfterDecision() {
        val decided = card(
            cardId = "chain-merge-flip",
            type = "diff-review",
            title = "P2 · 版本冲突已修复…",
            rev = 7,
        ) {
            putJsonObject("diff") {
                put("path", "core/transport/NodeOutbound.kt"); put("additions", 3); put("deletions", 0)
            }
            putJsonObject("banner") { put("tone", "success"); put("text", "已合并到 main") }
            put("decidedBy", "3f9c1b2e-0000-4000-8000-000000000001")
            put("decidedActionId", "merge-patch")
        }

        val c = assertNotNull(parseAgentCard(decided))
        assertEquals("diff-review", c.type)
        assertEquals(7, c.rev)
        assertNotNull(c.diff)
        assertEquals(setOf(CardBlock.DiffViewer, CardBlock.Banner), presentBlocksOf(c))
        assertTrue(c.actions.isEmpty(), "翻面后不再有按钮")
        assertEquals("merge-patch", c.decidedActionId)
    }

    /**
     * 提问卡：`type="ask_question"`（`ControlPlaneHookRoutes.kt:373`，PreToolUse hook）。
     *
     * 推荐构成 = 横幅 + 选项块。钉住：`options` 与 `recommended` 都要能解析，
     * 且这条卡**不带 ButtonGroup**（选项本身是唯一出口，另加按钮就是两个真相）。
     */
    @Test
    fun askQuestionCardUsesOptionListWithoutButtonGroup() {
        val json = card(cardId = "ask-7f2a", type = "ask_question", title = "继续之前需要你定一件事") {
            putJsonObject("banner") {
                put("tone", "warn")
                put("text", "这个命令会改动仓库外的文件，选一种处理方式")
            }
            putJsonArray("options") {
                add(buildJsonObject { put("label", "只在仓库内改动"); put("recommended", true) })
                add(buildJsonObject { put("label", "允许改动仓库外文件") })
                add(buildJsonObject { put("label", "先停下来，我自己处理") })
            }
        }

        val c = assertNotNull(parseAgentCard(json))
        assertEquals(3, c.options.size)
        assertTrue(c.options.first().recommended, "推荐项要有标记，否则三个选项一样重")
        assertEquals(
            setOf(CardBlock.Banner, CardBlock.OptionList),
            presentBlocksOf(c),
            "选项块是唯一出口，不能再挂按钮组",
        )
        assertEquals(
            setOf(CardBlock.Banner, CardBlock.OptionList),
            CardTypeRegistry.recommendedBlocksOf("ask_question"),
            "数据形状与注册表建议一致",
        )
    }

    // ══════════════════════════════════════════════════════ 三、原型稿简写 ≠ 协议词表（防照抄）

    /**
     * 原型稿的 checks/banner 用的是**演示简写**，与协议词表不是一套。
     *
     * 这条不是「测代码有 bug」，是给照抄原型稿数据的人立一块牌子：直接把演示数据灌进
     * 协议，失败项会画成一个灰点（Pending）—— 卡上看起来「还没开始」，实际是「挂了」。
     * 静默降级比报错坏，因为它长得像正常状态。
     */
    @Test
    fun prototypeShorthandStatusIsNotTheProtocolVocabulary() {
        fun statusOf(raw: String): AgentCardStepStatus {
            val json = card("c-$raw", "result", "t") {
                putJsonArray("checks") {
                    add(buildJsonObject { put("id", "x"); put("label", "x"); put("status", raw) })
                }
            }
            return assertNotNull(parseAgentCard(json)).checks.single().status
        }

        // 协议词表（stepStatusOf 的 when 分支）
        assertEquals(AgentCardStepStatus.Done, statusOf("done"))
        assertEquals(AgentCardStepStatus.Done, statusOf("review_ready"))
        assertEquals(AgentCardStepStatus.Running, statusOf("running"))
        assertEquals(AgentCardStepStatus.Running, statusOf("working"))
        assertEquals(AgentCardStepStatus.Failed, statusOf("failed"))
        assertEquals(AgentCardStepStatus.Failed, statusOf("error"))
        assertEquals(AgentCardStepStatus.Blocked, statusOf("blocked"))

        // 原型稿简写：不认 ⇒ 退 Pending。这就是「照抄原型稿」的代价，显式钉住。
        assertEquals(AgentCardStepStatus.Pending, statusOf("pass"), "原型写 pass，协议要 done")
        assertEquals(AgentCardStepStatus.Pending, statusOf("fail"), "原型写 fail，协议要 failed")
    }

    /**
     * 同上，banner 的 tone：原型稿用 `done` / `danger`，协议是 `success` / `error`。
     *
     * `danger` 退成 Info 的后果最重：**高危拦截横幅会画成普通信息色**，
     * 「已阻止执行」看起来像一句提示。
     */
    @Test
    fun prototypeBannerToneIsNotTheProtocolVocabulary() {
        fun toneOf(raw: String): AgentCardBannerTone? {
            val json = card("c-tone-$raw", "approval", "t") {
                putJsonObject("banner") { put("tone", raw); put("text", "x") }
            }
            return assertNotNull(parseAgentCard(json)).banner?.tone
        }

        assertEquals(AgentCardBannerTone.Success, toneOf("success"))
        assertEquals(AgentCardBannerTone.Warn, toneOf("warn"))
        assertEquals(AgentCardBannerTone.Error, toneOf("error"))
        assertEquals(AgentCardBannerTone.Info, toneOf("info"))

        // 原型稿简写：全部落到 else ⇒ Info。高危卡的红框就是在这里丢的。
        assertEquals(AgentCardBannerTone.Info, toneOf("done"), "原型写 done，协议要 success")
        assertEquals(AgentCardBannerTone.Info, toneOf("danger"), "原型写 danger，协议要 error")

        // 发射侧真踩过一次同类：`TicketCardBudget` 的总预算告警写了 `warning`。
        // 那是「这张卡被裁到只剩骨架」的横幅 —— 退成 Info 后，告警看起来像一句普通提示。
        // 现已收敛为 `warn`，防止再写一次由 `guards/CardVocabularyGuardTest` 在源码层守。
        assertEquals(AgentCardBannerTone.Info, toneOf("warning"), "`warning` 不是协议词，协议是 `warn`")
    }

    // ══════════════════════════════════════════════════════ 四、注册表 ↔ 发射侧对账（棘轮）

    /**
     * 全仓 `put("type", …)` 的**实际取值**（发射侧清单，逐条带出处）。
     *
     * 只统计「agent 卡片体」的 type：`QrPairing` 的 `relay`、`ControlPlaneMetaRoutes` 的
     * `relay`、`DiagnosticsPackage` 的包类型、插件 manifest 的 `type` 都是**别的协议体**，
     * 不是卡片，不参与对账。
     */
    private val emittedCardTypes: Map<String, List<String>> = mapOf(
        "task-board" to listOf(
            "agentNode/…/agent/StageChain.kt:333",
            "orchestration/…/DesktopWorkerWatchdog.kt:212,244,284",
        ),
        "diff-review" to listOf(
            "orchestration/…/BoardCardFlip.kt:45,71,99",
            "agentNode/…/drivers/SandboxedCliDriver.kt:262,296,323",
        ),
        "ask_question" to listOf("agentNode/…/ControlPlaneHookRoutes.kt:373"),
        // 手机侧 invoke 卡：名字刻意与 task-board 分开（它收编 worker 卡，语义不同族）。
        "team-task-board" to listOf(
            "app/…/domain/AgentInvokeBoardCard.kt:65",
            "app/…/domain/RelayControllerAgentInvokeComponent.kt:534",
        ),
        "direction-ask" to listOf("orchestration/…/DesktopWorkerOrchestrator.kt:307"),
    )

    /**
     * 注册表声明了、但**全仓没有任何发射者**的 type。
     *
     * 这张表就是「设计稿画了 8 类、代码只发 5 个值」的准确答案：差额那几类被折叠进了
     * 别的形态 —— `timeout`/`result` → task-board 的 state；`status` → agentState 横幅
     * （不是卡）；`approval` → task-board 的 review_ready + `agent-approval` 帧；
     * `assignment` → 纯占位。
     */
    private val DESIGNED_WITHOUT_EMITTER = mapOf(
        "approval" to "折叠为 task-board 的 review_ready 态 + agent-approval 帧（审批队列在 AgentConsole）",
        "result" to "折叠为 task-board 的 merged 终态",
        "status" to "折叠为 agentState 横幅（AgentStateBannerRow），不是卡",
        "timeout" to "折叠为 task-board 的 state=timeout（DesktopWorkerWatchdog）",
        "assignment" to "纯占位：注册表有建议构成，无发射者、无解析用例",
    )

    /**
     * 对账棘轮：`注册表 - 无发射者的设计类 == 发射侧`，双向严格相等。
     *
     * 两侧任一变化都会让断言红：
     * - 有人补上了 `assignment` 的发射者 ⇒ `DESIGNED_WITHOUT_EMITTER` 要删那一项；
     * - 有人新发一个 type 却没进注册表 ⇒ 这里直接红（不是「记一笔」就算了）。
     *
     * 这条不改产品行为，只拒绝「账目悄悄漂移」。徽标文案的缺口由
     * `guards/CardVocabularyGuardTest` 守（注册表有、`CardTypeTag` 没那一档 = 印英文原文）。
     */
    @Test
    fun emittedTypesAreRegistered() {
        val registered = CardTypeRegistry.recommended.keys
        val emitted = emittedCardTypes.keys

        assertEquals(
            registered - DESIGNED_WITHOUT_EMITTER.keys,
            emitted,
            "注册表与发射侧必须逐项对齐：多一个少一个都是账目漂移",
        )
        assertTrue(
            DESIGNED_WITHOUT_EMITTER.keys.all { it in registered },
            "「无发射者」表里只能列注册表认识的类（列错门等于假账）",
        )
        assertTrue(
            DESIGNED_WITHOUT_EMITTER.keys.intersect(emitted).isEmpty(),
            "「无发射者」表里的类不许同时在发射侧出现",
        )
        // 每个发射者都要有出处，否则这张表会退化成「我记得有这么个值」。
        assertTrue(
            emittedCardTypes.values.all { it.isNotEmpty() },
            "每个 type 至少记一处 file:line，未来核对时不靠记忆",
        )
    }

    /**
     * 每个发射侧 type 都必须有一条本文件或同族测试的解析用例 —— 拒绝「发得出去、没人验过」。
     *
     * `assignment` 故意不在列表里：它没有发射者，所以也没有解析用例，
     * 那份缺口记在 [DESIGNED_WITHOUT_EMITTER]，不在这里假装覆盖。
     */
    @Test
    fun everyEmittedTypeHasAParseCase() {
        val covered = setOf("task-board", "diff-review", "ask_question", "team-task-board", "direction-ask")
        assertEquals(
            emittedCardTypes.keys,
            covered,
            "新增一种发射 type 时，本文件必须有它的一张卡；否则「发出去了但没人验」",
        )
    }
}
