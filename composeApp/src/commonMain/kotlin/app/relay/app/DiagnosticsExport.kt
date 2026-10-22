// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.fs.deleteFile
import app.relay.core.fs.fileSize
import app.relay.core.fs.makeDirectories
import app.relay.core.fs.openRandomAccess
import app.relay.core.fs.pathJoin
import app.relay.core.fs.use
import app.relay.core.nowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导出结果。**失败原因用枚举而不是字符串**：用户文案归资源文件（`:cjkLiteralGate` 与
 * 多语言对账都要求文案在 `composeResources` 里），导出逻辑只报"哪一步不行"。
 */
sealed interface DiagnosticsExportResult {

    /**
     * 写成功了。
     *
     * @param fileName 落在 `<dataDir>/downloads/` 里的文件名（用户要能在分享面板里认出它）。
     * @param byteCount 包大小（字节）。界面上折成 KiB/MiB 显示。
     * @param entryCount zip 里的条目数（summary 自己也算一条）。
     * @param shared 是否真的交给了系统分享面板。false = 本平台没有 `ReceivedFileActions`
     *   （桌面由自己的导出实现承担），文件仍然写出来了，页面据此换一句话，而不是报失败。
     */
    data class Exported(
        val fileName: String,
        val byteCount: Int,
        val entryCount: Int,
        val shared: Boolean,
    ) : DiagnosticsExportResult

    data class Failed(val reason: DiagnosticsExportFailure) : DiagnosticsExportResult
}

enum class DiagnosticsExportFailure {
    /** 写 zip 失败（磁盘满、目录不可写、被系统拦）。 */
    WriteFailed,

    /** 打包过程中出了预期外的错（序列化、内存）。区别于 WriteFailed：文件可能没生成，也可能写了一半。 */
    Unexpected,
}

/**
 * 手机端「导出诊断包」：把 `diag/link.json` 与 `stores/` 下的日志做成
 * `<dataDir>/downloads/relay-diag-<UTC>.zip`，再交给系统分享面板。
 *
 * ## 为什么落在 downloads/
 *
 * Android 的文件契约只有一条路：`file_paths.xml` 里声明了 `relay/downloads/`，而
 * `file_paths.xml` 是全仓唯一允许 FileProvider 签发的目录（见 `AndroidReceivedFileActions`
 * 的 KDoc）。把包写进这里，就是**复用**既有的 `ReceivedFileActions.share` —— 不新增
 * SAF 授权流程、不改 manifest、不给外部存储开口子。写别处会让 `FileProvider.getUriForFile`
 * 抛 `IllegalArgumentException`，那条路在本仓被明确拒绝过（"宁可少一个按钮能用，
 * 也不放开整棵外部存储"）。
 *
 * ## 为什么读文件也是"有上限的尾巴"
 *
 * `stores/messages.log` 在真机上到过十几 MB，`queue.log` 会压着几十条重试。导出要的是
 * 最近发生了什么，不是从第一天开始的全量；同时读整个文件会在低端机上直接顶内存。
 * 所以每份日志只读尾部 [DIAG_READ_TAIL_BYTES] 字节，再由 [DiagnosticsPackage] 做行数/字节双限
 * 与脱敏。
 *
 * 整个函数**不抛异常**：导出失败是用户点一下按钮的结果，不该把界面掀掉。
 */
suspend fun RelayController.exportDiagnosticsPackage(): DiagnosticsExportResult =
    withContext(Dispatchers.Default) {
        runCatching { exportDiagnosticsPackageOrThrow() }
            .getOrElse { error ->
                if (error is DiagnosticsWriteFailure) {
                    DiagnosticsExportResult.Failed(DiagnosticsExportFailure.WriteFailed)
                } else {
                    DiagnosticsExportResult.Failed(DiagnosticsExportFailure.Unexpected)
                }
            }
    }

private class DiagnosticsWriteFailure : Exception()

private fun RelayController.exportDiagnosticsPackageOrThrow(): DiagnosticsExportResult {
    val snapshot = state.value
    val generatedAt = nowMillis()
    val environment = DiagnosticsEnvironment(
        appVersion = version,
        nodeId = snapshot.settings.nodeId,
        deviceNick = snapshot.settings.nick,
        transport = snapshot.network.transport.name,
        localAddresses = snapshot.network.localAddresses,
        advertisedAddresses = snapshot.network.advertisedAddresses,
        vpnActive = snapshot.network.vpnActive,
        peerTotal = snapshot.peers.size,
        peerOnline = snapshot.onlineCount,
        pending = snapshot.totalPending,
        persistMessages = snapshot.settings.persistMessages,
        peers = snapshot.peers.map { peer ->
            DiagPeer(
                nodeId = peer.nodeId,
                nick = peer.nick,
                platform = peer.platform,
                online = peer.online,
                isAgent = peer.isAgent,
                tcpPort = peer.tcpPort,
                // **对端声明过的全部地址**（LAN / IPv6 / Tailscale / ZeroTier）。
                //
                // 来源是候选池（`NodeRuntime.candidateEndpoints`），不是 `peer.ip` ——
                // 后者只是**当前被采纳的那一个**。排查「连不上」要看「还有哪些没试」，
                // 只有候选池能回答。一次 VPN 失联排查里就是靠手工读 known-peers.json
                // 反推这个信息的，那本该是诊断包直接给的东西。
                addresses = runCatching {
                    runtime?.candidateEndpoints()?.get(peer.nodeId)?.map { it.host }
                }.getOrNull().orEmpty(),
                // **本机在拨哪个地址**（`host:port`）。与上面配合才能看出「有 IPv6 候选
                // 却一直在试旧 LAN 地址」这类问题；null = 还没拨过或对端在线。
                dialTarget = runCatching {
                    runtime?.linkSnapshot()?.dial?.get(peer.nodeId)?.lastTarget
                }.getOrNull(),
            )
        },
    )

    val bundle = buildDiagnosticsBundle(
        environment = environment,
        // link.json 是整份重写的链路读数（run-as cat 的那个文件），原样收进来。
        linkJson = readTailText(linkDiagnosticsPath, maxBytes = DIAG_LINK_MAX_BYTES),
        queueLog = readTailText(pathJoin(dataDirectory, "stores", "queue.log"), maxBytes = DIAG_READ_TAIL_BYTES),
        messagesLog = readTailText(pathJoin(dataDirectory, "stores", "messages.log"), maxBytes = DIAG_READ_TAIL_BYTES),
        generatedAtMillis = generatedAt,
    )

    val zip = buildDiagnosticsZip(bundle.entries, generatedAt)
    val fileName = "relay-diag-" + diagStampUtc(generatedAt) + ".zip"
    val directory = pathJoin(dataDirectory, "downloads")
    val target = pathJoin(directory, fileName)
    makeDirectories(directory)
    // 同一秒重复导出时覆盖旧包（否则 FileProvider 会拿到上一份，看起来像"导出没反应"）。
    deleteFile(target)
    val written = runCatching {
        openRandomAccess(target, forWrite = true).use { file ->
            file.append(zip, zip.size)
            file.flush()
        }
        true
    }.getOrDefault(false)
    if (!written) throw DiagnosticsWriteFailure()

    val shared = ReceivedFileSharing.share(this, target)
    return DiagnosticsExportResult.Exported(
        fileName = fileName,
        byteCount = zip.size,
        entryCount = bundle.entries.size,
        shared = shared,
    )
}

/**
 * 交给系统分享面板；平台没有这条出口时返回 false（不是失败）。
 *
 * 抽一层只为了把"平台能力缺失"与"这次分享失败"分开：`ReceivedFileActions` 的契约是
 * **不抛异常**（做不到就什么都没发生），所以这里只需要问"有没有这个能力"。
 */
private object ReceivedFileSharing {
    fun share(controller: RelayController, path: String): Boolean {
        val actions = controller.receivedFileActions ?: return false
        runCatching { actions.share(path) }
        return true
    }
}

/**
 * 读文件的**尾部** [maxBytes] 字节并解码。
 *
 * 从尾部读而不是从头读，理由与日志脱敏一致：诊断要最近发生的。窗口起点落在某一行中间时，
 * 把第一段不完整的行丢掉（它的前半截不在窗口里，拼出来的是一条假记录 —— 正是本仓最忌讳的
 * "看起来有、其实不是那条"）。整个窗口里一个换行都没有时返回 null：那说明这一行比窗口还长，
 * 与其导出半条，不如如实说没读出来。
 */
private fun readTailText(path: String, maxBytes: Long): String? {
    val size = fileSize(path) ?: return null
    if (size <= 0L) return null
    val want = minOf(size, maxBytes)
    val start = size - want
    val handle = runCatching { openRandomAccess(path, forWrite = false) }.getOrNull() ?: return null
    try {
        val chunks = ArrayList<ByteArray>()
        var got = 0L
        val buffer = ByteArray(READ_CHUNK_BYTES)
        while (got < want) {
            val request = minOf(buffer.size.toLong(), want - got).toInt()
            val read = handle.read(buffer, start + got, request)
            if (read <= 0) break
            chunks += buffer.copyOf(read)
            got += read
        }
        if (got <= 0L) return null
        var bytes = ByteArray(got.toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        if (start > 0L) {
            val firstNewline = bytes.indexOf(NEWLINE)
            if (firstNewline < 0) return null
            bytes = bytes.copyOfRange(firstNewline + 1, bytes.size)
        }
        if (bytes.isEmpty()) return null
        return bytes.decodeToString()
    } catch (_: Throwable) {
        return null
    } finally {
        runCatching { handle.close() }
    }
}

/** 每份日志最多读这么多字节（脱敏前）。再大对低端机就是负担，且远超 256 KiB 的入库上限。 */
private const val DIAG_READ_TAIL_BYTES: Long = 1024L * 1024

/** link.json 是整份重写的小文件，不需要 1 MiB 的窗口。 */
private const val DIAG_LINK_MAX_BYTES: Long = 256L * 1024

private const val READ_CHUNK_BYTES: Int = 64 * 1024

private val NEWLINE: Byte = '\n'.code.toByte()
