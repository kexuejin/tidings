// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.ScreenSessionUiState
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.CodecScreen
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.Limits
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.ScreenEndReasons
import com.kapp.tidings.core.protocol.ScreenMessage
import com.kapp.tidings.core.protocol.ScreenMode
import com.kapp.tidings.core.protocol.ScreenPhase
import com.kapp.tidings.core.protocol.ScreenRejectReasons
import com.kapp.tidings.core.protocol.ScreenRole
import com.kapp.tidings.core.protocol.ScreenSessionState
import com.kapp.tidings.core.protocol.ScreenTcpFrame
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.protocol.newScreenToken
import com.kapp.tidings.core.protocol.screenTerminalPhaseFor
import com.kapp.tidings.core.transport.ScreenFrameSource
import com.kapp.tidings.core.transport.ScreenReceiver
import com.kapp.tidings.core.transport.ScreenSender
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 投屏与屏幕协助的会话侧：发起、接受、拒绝、结束，以及帧流的收与断。
 *
 * 切出来的理由：这一族的演化动力与其它 7 个分片互不相干。过去它们都是
 * `internal fun RelayController.` 的顶层扩展，凑在一起约 3000 行；改其中一族要在
 * 另外六族里找它依赖了什么。
 *
 * @param host 提供本族用到的控制器成员与同包顶层扩展。逐个 `host.` 前缀而不是
 *   持有整个控制器，是为了让**依赖面完全显式**：读方法体就知道这一族碰了哪些状态。
 *   只对本族真正用到的名字加前缀 —— 同名的函数形参会遮蔽控制器成员（`state` 就是
 *   一例：控制器有 `state: StateFlow<RelayUiState>`，而 agent 帧形参叫 `state: String?`），
 *   给它加前缀会直接产出语法垃圾。
 */
internal class RelayControllerScreenComponent(
    private val host: RelayController,
) {

/**
 * 发起屏幕协助请求（作为 Viewer 查看对方屏幕）。
 */
fun handleRequestScreenView(peerId: String) {
    val rt = host.runtime
    if (rt == null) {
        host.warn(BannerMessage.NodeNotStarted)
        return
    }
    val current = host._state.value.screenSession.session
    if (current != null && !current.isTerminal) {
        if (current.peerId == peerId) {
            return
        }
        host.warn(BannerMessage.ScreenViewUnavailable)
        return
    }

    val peer = host._state.value.peers.firstOrNull { it.nodeId == peerId }
    if (peer == null || !peer.online) {
        host.warn(BannerMessage.ScreenViewUnavailable)
        return
    }
    // 对端必须声明支持协助与屏幕共享
    if (!peer.caps.contains(MsgTypes.CAP_REMOTE_VIEW) && !peer.caps.contains(MsgTypes.CAP_REMOTE_SHARE)) {
        host.warn(BannerMessage.ScreenViewUnavailable)
        return
    }

    // sessionId 必须是**严格 UUIDv4**：茶话间 `isScreenSessionId` 就这么校验，不合格的帧
    // 在它的 `decodeEnvelopeObject` 里就被丢掉 —— 不弹邀请、不回 ACK，发起方只能等到重传
    // 耗尽后报「屏幕协助已结束 (连接已断开)」。这里不能再用 `host.localMessageId()`
    // （`local-<seq>-<ts>`，真机 E2E 实测的根因）。
    val sessionId = newId()
    val sessionState = ScreenSessionState(
        revision = 1L,
        sessionId = sessionId,
        peerId = peerId,
        peerName = peer.nick,
        peerIp = peer.host,
        role = ScreenRole.Viewer,
        phase = ScreenPhase.Requesting,
        mode = ScreenMode.Auto,
        targetFps = 10,
        requestedAt = nowMillis(),
    )

    host._state.update {
        it.copy(
            screenSession = ScreenSessionUiState(
                session = sessionState,
                isSharingScreen = false,
            ),
        )
    }

    // 设置请求超时定时器 (60s)
    activeScreenTimeoutJob?.cancel()
    activeScreenTimeoutJob = host.scope.launch {
        delay(Limits.SCREEN_REQUEST_TIMEOUT_MS)
        val s = host._state.value.screenSession.session
        if (s != null && s.sessionId == sessionId && s.phase == ScreenPhase.Requesting) {
            handleStopScreenView(reason = ScreenRejectReasons.TIMEOUT)
        }
    }

    host.scope.launch {
        val payload = CodecScreen.screenRequestPayload(sessionId)
        val env = makeEnvelope(MsgTypes.SCREEN, host._state.value.selfNodeId, payload)
        val ok = rt.sendReliableNoQueue(peerId, env)
        if (!ok) {
            handleStopScreenView(reason = ScreenEndReasons.DISCONNECTED)
        }
    }
}

/**
 * 响应收到的屏幕协助邀请（作为 Sharer 决定是否共享屏幕）。
 */


/**
 * 响应收到的屏幕协助邀请（作为 Sharer 决定是否共享屏幕）。
 */
fun handleRespondScreenView(accept: Boolean) {
    val current = host._state.value.screenSession.session ?: return
    if (current.role != ScreenRole.Sharer || current.phase != ScreenPhase.AwaitingConsent) return

    if (!accept) {
        sendScreenReject(current.peerId, current.sessionId, ScreenRejectReasons.DECLINED)
        // 是本侧按的拒绝：declined 这个码两侧语义相反，本侧发起时是正常收场（不弹卡片、不升 yellow）。
        finishScreenSession(ScreenRejectReasons.DECLINED, localDecision = true)
        return
    }

    val sharing = host.platform.screenSharing
    if (sharing == null || !sharing.isShareSupported()) {
        sendScreenReject(current.peerId, current.sessionId, ScreenRejectReasons.UNSUPPORTED)
        // 同上：是本侧这台机器说自己不支持，不是对端不支持我。
        finishScreenSession(ScreenRejectReasons.UNSUPPORTED, localDecision = true)
        return
    }

    host._state.update {
        it.copy(
            screenSession = it.screenSession.copy(
                session = current.copy(
                    phase = ScreenPhase.Preparing,
                    revision = current.revision + 1,
                ),
            ),
        )
    }

    host.scope.launch {
        val prepOk = sharing.prepareCapture()
        val s = host._state.value.screenSession.session
        if (s == null || s.sessionId != current.sessionId || s.phase != ScreenPhase.Preparing) {
            sharing.stopCapture()
            return@launch
        }
        if (!prepOk) {
            sendScreenReject(s.peerId, s.sessionId, ScreenRejectReasons.CAPTURE_FAILED)
            finishScreenSession(ScreenRejectReasons.CAPTURE_FAILED)
            sharing.stopCapture()
            return@launch
        }

        // 采集已起来。登记「外部终止」回调：用户从系统通知里停了投射、或采集进程被系统
        // 回收时，平台侧调它结束会话。推式采集的"没新帧"是静止画面的常态，不能拿它当
        // 终止信号（缓存层会吞掉），所以终止必须由平台显式说（契约见 ScreenSharingService.onCaptureEnded）。
        sharing.onCaptureEnded {
            val cur = host._state.value.screenSession.session
            if (cur != null && cur.sessionId == current.sessionId && !cur.isTerminal) {
                handleStopScreenView(reason = ScreenEndReasons.CAPTURE_ENDED)
            }
        }

        // 生成一次性连接 token：茶话间 `isScreenToken` 要求 `/^[0-9a-f]{32}$/`，
        // 原先的 `host.localMessageId().replace("-", "")` 产出 `local1…`（含非 hex 字符、长度也不定），
        // 同样会被静默丢弃 —— TCP 侧的 screen-open 握手也过不去。
        val token = newScreenToken()
        activeScreenToken = token

        host._state.update {
            it.copy(
                screenSession = it.screenSession.copy(
                    session = s.copy(
                        phase = ScreenPhase.Connecting,
                        revision = s.revision + 1,
                    ),
                    isSharingScreen = true,
                ),
            )
        }

        // 设置连接超时定时器 (15s)
        activeScreenTimeoutJob?.cancel()
        activeScreenTimeoutJob = host.scope.launch {
            delay(Limits.SCREEN_CONNECT_TIMEOUT_MS)
            val checking = host._state.value.screenSession.session
            if (checking != null && checking.sessionId == current.sessionId && checking.phase == ScreenPhase.Connecting) {
                handleStopScreenView(reason = ScreenEndReasons.TIMEOUT)
            }
        }

        val acceptPayload = CodecScreen.screenAcceptPayload(s.sessionId, token)
        val env = makeEnvelope(MsgTypes.SCREEN, host._state.value.selfNodeId, acceptPayload)
        val sent = host.runtime?.sendReliableNoQueue(s.peerId, env) ?: false
        if (!sent) {
            handleStopScreenView(reason = ScreenEndReasons.DISCONNECTED)
        }
    }
}

/**
 * 切换查看端帧率模式。
 */


/**
 * 切换查看端帧率模式。
 */
fun handleSetScreenViewMode(mode: ScreenMode) {
    val current = host._state.value.screenSession.session ?: return
    if (current.role != ScreenRole.Viewer) return
    val newFps = when (mode) {
        ScreenMode.Economy -> 3
        ScreenMode.Standard -> 5
        ScreenMode.Smooth, ScreenMode.Auto -> 10
    }
    host._state.update {
        it.copy(
            screenSession = it.screenSession.copy(
                session = current.copy(
                    mode = mode,
                    targetFps = newFps,
                    revision = current.revision + 1,
                ),
            ),
        )
    }
    activeScreenReceiver?.setMode(mode)
}

/**
 * 结束当前屏幕协助会话。
 *
 * 失败态（[ScreenPhase.Error]）允许再走一次这条路径：那不是"重复结束"，而是用户按了错误
 * 卡片上的关闭/知道了 —— 错误卡片要能收掉，否则它会一直挂在屏幕上。收掉之后相位落到
 * [ScreenPhase.Ended]（原因改写为 [reason]），会话才真正从 UI 上退场。
 */


/**
 * 结束当前屏幕协助会话。
 *
 * 失败态（[ScreenPhase.Error]）允许再走一次这条路径：那不是"重复结束"，而是用户按了错误
 * 卡片上的关闭/知道了 —— 错误卡片要能收掉，否则它会一直挂在屏幕上。收掉之后相位落到
 * [ScreenPhase.Ended]（原因改写为 [reason]），会话才真正从 UI 上退场。
 */
fun handleStopScreenView(reason: String = ScreenEndReasons.USER) {
    val current = host._state.value.screenSession.session ?: return
    if (current.isTerminal && !current.isFailed) return

    val peerId = current.peerId
    val sessionId = current.sessionId

    finishScreenSession(reason)

    host.scope.launch {
        val endPayload = CodecScreen.screenEndPayload(sessionId, reason)
        val env = makeEnvelope(MsgTypes.SCREEN, host._state.value.selfNodeId, endPayload)
        host.runtime?.sendReliableNoQueue(peerId, env)
    }
}

/**
 * 处理入站 screen 控制信令。
 */


/**
 * 处理入站 screen 控制信令。
 */
fun handleOnScreenEnvelope(envelope: Envelope) {
    val msg = CodecScreen.decodeScreenMessage(envelope.payload) ?: return
    val peer = host._state.value.peers.firstOrNull { it.nodeId == envelope.from } ?: return
    val current = host._state.value.screenSession.session

    when (msg) {
        is ScreenMessage.Request -> {
            if (current != null && !current.isTerminal) {
                // 忙碌拒绝
                sendScreenReject(envelope.from, msg.sessionId, ScreenRejectReasons.BUSY)
                return
            }
            val newSession = ScreenSessionState(
                revision = 1L,
                sessionId = msg.sessionId,
                peerId = envelope.from,
                peerName = peer.nick,
                peerIp = peer.host,
                role = ScreenRole.Sharer,
                phase = ScreenPhase.AwaitingConsent,
                mode = ScreenMode.Auto,
                targetFps = 10,
                requestedAt = envelope.ts,
            )
            host._state.update {
                it.copy(
                    screenSession = ScreenSessionUiState(
                        session = newSession,
                        isSharingScreen = false,
                    ),
                )
            }
            // 超时自动拒绝 (60s)
            activeScreenTimeoutJob?.cancel()
            activeScreenTimeoutJob = host.scope.launch {
                delay(Limits.SCREEN_REQUEST_TIMEOUT_MS)
                val s = host._state.value.screenSession.session
                if (s != null && s.sessionId == msg.sessionId && s.phase == ScreenPhase.AwaitingConsent) {
                    sendScreenReject(envelope.from, msg.sessionId, ScreenRejectReasons.TIMEOUT)
                    // 是本侧没人应答（同意窗口到期）：不是"对端超时"，不该给本侧弹错误卡片。
                    finishScreenSession(ScreenRejectReasons.TIMEOUT, localDecision = true)
                }
            }
        }

        is ScreenMessage.Accept -> {
            if (current == null || current.sessionId != msg.sessionId || current.role != ScreenRole.Viewer || current.phase != ScreenPhase.Requesting) {
                return
            }
            activeScreenTimeoutJob?.cancel()
            host._state.update {
                it.copy(
                    screenSession = it.screenSession.copy(
                        session = current.copy(
                            phase = ScreenPhase.Connecting,
                            revision = current.revision + 1,
                        ),
                    ),
                )
            }

            // 作为 Viewer，主动建立 TCP 连接至对方绑定的 TCP 端口
            val tcpPort = peer.tcpPort
            if (tcpPort <= 0) {
                handleStopScreenView(reason = ScreenEndReasons.DISCONNECTED)
                return
            }

            val receiver = ScreenReceiver(
                host = peer.host,
                port = tcpPort,
                selfId = host._state.value.selfNodeId,
                sessionId = msg.sessionId,
                token = msg.token,
                display = { seq, bytes, width, height ->
                    host._state.update { st ->
                        st.copy(
                            screenSession = st.screenSession.copy(
                                latestFrameBytes = bytes,
                                frameWidth = width,
                                frameHeight = height,
                                frameSeq = seq,
                            ),
                        )
                    }
                },
                onReady = {
                    host._state.update { st ->
                        val cur = st.screenSession.session
                        if (cur != null && cur.sessionId == msg.sessionId) {
                            st.copy(
                                screenSession = st.screenSession.copy(
                                    session = cur.copy(
                                        phase = ScreenPhase.Active,
                                        startedAt = nowMillis(),
                                        revision = cur.revision + 1,
                                    ),
                                ),
                            )
                        } else st
                    }
                },
                onTarget = { fps ->
                    host._state.update { st ->
                        val cur = st.screenSession.session
                        if (cur != null && cur.sessionId == msg.sessionId) {
                            st.copy(
                                screenSession = st.screenSession.copy(
                                    session = cur.copy(targetFps = fps, revision = cur.revision + 1),
                                ),
                            )
                        } else st
                    }
                },
                onEnd = { reason ->
                    handleStopScreenView(reason = reason)
                },
                scope = host.scope,
            )
            activeScreenReceiver = receiver
            receiver.start()
        }

        is ScreenMessage.Reject -> {
            if (current != null && current.sessionId == msg.sessionId && current.role == ScreenRole.Viewer && current.phase == ScreenPhase.Requesting) {
                finishScreenSession(msg.reason)
            }
        }

        is ScreenMessage.End -> {
            if (current != null && current.sessionId == msg.sessionId && !current.isTerminal) {
                finishScreenSession(msg.reason)
            }
        }
    }
}

/**
 * TransferServer 收到 screen-open 握手时的处理钩子（Sharer 侧）。
 *
 * [read]/[write] 是监听器已经打开好的通道，必须原样转交给 [ScreenSender]：ktor 的 socket
 * 通道是一次性绑定的，这里若再 `socket.openReadChannel()`/`openWriteChannel()` 会抛
 * `IllegalStateException("… channel has already been set")`，异常被监听循环吞掉、socket
 * 随即关闭，Viewer 侧就看到 `reason:"disconnected"`（真机 E2E 第三个根因）。
 */


/**
 * TransferServer 收到 screen-open 握手时的处理钩子（Sharer 侧）。
 *
 * [read]/[write] 是监听器已经打开好的通道，必须原样转交给 [ScreenSender]：ktor 的 socket
 * 通道是一次性绑定的，这里若再 `socket.openReadChannel()`/`openWriteChannel()` 会抛
 * `IllegalStateException("… channel has already been set")`，异常被监听循环吞掉、socket
 * 随即关闭，Viewer 侧就看到 `reason:"disconnected"`（真机 E2E 第三个根因）。
 */
fun handleOnIncomingScreenSocket(
    socket: Socket,
    open: ScreenTcpFrame.Open,
    remoteAddress: String,
    read: ByteReadChannel,
    write: ByteWriteChannel,
): Boolean {
    val current = host._state.value.screenSession.session ?: return false
    val token = activeScreenToken ?: return false
    if (current.role != ScreenRole.Sharer || current.phase != ScreenPhase.Connecting) return false
    if (current.sessionId != open.sessionId || current.peerId != open.from || token != open.token) return false

    activeScreenToken = null
    activeScreenTimeoutJob?.cancel()

    val sharing = host.platform.screenSharing
    // 推式采集（手机的 ImageReader / 扩展帧槽）在画面静止时本来就没有新帧，返回 null 是
    // 常态而不是故障；ScreenSender 的契约却是「null ⇒ capture-ended」。不加这层缓存，
    // 手机投屏的表现是「屏幕一停就断线」。首帧都没有时仍然按原契约结束（采集真没起来）。
    val frameSource = ScreenFrameSource { seq -> sharing?.captureFrame(seq) }
    activeScreenFrameSource = frameSource
    val sender = ScreenSender(
        socket = socket,
        sessionId = open.sessionId,
        sample = { seq -> frameSource.sample(seq) },
        onEnd = { reason ->
            handleStopScreenView(reason = reason)
        },
        scope = host.scope,
        // 监听器已绑定的通道，直接复用（socket 上不能再开第二条）。
        readChannel = read,
        writeChannel = write,
    )
    activeScreenSender = sender
    sender.start()

    host._state.update {
        it.copy(
            screenSession = it.screenSession.copy(
                session = current.copy(
                    phase = ScreenPhase.Active,
                    startedAt = nowMillis(),
                    revision = current.revision + 1,
                ),
                isSharingScreen = true,
            ),
        )
    }
    return true
}

private fun sendScreenReject(peerId: String, sessionId: String, reason: String) {
    host.scope.launch {
        val payload = CodecScreen.screenRejectPayload(sessionId, reason)
        val env = makeEnvelope(MsgTypes.SCREEN, host._state.value.selfNodeId, payload)
        host.runtime?.sendReliableNoQueue(peerId, env)
    }
}

/**
 * 结束会话并落终态相位。
 *
 * [localDecision] 说的是"**这次收场是本侧自己的动作**"。`declined` / `unsupported` /
 * `timeout` 这几个码**两侧语义相反**：对端发来的同名码说的是"对端拒绝 / 不支持 / 没人
 * 应答"（对本侧是失败），本侧发出的同名码说的是"我拒绝 / 我这台机器不支持 / 我这边没人
 * 应答"（对本侧是正常收场）。同一个字符串推不出同一个相位，所以由调用点显式声明发起方，
 * 而不是在 [screenTerminalPhaseFor] 里按码打特例 —— 后者会把"对方真的拒绝了我"也一起
 * 洗成正常收场。
 *
 * 反例（本轮 verifier 抓到的自打脸）：分享方自己按「拒绝」，若落 `Error`，UI 就会给
 * **拒绝者本人**弹一张「与 <请求方> 的屏幕协助已中断 / 对方已拒绝」的卡片 —— 自己拒绝了
 * 对方，界面却说是对方拒绝了我。同形状的还有分享方答"不支持"、分享方同意窗口超时。
 */
private fun finishScreenSession(reason: String, localDecision: Boolean = false) {
    activeScreenTimeoutJob?.cancel()
    activeScreenTimeoutJob = null
    activeScreenToken = null

    activeScreenReceiver?.stop()
    activeScreenReceiver = null

    activeScreenSender?.stop()
    activeScreenSender = null

    // 置死帧源再停采集：防止残泵在 stopCapture 之后再采一拍，也保证
    // onCaptureEnded 回调若从平台线程迟到，命中的是已死的 source 而不是新会话。
    activeScreenFrameSource?.markDead()
    activeScreenFrameSource = null

    host.platform.screenSharing?.apply {
        // 先注销回调再停采集：stopCapture 的清理路径（关 VirtualDisplay / 收扩展）
        // 会同步或异步触发"采集结束"，那是我们自己停的，不该再回调进来结束一次
        // 已经结束的会话。
        onCaptureEnded(null)
        stopCapture()
    }

    val current = host._state.value.screenSession.session
    val endedAt = nowMillis()
    val duration = current?.startedAt?.let { endedAt - it }

    // 这是 ScreenPhase.Error 的唯一生产口径：失败原因落 Error、正常收场落 Ended。
    // 以前这里写死 Ended，于是"对方拒绝了""断链了"在状态机上和"我按了结束"一模一样，
    // UI 侧只判 isTerminal 就提前退场，弹窗直接消失 —— 用户永远看不到失败原因。
    // 但 reason 只在**对端发起**时才代表失败：本侧自己的决定（[localDecision]）一律正常收场。
    val terminalPhase = if (localDecision) ScreenPhase.Ended else screenTerminalPhaseFor(reason)

    host._state.update {
        it.copy(
            screenSession = it.screenSession.copy(
                session = current?.copy(
                    phase = terminalPhase,
                    reason = reason,
                    endedAt = endedAt,
                    durationMs = duration,
                    revision = current.revision + 1,
                ),
                isSharingScreen = false,
            ),
        )
    }
    // 错误要有错误的语气：失败落 warning（黄），正常结束落 host.info（灰）。
    if (terminalPhase == ScreenPhase.Error) {
        host.warn(BannerMessage.ScreenSessionEnded(reason))
    } else {
        host.info(BannerMessage.ScreenSessionEnded(reason))
    }
}
}


/**
 * [RelayController] 屏幕协助 (Screen Remote View) 扩展。
 *
 * 对齐茶话间 RemoteViewService：单节点持有一个会话、逐次授权同意、信令协调与 TCP 帧流流转。
 */

// 内部活跃会话凭据及传输组件管理
internal var activeScreenToken: String? = null

internal var activeScreenReceiver: ScreenReceiver? = null

internal var activeScreenSender: ScreenSender? = null

internal var activeScreenTimeoutJob: Job? = null

/** 推式采集的「静止重发」包装；会话结束时置死并注销（见 [ScreenFrameSource]）。 */


/** 推式采集的「静止重发」包装；会话结束时置死并注销（见 [ScreenFrameSource]）。 */
internal var activeScreenFrameSource: ScreenFrameSource? = null

/**
 * 发起屏幕协助请求（作为 Viewer 查看对方屏幕）。
 */
