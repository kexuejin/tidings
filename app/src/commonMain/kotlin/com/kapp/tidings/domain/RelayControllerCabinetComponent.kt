// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.CabinetEntryUi
import com.kapp.tidings.core.fs.fileAttributes
import com.kapp.tidings.core.fs.listDirectory
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.sanitizeRelativePath
import com.kapp.tidings.core.nowMillis
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
    host._state.update {
        it.copy(
            cabinet = it.cabinet.copy(
                activePeerId = nodeId,
                currentPath = "",
                entries = emptyList(),
                totalEntries = 0,
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
    host._state.update {
        it.copy(
            cabinet = it.cabinet.copy(
                currentPath = path,
                loading = true,
                errorMessage = null,
                selectedNames = emptySet(),
            ),
        )
    }

    val rt = host.runtime
    if (rt == null) {
        val mockEntries = if (path.isEmpty()) {
            listOf(
                CabinetEntryUi(name = "docs", isDir = true, size = 0, mtime = nowMillis() - 3600_000),
                CabinetEntryUi(name = "contract-v2.pdf", isDir = false, size = 2516582, mtime = nowMillis() - 7200_000),
                CabinetEntryUi(name = "interview-notes.md", isDir = false, size = 38912, mtime = nowMillis() - 86400_000),
                CabinetEntryUi(name = "drawings", isDir = true, size = 0, mtime = nowMillis() - 90000_000),
                CabinetEntryUi(name = "board.png", isDir = false, size = 1992294, mtime = nowMillis() - 172800_000),
            )
        } else {
            listOf(
                CabinetEntryUi(name = "prd-spec.docx", isDir = false, size = 1048576, mtime = nowMillis() - 1800_000),
                CabinetEntryUi(name = "architecture.drawio", isDir = false, size = 524288, mtime = nowMillis() - 3600_000),
            )
        }
        host._state.update {
            it.copy(
                cabinet = it.cabinet.copy(
                    entries = mockEntries,
                    totalEntries = if (path.isEmpty()) 1280 else mockEntries.size,
                    currentPerm = "write",
                    loading = false,
                ),
            )
        }
        return
    }

    host.scope.launch {
        runCatching {
            rt.sendShareList(activePeerId, path)
        }.onFailure {
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


fun handleDownloadCabinetEntries(names: List<String>, saveAs: Boolean) {
    if (names.isEmpty()) return
    val activePeerId = host._state.value.cabinet.activePeerId ?: return
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    val cur = host._state.value.cabinet.currentPath
    val fullPaths = names.map { n -> if (cur.isEmpty()) n else "$cur/$n" }
    host.info(BannerMessage.CabinetDownloadStarted(names.size))
    host.scope.launch {
        runCatching { rt.sendShareGet(activePeerId, fullPaths) }
    }
}


fun handleUploadToCabinet(peerId: String) {
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    host.scope.launch {
        val picked = runCatching { host.platform.filePicker.pick() }.getOrNull() ?: return@launch
        val file = LocalFile(picked.path, picked.name, picked.sizeBytes)
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
            val rt = host.runtime ?: return
            val get = msg.get
            val grant = settings.shareGrants.firstOrNull { it.nodeId == envelope.from }
            val mode = grant?.mode ?: settings.shareMode
            if (mode == "off") {
                host.scope.launch { rt.sendShareDeny(envelope.from, get.reqId, ShareDenyReasons.NO_PERM) }
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
            if (validFiles.isNotEmpty()) {
                host.scope.launch {
                    rt.offerFiles(envelope.from, validFiles, purpose = FilePurpose.SHARE_GET)
                }
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
                host._state.update {
                    it.copy(
                        cabinet = it.cabinet.copy(
                            entries = mapped,
                            totalEntries = res.total,
                            currentPerm = res.perm,
                            loading = false,
                            errorMessage = null,
                        ),
                    )
                }
            }
        }

        is ShareMessage.Deny -> {
            val deny = msg.deny
            if (host._state.value.cabinet.activePeerId == envelope.from) {
                host._state.update {
                    it.copy(
                        cabinet = it.cabinet.copy(
                            loading = false,
                            errorMessage = deny.reason,
                        ),
                    )
                }
            }
        }
    }
}
}
