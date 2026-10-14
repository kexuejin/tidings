// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import app.relay.platform.ReceivedFileActions
import java.awt.Desktop
import java.io.File

/**
 * 桌面侧 [ReceivedFileActions] 的真实实现。
 *
 * ## 两个动作在桌面上各自对应什么
 *
 * - 「打开」= 交给系统默认应用：AWT `Desktop.open`，等价于在 Finder / 资源管理器里双击。
 * - 「分享」= **在文件管理器里定位这个文件**。
 *
 * 第二条需要说清楚：桌面**没有系统分享面板**。macOS 的「共享」菜单长在 Finder 里，
 * AWT 没有对它的封装，硬做就得 `ProcessBuilder("open", ...)` 或走 AppleScript —— 前者
 * 等于把文件类型再猜一遍，后者要 TCC 授权。所以这里做的是这一步里真正能兑现的那半：
 * 把文件在文件管理器里亮出来，用户可以直接拖进任何应用，也可以用 Finder 自己的
 * 右键 → 共享。**如实做到这一步，而不是把「分享」做成一个按下去什么都不发生的按钮。**
 *
 * 优先用 `Desktop.Action.BROWSE_FILE_DIR`（Java 9+，会选中该文件本身）；不支持时退到
 * `BROWSE` 打开它所在的目录 —— 目录里那一个文件同样找得到。
 *
 * ## 为什么不直接调 AWT
 *
 * `java.awt.Desktop` 是 final 类且方法直连系统，套不进单测。所以这里把用到的五件事
 * 收成 [DesktopFileHost] 这个窄接口（与 `DnsSdRunner` 是同一手法），
 * 单测塞假实现就能验"点了按钮到底做了什么"，不必真的去开系统应用。
 *
 * ## 为什么这里的诊断文案是 ASCII（而仓库里其它桌面日志是中文）
 *
 * CJK 字面量门禁（`tools/cjk-literal-gate.gradle.kts`，P2-21）的基线
 * `tools/cjk-literal-baseline.txt` 开头写的是「棘轮：只允许减少，不允许增加」，
 * 而它扫描的是**所有**生产源码里的字符串字面量，分不出"用户可见文案"和"诊断日志"
 * （仓库里已有的 122 处中文日志是被冻结在基线里的历史存量）。
 * 这些 `println` 是诊断，不是用户可见文案；为了不让基线往上涨，这里刻意写成 ASCII。
 * **改回中文会让 `:cjkLiteralGate` 直接失败** —— 要改的话得连基线一起动。
 *
 * ## 不许抛异常
 *
 * [ReceivedFileActions] 的契约：用户点一下按钮不该把应用掀掉。所有失败都写日志后放弃。
 * 尤其是 `Desktop.isDesktopSupported()` 为 false 的场合（headless 运行时，例如某些
 * Linux 容器里只有 headless JDK）—— 那时连「打开」都没有，只能留痕。
 */
public class DesktopReceivedFileActions(
    private val host: DesktopFileHost? = SystemDesktopFileHost.detect(),
) : ReceivedFileActions {

    override fun open(path: String) {
        val file = readableFile(path) ?: return
        // 取成局部 val：`host` 是类属性，Kotlin 不对它做智能转换（属性可能被重写/有自定义 getter）。
        val host = host
        if (host == null) {
            warnNoDesktop()
            return
        }
        if (!host.canOpen()) {
            log("this desktop cannot open files with the default application: ${file.name}")
            return
        }
        runCatching { host.open(file) }.onFailure { log("opening ${file.name} failed", it) }
    }

    override fun share(path: String) {
        val file = readableFile(path) ?: return
        val host = host
        if (host == null) {
            warnNoDesktop()
            return
        }
        if (host.canRevealFile()) {
            runCatching { host.revealFile(file) }
                .onFailure { log("revealing ${file.name} in the file manager failed", it) }
            return
        }
        val parent = file.parentFile
        if (parent == null || !host.canBrowseDirectory()) {
            log("this desktop can neither reveal a file nor browse a directory: ${file.name}")
            return
        }
        runCatching { host.browseDirectory(parent) }
            .onFailure { log("browsing the directory of ${file.name} failed", it) }
    }

    private fun readableFile(path: String): File? {
        val file = File(path)
        if (!file.isFile) {
            // 用户在系统里删掉/搬走了已经收下的文件：这里没有别的办法，如实留痕。
            log("file is gone, cannot open or share: $path")
            return null
        }
        return file
    }

    private fun warnNoDesktop() {
        log("no desktop environment (headless); open/share unavailable")
    }

    private fun log(message: String, cause: Throwable? = null) {
        val detail = cause?.let { " (${it.javaClass.simpleName}: ${it.message})" }.orEmpty()
        // 与 `desktopApp` 的 `Main.kt` 同一约定：桌面端没有日志框架，诊断走 stdout。
        println("$TAG $message$detail")
    }

    private companion object {
        const val TAG = "relay:"
    }
}

/**
 * 这个实现真正用到的五件桌面能力。
 *
 * 存在的唯一理由是**可测**：`java.awt.Desktop` 是 final 类、方法直连系统，
 * 单测里没法既验行为又不真的弹出 Finder。
 */
public interface DesktopFileHost {
    public fun canOpen(): Boolean
    public fun open(file: File)

    public fun canRevealFile(): Boolean
    public fun revealFile(file: File)

    public fun canBrowseDirectory(): Boolean
    public fun browseDirectory(directory: File)
}

/** 真机实现：把五个动作原样转发给 AWT `Desktop`。 */
public class AwtDesktopFileHost(private val desktop: Desktop) : DesktopFileHost {

    override fun canOpen(): Boolean = desktop.isSupported(Desktop.Action.OPEN)

    override fun open(file: File) {
        desktop.open(file)
    }

    override fun canRevealFile(): Boolean = desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)

    override fun revealFile(file: File) {
        desktop.browseFileDirectory(file)
    }

    override fun canBrowseDirectory(): Boolean = desktop.isSupported(Desktop.Action.BROWSE)

    override fun browseDirectory(directory: File) {
        desktop.browse(directory.toURI())
    }
}

/**
 * 检测当前运行时有没有桌面。
 *
 * 返回 `null` 是**设备事实**（headless，没有显示设备），不是"这次失败" ——
 * 调用方据此留痕并放弃，而不是重试。
 */
public object SystemDesktopFileHost {
    public fun detect(): DesktopFileHost? {
        if (!Desktop.isDesktopSupported()) return null
        return AwtDesktopFileHost(Desktop.getDesktop())
    }
}
