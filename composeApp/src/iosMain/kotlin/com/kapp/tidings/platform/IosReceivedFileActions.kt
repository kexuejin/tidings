// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kapp.tidings.platform

import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 侧 [ReceivedFileActions] 的真实实现：Quick Look 预览 + 系统分享面板。
 *
 * ## 为什么这两个按钮在 iOS 上曾经完全没有
 *
 * 收到的文件落在容器里的 `Library/Application Support/relay/downloads`（见
 * [IosAppPlatform.dataDirectory]）。那是应用私有目录：别的应用读不到，系统「文件」App
 * 也看不见。的真机验收里 I5 的结论是「收得到，取不走」—— 文件确实
 * 落盘了（sha256 与源逐字节相同），但用户除了那句「已存到 <路径>」之外没有任何出口。
 *
 * 补上之后两个动作各自对应：
 * - 「打开」= `UIDocumentInteractionController.presentPreview`，即系统 Quick Look。
 * - 「分享」= `UIActivityViewController`，即系统分享面板（AirDrop / 存储到「文件」/ 发给别的应用）。
 *
 * 与 Android 侧 `ACTION_VIEW` / `ACTION_SEND` 是同一组语义，只是 UIKit 的两个 API 名字不同。
 *
 * ## 与 Android 实现的三点差异
 *
 * 1. **不需要 FileProvider**。Android 必须签发 `content://` 才能越过沙箱；iOS 没有这条
 *    限制，`file://` URL 交给自己进程内的 UIKit 组件是合法的。
 * 2. **不需要"文件在不在可分享目录里"这层检查**。Android 的 `file_paths.xml` 只声明了
 *    `relay/downloads`，落在别处的路径会被 `FileProvider` 拒绝（这是有意堵住目录穿越）；
 *    iOS 侧这里只检查"文件还在不在"，因为用户在"另存为"里挑过的目录同样在沙箱内、同样可读。
 * 3. **必须在主队列上弹**。UIKit 的 present 只能在主线程做，而 [open] / [share] 是普通
 *    同步函数（契约如此），调用点不保证在主线程 —— 所以这里自己 `dispatch_async` 到主队列，
 *    与 `IosMdnsAdvertiser` / `IosMdnsBrowser` 搬主队列是同一条理由。
 *
 * ## 不许抛异常
 *
 * [ReceivedFileActions] 的契约：用户点一下按钮不该把应用掀掉。这里所有失败路径都只写
 * `NSLog` 然后放弃；「打开」失败时退到分享面板，因为"能把它发给别的应用"比一句"失败"有用
 * （Android 侧 `AndroidReceivedFileActions.open` 是同一条退路）。
 *
 * ## 为什么这里的诊断文案是 ASCII（而仓库里其它 iOS 日志是中文）
 *
 * CJK 字面量门禁（`tools/cjk-literal-gate.gradle.kts`，P2-21）的基线
 * `tools/cjk-literal-baseline.txt` 开头写的是「棘轮：只允许减少，不允许增加」，
 * 而它扫描的是**所有**生产源码里的字符串字面量，分不出"用户可见文案"和"诊断日志"。
 * 这些 `NSLog` 是诊断，不是用户可见文案；为了不让基线往上涨，这里刻意写成 ASCII。
 * **改回中文会让 `:cjkLiteralGate` 直接失败** —— 要改的话得连基线一起动。
 */
public class IosReceivedFileActions : ReceivedFileActions {

    /**
     * 预览控制器**必须存住**。
     *
     * `UIDocumentInteractionController` 不是 `UIViewController` 的子类，`presentPreview`
     * 之后没有任何人持有它 —— 局部变量一出作用域就被回收，预览面板会自己消失（或者压根不出现）。
     * 这与 `UNUserNotificationCenter.delegate` 是同一类坑：**系统只拿 weak 引用**。
     */
    private var previewController: UIDocumentInteractionController? = null

    /** delegate 同样是 weak 引用，不存就没人回答"从哪个控制器上弹预览"。 */
    private var previewDelegate: DocumentPreviewDelegate? = null

    override fun open(path: String) {
        val url = existingFileUrl(path) ?: return
        onMain {
            val host = topMostViewController()
            if (host == null) {
                NSLog("[relay-file] no window available, cannot open: $path")
                return@onMain
            }
            val controller = UIDocumentInteractionController.interactionControllerWithURL(url)
            val delegate = DocumentPreviewDelegate { host }
            controller.delegate = delegate
            previewDelegate = delegate
            previewController = controller
            if (!controller.presentPreviewAnimated(true)) {
                // 没有应用能预览这个类型：换一条路，让用户自己挑接收方。
                NSLog("[relay-file] no application can preview this type, falling back to share: $path")
                presentShareSheet(host, url)
            }
        }
    }

    override fun share(path: String) {
        val url = existingFileUrl(path) ?: return
        onMain {
            val host = topMostViewController()
            if (host == null) {
                NSLog("[relay-file] no window available, cannot share: $path")
                return@onMain
            }
            presentShareSheet(host, url)
        }
    }
}

/** 把 [NSURL] 交给系统分享面板。 */
private fun presentShareSheet(host: UIViewController, url: NSURL) {
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    // iPad 上 `UIActivityViewController` 是 popover，没有 `sourceView` 会直接崩。
    // iPhone 上这段是空操作，但同一份代码要能在两种设备上跑。
    sheet.popoverPresentationController?.let { popover ->
        popover.sourceView = host.view
        popover.sourceRect = host.view.bounds
    }
    host.presentViewController(sheet, animated = true, completion = null)
}

/**
 * 文件还在不在；不在就如实留痕并放弃。
 *
 * 用户在系统里删掉/搬走了已经收下的文件时，这里没有别的办法 —— 与
 * `AndroidReceivedFileActions.readableFile` 同一处理。
 */
private fun existingFileUrl(path: String): NSURL? {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
        NSLog("[relay-file] file is gone, cannot open or share: $path")
        return null
    }
    return NSURL(fileURLWithPath = path, isDirectory = false)
}

/** UIKit 的 present 只能在主队列上做；调用点不保证在主线程，所以在这里收敛。 */
private fun onMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) { block() }
}

/**
 * `UIDocumentInteractionController` 要由 delegate 回答"从哪个控制器上弹预览"。
 *
 * 不实现这个方法时 `presentPreviewAnimated` 直接返回 **false** —— 不崩溃，只是静默不出预览，
 * 所以它和 [IosReceivedFileActions.previewController] 一样必须被存住。
 *
 * `host` 是构造时捕获的**非空**控制器：`presentShareSheet` 已经确认过存在，
 * 返回可空值会与 ObjC 协议的非空签名对不上。
 */
private class DocumentPreviewDelegate(
    private val host: () -> UIViewController,
) : NSObject(), UIDocumentInteractionControllerDelegateProtocol {

    override fun documentInteractionControllerViewControllerForPreview(
        controller: UIDocumentInteractionController,
    ): UIViewController = host()
}
