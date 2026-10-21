// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package app.relay.ui.desktop

import androidx.compose.runtime.Composable
import app.relay.generated.resources.Res
import app.relay.generated.resources.desktop_app_title
import app.relay.generated.resources.desktop_menu_new_conversation
import app.relay.generated.resources.desktop_tray_agent_running
import app.relay.generated.resources.desktop_tray_agent_stopped
import app.relay.generated.resources.desktop_tray_headline_files
import app.relay.generated.resources.desktop_tray_headline_unread
import app.relay.generated.resources.desktop_tray_open_main
import app.relay.generated.resources.desktop_tray_open_settings
import app.relay.generated.resources.desktop_tray_quit
import app.relay.generated.resources.desktop_tray_received_files
import app.relay.generated.resources.desktop_tray_received_files_count
import app.relay.ui.components.formatBytes
import app.relay.app.model.MessageUi
import app.relay.app.model.TransferState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 托盘上的一句话：资源 + 参数。
 *
 * 中间隔一层是因为两头对不上：[trayMenuSpec] 是普通函数（没有 composition，单测里
 * 直接调它），够不到 `stringResource`；而 AWT 那侧（[TrayMenuModel]）要的是最终字符串。
 * 于是模型只回答"哪条文案、带哪些参数"，落地由 composable 里的 [TrayMenuSpec.resolve] 做。
 */
public data class TrayText(
    val resource: StringResource,
    val args: List<Any> = emptyList(),
)

/**
 * 托盘菜单的一条命令。
 *
 * [action] 为 null 表示这是一条**说明性条目**（不可点）—— 托盘菜单里要如实报告
 * 「本机 Agent 在不在跑」这类状态，而 AWT 的托盘没有别的行可以承载它。
 */
public data class TrayMenuItemSpec(
    val label: TrayText,
    val enabled: Boolean = true,
    val action: TrayAction? = null,
)

/** 托盘菜单能触发的动作。窗口层负责把每个动作接到真实回调上。 */
public enum class TrayAction { ShowMain, NewConversation, OpenReceivedFiles, OpenSettings, Quit }

/**
 * 托盘菜单的完整形态：标题 + 条目表。
 *
 * 抽成数据而不是直接在 AWT 里拼菜单，是因为**这套判断在无图形环境里也得能验**：
 * 菜单标题该说「3 条新消息」还是「收到 1 个文件（12 MB）」、一个文件都没有时
 * 「收到的文件」是不是灰的，这些都能用单测钉住，而 `PopupMenu` 在 CI 里根本建不出来。
 */
public data class TrayMenuSpec(
    /** 托盘提示语。有新闻时是那条新闻，没有时是产品名。 */
    val title: TrayText,
    val items: List<TrayMenuItemSpec>,
)

/**
 * 已解析的托盘菜单：AWT 侧（`DesktopTrayHandle`）消费的就是它。
 *
 * 托盘提示语是 [title]，条目表是 [items]。
 */
public data class TrayMenuModel(
    val title: String,
    val items: List<TrayMenuItem>,
)

/** 已解析的一条命令：文案已经落成字符串。 */
public data class TrayMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val action: TrayAction? = null,
)

/**
 * 把资源引用落成字符串。
 *
 * 只能在 composable 里调 —— 这正是模型分两层的原因。
 */
@Composable
public fun TrayMenuSpec.resolve(): TrayMenuModel = TrayMenuModel(
    title = title.resolve(),
    items = items.map { TrayMenuItem(it.label.resolve(), it.enabled, it.action) },
)

@Composable
private fun TrayText.resolve(): String =
    if (args.isEmpty()) stringResource(resource) else stringResource(resource, *args.toTypedArray())

/**
 * 按真实状态生成托盘菜单。
 *
 * 标题优先报未读消息而不是新文件：消息是有人在对你说活，文件是东西到了 ——
 * 前者更该把人叫回来，而两个都在时也只能显示一个。
 *
 * ## 未读数**不进** [TrayMenuSpec.items]
 *
 * 未读数是**每收一条消息就变**的量。它一旦出现在条目里，条目表就跟着变，AWT 那侧
 * 就得整份重建原生菜单 —— 而重建一次状态项菜单会让菜单栏重排几何，代价由
 * WindowServer 主线程付。于是「每来一条消息，系统菜单栏被重排一次」。
 *
 * 所以未读数只走两处**不改变菜单结构**的地方：
 * - [title]（落到 tooltip，它是 AWT 里唯一能显示"标题"的位置）；
 * - 图标角标（`hasUnread`，那张图由 `DesktopNotifications.trayIconImage` 画）。
 *
 * 「为什么这个图标在闪」仍然能被回答：tooltip 写着「3 条新消息」，角标是红的。
 * 条目里那条重复的说明性句子被删掉，是因为它和 tooltip 说同一件事，却要每次
 * 未读数跳动时让系统重建一份菜单。
 *
 * 文件数留在条目里（「收到的文件 (3)」）：它是**命令**而不是新闻，而且文件落盘是
 * 低频事件，不会形成每条消息一次的抖动。
 *
 * [agentRunning] 说的是**本机控制面**（菜单里的「Agent 控制台」）有没有在监听，
 * 不是"本机存在某个叫 Agent 的对端"。控制面端口为 0 时如实说未运行，
 * 而不是不管什么状态都写「运行中」。
 */
public fun trayMenuSpec(
    unreadTotal: Int,
    receivedFiles: Int,
    receivedBytes: Long = 0L,
    agentRunning: Boolean = false,
): TrayMenuSpec {
    val headline = when {
        unreadTotal > 0 -> TrayText(Res.string.desktop_tray_headline_unread, listOf(unreadTotal))
        receivedFiles > 0 ->
            TrayText(Res.string.desktop_tray_headline_files, listOf(receivedFiles, formatBytes(receivedBytes)))
        else -> null
    }
    val items = buildList {
        // 这里刻意**没有**那条说明性头条（未读数 / 文件数）。理由见上面 KDoc：
        // 头条只进 title（tooltip）与图标角标，不进条目表 —— 条目表一旦含未读数，
        // 每收一条消息就要重建一次原生菜单。
        add(TrayMenuItemSpec(TrayText(Res.string.desktop_tray_open_main), action = TrayAction.ShowMain))
        add(TrayMenuItemSpec(TrayText(Res.string.desktop_menu_new_conversation), action = TrayAction.NewConversation))
        add(
            TrayMenuItemSpec(
                if (receivedFiles > 0) {
                    TrayText(Res.string.desktop_tray_received_files_count, listOf(receivedFiles))
                } else {
                    TrayText(Res.string.desktop_tray_received_files)
                },
                // 一个文件都没有时点进去只有空态，不如就地置灰 —— 但条目本身要留着，
                // 否则用户不知道这台机器能不能收文件。
                enabled = receivedFiles > 0,
                action = TrayAction.OpenReceivedFiles,
            ),
        )
        add(
            TrayMenuItemSpec(
                TrayText(
                    if (agentRunning) Res.string.desktop_tray_agent_running else Res.string.desktop_tray_agent_stopped,
                ),
                enabled = false,
            ),
        )
        add(TrayMenuItemSpec(TrayText(Res.string.desktop_tray_open_settings), action = TrayAction.OpenSettings))
        add(TrayMenuItemSpec(TrayText(Res.string.desktop_tray_quit), action = TrayAction.Quit))
    }
    return TrayMenuSpec(title = headline ?: TrayText(Res.string.desktop_app_title), items = items)
}

/**
 * 统计**已经落到本机**的入站文件：条数与总字节。
 *
 * 只数 [TransferState.Completed]：传输中的文件还不叫"收到"，把它算进去，
 * 角标会在传输过程中一直跳，而用户点进去看到的还是没写完的半个文件。
 * 出站文件（`mine`）不算 —— 那是"我发出去的"，不是"收到的"。
 */
public fun receivedFileStats(messages: Map<String, List<MessageUi>>): Pair<Int, Long> {
    var count = 0
    var bytes = 0L
    messages.values.forEach { thread ->
        thread.forEach { msg ->
            val file = msg.file ?: return@forEach
            if (file.mine) return@forEach
            if (file.state != TransferState.Completed) return@forEach
            count++
            bytes += file.size
        }
    }
    return count to bytes
}
