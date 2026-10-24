// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.CabinetEntryUi
import com.kapp.tidings.domain.model.CabinetUiState
import com.kapp.tidings.core.fs.fileAttributes
import com.kapp.tidings.core.fs.listDirectory
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.sanitizeRelativePath
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.FilePurpose
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.ShareDenyReasons
import com.kapp.tidings.core.protocol.ShareEntry
import com.kapp.tidings.core.protocol.ShareMessage
import com.kapp.tidings.core.session.sendShareDeny
import com.kapp.tidings.core.session.sendShareGet
import com.kapp.tidings.core.session.sendShareList
import com.kapp.tidings.core.session.sendShareListOk
import com.kapp.tidings.core.transport.LocalFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 共享柜：授权、撤销、接收别人的东西。
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
internal class RelayControllerCabinetComponent(
    private val host: RelayController,
) {

/**
 * 在途的**目录列表**请求 id（`sendShareList` 的返回值）。
 *
 * 存在的理由：一条 `deny` 必须能归到**它答复的那次请求**上。过去 controller
 * 不看 reqId，一次「下载被拒」会把整张目录列表顶成错误页 —— 用户丢的是浏览上下文，
 * 而不是一次下载。
 */
private var pendingListReqId: String? = null

/**
 * 在途的列表请求是「首屏」还是「加载更多」。
 *
 * deny 的落点必须按它分：首屏被拒要显示错误（用户还没看到任何文件），
 * 加载更多被拒**不能**把已有列表顶成错误页（用户会以为文件都没了）。
 * 之前用「entries 是否为空」当代理判据 —— 首屏总数为 1 时 entries 非空，
 * 于是首屏被拒反而静默，测试 aDenyThatAnswersNoLiveDownload… 就是这么红的。
 */
private var pendingListIsLoadMore: Boolean = false

/** 在途的**下载（get）**请求 id。与 [pendingListReqId] 一起决定 deny 的落点。 */
private var pendingDownloadReqId: String? = null

/**
 * 列表请求的**代次**：每次新一轮加载 +1。
 *
 * 超时看门狗只对当前代次生效 —— 同一路径连点两次「重试」时，上一代的计时器晚到
 * 会误报「对方没有响应」（而那一次其实已经拿到了列表）。
 */
private var listRequestSeq: Long = 0

/**
 * [RelayController] 的共享文件柜 (Cabinet / Share) 域扩展。
 *
 * 覆盖本机共享目录与权限维护、同事文件柜目录浏览、条目选择与下载动作。
 */

fun handleSetShareRoot(path: String?) {
    val clean = path?.trim()?.takeIf { it.isNotEmpty() }
    persistShareSettings(host._state.value.settings.copy(shareRoot = clean))
    host.info(if (clean != null) BannerMessage.CabinetDirUpdated else BannerMessage.CabinetShareStopped)
    host.publish()
}


fun handlePickShareRoot() {
    host.scope.launch {
        val picked = runCatching { host.platform.saveDirectoryPicker.pickDirectory() }.getOrNull()
        if (picked != null) {
            handleSetShareRoot(picked)
        }
    }
}


fun handleSetShareMode(mode: String) {
    persistShareSettings(host._state.value.settings.copy(shareMode = mode))
    host.publish()
}


fun handleSetShareGrant(nodeId: String, mode: String) {
    val current = host._state.value.settings
    val updated = current.shareGrants.filterNot { it.nodeId == nodeId } + ShareGrantEntry(nodeId, mode)
    persistShareSettings(current.copy(shareGrants = updated))
    host.publish()
}


fun handleRemoveShareGrant(nodeId: String) {
    val current = host._state.value.settings
    persistShareSettings(current.copy(shareGrants = current.shareGrants.filterNot { it.nodeId == nodeId }))
    host.publish()
}

fun persistShareSettings(next: RelaySettings) {
    val current = host._state.value.settings
    val capsChanged = next.isShareEnabled() != current.isShareEnabled()
    host.handlePersist(if (capsChanged) next.copy(profileRev = next.profileRev + 1) else next)
    if (!capsChanged) return
    val rt = host.runtime ?: return
    val settings = host._state.value.settings
    rt.profile = rt.profile.copy(
        caps = settings.caps(
            remoteView = rt.profile.caps.contains(MsgTypes.CAP_REMOTE_VIEW),
            remoteShare = rt.profile.caps.contains(MsgTypes.CAP_REMOTE_SHARE),
        ),
        profileRev = settings.profileRev,
    )
    host.scope.launch { runCatching { rt.announce() } }
}


fun handleSelectCabinetPeer(nodeId: String?) {
    // 换对端 = 上一对端的回执/看门狗全部作废：代次 +1 让在途的计时器认不出自己。
    listRequestSeq++
    pendingListReqId = null
    pendingDownloadReqId = null
    host._state.update {
        it.copy(
            cabinet = it.cabinet.copy(
                activePeerId = nodeId,
                currentPath = "",
                entries = emptyList(),
                totalEntries = 0,
                // 上一对端的权限不能留给这一位：徽标写着「可上传」而对方只给只读，
                // 是拿上一台设备的授权冒充这一台的。
                currentPerm = CabinetUiState().currentPerm,
                // 换对端不继承「正在加载」：上一对端的加载态挂到新对端头上，
                // 界面会对着还没发出的请求转圈。
                loading = false,
                selectedNames = emptySet(),
                errorMessage = null,
            ),
        )
    }
    if (nodeId != null) {
        handleLoadCabinetPath("")
    }
}


fun handleLoadCabinetPath(path: String) {
    val activePeerId = host._state.value.cabinet.activePeerId ?: return
    pendingListReqId = null
    pendingListIsLoadMore = false
    val seq = ++listRequestSeq
    // 换目录时**必须清列表**：留着上一级的 entries，UI 的 `loading && entries.isEmpty()`
    // 这支就永远不成立 —— 面包屑已经是新目录，行还是旧目录的行。totalEntries 同理，
    // 陈旧的「共 N 项」会让截断提示报上一级的数字。
    host._state.update { it.copy(cabinet = cabinetPathLoadingState(it.cabinet, path)) }

    val rt = host.runtime
    if (rt == null) {
        // **节点没起来就如实说没起来。**
        //
        // 这一分支过去填的是一套编造的演示数据（docs/、contract-v2.pdf…、totalEntries=1280、
        // currentPerm="write"）—— 用户在节点未启动时打开同事的文件柜，看到的是一个
        // 根本不存在的目录。它违反本仓的第一条纪律：界面不说假话（与「诊断字段要么如实、
        // 要么不存在」同一条）。现在与 [BannerMessage.NodeNotStarted] 同一口径：
        // error 态 + 可重试，文案由 UI 层按 [CABINET_ERR_NODE_OFFLINE] 哨兵本地化。
        // totalEntries 保持 0、entries 保持空 —— 不编任何数字。
        host._state.update {
            it.copy(
                cabinet = it.cabinet.copy(
                    loading = false,
                    errorMessage = CABINET_ERR_NODE_OFFLINE,
                ),
            )
        }
        return
    }

    host.scope.launch {
        val reqId = runCatching {
            rt.sendShareList(activePeerId, path)
        }.getOrElse {
            host._state.update { s -> s.copy(cabinet = s.cabinet.copy(loading = false)) }
            return@launch
        }
        pendingListReqId = reqId
        // 看门狗：协议不保证一定回执（对端进程被杀、链路半断、旧版本静默丢弃），
        // 没有这一档用户看到的是永远转圈 —— 比「加载失败」更糟，因为不给任何出路。
        delay(CABINET_LIST_TIMEOUT_MS)
        val cur = host._state.value.cabinet
        if (cur.activePeerId == activePeerId &&
            cabinetListTimedOut(cur.loading, cur.errorMessage, seq, listRequestSeq)
        ) {
            host._state.update {
                it.copy(cabinet = it.cabinet.copy(loading = false, errorMessage = CABINET_ERR_TIMEOUT))
            }
        }
    }
}


/**
 * 「加载更多」：按 `entries.size` 作为 offset 再拉一页，**追加**（见 [cabinetEntriesAfterPage]）。
 *
 * 只有真能拉时才发：`entries.size >= totalEntries` 就什么都不做（回调本来就该被 UI 藏掉），
 * `loading` 中也不发 —— 连点两次会把同一页拉两遍，而追加虽然去重，却白跑一趟来回。
 *
 * 失败（超时 / deny）**不把已有列表顶成错误页**：用户已经看到的内容比"这次没拉到"重要。
 * 这与首屏加载的处置不同 —— 首屏失败时没有任何内容可保留。
 */
fun handleLoadMoreCabinetEntries() {
    val st = host._state.value.cabinet
    val activePeerId = st.activePeerId ?: return
    if (st.loading) return
    if (st.entries.size >= st.totalEntries) return
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    pendingListIsLoadMore = true
    val seq = ++listRequestSeq
    pendingListReqId = null
    val offset = st.entries.size
    val path = st.currentPath
    host._state.update { it.copy(cabinet = it.cabinet.copy(loading = true, errorMessage = null)) }
    host.scope.launch {
        val reqId = runCatching { rt.sendShareList(activePeerId, path, offset) }.getOrElse {
            host._state.update { s -> s.copy(cabinet = s.cabinet.copy(loading = false)) }
            return@launch
        }
        pendingListReqId = reqId
        delay(CABINET_LIST_TIMEOUT_MS)
        if (listRequestSeq == seq && host._state.value.cabinet.loading) {
            // 只收 loading，不写 errorMessage：列表还在，别用一次超时把浏览上下文顶掉。
            host._state.update { s -> s.copy(cabinet = s.cabinet.copy(loading = false)) }
        }
    }
}

fun handleToggleCabinetSelection(name: String) {
    host._state.update {
        val cur = it.cabinet.selectedNames
        val next = if (name in cur) cur - name else cur + name
        it.copy(cabinet = it.cabinet.copy(selectedNames = next))
    }
}


fun handleClearCabinetSelection() {
    host._state.update {
        it.copy(cabinet = it.cabinet.copy(selectedNames = emptySet()))
    }
}


fun handleDownloadCabinetEntries(names: List<String>) {
    if (names.isEmpty()) return
    val activePeerId = host._state.value.cabinet.activePeerId ?: return
    val cur = host._state.value.cabinet.currentPath
    val fullPaths = names.map { n -> if (cur.isEmpty()) n else "$cur/$n" }
    // 请求 id 要留下：这条请求的回执（offer / deny）必须能对上号，否则一次「下载被拒」
    // 只能靠猜落点（见 [cabinetDenyTarget]）。
    val send = shareGetSenderForTest ?: host.runtime?.let { rt ->
        { peerId: String, paths: List<String> ->
            runCatching { rt.sendShareGet(peerId, paths) }.getOrNull()
        }
    } ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.info(BannerMessage.CabinetDownloadStarted(names.size))
    host.scope.launch {
        send(activePeerId, fullPaths)?.let { reqId -> pendingDownloadReqId = reqId }
    }
}


fun handleUploadToCabinet(peerId: String) {
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.scope.launch {
        val picked = runCatching { host.platform.filePicker.pick() }.getOrNull() ?: return@launch
        val file = LocalFile(picked.path, picked.name, picked.sizeBytes)
        // 上传是一条**长任务**（挑文件 → 分片 → 等对端收），用户点完「上传」必须立刻
        // 知道它已经开始了，否则只会再点一次。`cabinet_upload_started` 文案早就备好，
        // 此前只是没有任何调用点 —— 0 消费的承诺等于没有。
        host.info(BannerMessage.CabinetUploadStarted)
        rt.offerFiles(peerId, listOf(file), purpose = FilePurpose.SHARE_PUT)
    }
}


fun handleSetCabinetViewMode(mode: String) {
    host._state.update {
        it.copy(cabinet = it.cabinet.copy(viewMode = mode))
    }
}

/**
 * 处理入站 `share` 信封（协议 §8.2）。
 */
fun handleOnShareEnvelope(envelope: Envelope) {
    val msg = Codec.decodeShareMessage(envelope.payload) ?: return
    val settings = host._state.value.settings

    when (msg) {
        is ShareMessage.ListReq -> {
            val rt = host.runtime ?: return
            val req = msg.req
            if (!settings.isShareEnabled() || settings.shareRoot.isNullOrBlank()) {
                host.scope.launch { rt.sendShareDeny(envelope.from, req.reqId, ShareDenyReasons.OFF) }
                return
            }
            val grant = settings.shareGrants.firstOrNull { it.nodeId == envelope.from }
            val mode = grant?.mode ?: settings.shareMode
            if (mode == "off") {
                host.scope.launch { rt.sendShareDeny(envelope.from, req.reqId, ShareDenyReasons.NO_PERM) }
                return
            }

            val cleanRel = if (req.path.isBlank()) "" else (sanitizeRelativePath(req.path) ?: run {
                host.scope.launch { rt.sendShareDeny(envelope.from, req.reqId, ShareDenyReasons.TOO_DEEP) }
                return
            })
            val root = settings.shareRoot
            val target = if (cleanRel.isEmpty()) root else pathJoin(root, cleanRel)
            val attrs = fileAttributes(target)
            if (attrs == null || !attrs.isDir) {
                host.scope.launch { rt.sendShareDeny(envelope.from, req.reqId, ShareDenyReasons.NOT_FOUND) }
                return
            }

            val names = listDirectory(target).filter { !it.startsWith(".") }
            val allEntries = names.mapNotNull { name ->
                val ca = fileAttributes(pathJoin(target, name)) ?: return@mapNotNull null
                ShareEntry(
                    name = name,
                    size = if (ca.isDir) 0L else ca.size,
                    isDir = ca.isDir,
                    mtime = ca.mtime,
                )
            }.sortedWith(compareByDescending<ShareEntry> { it.isDir }.thenBy { it.name.lowercase() })

            val offset = req.offset.coerceAtLeast(0)
            val pageSize = 100
            val paged = allEntries.drop(offset).take(pageSize)
            val truncated = (offset + paged.size) < allEntries.size

            host.scope.launch {
                rt.sendShareListOk(
                    peerId = envelope.from,
                    reqId = req.reqId,
                    path = req.path,
                    perm = mode,
                    snapshotId = req.snapshotId ?: req.reqId,
                    offset = offset,
                    total = allEntries.size,
                    truncated = truncated,
                    entries = paged,
                )
            }
        }

        is ShareMessage.GetReq -> {
            val get = msg.get
            val grant = settings.shareGrants.firstOrNull { it.nodeId == envelope.from }
            val mode = grant?.mode ?: settings.shareMode
            // **本机没在共享也必须回一条 deny。** 过去这里是 `settings.shareRoot ?: return`：
            // 本机停掉共享 / 清空共享目录之后，旧版对端点「下载」，发送侧横幅说完
            // 「下载 N 项」就永远等不到下文 —— 与列表请求同一档静默丢失（「没收到」的
            // 第一种时间形态）。判定抽成 [shareGetGuardReason]，与 ListReq 的 OFF/NO_PERM
            // 同一口径，而不是在这一支里另写一遍。
            val guardReason = shareGetGuardReason(
                shareEnabled = settings.isShareEnabled(),
                shareRoot = settings.shareRoot,
                mode = mode,
            )
            if (guardReason != null) {
                host.scope.launch { sendShareDeny(envelope.from, get.reqId, guardReason) }
                return
            }
            val root = settings.shareRoot ?: return
            val validFiles = mutableListOf<LocalFile>()
            for (p in get.paths) {
                val rel = if (p.isBlank()) continue else (sanitizeRelativePath(p) ?: continue)
                val full = pathJoin(root, rel)
                val fa = fileAttributes(full) ?: continue
                if (!fa.isDir && fa.size >= 0) {
                    validFiles += LocalFile(
                        path = full,
                        name = full.substringAfterLast("/"),
                        size = fa.size,
                    )
                }
            }
            // **「要了却没下文」也是静默丢失。**
            //
            // 对端请求的路径一个都拿不出来（整个选了目录、路径越界、文件已不在）时，
            // 过去这里什么都不回：发送侧横幅说完「下载 N 项」就永远等不到下文。
            // 现在回一条 deny（决策见 [shareGetDenyReason]：原因码取现成的
            // [ShareDenyReasons.NOT_FOUND] —— 「请求的内容不是可下载的文件」是这个
            // 语义集合里最贴近的一条，不扩协议）；发送侧据此把 deny reason 显示出来。
            //
            // 有可下载文件时**不 deny**：deny 是按 reqId 对整次请求的否决，
            // 与 offer 并存自相矛盾；混合请求里的目录路径随 offer 静默略过 ——
            // 新版发送侧 UI 已禁止把目录混进下载（见 PeerCabinetView 的 canDownloadSelected），
            // 这里只是对旧版对端的兼容行为。
            val denyReason = shareGetDenyReason(validCount = validFiles.size, requestedCount = get.paths.size)
            if (validFiles.isNotEmpty()) {
                val rt = host.runtime ?: return
                host.scope.launch {
                    rt.offerFiles(envelope.from, validFiles, purpose = FilePurpose.SHARE_GET)
                }
            } else if (denyReason != null) {
                host.scope.launch { sendShareDeny(envelope.from, get.reqId, denyReason) }
            }
        }

        is ShareMessage.ListOk -> {
            val res = msg.res
            if (host._state.value.cabinet.activePeerId == envelope.from && host._state.value.cabinet.currentPath == res.path) {
                val mapped = res.entries.map {
                    CabinetEntryUi(
                        name = it.name,
                        isDir = it.isDir,
                        size = it.size,
                        mtime = it.mtime,
                    )
                }
                // **追加还是替换由 offset 决定**（见 [cabinetEntriesAfterPage]）：
                // 一页一页往后拿时，后一页绝不能把前一页的行顶掉。
                host._state.update {
                    it.copy(
                        cabinet = cabinetEntriesAfterPage(
                            prev = it.cabinet,
                            offset = res.offset,
                            total = res.total,
                            perm = res.perm,
                            page = mapped,
                        ),
                    )
                }
                // 这条请求已经有下文了：它的 id 不能再拿来认领后续的 deny。
                pendingListReqId = null
            }
        }

        is ShareMessage.Deny -> {
            val deny = msg.deny
            if (host._state.value.cabinet.activePeerId != envelope.from) return
            when (cabinetDenyTarget(deny.reqId, pendingListReqId, pendingDownloadReqId)) {
                CabinetDenyTarget.List -> {
                    pendingListReqId = null
                    host._state.update {
                        // 「加载更多」被拒时列表里已经有内容：这一条 deny 只该结束这次加载，
                        // **不该**把整张列表顶成错误页（用户会以为文件都没了）。
                        it.copy(
                            cabinet = it.cabinet.copy(
                                loading = false,
                                errorMessage = if (pendingListIsLoadMore) it.cabinet.errorMessage else deny.reason,
                            ),
                        )
                    }
                }
                // 一次「下载被拒」只影响那次下载：**不许**写 cabinet.errorMessage ——
                // 那是目录列表的槽，过去它会把整张列表顶成错误页（用户丢的是浏览上下文）。
                CabinetDenyTarget.Download -> {
                    pendingDownloadReqId = null
                    host.warn(BannerMessage.CabinetDownloadDenied)
                }
            }
        }
    }
}

/**
 * 回一条 `deny`；传输缺席（无运行时）或测试接缝生效时不炸。
 *
 * 为什么单独一个方法而不是每处 `rt.sendShareDeny(...)`：拒绝路径必须在**没有运行时**
 * 时也可观测 —— 否则「回没回 deny」在外部看起来与静默丢弃完全一样（本仓
 * 「删掉 deny 处理仍然全绿」就是这么来的）。
 */
private suspend fun sendShareDeny(peerId: String, reqId: String, reason: String) {
    val seam = shareDenySenderForTest
    if (seam != null) {
        seam(peerId, reqId, reason)
        return
    }
    host.runtime?.sendShareDeny(peerId, reqId, reason)
}
}

/**
 * `cabinet.errorMessage` 的**本地哨兵**：本机节点未启动。
 *
 * 它不是对端回的 deny 原因（协议 [ShareDenyReasons] 里没有也不该有「我没开机」这一条 ——
 * 那是本机事实，不走 wire）。UI 层（PeerCabinetView）按这个哨兵把错误映射成
 * `node_not_started` 文案；两个模块共享这一个常量， token 字面量不许在两侧各写一遍。
 */
public const val CABINET_ERR_NODE_OFFLINE: String = "node-offline"

/**
 * `get` 请求的**回执决策**（纯函数，可单测钉住）。
 *
 * 对端请求的路径一个都拿不出来时必须回 deny（「发了没下文」就是静默丢失）；
 * 有可下载文件时回 offer、不再 deny —— deny 是按 reqId 对整次请求的否决，与 offer 并存自相矛盾。
 *
 * @param validCount 请求里真正可下载的文件数。
 * @param requestedCount 对端请求的路径总数（0 = 对端什么都没要，保持沉默不是丢上下文）。
 * @return 要回的 deny 原因码；null = 不回 deny（offer 或无事可说）。
 */
internal fun shareGetDenyReason(validCount: Int, requestedCount: Int): String? =
    if (validCount == 0 && requestedCount > 0) ShareDenyReasons.NOT_FOUND else null

/**
 * `cabinet.errorMessage` 的**本地哨兵**：对端在 [CABINET_LIST_TIMEOUT_MS] 内没有回执。
 *
 * 与 [CABINET_ERR_NODE_OFFLINE] 同档：这是本机观察到的事实（等不到回执），不走 wire，
 * 也不假装知道对端那边发生了什么。UI 层按它映射成 `cabinet_reason_timeout`。
 */
public const val CABINET_ERR_TIMEOUT: String = "timeout"

/**
 * 目录列表等回执的上限。
 *
 * 为什么必须有：`loading` 只在收到 `list.ok` / `deny` 时才被清掉，而协议里没有
 * 「一定回执」的保证（对端进程被杀、链路半断、旧版本直接静默丢弃）。缺了这一档，
 * 界面表现为**永远转圈** —— 比「加载失败」更糟，因为它不给任何出路。
 */
internal const val CABINET_LIST_TIMEOUT_MS: Long = 12_000L

/**
 * 收到一页 `list-ok` 之后目录该怎么变 —— **追加还是替换只看 offset**。
 *
 * 为什么必须有这条判据：文件柜一页最多 100 项，服务端按 offset 分页（`share list` 的
 * `offset/total/truncated`）。页 2 到达时若照旧"替换"，用户点「加载更多」的结果是
 * **前 100 行消失、只剩后一页** —— 比没有这个按钮更糟。
 *
 * 追加时按 name 去重：两次请求之间对端可能新增/删除文件，页 2 的尾部会与页 1 重叠；
 * 去重保证"同一条只出现一次"，顺序保持服务端给的顺序（它已排序）。
 * `total` 取服务端值（权威），但不允许小于当前行数 —— 否则界面会显示"共 3 项"却有 100 行。
 */
internal fun cabinetEntriesAfterPage(
    prev: CabinetUiState,
    offset: Int,
    total: Int,
    perm: String,
    page: List<CabinetEntryUi>,
): CabinetUiState {
    val merged = if (offset <= 0) {
        page
    } else {
        val seen = prev.entries.mapTo(HashSet()) { it.name }
        prev.entries + page.filterNot { it.name in seen }
    }
    return prev.copy(
        entries = merged,
        totalEntries = total.coerceAtLeast(merged.size),
        currentPerm = perm,
        loading = false,
        errorMessage = null,
    )
}

/**
 * 切到 [path] 时的**清态**（纯函数，可单测钉住）。
 *
 * 三件事一起做，缺一条界面就在说谎：换目录要清 `entries`（否则画着上一级的行、
 * 配着新面包屑）、清 `totalEntries`（否则截断提示报上一级的数字）、置 `loading`
 * （否则新目录在回执到达前被画成「空目录」）。
 */
internal fun cabinetPathLoadingState(prev: CabinetUiState, path: String): CabinetUiState =
    prev.copy(
        currentPath = path,
        entries = emptyList(),
        totalEntries = 0,
        // 新目录的权限还没问到：沿用上一档就会在只读目录上继续挂着「可上传」。
        currentPerm = CabinetUiState().currentPerm,
        loading = true,
        errorMessage = null,
        selectedNames = emptySet(),
    )

/**
 * 等回执超时的判定（纯函数，可单测钉住）。
 *
 * @param seq 发起这次加载时的代次。
 * @param latestSeq 当前最新代次 —— 不相等说明用户已经换了目录/对端，这一代作废。
 * @return true = 这次加载确实超时了，该落成可重试的错误态。
 */
internal fun cabinetListTimedOut(
    loading: Boolean,
    errorMessage: String?,
    seq: Long,
    latestSeq: Long,
): Boolean = loading && errorMessage == null && seq == latestSeq

/** 一条 `deny` 答复的是哪一类请求。 */
internal enum class CabinetDenyTarget {
    /** 目录列表请求 —— 落成列表错误态（可重试）。 */
    List,

    /** 下载（get）请求 —— 只影响那次下载，**不许**顶掉目录列表。 */
    Download,
}

/**
 * 按 reqId 把一条 `deny` 归到**它答复的那次请求**上（纯函数，可单测钉住）。
 *
 * 真身：`deny` 曾经只看 `activePeerId`，于是「下载被拒」把整张目录列表顶成错误页 ——
 * 用户丢的是浏览上下文，而不是一次下载。列表优先于下载（同一 id 不可能属于两个在途
 * 请求；真撞上时按列表处理，因为列表槽才是屏幕上那一屏）。
 *
 * 对不上任何在途请求时**仍按列表处理**：与旧行为一致，且「拒绝」总要有个可重试的出口 ——
 * 静默丢掉会让加载停在转圈（真正没人回执的那一档由 [CABINET_LIST_TIMEOUT_MS] 兜）。
 */
internal fun cabinetDenyTarget(
    denyReqId: String,
    listReqId: String?,
    downloadReqId: String?,
): CabinetDenyTarget = when {
    denyReqId == listReqId -> CabinetDenyTarget.List
    denyReqId == downloadReqId -> CabinetDenyTarget.Download
    else -> CabinetDenyTarget.List
}

/**
 * `get` 请求的**前置闸门**（纯函数，可单测钉住）：本机到底能不能为这次请求拿文件。
 *
 * 与 ListReq 同一口径：本机停掉共享 / 共享目录被清空 ⇒ OFF；这位对端被关掉权限 ⇒ NO_PERM。
 * 过去 GetReq 在共享目录为空时直接 `return`，发送侧永远等不到下文 —— 正是
 * 「发了没下文」那一族。
 *
 * @return 要回的 deny 原因码；null = 放行，去挑文件。
 */
internal fun shareGetGuardReason(
    shareEnabled: Boolean,
    shareRoot: String?,
    mode: String,
): String? = when {
    !shareEnabled || shareRoot.isNullOrBlank() -> ShareDenyReasons.OFF
    mode == "off" -> ShareDenyReasons.NO_PERM
    else -> null
}

/**
 * 测试接缝：把 `share` 的 deny 回执从真实传输上摘下来（照 `ackSenderForTest` 的形状）。
 *
 * 为什么需要：`sendUserMessage` 把可预期的失败折进返回值，真实传输造不出「到底回没回
 * 一条 deny」这条断言 —— 在无运行时的控制器上，拒绝路径与静默路径在外部完全一样。
 */
internal var shareDenySenderForTest: (suspend (peerId: String, reqId: String, reason: String) -> Unit)? = null

/**
 * 测试接缝：把 `share get` 的发送换成可控实现并交回一个请求 id。
 *
 * 存在的理由同 [shareDenySenderForTest]：没有它，`pendingDownloadReqId` 永远拿不到值，
 * 「下载被拒只影响那次下载」这条纪律就无法在行为层被钉住。
 */
internal var shareGetSenderForTest: (suspend (peerId: String, paths: List<String>) -> String?)? = null
