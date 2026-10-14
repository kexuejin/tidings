// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import app.relay.agent.AgentGrantStore
import app.relay.agent.AgentSessionRegistry
import app.relay.agent.AgentWorkspaceRegistry
import app.relay.agent.ControlPlane
import app.relay.app.RelayController
import app.relay.core.session.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 桌面版的「扩展装配」：让 agent 与 UI 活在同一个进程里。
 *
 * ## 为什么不自己 new 一个 NodeRuntime
 * [RelayController] 已经拥有唯一的运行时（它 start 时按设置里的端口绑 UDP/TCP）。
 * 桌面壳如果再建一个 `NodeRuntime`，两个实例会抢同一个端口：后绑的那个要么失败，
 * 要么悄悄分流 UDP 报文，结果就是 UI 收不到消息、控制面也读不到 peer。
 * 所以这里只**读**控制器的运行时（[RelayController.activeRuntime]），把控制面挂上去。
 *
 * ## 为什么控制面要跟着运行时起落
 * 控制面只是一个 localhost 的 JSON 门面，它读的全是运行时的状态。运行时没了，
 * `/v1/peers` 就会返回一份空表 —— 那比 503 更糟，因为调用方会以为「真的没有节点」。
 * 运行时生命周期不暴露事件（见 [RelayController.stop]），所以这里轮询对齐。
 *
 * ## 为什么 agent 台账是这里建的
 * [ControlPlane] 要求记账的**出站**方与**入站**方共用同一个 [AgentSessionRegistry]；
 * 入站那一半由「订阅 `runtime.inbound` 的人」写。桌面端与 agentNode 是两个进程，
 * 所以桌面端必须自己订阅、自己持有，详见 [sessions] 的 KDoc。
 */
internal class DesktopExtension(
    private val controller: RelayController,
    private val scope: CoroutineScope,
    /** 0 = 由系统分配端口；实际端口读 [controlPlanePort]。 */
    private val httpPort: Int = 0,
    /** 空串 = 回环放行（见 [ControlPlane] 的 token 约定）。 */
    private val token: String = "",
) {
    private var plane: ControlPlane? = null

    /**
     * 当前控制面挂着的运行时。必须记引用而不是只记「已挂」：
     * `RelayController.restart()` 会新建运行时而未必出现可观察到的 null 窗口
     * （旧运行时被 stop 后马上换新的），只比「plane != null」会让控制面永远留在
     * 已死的旧运行时上，`/v1/peers` 就成了一份过期的空表。
     */
    private var attachedRuntime: NodeRuntime? = null
    private var followJob: Job? = null

    /**
     * 当前运行时的 agent 会话台账；未挂时为 null。
     *
     * ## 为什么桌面端要自己持有一个
     *
     * [ControlPlane] 的 `sessions` 形参要求「出站写入方与入站写入方共用同一个实例」：
     * 控制面只记自己**发出**的 agent 帧，**收到**的帧得由拥有 `runtime.inbound` 的
     * 那一方补上，否则面板只看得到一半会话（自己发的，看不到对方发的）。
     *
     * 在 agentNode 进程里，那一方是 `Main`；而桌面端是**另一个进程**，`Main` 里那个
     * 实例根本不在这里。所以这条约束在桌面端的正确落点就是：**本进程自己订阅
     * 本进程运行时的入站流，并把同一个实例交给控制面**（`ControlPlane.kt:78-85` 的
     * KDoc 说的就是这件事）。解析规则复用 `recordInbound`，与 `Main` 逐字同源。
     *
     * 随运行时重挂而重建：台账是**当前态**的视图（见 [AgentSessionRegistry] 的 KDoc），
     * 换了一个运行时之后，旧运行时的会话已经跟我们没关系了，留着比清掉更误导。
     */
    private var sessions: AgentSessionRegistry? = null

    /**
     * 入站订阅的 Job；与 [sessions] 同生命周期，[detach] 时一并取消。
     *
     * 订阅由 `ControlPlane.observeInbound` 托管（三张台账一份规则），
     * 它**必须**可取消：桌面端会随运行时重启重挂控制面，旧任务必须取消以防重复记账。
     */
    private var inboundJob: Job? = null

    /** 控制面实际监听端口；未启动时为 0。菜单栏与状态栏都读它。 */
    val controlPlanePort: Int
        get() = plane?.boundPort ?: 0

    /**
     * 开始跟随控制器生命周期。必须在 [RelayController.start] **之前**调用：
     * 运行时的创建是异步完成的，之后再挂会错过时机。
     */
    fun start() {
        if (followJob != null) return
        followJob = scope.launch {
            while (isActive) {
                syncOnce()
                delay(FOLLOW_POLL_MS)
            }
            // 协程被取消（窗口退出）时也别把控制面留在监听状态。
            detach()
        }
    }

    fun stop() {
        followJob?.cancel()
        followJob = null
        detach()
    }

    /**
     * 一拍对齐：运行时换了就重挂，没了（stop / 重启中）就摘掉。
     * 幂等 —— 稳态下每拍只做一次引用比较，不产生任何副作用。
     */
    private fun syncOnce() {
        val runtime = controller.activeRuntime()
        if (runtime == null || (plane != null && attachedRuntime !== runtime)) {
            detach()
            if (runtime == null) return
        }
        if (plane != null) return
        // 台账先建再 start：控制面一旦 start()，`/v1/agent/sessions` 就可能被问，
        // 那一刻台账必须已经在收对方的帧了（空列表对调用方就是「本机没有会话」）。
        // 入站订阅不再在这里手写 —— 三张台账（会话 / 工作区 / 授权）的解析规则
        // 只有 `ControlPlane.observeInbound` 这一份，桌面端、agentNode、测试共用，
        // 集中维护解析分支，防止分叉遗漏。
        val registry = AgentSessionRegistry()
        val workspaces = AgentWorkspaceRegistry()
        val grants = AgentGrantStore(java.io.File(controller.dataDirectory, "agent-grants.json"))
        val created = ControlPlane(
            runtime, httpPort, token,
            sessions = registry, workspaces = workspaces, grants = grants,
        )
        try {
            created.start()
            inboundJob = created.observeInbound(scope)
            plane = created
            attachedRuntime = runtime
            sessions = registry
        } catch (t: Throwable) {
            // 控制面是「加装」能力，绑不上（例如 8788 被别的进程占了）不能拖垮整个桌面壳：
            // UI 和传输层跟它无关，仍然可用。这里吞掉异常，菜单栏的状态栏会显示端口 0。
            plane = null
            attachedRuntime = null
            sessions = null
            inboundJob?.cancel()
            inboundJob = null
        }
    }

    private fun detach() {
        plane?.stop()
        plane = null
        attachedRuntime = null
        sessions = null
        // 先停控制面再撤订阅：collector 里会调 runtime 发送（grant 回包），
        // 运行时可能紧随其后被 stop —— 留着它跑就是往死运行时里发帧。
        inboundJob?.cancel()
        inboundJob = null
    }

    private companion object {
        /**
         * 运行时生命周期不暴露事件，只能轮询。1s 足够 —— 这个窗口只影响
         * 「重启后控制面多久恢复」，不影响正确性。
         */
        const val FOLLOW_POLL_MS = 1_000L
    }
}