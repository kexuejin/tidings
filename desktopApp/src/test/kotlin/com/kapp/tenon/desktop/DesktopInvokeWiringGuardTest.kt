// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M4 跨端派单的**桌面装配守卫**。
 *
 * 派单网道的三段住在三个模块：协议帧（core）、租约/看板卡/审批防重（:app 域层）、
 * HTTP 入口与插件执行（agentNode 的 ControlPlane）。desktopApp 是把它们接成通路的
 * 唯一一处 —— 而「接没接上」类型系统表达不了：任何一桥被摘掉（attach 里删一行、
 * 或 detach 忘了清），编译照样绿，但那一端的派单就静默变成 no-executor / 野通路。
 *
 * 所以按源码文本钉住**装配点**：五个桥必须在 attach 里赋值、必须在 detach 里清空。
 * 剥掉注释再匹配（[GuardSourceScan] 的纪律），避免被解释这件事的注释绊倒。
 */
class DesktopInvokeWiringGuardTest {

    private val extension = "orchestration/src/main/kotlin/com/kapp/tenon/orchestration"

    /** 判据问的是「编排层有没有这条接线」—— 装配层已按职责拆成四个文件，读整包。 */
    private fun live(): String = GuardSourceScan.livePackageOf(extension)

    @Test
    fun dispatchIsDelegatedToTheDomainMeshNotARouteLocalImplementation() {
        val source = live()
        assertTrue(
            source.contains("created.invokeDispatcher = "),
            "ControlPlane 的 invokeDispatcher 必须接到域层派单（dispatchCrossHostInvoke）：HTTP 入口只解包，网道在域层",
        )
        assertTrue(
            source.contains("controller.dispatchCrossHostInvoke("),
            "派发必须委托域层网道：在装配层另写一份派发，租约与看板卡就分叉了",
        )
    }

    @Test
    fun diagnosticLogBridgeIsInstalledAndClearedOnDetach() {
        val source = live()
        assertTrue(
            source.contains("controller.diagnosticLogSink = { tag, message -> RelayLog.info(tag, message) }"),
            "诊断日志桥必须装上（SPEC-ORCHESTRATION-2026-11-03 §7.2）：不装 = :app 的 NO_WAKE " +
                "静默丢弃，「必须写一条诊断」假落地",
        )
        assertTrue(
            source.contains("controller.diagnosticLogSink = null"),
            "诊断日志桥必须在 detach 里清空（与其它桥同纪律）",
        )
    }

    @Test
    fun executorAndAuthorizationBridgesAreInstalled() {
        val source = live()
        assertTrue(
            source.contains("controller.invokeExecutor = object : com.kapp.tenon.domain.AgentInvokeExecutor"),
            "执行桥必须装上：不装 = 每条入站派单都答 no-executor，跨端派单整条死路",
        )
        assertTrue(
            source.contains("created.pluginForAgent("),
            "执行必须先解析本机承接插件：没有它就没有可派单的对象",
        )
        assertTrue(
            source.contains("created.executePluginTask("),
            "执行必须走 executePluginTask 唯一实现：另写一份，台账就漂成两本",
        )
        assertTrue(
            source.contains("controller.invokePluginPresenceOf = "),
            "插件在线状态桥必须装上（S4-4b）：没有它，插件判离线后派单照样干等租约 —— 真机读数是 14 分钟零反馈",
        )
        // T-3b 产物投递：沙箱 patch.diff 经结构化回调收下、成功后投进派单来源的群。
        // 摘掉任何一环（不接回调 / 不发群）⇒ patch 只躺在宿主 /tmp 里，群里永远看不到。
        assertTrue(
            source.contains("onArtifact = { path, name, bytes, _, _ -> artifacts.add(Triple(path, name, bytes)) }"),
            "执行桥没有接 onArtifact 回调 —— 沙箱产物到不了投递环节（T-3b 断链）",
        )
        assertTrue(
            source.contains("controller.sendAgentArtifacts(") && source.contains("ConvId.group(deliverGroupId)"),
            "产物没有投进派单来源的群（sendAgentArtifacts + ConvId.group 群路径）—— patch 只躺在宿主本机",
        )
        // T-G1 审查合流：merge-patch 动作在宿主本机落地（git apply），冲突落台账。
        assertTrue(
            source.contains("actionId == \"merge-patch\"") && source.contains("DesktopPatchMerge.apply("),
            "合流动作没有接 DesktopPatchMerge —— diff 卡上的 [合并此补丁] 点了没反应",
        )
        assertTrue(
            source.contains("latestPatchRefFor(") && source.contains("eventType = \"merge-result\""),
            "合流没有按会话找回 patch（latestPatchRefFor）或没有把冲突落台账（merge-result）",
        )
        // 动作帧（op=action）里没有会话位，这一跳拿到的 sessionId 恒为 null —— 只按会话
        // 找 patch 的判据在真机上永远不成立（2026-11-01 幕 6：卡面「已由 Pixel 6 处理」，
        // 宿主仓零提交）。按卡片反查这条路必须同时在，否则「合并」是个假按钮。
        assertTrue(
            source.contains("latestPatchRefForCard("),
            "合流没有按卡片找回 patch（latestPatchRefForCard）—— 动作帧不带会话，合并分支永远进不去",
        )
        // **工单卡的补丁只能由编排器自己记的账找回。** `latestPatchRefForCard` 走的是
        // 会话台账反查（找一条 type=card 且 body 含 cardId 的事件），而工单卡是
        // `broadcastChainCard` 直接广播的、从不写台账 ⇒ 对工单卡恒为 null。
        // 2026-11-02 真机实测：点「合并此补丁」得到
        // `no sandbox patch recorded for card chain-tmvyqfi84`，宿主仓零字节变化。
        assertTrue(
            source.contains("patchRefForCard("),
            "合流没有先问编排器要补丁（patchRefForCard）—— 工单卡不写会话台账，按会话反查恒为空，合并必失败",
        )
        // 合并成功后要把同一张卡翻成「已合并」。群号从前靠扫本地消息反推，扫不到就
        // 静默跳过 —— 合并落地、仓已改、卡面停在「待合并」，且一行日志都没有。
        assertTrue(
            source.contains("groupForCard(") && source.contains("merged card has no group to publish to"),
            "翻面卡没有确定性的群来源，或发不出去时不留痕 —— 合并成功而卡面不翻面",
        )
        // 受阻卡上的 [重试]：卡里画了 actionId=retry，宿主就必须有分支接它，而且
        // 必须排在 `followUpByCard` **之前**（否则 retry 会被当成一句用户微调）。
        assertTrue(
            source.contains("actionId == \"retry\"") && source.contains("retryByCard("),
            "重试按钮没有落点 —— 真机上要么被插件转发丢掉，要么被当成一句微调",
        )
        assertTrue(
            source.indexOf("actionId == \"retry\"") < source.indexOf("followUpByCard(cardId, actionId)"),
            "retry 分支排在 followUpByCard 之后 —— actionId 会被当成用户文本喂进原型闭包",
        )
        // T-G2 标签路由：两桥装配 + 接收侧持标签校验（缺一整条链就黑）。
        assertTrue(
            source.contains("controller.peerTagRouter = ") && source.contains("controller.channelDispatch = "),
            "发起侧标签路由两桥没装配 —— requiredTag 消费端永远走失败横幅（T-2c 悬空账）",
        )
        assertTrue(
            source.contains("PeerTagRouter.resolve(") && source.contains("nodeIdsWithTag("),
            "路由语义没接 PeerTagRouter（在线优先 + 字典序）或没读标签 store",
        )
        assertTrue(
            source.contains("node does not hold tag"),
            "接收侧没有持标签校验 —— 不持标签的节点不该接活（防御门）",
        )
        // E1 + 编排器归位：CLI 驱动执行桥 —— @antigravity/@claude/@codex 的本机子进程通路。
        // 执行体必须把 CLI 派单交给编排器（handlePromptAwait），不许直调 executeCliTask：
        // 真机 `@agent` 帧全走 invoke，直调会让工单卡/原型立项/追问唤醒/自愈退单
        // 在真机路径上整段消失 —— 「单测全绿、现场不存在」的旧形状。
        assertTrue(
            source.contains("created.resolveCliDriver(cliKey) != null") &&
                source.contains("val cliKey = AgentProfiles.resolveKey("),
            "CLI 驱动回退没接，或群里点名的席位 ID 没归位到档案键 —— " +
                "resolveCliDriver 拿到 dsh-a030882e 恒 null，真机派单落 NO_PLUGIN",
        )
        assertTrue(
            source.contains("agentKey = cliKey"),
            "CLI 派单传给编排器的键必须与 resolveCliDriver 的判据同源",
        )
        assertFalse(
            source.contains("created.resolveCliDriver(request.agentId)"),
            "不许把未归位的席位 ID 再喂给驱动注册表",
        )
        assertTrue(
            source.contains("workerOrchestrator.handlePromptAwait("),
            "CLI 派单必须交给编排器（handlePromptAwait）—— 绕过编排器的执行体是旧形状",
        )
        assertTrue(
            !source.contains("created.executeCliTask("),
            "invoke 执行器不得直调 executeCliTask —— 那会绕过工单编排器",
        )
        assertTrue(
            source.contains("cliDrivers = driverRegistry"),
            "CLI 注册表没注入 ControlPlane —— executeCliTask 无驱动可解析",
        )
        // T-G9/T-G10：transfer 宣告（原主可见「已移交」）+ 结果有界重投 + 域层首赢去重。
        assertTrue(
            source.contains("origin = \"transferred\""),
            "transfer 没有向原宿主宣告接管 —— 原主的看板永远不知道任务去了哪",
        )
        assertTrue(
            source.contains("result delivery failed after"),
            "结果投递没有有界重试 —— 对端离线丢一次 = 发起方白等租约超时",
        )
        val domain = GuardSourceScan.stripComments(
            GuardSourceScan.read("app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayController.kt"),
        )
        assertTrue(
            domain.contains("seenChannelResults.add(channelId)"),
            "结果消费没有按 channelId 首赢去重 —— 重投会复制群流消息",
        )
        assertTrue(source.contains("controller.invokeGrantAllows = "), "可信设备判据桥必须装上（§6.3）")
        assertTrue(source.contains("controller.invokeExecPolicyOf = "), "每 agent 执行范围桥必须装上（§6.3）")
        assertTrue(source.contains("controller.invokeRecordGrantRequest = "), "未授权申请桥必须装上（面板要能冒出待批项）")
        assertTrue(source.contains("controller.onHostCardAction = "), "宿主侧卡片动作出口必须装上（§7.3-2 的插件转发段）")
    }

    @Test
    fun chainCardMergeSurvivesAHostRestart() {
        // D6 现场（2026-11-08 真机）：宿主重启后点旧工单卡的「合并此补丁」得到
        // `patch merge FAILED … cannot resolve workspace for chain card chain-tmw79h7vw`
        // —— 仓里 `chain/<id>` 分支好端端躺着，宿主却说不知道往哪合。两个成因都不是
        // 「没接线」，而是**判据只相信内存**：
        //   a) 工作区来自 `groupForCard`（发卡那一刻的登记簿，重启即空）；
        //   b) 会话键过了 `ConvId.groupIdOf(peerId)`，对 1:1 恒 null ——
        //      于是翻面卡/受阻卡也发不出去（`merged card has no group to publish to`）。
        // 修法是把判据换到磁盘上（仓库自己的 refs）与会话键本身。这两条都是
        // 「不接就静默坏掉」的类型：编译绿、单跑绿，只有重启后点一下才现形。
        val source = live()
        assertTrue(
            source.contains("ChainWorkspaceResolve.workspaceOwning("),
            "链卡工作区没有落到「谁拥有 chain/<id> 分支」这条磁盘判据上 —— 重启后合并必失败",
        )
        assertTrue(
            source.contains("?: tickets()?.workspacePathForChainBranch(branch)"),
            "分支反查没挂在合并分支的兜底位（定义而不调用 = 什么都没修）",
        )
        assertTrue(
            source.contains("val chainConv = tickets()?.groupForCard(cardId) ?: actionRow?.peerId"),
            "链卡的会话键没退回卡片行 peerId —— 宿主重启后 1:1 链卡连会话都认不出来",
        )
        // 翻面 / 受阻两条路都必须用会话键本身，不能过 `groupIdOf`（1:1 会被折成 null）。
        assertTrue(
            source.contains("val appliedGroupId = tickets()?.groupForCard(cardId) ?: actionRow?.peerId"),
            "合并成功的翻面卡没有 1:1 退路 —— 私聊里合并成功，卡面却停在「待合并」",
        )
        assertTrue(
            source.contains("val conflictGroupId = tickets()?.groupForCard(cardId) ?: conflictRow?.peerId"),
            "受阻卡没有 1:1 退路 —— 私聊里合不上，一个字都回不去",
        )
        // **补丁位置也必须有一条磁盘来源。** 真机查证：链卡在这套装置上多数走单棒沙箱，
        // 仓里根本没有 `chain/*` 分支，产物只有 `/tmp/tenon-ws/artifacts/<taskId>/patch.diff`
        // —— 分支反查救不了它，能救的只有「发卡时把 patch 路径记到磁盘」。
        assertTrue(
            source.contains("TicketPatchLedger.record("),
            "发卡时没把（卡片 → 补丁路径）落到磁盘 —— 宿主重启后这批卡全部作废",
        )
        assertTrue(
            source.contains("TicketPatchLedger.lookup(cardId)"),
            "合并前没查磁盘账簿 —— 内存登记簿重启即空，patch 却在盘上躺着",
        )
        assertTrue(
            source.contains("?: ledgerEntry?.convKey"),
            "翻面/受阻卡的会话键没有账簿退路 —— 卡片行落到恢复窗口之外时卡面不翻",
        )
    }

    @Test
    fun presenceVocabularyMatchesThePluginRegistry() {
        // 域层的两个词必须与 agentNode `/v1/plugins` 的 status 同形 —— 映射错一个字母，
        // S4-4b 的「离线即回声」就永远不触发。两套常量的同值关系在这里钉死。
        assertEquals(
            com.kapp.tenon.agent.plugins.PluginRegistry.STATUS_ONLINE,
            com.kapp.tenon.domain.PluginPresence.ONLINE,
        )
        assertEquals(
            com.kapp.tenon.agent.plugins.PluginRegistry.STATUS_OFFLINE,
            com.kapp.tenon.domain.PluginPresence.OFFLINE,
        )
    }

    @Test
    fun detachClearsEveryInvokeBridge() {
        val source = live()
        for (bridge in listOf(
            "controller.invokeExecutor = null",
            "controller.invokePluginPresenceOf = null",
            "controller.invokeGrantAllows = null",
            "controller.invokeExecPolicyOf = null",
            "controller.invokeRecordGrantRequest = null",
            "controller.onHostCardAction = null",
        )) {
            assertTrue(
                source.contains(bridge),
                "detach 必须清掉 $bridge：留着它，域层会把派单交给一个已停止的控制面",
            )
        }
    }

    @Test
    fun delegationPlansAreParsedAndGatedBeforeTheLegacyIntake() {
        // 委派双挡的编排器接线（orchestration-model-fit-2026-11-03 §二/§五；§16.4 单源）：
        // 1. 计划解析必须接进编排器（挡位 A 点名 / 挡位 B 组队），且两条派单路都走
        //    单源函数 parseDelegation —— 直接调 DelegationParser.parse 的旁路 = 分叉；
        // 2. 计划必须过代码门（StageChainGate.enforce）才可能进链 ——
        //    异厂商审核是硬规则不是建议，摘掉门 = 审核棒退化成自审；
        // 3. 委派解析优先于旧阶段语法 —— 顺序反了，双挡就永远轮不到。
        val source = GuardSourceScan.stripComments(
            GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        )
        val planAt = source.indexOf("parseDelegation(prompt, available, groupId)")
        val gateAt = source.indexOf("StageChainGate.enforce(")
        val legacyAt = source.indexOf("StageChain.parseStages(prompt)")
        assertTrue(
            planAt >= 0,
            "委派计划解析必须接进编排器：没有它，双挡（点名/组队）在真机上不存在",
        )
        // §16.4：DelegationParser.parse 全文件只许出现在 parseDelegation 单源函数里 ——
        // 第二处直接调用 = 两条派单路分叉（模板与健康名册的口径就各走各的了）。
        val directParses = Regex(Regex.escape("DelegationParser.parse(")).findAll(source).count()
        assertEquals(
            1, directParses,
            "DelegationParser.parse 只许在 parseDelegation 单源里出现一次：多一处 = 旁路分叉",
        )
        assertTrue(
            gateAt > planAt,
            "委派计划必须过代码门（StageChainGate.enforce）：在位与异厂商审核是硬约束",
        )
        assertTrue(
            legacyAt > planAt,
            "委派解析必须先于旧阶段语法判据：顺序反了挡位 A/B 永远轮不到",
        )
    }
}
