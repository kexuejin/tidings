// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.desktop_app_title
import com.kapp.tenon.generated.resources.desktop_brand_short
import com.kapp.tenon.generated.resources.desktop_diag_bound_udp
import com.kapp.tenon.generated.resources.desktop_diag_control_plane
import com.kapp.tenon.generated.resources.desktop_diag_control_plane_off
import com.kapp.tenon.generated.resources.desktop_diag_export_failed
import com.kapp.tenon.generated.resources.desktop_diag_exported
import com.kapp.tenon.generated.resources.desktop_diag_failure
import com.kapp.tenon.generated.resources.desktop_diag_generated_at
import com.kapp.tenon.generated.resources.desktop_diag_local_addrs
import com.kapp.tenon.generated.resources.desktop_diag_manual_peers
import com.kapp.tenon.generated.resources.desktop_diag_nick
import com.kapp.tenon.generated.resources.desktop_diag_no_failure
import com.kapp.tenon.generated.resources.desktop_diag_none
import com.kapp.tenon.generated.resources.desktop_diag_org
import com.kapp.tenon.generated.resources.desktop_diag_peer_offline
import com.kapp.tenon.generated.resources.desktop_diag_peer_online
import com.kapp.tenon.generated.resources.desktop_diag_persist
import com.kapp.tenon.generated.resources.desktop_diag_phase
import com.kapp.tenon.generated.resources.desktop_diag_section_network
import com.kapp.tenon.generated.resources.desktop_diag_section_peers
import com.kapp.tenon.generated.resources.desktop_diag_section_recent_log
import com.kapp.tenon.generated.resources.desktop_diag_section_settings
import com.kapp.tenon.generated.resources.desktop_diag_title
import com.kapp.tenon.generated.resources.desktop_diag_transport
import com.kapp.tenon.generated.resources.desktop_dialog_choose
import com.kapp.tenon.generated.resources.desktop_dialog_choose_dir
import com.kapp.tenon.generated.resources.desktop_dialog_choose_file
import com.kapp.tenon.generated.resources.desktop_drag_multi_files
import com.kapp.tenon.generated.resources.desktop_drag_unsupported_flavor
import com.kapp.tenon.generated.resources.desktop_link_added_peer
import com.kapp.tenon.generated.resources.desktop_login_delete_failed
import com.kapp.tenon.generated.resources.desktop_login_entry_comment
import com.kapp.tenon.generated.resources.desktop_login_entry_name
import com.kapp.tenon.generated.resources.desktop_login_no_command
import com.kapp.tenon.generated.resources.desktop_login_no_path
import com.kapp.tenon.generated.resources.desktop_login_reg_failed
import com.kapp.tenon.generated.resources.desktop_login_unsupported
import com.kapp.tenon.generated.resources.desktop_login_write_failed
import com.kapp.tenon.generated.resources.desktop_msg_file_unsupported
import com.kapp.tenon.generated.resources.desktop_notification_file
import com.kapp.tenon.generated.resources.desktop_power_saver_off
import com.kapp.tenon.generated.resources.desktop_power_saver_on
import com.kapp.tenon.generated.resources.desktop_scan_unsupported
import com.kapp.tenon.generated.resources.desktop_scan_unsupported_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 桌面壳里那些**拿不到组合上下文**的地方要的文案（ARP-3）。
 *
 * ## 为什么需要它（而不是到处 `stringResource`）
 *
 * `stringResource` 只能在 `@Composable` 里调；`getString` 是 suspend。而桌面壳有一批
 * 纯 JVM 代码路径，它们**在** UI 回调链上、却不在组合里：
 *
 * - AWT/Swing 文件对话框的标题（`DesktopDialogs`）；
 * - 开机自启的注册表/plist/.desktop 写入（`DesktopLoginItem`）—— 失败原因会被设置页
 *   原样显示，所以它也是用户可见文案（`launchAtLoginError = failure.message`）；
 * - 诊断包导出（`DesktopDiagnostics`）—— 拼一个**文本文件**，里面全是人读的标题；
 * - 拖拽跟手小图上的文件名摘要（`DesktopDragOut`）；
 * - 桌面端不支持扫码的那句原因（`DesktopPlatform`，随 `PlatformCapabilityUnavailable` 抛出）；
 * - 从链接加节点 / 消息文件的系统通知（`Main`）。
 *
 * 于是：**在组合里一次解析好，装进这里**，纯 JVM 侧同步读。这与 `RelayUiTexts` 桥
 * 同一个取舍 —— 措辞只住在 strings.xml，壳只负责「什么时候、从哪里取」。
 *
 * ## 未装时是空串，而且会留一条读数
 *
 * 与 `NoUiTexts` 同一条纪律：不编中文、不崩溃。但**不能静默** —— 「文案空串」在桌面
 * 上的后果是「通知没有标题」这种看起来像系统故障的现象（ARP-1 的真机现场就是这一类）。
 * 所以第一次读到未安装时打一行 `[DESKTOP-TEXT]`，进程内只打一次。
 */
internal class DesktopStrings(
    val appTitle: String = "",
    /** 省电档通知正文；`%1$s` = 放慢倍数。 */
    val powerSaverOn: String = "",
    val powerSaverOff: String = "",
    val notificationFile: String = "",
    val dialogChooseFile: String = "",
    val dialogChooseDir: String = "",
    val dialogChoose: String = "",
    /** 自启：`%1$s` = 系统名。 */
    val loginUnsupported: String = "",
    val loginNoCommand: String = "",
    val loginNoPath: String = "",
    /** 自启：`%1$s` = 注册文件路径。 */
    val loginDeleteFailed: String = "",
    val loginWriteFailed: String = "",
    /** 自启（Windows）：`%1$s` = reg 输出尾巴。 */
    val loginRegFailed: String = "",
    val loginEntryName: String = "",
    val loginEntryComment: String = "",
    val scanUnsupportedHint: String = "",
    val scanUnsupported: String = "",
    /** 拖出：`%1$s` = DataFlavor 描述。 */
    val dragUnsupportedFlavor: String = "",
    /** 拖出：`%1$s` = 首个文件名，`%2$d` = 文件数。 */
    val dragMultiFiles: String = "",
    val brandShort: String = "",
    /** 深链：`%1$s` = host:port。 */
    val linkAddedPeer: String = "",
    val msgFileUnsupported: String = "",
    val diag: DiagnosticsStrings = DiagnosticsStrings(),
) {
    companion object {
        /** 未安装时的兜底：全空串，且**不含任何中文**（壳不许自己编文案）。 */
        val Empty: DesktopStrings = DesktopStrings()
    }
}

/** 诊断包（一个给人读的文本文件）里的标题与字段标签。 */
internal class DiagnosticsStrings(
    val title: String = "",
    /** `%1$s` = 生成时刻。 */
    val generatedAt: String = "",
    val sectionSettings: String = "",
    val sectionNetwork: String = "",
    val sectionPeers: String = "",
    val sectionRecentLog: String = "",
    /** 以下均为「标签 = %1$s」形状。 */
    val nick: String = "",
    val org: String = "",
    val persist: String = "",
    val manualPeers: String = "",
    val phase: String = "",
    val transport: String = "",
    val localAddrs: String = "",
    val boundUdp: String = "",
    val failure: String = "",
    val controlPlane: String = "",
    val none: String = "",
    val noFailure: String = "",
    val controlPlaneOff: String = "",
    val online: String = "",
    val offline: String = "",
    val exportFailed: String = "",
    /** `%1$s` = 文件名。 */
    val exported: String = "",
)

/**
 * 装/取 [DesktopStrings]。
 *
 * 与 `RelayUiTexts` 桥的差别只有一个：这里**必须**是同步可读的（调用方都在 AWT/回调线程
 * 上，挂起会让文件对话框与自启开关的反馈延后一拍）。
 */
internal object DesktopCopy {

    private var installed: DesktopStrings? = null
    private var warned = false

    fun install(value: DesktopStrings) {
        installed = value
        // 重新装上一次要允许再报一次：这个警告的意义是「这次启动的文案源没接上」。
        warned = false
    }

    fun clear() {
        installed = null
    }

    val value: DesktopStrings
        get() {
            val current = installed
            if (current != null) return current
            if (!warned) {
                warned = true
                // 这一行刻意用 ASCII：它是诊断读数，不是文案 —— 本仓的 CJK 门禁对
                // desktopApp 是逐条清零的（见 ARP-3），新写的中文日志会被算成新违规。
                println(
                    "[DESKTOP-TEXT] copy source not installed: desktop dialog / login item / " +
                        "diagnostics text will be empty. Install point: DesktopApp composition " +
                        "(rememberDesktopCopy + install).",
                )
            }
            return DesktopStrings.Empty
        }
}

/**
 * 在组合里把 [DesktopStrings] 解析出来（一次 37 个键，全部来自 strings.xml）。
 *
 * 参数化的那几条**原样**取出（`%1$s` 仍留在串里），由调用方用 [desktopFormat] 填值 ——
 * 因为这些值（路径、系统名、文件数）只有纯 JVM 侧才知道，那时已经离开组合了。
 */
@Composable
internal fun rememberDesktopCopy(): DesktopStrings = DesktopStrings(
    appTitle = stringResource(Res.string.desktop_app_title),
    powerSaverOn = stringResource(Res.string.desktop_power_saver_on),
    powerSaverOff = stringResource(Res.string.desktop_power_saver_off),
    notificationFile = stringResource(Res.string.desktop_notification_file),
    dialogChooseFile = stringResource(Res.string.desktop_dialog_choose_file),
    dialogChooseDir = stringResource(Res.string.desktop_dialog_choose_dir),
    dialogChoose = stringResource(Res.string.desktop_dialog_choose),
    loginUnsupported = stringResource(Res.string.desktop_login_unsupported),
    loginNoCommand = stringResource(Res.string.desktop_login_no_command),
    loginNoPath = stringResource(Res.string.desktop_login_no_path),
    loginDeleteFailed = stringResource(Res.string.desktop_login_delete_failed),
    loginWriteFailed = stringResource(Res.string.desktop_login_write_failed),
    loginRegFailed = stringResource(Res.string.desktop_login_reg_failed),
    loginEntryName = stringResource(Res.string.desktop_login_entry_name),
    loginEntryComment = stringResource(Res.string.desktop_login_entry_comment),
    scanUnsupportedHint = stringResource(Res.string.desktop_scan_unsupported_hint),
    scanUnsupported = stringResource(Res.string.desktop_scan_unsupported),
    dragUnsupportedFlavor = stringResource(Res.string.desktop_drag_unsupported_flavor),
    dragMultiFiles = stringResource(Res.string.desktop_drag_multi_files),
    brandShort = stringResource(Res.string.desktop_brand_short),
    linkAddedPeer = stringResource(Res.string.desktop_link_added_peer),
    msgFileUnsupported = stringResource(Res.string.desktop_msg_file_unsupported),
    diag = DiagnosticsStrings(
        title = stringResource(Res.string.desktop_diag_title),
        generatedAt = stringResource(Res.string.desktop_diag_generated_at),
        sectionSettings = stringResource(Res.string.desktop_diag_section_settings),
        sectionNetwork = stringResource(Res.string.desktop_diag_section_network),
        sectionPeers = stringResource(Res.string.desktop_diag_section_peers),
        sectionRecentLog = stringResource(Res.string.desktop_diag_section_recent_log),
        nick = stringResource(Res.string.desktop_diag_nick),
        org = stringResource(Res.string.desktop_diag_org),
        persist = stringResource(Res.string.desktop_diag_persist),
        manualPeers = stringResource(Res.string.desktop_diag_manual_peers),
        phase = stringResource(Res.string.desktop_diag_phase),
        transport = stringResource(Res.string.desktop_diag_transport),
        localAddrs = stringResource(Res.string.desktop_diag_local_addrs),
        boundUdp = stringResource(Res.string.desktop_diag_bound_udp),
        failure = stringResource(Res.string.desktop_diag_failure),
        controlPlane = stringResource(Res.string.desktop_diag_control_plane),
        none = stringResource(Res.string.desktop_diag_none),
        noFailure = stringResource(Res.string.desktop_diag_no_failure),
        controlPlaneOff = stringResource(Res.string.desktop_diag_control_plane_off),
        online = stringResource(Res.string.desktop_diag_peer_online),
        offline = stringResource(Res.string.desktop_diag_peer_offline),
        exportFailed = stringResource(Res.string.desktop_diag_export_failed),
        exported = stringResource(Res.string.desktop_diag_exported),
    ),
)

/**
 * 把模板里的 `%1$s` / `%2$d` 换成实参。
 *
 * ## 为什么不是 `String.format`
 *
 * Compose Multiplatform 的 `org.jetbrains.compose.resources` 用的是**正则替换**
 * （`SimpleStringFormatRegex = Regex("%(\\d+)\\$[ds]")`），不是 `String.format`。两条
 * 语义真的不同：`%%` 在 CMP 里不转义、会原样显示两个百分号；`String.format` 会把它
 * 变成一个。这里复刻的是 CMP 那条（与 `stringResource(res, args)` 的输出一致），
 * 所以钉了两条判据（`DesktopCopyFormatTest`）。
 *
 * 越界的位置参数原样保留 —— 宁可让模板痕迹露出来，也不要悄悄吞掉一段文字。
 */
internal fun desktopFormat(template: String, vararg args: Any?): String {
    if (template.isEmpty()) return template
    if (!template.contains('%')) return template
    return Regex("%(\\d+)\\$[ds]").replace(template) { match ->
        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@replace match.value
        args.getOrNull(index)?.toString() ?: match.value
    }
}

/**
 * 把 [DesktopStrings] 装进 [DesktopCopy]，离开组合时摘掉。
 *
 * 摘掉后回落到 `Empty`（并在下次读取时留一条读数）—— 留一个指向已离开组合实现的
 * 文案源，比没有更糟：它会给出**上一次的**路径与文件名。
 */
@Composable
internal fun InstallDesktopCopy() {
    val copy = rememberDesktopCopy()
    DisposableEffect(copy) {
        DesktopCopy.install(copy)
        onDispose { DesktopCopy.clear() }
    }
}
