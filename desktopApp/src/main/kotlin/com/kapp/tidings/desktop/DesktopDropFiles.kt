// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.domain.DesktopPaneActions
import com.kapp.tidings.core.protocol.FileTransfer
import com.kapp.tidings.core.transport.LocalFile
import java.io.File
import java.nio.file.Files

/**
 * 一次拖放展开后的结果。
 *
 * [files] 里的 [LocalFile.name] 是**相对路径**（`照片/2024/a.jpg`），不是裸文件名 ——
 * 接收端靠它还原目录树（`sanitizeRelativePath` 会再校验一次）。
 */
internal data class DroppedPayload(
    val files: List<LocalFile>,
    /** 有目录参与 ⇒ 这一批按「一个文件夹」发（对端显示文件夹角标、按相对路径还原）。 */
    val isFolder: Boolean,
    /** 文件夹传输时显示的名字；非文件夹传输为 null。 */
    val folderRootName: String?,
    /** 被跳过的空目录个数（见 [expandDropped] 的 KDoc）。 */
    val skippedEmptyDirs: Int,
    /** 展开后是否超过了单次传输上限（此时 [files] 已被截断，**不该发送**）。 */
    val overflow: Boolean,
) {
    val isEmpty: Boolean get() = files.isEmpty()
}

/**
 * 把「拖进来的东西」展开成可直接发送的文件列表。
 *
 * ## 为什么要这一层
 *
 * 拖放进来的可能是目录，而传输层只认文件（`LocalFile`）。不展开的话，目录会被当成一个
 * 零字节左右的「文件」发出去，对端收到一个打不开的东西 —— **静默失败**。
 *
 * ## 规则（对标茶话间的 `walkDir`）
 *
 * 1. 递归展开子目录，相对路径按 `根/子/文件` 拼；
 * 2. **只收普通文件** —— 符号链接、socket、设备文件一律跳过（跟着链接可能走出目录树，
 *    也可能指向一个永远读不完的管道）；
 * 3. **上限与传输层一致**（[FileTransfer.MAX_FILES_PER_TRANSFER]）：超过就停止展开并标记
 *    [DroppedPayload.overflow]，由调用方给一句明确的拒绝 —— 不在这里截断后照发，
 *    那等于悄悄少传一半文件。
 *
 * ## 与茶话间的一处**故意不同**
 *
 * 茶话间会把空目录也记成一条 `isDir` 条目，于是空文件夹能传过去。本仓的发送侧目前
 * 不设置 `isDir`（`prepareOffer` 里 `FileMeta.isDir` 恒为 null），而服务端读文件时是
 * 直接把路径当普通文件打开的 —— 硬塞一个目录进去会让那条传输报错。
 * 所以这里**跳过空目录并如实告知**（[DroppedPayload.skippedEmptyDirs]），而不是假装成功。
 * 要支持空目录，得先把 `isDir` 贯通到 `OutgoingFile` 与服务端读取路径。
 */
internal fun expandDropped(
    dropped: List<File>,
    limit: Int = FileTransfer.MAX_FILES_PER_TRANSFER,
): DroppedPayload {
    if (dropped.isEmpty()) return DroppedPayload(emptyList(), false, null, 0, false)
    val out = mutableListOf<LocalFile>()
    var skippedEmptyDirs = 0
    var overflow = false

    fun addFile(file: File, rel: String) {
        if (out.size >= limit) {
            overflow = true
            return
        }
        out += LocalFile(path = file.absolutePath, name = rel, size = file.length())
    }

    fun walk(dir: File, rel: String) {
        if (overflow) return
        val entries = dir.listFiles() ?: run {
            // 读不了（权限 / 循环链接）：不算空目录，也不算成功 —— 直接跳过。
            return
        }
        if (entries.isEmpty()) {
            skippedEmptyDirs++
            return
        }
        for (entry in entries.sortedBy { it.name }) {
            if (overflow) return
            val childRel = if (rel.isEmpty()) entry.name else rel + "/" + entry.name
            when {
                entry.isDirectory -> walk(entry, childRel)
                // 只认普通文件：符号链接的 isFile 可能为真，但 isSymbolicLink 会漏过去，
                // 所以两条都判。
                entry.isFile && !Files.isSymbolicLink(entry.toPath()) -> addFile(entry, childRel)
                else -> Unit
            }
        }
    }

    val hasDir = dropped.any { it.isDirectory }
    for (item in dropped) {
        if (overflow) break
        if (item.name.endsWith(CHECKSUM_SUFFIX, ignoreCase = true)) continue
        if (item.isDirectory) {
            walk(item, item.name)
        } else {
            addFile(item, item.name)
        }
    }

    // 名字只由这里决定**单一目录**那一种（直接用目录名，不含任何自然语言）。
    // 多条路径那种「第一个 等 N 个文件」的措辞交给 core 的 `prepareOffer` 拼 ——
    // 它本来就在那么做，在桌面层再拼一遍等于同一句话有两个出处（而且这里拼就会
    // 把中文写死在 `desktopApp`，`cjkLiteralGate` 也会判红）。
    val rootName = if (hasDir && dropped.size == 1) dropped.first().name else null
    return DroppedPayload(
        files = out,
        isFolder = hasDir,
        folderRootName = rootName,
        skippedEmptyDirs = skippedEmptyDirs,
        overflow = overflow,
    )
}
/**
 * 拖放失败/降级时要说的话。**在 composable 里解析好再传进来** —— 这一层不是 Composable，
 * 拿不到 `stringResource`；而 `desktopApp` 里新写硬编码中文会被 `cjkLiteralGate` 判红
 * （历史上那几条在基线里，不是可以继续加的理由）。
 */
internal class DropTexts(
    val pickConversation: String,
    val tooMany: String,
    val emptyDirsSkipped: String,
)

/**
 * 处理一次拖放：展开目录 → 传文件夹 / 传文件。
 *
 * 主窗与独立会话窗**共用这一份**：同一件事不该因为窗口不同而有两种行为。
 * 三种结果各自有话说 ——
 *  · 展开后超过上限 ⇒ **明确拒绝**（不截断后照发：那等于悄悄少传一半）；
 *  · 没有选中会话 ⇒ 交给 [onPickTarget] 弹目标选择器（空态文案承诺的是
 *    "捎话会问你要发给谁"，不是只弹一句通知）；宿主没给选择器才退回通知；
 *  · 跳过了空文件夹 ⇒ 如实告知（发送侧暂不支持 `isDir`）；
 *  · 其余 ⇒ 正常发送。
 */
internal fun handleDroppedFiles(
    controller: DesktopPaneActions,
    peerId: String?,
    dropped: List<File>,
    notify: (String) -> Unit,
    texts: DropTexts,
    onPickTarget: ((DroppedPayload) -> Unit)? = null,
    onStageFiles: ((List<LocalFile>) -> Unit)? = null,
) {
    val payload = expandDropped(dropped)
    if (payload.isEmpty) return
    if (payload.overflow) {
        // 只报到「上限」：真实个数没数完，而为了报一个数把几万个文件走一遍不值得。
        notify(texts.tooMany)
        return
    }
    if (peerId == null) {
        val pick = onPickTarget
        if (pick != null) pick(payload) else notify(texts.pickConversation)
        return
    }
    if (payload.skippedEmptyDirs > 0) notify(texts.emptyDirsSkipped)
    if (onStageFiles != null && !payload.isFolder) {
        onStageFiles(payload.files)
    } else {
        controller.sendFiles(
            peerId = peerId,
            files = payload.files,
            isFolder = payload.isFolder,
            folderRootName = payload.folderRootName,
        )
    }
}