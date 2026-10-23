// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.cli

import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.RelaySettings
import com.kapp.tidings.domain.RelayUiTexts
import com.kapp.tidings.domain.createAgentStore
import com.kapp.tidings.domain.createMessageStore
import com.kapp.tidings.domain.createSettingsStore
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.NodePhase
import com.kapp.tidings.domain.model.ScreenRecordUi
import com.kapp.tidings.core.protocol.DEFAULT_TIMINGS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一个**没有 UI** 的 Tidings 客户端。
 *
 * ## 它不是产品功能，是验收物
 *
 * 阶段 2 的判据是「UI 是不是真的只是观察者」。分模块本身证明不了这件事 ——
 * 能被证明的只有：**这个程序里根本没有 Compose**，而它照样起控制器、发消息、读收件箱。
 *
 * 它的编译面只有 `:app`（传递 `:core`）。如果哪天有人往 `:app` 里加一行 Compose
 * 依赖，这个模块就会变成拖着一整套 UI 栈 —— 那正是这条验收要拦住的事。
 *
 * ## 用法
 *
 *     :cli:run -- --send <peerId> <text>
 *     :cli:run -- --inbox [--since <epochMillis>] [--limit <n>]
 *     :cli:run -- --peers
 *     :cli:run -- --link
 *
 * ## 为什么这里**没有** `--serve`
 *
 * 控制面（`:agentNode` 的 `ControlPlane`）要注入四本台账 —— 授权、工作区、执行策略、
 * 会话。那四本台账现在由 **desktopApp** 装配（它是本机 agent 节点的宿主）。在
 * `:cli` 里重装一遍，就等于让这个「验收物」依赖 desktopApp，而验收的全部意义是
 * 「**只有** :app」。把依赖加进去，验收就变成了自证。
 *
 * 控制面那条路已由 desktopApp 的 `--headless` 形态在验（它不组合 Compose）。
 */
fun main(args: Array<String>): Unit = runBlocking {
    val opts = CliOptions.parse(args)
    if (opts.help || args.isEmpty()) {
        print(CliOptions.USAGE)
        return@runBlocking
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val platform = CliPlatform(opts.data)
    val settingsStore = createSettingsStore(platform)
    // 端口可指定：同一个主机上跑 CLI、桌面端与测试时，默认端口会被抢占。
    // 这不是「新增一个设置项」—— 它是命令行工具的必要参数，不改产品默认值。
    val baseSettings = settingsStore.load() ?: RelaySettings.firstRun()
    if (opts.udpPort != null) {
        // TCP 默认跟 UDP 相邻（+1）：两个实例各自绑一对，撞端口的概率与只让 UDP 撞一样低。
        settingsStore.save(
            baseSettings.copy(
                udpPort = opts.udpPort,
                tcpPort = opts.tcpPort ?: (opts.udpPort + 1),
            ),
        )
    } else if (opts.tcpPort != null) {
        settingsStore.save(baseSettings.copy(tcpPort = opts.tcpPort))
    }
    val controller = RelayController(
        platform = platform,
        store = settingsStore,
        messageStore = createMessageStore(platform),
        agentStore = createAgentStore(platform),
        scope = scope,
        timings = DEFAULT_TIMINGS,
        advertiseAgent = false,
    )
    // 领域层要的文案由宿主提供。CLI 不引 Compose，所以用最朴素的实现 ——
    // 这也正是 RelayUiTexts 作为接口的价值：同一份领域逻辑跑在两个措辞不同的宿主上。
    controller.uiTexts = PlainTextUiTexts

    controller.start()
    // 运行时是异步建的；等它真的起来，否则第一条 send 会撞上「还没起」。
    val up = withTimeoutOrNull(15_000) {
        while (controller.state.value.network.phase != NodePhase.Running) delay(200)
        true
    } ?: false
    if (!up) {
        // 失败要如实说清楚：phase 是什么、有没有 failure 横幅。
        // 「起不来」与「起得慢」是两件事，只报前者会让人以为后者也不存在。
        val net = controller.state.value.network
        System.err.println("relay-cli: runtime did not reach Running within 15s")
        System.err.println("relay-cli: phase=" + net.phase.name + " failure=" + net.failure)
        controller.stop()
        scope.cancel()
        kotlin.system.exitProcess(2)
    }

    // 登记邻居：必须在 start() 之后（运行时已就位），否则 addManualPeer 无处可写。
    // 失败要报错而不是静默跳过 —— 静默的后果是"发了但没到"，很难查。
    for (spec in opts.peersToAdd) {
        val parts = spec.split(":")
        if (parts.size != 2 || parts[1].toIntOrNull() == null) {
            System.err.println("relay-cli: bad --peer (want HOST:PORT): " + spec)
            controller.stop()
            scope.cancel()
            kotlin.system.exitProcess(2)
        }
        controller.addManualPeer(parts[0], parts[1])
    }

    val exit = try {
        when {
            opts.listen -> runListen(controller, opts)
            opts.link -> runLink(controller)
            opts.sendTo != null -> runSend(controller, opts)
            opts.inbox -> runInbox(controller, opts)
            opts.peers -> runPeers(controller)
            else -> { print(CliOptions.USAGE); 0 }
        }
    } finally {
        controller.stop()
        scope.cancel()
    }
    kotlin.system.exitProcess(exit)
}

/**
 * 保持在线，让**另一个**节点能找到它、把消息投进来。
 *
 * 这条不是"守护进程功能"，而是对拍的另一半：`--send` 需要有人接收。
 * 打印收到的每一条（含首次出现的会话），并且 `--ttl` 到期就退 ——
 * 一个永远不会自己结束的命令行程序在脚本里没法用。
 */
private suspend fun runListen(controller: RelayController, opts: CliOptions): Int {
    val selfId = controller.state.value.selfNodeId
    println("relay-cli: listening as " + selfId + " (ttl " + (opts.ttlMs / 1000) + "s)")
    val known = mutableSetOf<String>()
    val deadline = System.currentTimeMillis() + opts.ttlMs
    var received = 0
    while (System.currentTimeMillis() < deadline) {
        for ((convId, list) in controller.state.value.messages) {
            for (msg in list) {
                if (msg.mine) continue
                val key = convId + "/" + msg.id
                if (!known.add(key)) continue
                println("relay-cli: received from=" + msg.peerId.take(8) + " text=" + msg.text)
                received++
            }
        }
        delay(200)
    }
    println("relay-cli: received " + received + " message(s), peers=" + controller.state.value.peers.size)
    // 一条都没收到也要成功退出：TTL 到期是正常结束，不是失败。
    return 0
}

/** 链路读数。这是「领域层在无 UI 时真的在跑」最直接的一条判据。 */
private fun runLink(controller: RelayController): Int {
    val net = controller.state.value.network
    println("relay-cli: phase=" + net.phase.name);
    println("relay-cli: transport=" + net.transport.name);
    println("relay-cli: addresses=" + net.advertisedAddresses.joinToString(","));
    println("relay-cli: udpPort=" + net.boundUdpPort);
    println("relay-cli: tlsPin=" + (net.selfTlsPin ?: "none"));
    println("relay-cli: peers=" + controller.state.value.peers.size);
    return if (net.phase == NodePhase.Running) 0 else 1
}

private suspend fun runSend(controller: RelayController, opts: CliOptions): Int {
    val peer = opts.sendTo!!
    val body = opts.text.orEmpty()
    // **先等目标上线**，否则 send 只会进离线队列（delivery=Queued），然后这台进程
    // 立刻就退出了 —— 队列里那条再也不会有人来发。
    //
    // 这不是"给 CLI 开小灶"：产品里界面一直活着，队列会被重连/上线事件驱动发出去；
    // 而一个活 20 秒的命令行进程必须自己等，否则它报的"Queued"是必然结果，
    // 不是网络的错。
    val online = withTimeoutOrNull(15_000) {
        while (controller.state.value.peers.none { it.nodeId == peer }) delay(100)
        true
    } ?: false
    if (!online) {
        System.err.println("relay-cli: peer " + peer + " never came online within 15s")
        System.err.println("relay-cli: known peers=" + controller.state.value.peers.map { it.nodeId.take(8) })
        return 1
    }
    controller.sendToConversation(peer, body, null)
    // **等回执，而不是「调用返回就算发出去了」**。
    //
    // 这是本仓最贵的一类缺陷的正面写法：`send` 只是把消息放进队列，返回时什么都
    // 还没发生。若 CLI 在这里就报成功，它就成了一个「发送侧一切正常、接收侧什么都没有」
    // 的样板 —— 而那正是这个仓库反复付代价的那种缺陷。所以等 `DeliveryState.Sent`，
    // 超时就如实报失败并返回非零退出码。
    val ok = runBlocking {
        withTimeoutOrNull(20_000) {
            while (true) {
                val sent = controller.state.value.messages[peer].orEmpty().any {
                    it.text == body && it.delivery == DeliveryState.Sent
                }
                if (sent) break
                delay(100)
            }
        } != null
    }
    val latest = controller.state.value.messages[peer].orEmpty().lastOrNull { it.text == body }
    println("relay-cli: send -> " + peer + " delivery=" + (latest?.delivery ?: "absent"))
    if (!ok) {
        System.err.println("relay-cli: not delivered within 20s (queued, peer may be offline)")
        return 1
    }
    return 0
}

private fun runInbox(controller: RelayController, opts: CliOptions): Int {
    var printed = 0
    val all = controller.state.value.messages.values.flatten()
        .filter { it.at >= opts.since }
        .sortedBy { it.at }
    for (msg in all) {
        if (printed >= opts.limit) break
        println(msg.at.toString() + "\t" + msg.peerId + "\t" + (if (msg.mine) "out" else "in") + "\t" + msg.text.take(80))
        printed++
    }
    println("relay-cli: " + printed + " message(s)")
    // 「0 条」与「读不到」必须能分开：没有这一行，空收件箱和读失败长得一模一样。
    println("relay-cli: conversations=" + controller.state.value.messages.size + " peers=" + controller.state.value.peers.size)
    return 0
}

private fun runPeers(controller: RelayController): Int {
    val peers = controller.state.value.peers
    for (peer in peers) {
        println(peer.nodeId.take(8) + "\t" + peer.displayName + "\t" + (if (peer.online) "online" else "offline"))
    }
    println("relay-cli: " + peers.size + " peer(s)")
    return 0
}

/**
 * CLI 的文案实现：纯 ASCII、不碰任何资源系统。
 *
 * 它是 RelayUiTexts「换一个宿主就换一套措辞」那条设计的实例 —— 桌面与手机用
 * `ResourceUiTexts`（走 strings.xml + 多语言），CLI 用这一份。
 */
internal object PlainTextUiTexts : RelayUiTexts {
    override suspend fun screenCardText(record: ScreenRecordUi): String =
        "screen " + record.phase.name.lowercase()
    override suspend fun nudgeNotice(): String = "nudge"
    override suspend fun workspaceGreeting(agentKind: String, workspaceTitle: String): String =
        "[" + agentKind + "] " + workspaceTitle
    override suspend fun workspaceSessionBrief(sessionTitle: String, busy: Boolean): String =
        sessionTitle + (if (busy) " busy" else " ready")
}
