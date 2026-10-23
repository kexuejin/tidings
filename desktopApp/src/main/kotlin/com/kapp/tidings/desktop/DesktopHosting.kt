// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_toolbar_capture
import com.kapp.tidings.generated.resources.desktop_action_quit
import com.kapp.tidings.generated.resources.desktop_app_title
import com.kapp.tidings.generated.resources.desktop_menu_diagnostics
import com.kapp.tidings.generated.resources.desktop_menu_file
import com.kapp.tidings.generated.resources.desktop_menu_import_files
import com.kapp.tidings.generated.resources.desktop_menu_new_conversation
import com.kapp.tidings.generated.resources.ic_down
import com.kapp.tidings.generated.resources.screen_settings
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.desktop.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.core.fs.directoryUsageBytes
import com.kapp.tidings.core.transport.PartFile
import com.kapp.tidings.feature.settings.SettingsAppearSubpage
import com.kapp.tidings.feature.settings.SettingsCapsSubpage
import com.kapp.tidings.feature.settings.SettingsDataSubpage
import com.kapp.tidings.feature.settings.SettingsDiagSubpage
import com.kapp.tidings.feature.settings.SettingsNotifySubpage
import com.kapp.tidings.feature.settings.SetAvatarPage
import com.kapp.tidings.feature.settings.SettingsProfileSubpage
import com.kapp.tidings.feature.settings.SettingsConnectionSubpage
import com.kapp.tidings.feature.settings.THEME_MODE_SYSTEM
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.LocalCardShape
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.ui.theme.AppTheme
import com.kapp.tidings.ui.theme.RelayDarkColors
import com.kapp.tidings.ui.theme.RelayLightColors
import com.kapp.tidings.ui.theme.RelayTypography
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面的 UI 偏好持久化。
 *
 * 只存"用户自己改过"的东西：主题、省电后台档、开机自启。放在数据目录下的
 * ui-prefs.properties，读不出来就退回默认（首次启动、文件损坏、只读目录都不该让
 * 应用起不来）。
 */
internal object DesktopUiPrefs {
    private const val FILE_NAME = "ui-prefs.properties"

    private const val DARK_THEME = "darkTheme"
    private const val POWER_SAVER = "powerSaver"
    private const val LAUNCH_AT_LOGIN = "launchAtLogin"

    fun loadDarkTheme(dataDirectory: String, fallback: Boolean = true): Boolean =
        loadFlag(dataDirectory, DARK_THEME, fallback)

    fun saveDarkTheme(dataDirectory: String, dark: Boolean) =
        saveFlag(dataDirectory, DARK_THEME, dark)

    /** 省电后台档；默认关（默认档就是协议 §9 的心跳频率）。 */
    fun loadPowerSaver(dataDirectory: String, fallback: Boolean = false): Boolean =
        loadFlag(dataDirectory, POWER_SAVER, fallback)

    fun savePowerSaver(dataDirectory: String, enabled: Boolean) =
        saveFlag(dataDirectory, POWER_SAVER, enabled)

    /** 开机自启；默认关，理由见 [DesktopLoginItem]。 */
    fun loadLaunchAtLogin(dataDirectory: String, fallback: Boolean = false): Boolean =
        loadFlag(dataDirectory, LAUNCH_AT_LOGIN, fallback)

    fun saveLaunchAtLogin(dataDirectory: String, enabled: Boolean) =
        saveFlag(dataDirectory, LAUNCH_AT_LOGIN, enabled)

    fun loadFlag(dataDirectory: String, key: String, fallback: Boolean): Boolean {
        val file = File(dataDirectory, FILE_NAME)
        if (!file.isFile) return fallback
        return try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            // getProperty 返回 null 时用 ?: 兜住，而不是抛 —— 偏好缺失不是错误。
            props.getProperty(key)?.trim()?.toBooleanStrictOrNull() ?: fallback
        } catch (_: IOException) {
            fallback
        } catch (_: IllegalArgumentException) {
            // Properties.load 遇到坏的 \uXXXX 转义会抛 IllegalArgumentException（不是
            // IOException）。偏好文件被截断/写坏时必须退回默认，不能让启动直接崩。
            fallback
        }
    }

    /**
     * 读-改-写。
     *
     * **不能**像只有主题那一版那样直接 `Properties().store()`：`store` 覆盖整个文件，
     * 写主题就会把省电档和自启开关一起抹掉（表现为"改完主题，重启后自启设置丢了"）。
     */
    fun saveFlag(dataDirectory: String, key: String, value: Boolean) {
        val dir = File(dataDirectory)
        if (!dir.isDirectory && !dir.mkdirs()) return
        val file = File(dir, FILE_NAME)
        val props = Properties()
        if (file.isFile) {
            // 读失败（损坏/权限）也要继续：这次只写一个键，总好过整个偏好写不进去。
            runCatching { file.inputStream().use { props.load(it) } }
        }
        props.setProperty(key, value.toString())
        try {
            file.outputStream().use { props.store(it, "Tidings desktop UI preferences") }
        } catch (_: IOException) {
            // 写不进去只丢这次偏好，不该让消息收发跟着失败。
        }
    }
}

/**
 * 一次「已经落到原生对象上」的渲染。
 *
 * 用它和下一份渲染做差，避免把没有变化的原生写操作重做一遍 —— 每一次
 * `icon.image =` / `icon.popupMenu =` 都会让系统重算菜单栏里那个状态项的几何。
 */
internal data class TrayAppliedState(
    val model: TrayMenuModel,
    val hasUnread: Boolean,
)

/** 一份渲染相对上一份，哪些原生写操作真的需要重做。 */
internal data class TrayPatch(
    val rebuildMenu: Boolean,
    val updateToolTip: Boolean,
    val updateImage: Boolean,
)

/**
 * 算出这一份渲染相对上一份的最小改动作业。
 *
 * [previous] 为 null 表示还没画过（首次安装），三项全做。
 *
 * 判据逐项独立，而不是「模型变了就全做」：
 * - 菜单只认 [TrayMenuModel.items]：条目文案、置灰、动作、顺序。只有它们变了才换
 *   `PopupMenu`（换一次就等于让系统重建一条原生菜单）。
 * - 提示语只认 [TrayMenuModel.title]。
 * - 图标只认 [TrayAppliedState.hasUnread]：角标红点只该换一张图。
 *
 * 这三件事互不蕴含（标题变了条目不一定变、条目变了标题不一定变），所以不能合成一个
 * 布尔量 —— 合成之后任何一处变化都会把另外两处也重写一遍。
 */
internal fun trayPatch(previous: TrayAppliedState?, next: TrayAppliedState): TrayPatch = TrayPatch(
    rebuildMenu = previous == null || previous.model.items != next.model.items,
    updateToolTip = previous == null || previous.model.title != next.model.title,
    updateImage = previous == null || previous.hasUnread != next.hasUnread,
)

/**
 * 托盘图标。
 *
 * 桌面端关窗不是退出 —— 用户点叉号后进程还得留着收消息，所以必须有一块"召回"
 * 的地方。系统托盘不可用（部分 Linux 桌面）时返回 null，调用方照常跑主窗。
 */
internal class DesktopTrayHandle internal constructor(
    private val icon: TrayIcon,
    private val onAction: (TrayAction) -> Unit,
) {

    /** 上一次成功落到原生对象上的渲染。null = 还没画过。 */
    private var applied: TrayAppliedState? = null

    /**
     * 按 [model] 重画菜单。
     *
     * 结构变了就整份换一个新的 `PopupMenu`，而不是就地增删条目：有的平台把
     * `popupMenu` 取走时就固化了原生菜单对象（macOS 尤其），就地改 AWT 列表
     * 未必反映到屏幕上。菜单只有几项，整份换的代价可以忽略。
     *
     * 没变的部分不重写（见 [trayPatch]）：重组一次就重写一次原生对象的话，每一次
     * 未读数字跳动都会让菜单栏重排一遍，而这条路上的代价由 WindowServer 付。
     */
    fun apply(model: TrayMenuModel, hasUnread: Boolean = false) {
        val next = TrayAppliedState(model, hasUnread)
        val patch = trayPatch(applied, next)
        val ok = runCatching {
            if (patch.rebuildMenu) {
                val menu = PopupMenu()
                model.items.forEach { item ->
                    MenuItem(item.label).apply {
                        isEnabled = item.enabled
                        item.action?.let { a -> addActionListener { onAction(a) } }
                    }.let(menu::add)
                }
                // TrayIcon.popupMenu 只接受 PopupMenu（不是 Menu），否则类型不匹配。
                icon.popupMenu = menu
            }
            if (patch.updateToolTip) {
                // 菜单标题本身在 AWT 里没有位置（NSStatusItem 的 title 不是可移植能力），
                // 所以同一句话写进 tooltip：它是唯一跨平台可见的"标题"位置。
                icon.toolTip = model.title
            }
            if (patch.updateImage) {
                icon.image = DesktopNotifications.trayIconImage(hasUnread)
            }
        }.isSuccess
        // 写失败就不记账：下一次 apply 会把它当"没画过"重做一遍，而不是沉默地留一个
        // 与事实不符的 applied（那会让后续同值状态永远不再重试）。
        if (ok) applied = next
    }

    /**
     * 在**这个常驻图标**上弹一条气泡。返回 false 表示这条路用不了。
     *
     * 为什么通知必须复用常驻图标，而不是自己 add 一个临时 `TrayIcon`：临时图标
     * 要 add + 定时 remove，而 macOS 的 WindowServer 在**移除**状态项时走的是
     * `RemovePendingStatusItems` → 状态栏全量重建（`RepositionAndClipStatusItemList`），
     * 主线程被拖住就是一次系统级卡死。复用常驻图标时没有 add/remove，只有一条气泡。
     */
    fun showMessage(title: String, body: String): Boolean = runCatching {
        icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
        true
    }.getOrDefault(false)

    fun focusMainWindow(window: java.awt.Window) {
        window.isVisible = true
        // java.awt.Window.isActive 是只读 val，聚焦只能靠 toFront + requestFocus。
        window.toFront()
        window.requestFocus()
    }

    fun dispose() {
        runCatching { SystemTray.getSystemTray().remove(icon) }
        DesktopTray.forget(this)
    }
}

internal object DesktopTray {

    /**
     * 当前挂着的常驻图标。
     *
     * 存在的唯一理由是**通知复用**：气泡必须打在这一个状态项上，而不是每条通知
     * 都临时 add 一个再 remove（见 [DesktopTrayHandle.showMessage]）。
     * 只记账、不持有生命周期 —— 谁 install 谁 dispose，dispose 时自己摘掉。
     */
    private var mounted: DesktopTrayHandle? = null

    /** 装托盘图标。 */
    private fun mount(handle: DesktopTrayHandle) {
        mounted = handle
    }

    fun forget(handle: DesktopTrayHandle) {
        if (mounted === handle) mounted = null
    }

    /** 当前有没有可用的常驻图标（通知能不能复用）。 */
    internal fun current(): DesktopTrayHandle? = mounted

    /**
     * 装托盘图标。返回 null 表示这台机器没有可用托盘（部分 Linux 桌面），
     * 调用方照常跑主窗。
     *
     * [onAction] 覆盖**所有**入口：菜单条目和"点图标"都走它，所以点击图标与
     * 「打开主窗口」必然是同一个动作 —— 早先两者各接一个回调，结果点图标会
     * 顺带弹设置窗。
     */
    fun install(
        model: TrayMenuModel,
        onAction: (TrayAction) -> Unit,
    ): DesktopTrayHandle? {
        if (!DesktopNotifications.trayAvailable) return null
        return try {
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(DesktopNotifications.trayIconImage(), model.title)
            icon.isImageAutoSize = true
            // 点图标 = 唤回主窗（原型稿把这个动作也算作菜单「打开主窗口」那一项）。
            icon.addActionListener { onAction(TrayAction.ShowMain) }
            val handle = DesktopTrayHandle(icon, onAction)
            handle.apply(model)
            tray.add(icon)
            mount(handle)
            handle
        } catch (_: Exception) {
            // 无托盘环境直接放弃，不影响主窗。
            null
        }
    }
}

/**
 * 主修饰键：macOS 是 ⌘(meta)，其它平台是 Ctrl。
 *
 * 与 [com.kapp.tidings.ui.desktop.desktopShortcutFor] 的 `isPrimaryModifier` 同一条判据 ——
 * 键盘处理那边接受 meta 或 ctrl 任一，菜单这边由平台决定印哪一个。
 * 判据复用 `DesktopLoginItem` 里那个 `desktopOs(...)`，不另写一份平台判断。
 */
private val primaryModifierIsMeta: Boolean =
    desktopOs(System.getProperty("os.name").orEmpty()) == DesktopOs.Macos

/**
 * **系统菜单栏**（用户裁决：窗口内那条 26px 自绘菜单栏整条删除）。
 *
 * ## 它取代了什么
 *
 * 此前窗口客户区里有一条 26px 的**自绘**菜单栏（`composeApp` 里那个已删除的自绘实现，
 * 文件/编辑/视图/窗口/帮助共 16 条）。那 16 条里没有一条是独占能力（设置×3、视图与
 * 左侧竖导重复、主题在设置里、刷新各页都有、诊断包在状态栏、退出有标题栏✕/托盘/⌘Q、
 * 截图在输入栏、复制本机 ID 在节点页、导入文件与新建会话在工具栏），它唯一的价值是
 * 当快捷键的说明书 —— 而快捷键由 `DesktopShell` 的键盘处理独立驱动，删了照样能用。
 * 所以整条删掉，改由本函数提供 Compose 的 [MenuBar]：macOS 自动进屏幕顶部系统菜单栏，
 * Windows/Linux 自动进窗口菜单。原型自己也是这个意思（`prototype/relay.html` 的
 * `.menubar` 注释逐字「macOS 系统菜单栏（mac 上由系统提供；Win/Linux 上同一份 Compose
 * 菜单落在 .titlebar）」）。
 *
 * ## 只留 6 条，且与既有行为逐字一致
 *
 * 传入 null 的那一项**不画**（与自绘版「没有能力就不留一个点了没反应的项」同一条规矩）：
 * 主窗传全 6 条；会话独立窗没有视图切换、`onNewConversation`/`onImportFiles` 在那边
 * 本来就是空/无收件人，于是只传 设置/截图/诊断包/退出 四条。
 *
 * 快捷键用 [KeyShortcut] 声明而不是手写字符串：印出来的组合由系统渲染（mac ⌘ / 其它 Ctrl），
 * 与 `desktopShortcutFor` 的解析是否一致由单测钉住。
 */
@Composable
internal fun FrameWindowScope.DesktopSystemMenuBar(
    /** 「新建会话」：切到节点视图 + 宿主动作（与 ⌘N 同一条路）。null = 不画。 */
    onNewConversation: (() -> Unit)? = null,
    /** 「导入文件」：把文件发给当前会话。null = 不画（会话独立窗没有收件人）。 */
    onImportFiles: (() -> Unit)? = null,
    /** 当前有没有选中会话；false 时「导入文件」置灰（缺陷③）。 */
    canImportFiles: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    /** 「截图」：`controller.triggerScreenCapture(选中会话)`，与 ⌘⇧A 同一个表达式。 */
    onScreenCapture: (() -> Unit)? = null,
    onExportDiagnostics: (() -> Unit)? = null,
    onQuit: (() -> Unit)? = null,
) {
    // macOS：把系统应用菜单里的 Quit 接到与这条菜单项、托盘、⌘Q 相同的退出路径上。
    // 装一次即可（进程级设置，helper 内部幂等）；装不上就保持 JDK 默认行为。
    if (onQuit != null) {
        LaunchedEffect(onQuit) { installAwtQuitHandler(onQuit) }
    }
    MenuBar {
        // 第一条 = 应用菜单：macOS 上就是屏幕顶部那一条（设置/退出按平台惯例住这里），
        // Windows/Linux 上它落在窗口菜单的第一格。
        // mnemonic = null：与自绘菜单栏一致（那版也没有助记键），不在 Windows/Linux 上
        // 引入一套新的 Alt 导航 —— 菜单项的助记键与既有快捷键（见 desktopShortcutFor）
        // 是两套东西，混在一起会出现「Alt+F 打开文件菜单」与「⌘F 做别的事」并存。
        Menu(stringResource(Res.string.desktop_app_title), mnemonic = null) {
            onOpenSettings?.let { open ->
                Item(
                    stringResource(Res.string.screen_settings),
                    onClick = open,
                    shortcut = KeyShortcut(Key.Comma, ctrl = !primaryModifierIsMeta, meta = primaryModifierIsMeta),
                )
            }
            onExportDiagnostics?.let { export ->
                Item(
                    stringResource(Res.string.desktop_menu_diagnostics),
                    onClick = export,
                    shortcut = KeyShortcut(Key.D, ctrl = !primaryModifierIsMeta, meta = primaryModifierIsMeta, alt = true),
                )
            }
            // 退出项**只在非 macOS** 上由我们提供。
            //
            // 真机证据（在 Mac 上用 System Events 读菜单栏）：
            // 菜单栏四格 = Apple | Tidings（AWT 自带应用菜单：About/Services/Hide/Quit 捎话 Tidings）
            // | 捎话 Tidings（我们的 Compose MenuBar 应用菜单：设置/诊断包/**退出**）| 文件。
            // 即同一台机器上出现**两个退出项**。AWT 那份是 macOS 应用菜单的强制部分，删不掉；
            // 所以删我们这条，并由 installAwtQuitHandler 把它的行为接到同一条退出路径上
            // （默认策略会绕过 shutdown() 直接退出进程）。
            // Windows/Linux 没有 AWT 应用菜单 ⇒ 这一条必须保留，否则那两处**没有退出入口**。
            if (onQuit != null && showsOwnQuitItem(primaryModifierIsMeta)) {
                Separator()
                Item(
                    stringResource(Res.string.desktop_action_quit),
                    onClick = onQuit,
                    shortcut = KeyShortcut(Key.Q, ctrl = !primaryModifierIsMeta, meta = primaryModifierIsMeta),
                )
            }
        }
        if (onNewConversation != null || onImportFiles != null || onScreenCapture != null) {
            Menu(stringResource(Res.string.desktop_menu_file), mnemonic = null) {
                onNewConversation?.let { start ->
                    Item(
                        stringResource(Res.string.desktop_menu_new_conversation),
                        onClick = start,
                        shortcut = KeyShortcut(Key.N, ctrl = !primaryModifierIsMeta, meta = primaryModifierIsMeta),
                    )
                }
                onImportFiles?.let { import ->
                    Item(
                        stringResource(Res.string.desktop_menu_import_files),
                        onClick = import,
                        enabled = canImportFiles,
                        shortcut = KeyShortcut(Key.O, ctrl = !primaryModifierIsMeta, meta = primaryModifierIsMeta),
                    )
                }
                onScreenCapture?.let { capture ->
                    Item(
                        stringResource(Res.string.chat_toolbar_capture),
                        onClick = capture,
                        shortcut = KeyShortcut(Key.A, ctrl = !primaryModifierIsMeta, meta = primaryModifierIsMeta, shift = true),
                    )
                }
            }
        }
    }
}
/**
 * 把 AWT 拖放接到 Compose 窗口上，并在拖动过程中给出一层「可以松手了」的覆盖层。
 *
 * ComposeWindow 是 heavyweight 的 JFrame，可以直接当 DropTarget 的组件。
 * 关键顺序：先 acceptDrop 再读 transferable —— 不 accept 就取，拿到的
 * transferable 是不可读的（Linux 上尤其明显）。
 *
 * ## 为什么以前只有 drop 还不够
 * 只有 `drop()` 时，用户把文件拖进窗口到松手之间**完全看不到任何反馈**：没有
 * 覆盖层、没有虚线框、没有文案，窗口和拖之前一模一样，于是「拖了没反应、是不是
 * 没拖进去」成了桌面端最常见的一条疑问。原型稿的 `.droplayer` 就是补这一段的：
 * 拖入时亮全屏遮罩 + 虚线框，移出或松手时收起。
 *
 * ## dragging 态必须在 dragOver 里反复确认
 * AWT 没有 Swing `TransferHandler` 那套 `canImport` + 返回 true，拖动阶段的等价物
 * 是 `DropTargetDragEvent.acceptDrag(action)`：它设的 dropAction 决定系统光标
 * （不设就是禁止号），同时告诉平台「继续把事件送过来」。只在 dragEnter 做一次
 * 不够 —— 拖动中目标与位置被反复重算，dropAction 会掉回 ACTION_NONE，事件链随之
 * 断开，drop() 根本不会再回调。见 [acceptDragAction]。
 *
 * ## 这些回调跑在 AWT EDT 上
 * Compose 桌面窗口的重组器也挂在 EDT，状态写在正确的线程上，不必再用
 * `invokeLater` 包一层；多包一层只会让覆盖层晚一帧出现。
 */
@Composable
internal fun FrameWindowScope.DesktopDropTarget(
    enabled: Boolean = true,

    /**
     * 覆盖层里的收件人（会话昵称）。null 时用「当前会话」这种中性说法 ——
     * 主窗没选中会话时，覆盖层不该编一个收件人出来。
     */
    recipientName: String? = null,

    /**
     * 覆盖层用哪一档配色。
     *
     * 不能直接读环境里的 `MaterialTheme`：两个调用点都把本函数发射在 `AppTheme`
     * **之前**，此处解析到的是 M3 默认浅色 scheme，深色主题下覆盖层会变成一张
     * 白卡片。所以由调用方显式传入；不传就退回系统外观，至少不会与应用主题反着来。
     */
    darkTheme: Boolean? = null,

    onFiles: (List<File>) -> Unit,
) {
    val awtWindow = window
    // 覆盖层尺寸。拖动的那一瞬间窗口基本不会变，读一次就够；这里刻意不在重组期
    // 订阅它，免得给每帧加一个毫无用处的依赖。
    // AWT 给的是像素，覆盖层要的是 dp；桌面 Popup 内容按内容测量，fillMaxSize() 在里面
    // 会拿到无界约束塌成 0×0，所以这里必须换算出窗口实际 dp 尺寸传进去。
    val overlayDensity = LocalDensity.current
    val overlaySize = with(overlayDensity) {
        DpSize(awtWindow.width.toDp(), awtWindow.height.toDp())
    }
    var dragging by remember { mutableStateOf(false) }
    DisposableEffect(awtWindow, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val listener = object : DropTargetAdapter() {
            // 不 override dropActionChanged：拖动阶段只能设 dropAction，而它在 Kotlin 侧是只读 val。
            // 真正的 acceptDrop 在下面的 drop() 里做，那里才拿得到可读 transferable。

            /**
             * 指针进入窗口：亮覆盖层，并且当场接受这次拖拽。
             *
             * 为什么必须在进入时就接受：macOS/Windows 上不接受的拖拽，光标直接变
             * 禁止号，用户还没松手就被告知「不行」；平台也可能就此掐断事件链。
             *
             * 非文件拖拽（从浏览器里拖一段文字）**不**亮层：drop() 里取不到文件，
             * 先亮出「可以松手」的提示再拒，等于骗人一次。
             */
            override fun dragEnter(dte: DropTargetDragEvent) {
                if (!dte.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return
                acceptDragAction(dte)
                dragging = true
            }

            /**
             * 指针在窗口内移动：不用画任何东西，只要维持住 dropAction。
             *
             * 这一步最容易漏。光在 dragEnter 接受一次不够 —— 拖动中系统会反复重算
             * dropAction，不在这里每帧确认，dropAction 会掉回 ACTION_NONE，光标变成
             * 禁止号，事件链断掉，drop() 不再回调。
             */
            override fun dragOver(dte: DropTargetDragEvent) {
                acceptDragAction(dte)
            }

            /**
             * 指针在**未松手**的情况下离开窗口：收回覆盖层。
             *
             * 少了这一步，用户把文件拖到窗口上又划走，遮罩会永久糊在屏幕上，
             * 而此时根本没有 drop() 来收拾它。
             */
            override fun dragExit(dte: DropTargetEvent) {
                dragging = false
            }

            override fun drop(dtde: DropTargetDropEvent) {
                // 先撤覆盖层再走原有逻辑：取文件、过滤 .sha256、onFiles 的行为一个字没改，
                // 只是松手的那一刻画面不该还停在「拖动中」。
                dragging = false
                dtde.acceptDrop(DnDConstants.ACTION_COPY)
                val files = collectDroppedFiles(dtde)
                // 必须配 dropComplete，否则部分平台认为这次 drop 没处理完。
                if (files.isEmpty()) {
                    dtde.dropComplete(false)
                    return
                }
                onFiles(files)
                dtde.dropComplete(true)
            }
        }
        val target = DropTarget(awtWindow, DnDConstants.ACTION_COPY, listener, true)
        onDispose {
            awtWindow.dropTarget = null
            // 拖动中途关窗/切主题时 target 会被拆掉，此时不会再有 dragExit/drop 来收尾，
            // 不手动归零就会把覆盖层连同 Popup 一起留在屏幕上。
            dragging = false
        }
    }

    // 覆盖层必须画在窗口内容**之上**，而这里做不到：两个调用点都把 DesktopDropTarget
    // 发射在 `AppTheme` 之前，Compose 桌面窗口的根节点按发射顺序叠放，直接画会被
    // 后发射的 AppTheme 子树整片盖住。走 Popup —— 它是独立的一层原生窗口，永远在最上，
    // 且不依赖调用点把本函数摆在哪。
    if (dragging) {
        DesktopDropOverlay(
            windowSize = overlaySize,
            recipientName = recipientName,
            darkTheme = darkTheme,
        )
    }
}

/**
 * 拖动过程中铺在整扇窗口上的覆盖层，还原原型稿 `prototype/relay.html` 的 `.droplayer`
 * （CSS 在 `:585`，画出来的是屏函数 `screenDDrop()`，`:3102`）：
 * 全屏遮罩 + 居中虚线边框 + 一句「松手即发给 X」。
 *
 * ## 为什么走 Popup
 * 见 [DesktopDropTarget] 里那段：窗口内容按发射顺序叠放，覆盖层需要一个永远在最上
 * 的独立层。Popup 顺带解决尺寸问题：桌面端 Popup 的内容是按内容测量的，
 * `fillMaxSize()` 在里面会拿到无界约束而塌成 0×0，所以直接用窗口实际尺寸 [windowSize]。
 *
 * ## 配色为什么在这里重铺一次 theme
 * 本函数在两个调用点都位于 `AppTheme` 之外，环境里的 `MaterialTheme` 是 M3 默认
 * 浅色；直接用它，深色主题下覆盖层会是一张白卡片。显式提供 Tidings 的那两套 scheme，
 * 深浅两态都不硬编码颜色（项目硬规则：不允许 `Color(0x…)`）。
 *
 * ## 文案
 * 桌面侧提示文案，后续如需多语言再迁到资源（本次改动范围不涉及 strings 资源文件）。
 * 标题逐字照抄原型「松手即发给 …」；收件人为空时说「当前会话」，不编名字。
 * 原型第二行的「共 3 个文件 · 24.9 MB」是演示数据：拖动过程中 transferable 读不出
 * 内容（本文件顶部 KDoc 写明过），报不出真实数量，所以只保留它后面的规则说明
 * 「也会按上面的规则校验后落盘」。
 */
@Composable
private fun DesktopDropOverlay(
    windowSize: DpSize,
    recipientName: String?,
    darkTheme: Boolean?,
) {
    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(
            // 不可聚焦：拖动期间焦点必须留在主窗上，否则松手后输入焦点跳到一个
            // 刚消失的 Popup 上，光标和快捷键都要重来一次。
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme ?: isSystemInDarkTheme()) RelayDarkColors else RelayLightColors,
            typography = RelayTypography,
        ) {
            // 原型是 7% 的品牌色薄纱（浅色 rgba(45,106,79,.07) / 深色
            // rgba(116,198,157,.09)），但那是对着已知静态背景调的；真机上盖的是
            // 任意会话内容，太淡会读不出。这里用 scrim + alpha 加深一档，深浅两态
            // 都靠 token 自适应，没有硬编码黑/白。
            Box(
                modifier = Modifier
                    .size(windowSize)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                val frame = RoundedCornerShape(14.dp)
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface, frame)
                        .border(2.dp, MaterialTheme.colorScheme.primary, frame)
                        .padding(horizontal = 38.dp, vertical = LocalAppSpacing.current.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_down),
                        // 纯装饰：提示文字已经把同一件事说了一遍，无障碍上不重复播报。
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        text = if (recipientName.isNullOrBlank()) "松手即发给当前会话" else "松手即发给 $recipientName",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "也会按上面的规则校验后落盘",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 在**拖动阶段**接受这次拖拽。
 *
 * AWT 没有 Swing `TransferHandler` 的 `canImport` + 返回 true 这套二元判定；
 * 拖动事件上的等价物就是 `acceptDrag(action)`：它设下的 dropAction 决定系统光标
 * （不设 → 禁止号），也向平台确认「继续把拖动事件送进来」。
 *
 * 源端不支持 COPY（有些应用只声明 MOVE）时直接放弃亮层，而不是让异常顺着 AWT
 * 事件队列抛出去 —— 那会连累后续所有 UI 事件。
 */
private fun acceptDragAction(dte: DropTargetDragEvent) {
    if (dte.sourceActions and DnDConstants.ACTION_COPY == 0) return
    runCatching { dte.acceptDrag(DnDConstants.ACTION_COPY) }
}

private fun collectDroppedFiles(dtde: DropTargetDropEvent): List<File> {
    val raw = try {
        dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
    } catch (_: Exception) {
        // 非本地上传（比如从浏览器拖文本）时这里会抛，桌面不收。
        null
    } ?: return emptyList()
    return raw.filterIsInstance<File>()
        .filter { !it.name.endsWith(CHECKSUM_SUFFIX, ignoreCase = true) }
}

/** 下载时同名的校验旁车；拖进来时不该再当附件发一遍。 */
internal const val CHECKSUM_SUFFIX = ".sha256"

/**
 * 设置窗内容。
 *
 * 桌面设置是独立窗口，所以"返回"不能回到别的屏 —— 一级页直接关窗，
 * 二级页（更多）用 onBack 退回一级。
 *
 * 这里**不再**自己套 AppTheme：窗口层（DesktopSettingsWindow）已经按主窗
 * 选的主题包了一层。这里若再套一个 AppTheme()，它会退回系统主题，
 * 主窗深色而设置窗浅色，⌘⇧D 切主题时设置窗纹丝不动。
 */
@Composable
internal fun DesktopSettingsPane(
    state: RelayUiState,
    controller: RelayController,
    host: DesktopHost,
    onClose: () -> Unit,
) {
    var selectedNav by remember { mutableStateOf(DesktopSettingsNav.Identity) }
    // 托盘「有待批准」那条直达：进来就落在指的那一页，并且读完即清空。
    LaunchedEffect(Unit) {
        host.settingsInitialNav?.let {
            selectedNav = it
            host.settingsInitialNav = null
        }
    }
    // 「上传头像」独立页（台账 `m-set-avatar`）。它挂在身份页的第三档后面，不是左栏的
    // 第八行 —— 原型左栏也只有四行可点（身份与连接 / 数据与存储 / 桌面 / 输入法自检）。
    var showAvatarPage by remember { mutableStateOf(false) }

    // 占用数字与移动端同一条路：只在用户真的进这一屏时数一次，且在后台线程。
    val usage by produceState<Pair<Long, Long>?>(null) {
        value = withContext(Dispatchers.Default) {
            directoryUsageBytes(controller.dataDirectory) to
                directoryUsageBytes(controller.cacheDirectory)
        }
    }
    // 续传残片与移动端同一条路：进屏数一次、放后台线程；清完再 ++ 让重数读到的
    // 是删之后的那份（先 ++ 就会读到删之前的）。
    val leftoverEpoch = remember { mutableIntStateOf(0) }
    val leftoverScope = rememberCoroutineScope()
    val leftoverParts by produceState<List<PartFile>?>(null, leftoverEpoch.intValue) {
        value = withContext(Dispatchers.Default) { controller.leftoverParts() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 设置窗是独立窗口、不经 `DesktopShell`，所以在这里自己把卡片圆角钉成桌面档 12dp
        // （原型 `.win{--r-card:12px}`，`prototype/relay.html:584`；不钉就吃 [LocalCardShape]
        // 的默认值 16dp —— 那是 `.app`/手机的值）。与 `composeApp` 里 `DesktopSettingsChrome`
        // 的同一处覆盖配对：那个是给截图门禁用的共享壳，这个是生产路径。
        CompositionLocalProvider(LocalCardShape provides RelayShapes.medium) {
        Column(Modifier.fillMaxSize()) {
            DesktopSettingsTitleBar()
            HorizontalDivider()
            Row(Modifier.fillMaxSize()) {
                DesktopSettingsSidebar(
                    selected = selectedNav,
                    onSelect = { selectedNav = it },
                    modifier = Modifier.width(220.dp),
                )
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (showAvatarPage) {
                        SetAvatarPage(
                            state = state,
                            pickedAvatarBytes = state.pickedAvatarBytes,
                            onPickFromAlbum = controller::pickAvatar,
                            onRemovePicked = controller::removePickedAvatar,
                            onSaveProfile = { n, c, d, t, a ->
                                controller.saveProfile(n, c, d, t, a)
                            },
                            onBack = { showAvatarPage = false },
                        )
                        return@Box
                    }
                    when (selectedNav) {
                        DesktopSettingsNav.Identity -> {
                            SettingsProfileSubpage(
                                state = state,
                                onSaveProfile = { n, c, d, t, a ->
                                    controller.saveProfile(n, c, d, t, a)
                                },
                                onBack = null,
                                onOpenAvatarPage = controller::pickAvatar,
                                onRemovePickedAvatar = controller::removePickedAvatar,
                            )
                        }
                        DesktopSettingsNav.TrustedDevices -> {
                            DesktopTrustedDevicesPanel(
                                state = state,
                                controller = controller,
                                grantStore = host.extension.grantStore,
                                // 面板的每个决定都经这里回给设备（agent-grant 帧）。此前面板够不到
                                // 发帧能力，只能写本机台账 ⇒ 用户点完手机看不到。
                                grantReplySender = host.extension::sendGrantReply,
                            )
                        }
                        DesktopSettingsNav.Connection -> {
                            SettingsConnectionSubpage(
                                state = state,
                                defaultPort = null,
                                onSavePorts = { udp, tcp -> controller.savePorts(udp, tcp) },
                                onRestart = { controller.requestRestart() },
                                onBack = null,
                            )
                        }
                        DesktopSettingsNav.Desktop -> {
                            DesktopPrefsPanel(host = host)
                        }
                        DesktopSettingsNav.ImeSmoke -> {
                            ImeSmokePane(onBack = null)
                        }
                        DesktopSettingsNav.Transfers -> {
                            com.kapp.tidings.ui.desktop.DesktopTransferLogPane(
                                records = state.transferLogs,
                                onClear = { controller.clearTransferLogs() },
                                onOpenFile = { path ->
                                    DesktopReceivedFileActions().open(path)
                                },
                            )
                        }
                        DesktopSettingsNav.Notifications -> {
                            SettingsNotifySubpage(
                                onBack = null,
                                notificationsEnabled = state.settings.notifyAgentMessages,
                                onNotificationsEnabledChange = { controller.updateNotifyAgentMessages(it) },
                                mentionOnlyEnabled = state.settings.notifyMentionOnly,
                                onMentionOnlyEnabledChange = { controller.updateNotifyMentionOnly(it) },
                                quietHoursEnabled = state.settings.notifyQuietHours,
                                onQuietHoursEnabledChange = { controller.updateNotifyQuietHours(it) },
                            )
                        }
                        DesktopSettingsNav.Appearance -> {
                            SettingsAppearSubpage(
                                onBack = null,
                                // 空串 = 取不到 = 跟随系统（见 RelaySettings.themeMode 的注释）。
                                themeMode = state.settings.themeMode.ifEmpty { THEME_MODE_SYSTEM },
                                onThemeModeChange = { controller.updateThemeMode(it) },
                            )
                        }
                        DesktopSettingsNav.Capabilities -> {
                            SettingsCapsSubpage(
                                onBack = null,
                                textCapabilityEnabled = state.settings.waitFrameCapability,
                                onTextCapabilityEnabledChange = { controller.updateWaitFrameCapability(it) },
                                recordCapabilityEnabled = state.settings.recordMessageCapability,
                                onRecordCapabilityEnabledChange = { controller.updateRecordMessageCapability(it) },
                            )
                        }
                        DesktopSettingsNav.DataStorage -> {
                            SettingsDataSubpage(
                                dataDirectory = controller.dataDirectory,
                                cacheDirectory = controller.cacheDirectory,
                                dataUsageBytes = usage?.first,
                                cacheUsageBytes = usage?.second,
                                leftoverPartCount = leftoverParts?.size,
                                leftoverPartBytes = leftoverParts?.sumOf { it.size },
                                onClearLeftoverParts = {
                                    leftoverScope.launch {
                                        withContext(Dispatchers.Default) { controller.clearLeftoverParts() }
                                        leftoverEpoch.intValue++
                                    }
                                },
                                onBack = null,
                                onOpenDataDirectory = { openInFileManager(controller.dataDirectory) },
                                persistMessagesEnabled = state.settings.persistMessages,
                                onPersistMessagesEnabledChange = { controller.updatePersistMessages(it) },
                                onClearHistory = { controller.clearAllMessages() },
                            )
                        }
                        DesktopSettingsNav.Diagnostics -> {
                            SettingsDiagSubpage(
                                state = state,
                                onBack = null,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private fun openInFileManager(path: String) {
    val dir = File(path)
    if (!dir.isDirectory) return
    try {
        java.awt.Desktop.getDesktop().open(dir)
    } catch (_: Exception) {
        // 无桌面环境（纯 SSH/容器）就当没点过。
    }
}

/**
 * 我们自己这条「退出」菜单项要不要画。
 *
 * macOS 上**不画**：AWT 应用菜单（屏幕菜单栏第 2 格，取进程名）自带 Quit，删不掉 ——
 * 再画一条就是真机上那两个退出项（真机证据）。它的行为由
 * [installAwtQuitHandler] 接到同一条退出路径上。
 * Windows/Linux 上**要画**：那两处没有 AWT 应用菜单，删了就没有退出入口了。
 */
internal fun showsOwnQuitItem(isMacos: Boolean): Boolean = !isMacos

/**
 * 要不要装 AWT 的退出处理器（把系统应用菜单的 Quit 接到 [onQuit]）。
 *
 * 两个前置都要真：桌面 API 可用（headless / 精简 JRE 下不可用）、且该平台支持
 * APP_QUIT_HANDLER（Windows/Linux 不支持，调用会抛 UnsupportedOperationException）。
 * 判定抽成纯函数是为了让它可单测 —— 这行判断反了的表现是**启动即崩**或**退出不生效**。
 */
internal fun shouldInstallAwtQuitHandler(desktopSupported: Boolean, quitHandlerSupported: Boolean): Boolean =
    desktopSupported && quitHandlerSupported

private val awtQuitHandlerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * macOS：把系统应用菜单里的 Quit 接到与菜单项、托盘、⌘Q 完全相同的退出路径上。
 *
 * 为什么必须接：JDK 默认的退出策略是直接结束 JVM，**绕过** [onQuit]（那里做摘控制面、
 * 停运行时等收尾）。接上之后「只有一个退出项」与「退出行为一致」同时成立。
 * 只装一次（进程级设置）；装不上就什么都不做 —— 那条路径退化成 JDK 默认行为，
 * 与今天之前一字不差，不会更糟。
 */
internal fun installAwtQuitHandler(onQuit: () -> Unit) {
    if (!awtQuitHandlerInstalled.compareAndSet(false, true)) return
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        val supported = java.awt.Desktop.isDesktopSupported() &&
            desktop.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)
        if (!shouldInstallAwtQuitHandler(java.awt.Desktop.isDesktopSupported(), supported)) return@runCatching
        desktop.setQuitHandler { _, response ->
            onQuit()
            response.performQuit()
        }
    }
}
