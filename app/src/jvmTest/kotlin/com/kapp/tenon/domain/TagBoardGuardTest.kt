// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-G4 R3 兜底卡的接线守卫：指定标签无匹配节点时，**静默落回本机执行**是意图违背
 * （用户要的是「有 gpu 的那台」）—— 挂起成卡 + 两个动作各自可达。
 *
 * 为什么只能源码级守：无匹配路径在域层组件内部，行为测试要完整双节点夹具 + 桥注入
 * （成本高且易脆）；接线纪律（挂起而非回退 / 两动作 / 横幅照旧）钉在源码上。
 */
class TagBoardGuardTest {

    private fun live(path: String): String = com.kapp.tenon.testsupport.GuardSourceScan.stripComments(
        com.kapp.tenon.testsupport.GuardSourceScan.read(path),
    )

    private val component =
        "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentInvokeComponent.kt"

    /** 卡片动作域（ARP-7 从组件里整块搬出）：标签卡的动作落点在这一侧。 */
    private val cardActions =
        "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentCardActions.kt"

    /**
     * 一张标签卡横跨两半：**挂起与横幅**在派单侧（无匹配分支就在那里），**动作落点**
     * 在卡片动作域。判据查的是「这两件事有没有做」，不是「某个文件第几行有没有」——
     * 钉文件名的守卫在拆分当天就会红，而纪律其实没破。
     */
    private fun liveSources(): String = live(component) + "\n" + live(cardActions)

    @Test
    fun tagNoMatchSuspendsIntoACardInsteadOfSilentlyRunningLocally() {
        val body = liveSources()
        // 挂起卡：无匹配分支在**派发之前**返回（不落进本机执行器路径）。
        assertTrue(
            body.contains("if (requiredTag != null && routed == null) {"),
            "无匹配分支没有独立出来 —— 会静默落回本机执行（意图违背）",
        )
        // 登记口在卡片动作域，调用在派单侧 —— 两句都要，只钉方法名会恒真
        // （方法定义自己就含这个串，删掉调用点也绿）。
        assertTrue(
            body.contains("fun rememberTagBoard(") && body.contains("rememberTagBoard(cardId"),
            "无匹配卡没有登记动作上下文 —— 重试/降级两个按钮点了没落点",
        )
        // 两个动作各自可达。
        assertTrue(
            body.contains("TagBoardActions.RETRY ->") && body.contains("TagBoardActions.FALLBACK_LOCAL ->"),
            "标签卡的两个动作没有接进看板动作分发",
        )
        // 降级动作走的是与租约降级同一条本机执行通路（不写第二份执行器）：
        // 卡片动作域不自己执行，经端口回到派单侧的唯一入口 `executeLocallyAndBroadcast`。
        assertTrue(
            body.contains("port.runLocallyAndMark(") && body.contains("executeLocallyAndMark("),
            "标签降级没有复用 executeLocallyAndMark —— 两份本机执行迟早漂",
        )
        // 横幅照旧（不点卡的人也有回声；S4-4 的「点了没反应」纪律）。
        assertTrue(
            body.contains("announceAgentGaveUp(groupId, agentId, seat.name, messageId, unavailable = true)"),
            "无匹配时横幅没了 —— 不点卡的人完全没有回声",
        )
    }

    /**
     * 兜底卡的 wsId 必须带席位/群绑定回落（真机缺口）：纯文本语法路径的意图不带
     * wsId，登记成 null ⇒ 「降级为本机执行」时宿主拿不到仓，必退单
     * 「no git workspace bound to group」。回落链复用 DispatchWorkspaceChoice.resolve
     * （派单工作区单一来源的既定纪律，不另写一条），且登记口必须用回落后的值。
     */
    @Test
    fun fallbackCardCarriesSeatWorkspaceSoLocalDegradeCanResolveRepo() {
        val body = live(component)
        assertTrue(
            body.contains("DispatchWorkspaceChoice.resolve(seat.defaultWsId, available)"),
            "兜底卡 wsId 没有席位/群绑定回落 —— 降级本机执行必退单 no git workspace bound to group",
        )
        assertTrue(
            body.contains("rememberTagBoard(cardId, groupId, agentId, seat, tagTask, boardWsId, requiredTag)"),
            "兜底卡登记没有用回落后的 wsId —— 降级按钮拿到的还是 null",
        )
    }

    /** T-G5 定向呼叫：显式宿主 = 只有被点名的机器处理（群帧全员可见，执行只有一家）。 */
    @Test
    fun explicitHostIntentIsProcessedOnlyByTheNamedHost() {
        val body = live(component)
        assertTrue(
            body.contains("val explicitHost = intent.strField(\"hostNodeId\")"),
            "接收侧没有读 hostNodeId —— 定向呼叫无人认领",
        )
        assertTrue(
            body.contains("if (explicitHost != null && explicitHost != self) return"),
            "非目标宿主没有让路 —— 每台有同名 agent 的机器都会抢着跑（§2.1 的原始困境）",
        )
    }

    /**
     * 纯文本语法路径（T-G2/D-031）的意图**不带 agentId**（只装 requiredTag）——
     * 真机现场：接收侧对无 agentId 的意图整链静默 return，而 intentOwnsDispatch
     * 又挡掉了文本路由 ⇒「require: 打在末尾也零反馈」。接收侧必须按正文 @ 解析目标。
     */
    @Test
    fun plainTextTagIntentResolvesTargetFromMention() {
        val body = live(component)
        assertTrue(
            body.contains("?: messageText?.let { text ->") &&
                body.contains("takeWhile { !it.isWhitespace() }") &&
                body.contains("startsWith(token, ignoreCase = true)"),
            "无 agentId 的意图没有按正文 @ 令牌解析目标席位 —— 纯文本 require: 语法整链静默",
        )
    }

    /**
     * 标签路由必须住在本地执行器判定**之前**：席位宿主装着执行器 ≠ 本机就是
     * 标签节点。真机现场：标签消费整段住在 ex == null 分支里，宿主有执行器时
     * require: 被静默忽略、活照跑本机（意图违背）。无宿主门时群里每台无执行器
     * 的机器还会各挂一张兜底卡 —— 两道门都要在。
     */
    @Test
    fun tagRoutingPrecedesLocalExecutorAndOnlyTheSeatHostActs() {
        val body = live(component)
        val tagRoute = body.indexOf("val requiredTag = intent.strField(\"requiredTag\")")
        // 文件里有两个 `val ex = executor`（前一个是跨机 invoke 应答路径的）——
        // 意图派单路径的那一个在 requiredTag 之后，取最后一次出现。
        val executorSplit = body.lastIndexOf("val ex = executor")
        assertTrue(
            tagRoute in 0 until executorSplit,
            "标签路由没有提到本地执行器判定之前 —— 有执行器的宿主会静默忽略 require:",
        )
        assertTrue(
            body.contains("if (effectiveHost != null && effectiveHost != self) return"),
            "席位宿主门没了 —— 非托管机会抢着派委派/挂兜底卡",
        )
        assertTrue(
            body.contains("if (requiredTag != null && routed != null && routed != self) {"),
            "委派分支没有排除本机 —— 标签命中的就是本机时会自委派一圈",
        )
    }
}
