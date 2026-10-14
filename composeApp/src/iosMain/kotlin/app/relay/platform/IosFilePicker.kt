// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package app.relay.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject

/**
 * iOS 的文件选择。
 *
 * ## 为什么两件事要分开做
 *
 * 选择器交回来的**不是路径，是授权**。iOS 的 `UIDocumentPickerViewController` 回给你一个
 * security-scoped URL —— 那是沙箱外那份文件的一张"临时通行证"，`path()` 上打印出来的
 * 字符串离开这张证就什么都读不到。而 [FilePicker] 的契约是一条**平台可读的绝对路径**
 * （传输层会拿它反复 `NSFileHandle` 按块读），两者的生命周期根本不匹配。
 *
 * 所以这里的做法是：选择器一回来，立刻把内容拷进 `NSTemporaryDirectory()/relay-picks/`，
 * 交出去的是那个副本的路径。副本在我们自己的沙箱里，没有授权期限，传输层读到发送结束
 * 都有效；代价是落盘多一份，但它同时换来一个好处 —— 用户选完之后再把源文件删掉、
 * 拔掉 U 盘、iCloud 文件被系统驱逐，这次发送都不会受影响。
 *
 * Android 侧（`AndroidFilePicker`）是同一套取舍：SAF 的 `content://` URI 同样不是路径，
 * 同样先拷进 cache 再把真实路径交出去。两侧的文件名规则也刻意逐字一致，否则同一个
 * 文件在两个平台上会落到两个不同的名字上。
 */

/** 用户在选择器里停留的上限。与 Android 侧 `AndroidPickerBridge.launch` 的 5 分钟对齐。 */
private const val PICK_TIMEOUT_MS = 5 * 60_000L

/** 副本落点，与 Android 侧的 `PICKS_DIR` 同名同义。 */
private const val PICKS_DIR = "relay-picks"

/**
 * 系统通用文件类型 `public.data`。
 *
 * 用 `UTTypeData` 而不是更宽的 `UTTypeItem`：`UTTypeItem` 把目录也算进去，而本契约要交付的
 * 是"一个能用 `NSFileHandle` 按块读的文件"（见 [PickedFile]），选到目录时传输层会直接读失败。
 * 把目录挡在选择器里，比让它通过了再由我们拒绝要诚实。
 */
private val FILE_CONTENT_TYPES: List<Any> = listOf<Any>(UTTypeData)

/** 系统目录类型 `public.folder`，给 [SaveDirectoryPicker] 用。 */
private val FOLDER_CONTENT_TYPES: List<Any> = listOf<Any>(UTTypeFolder)

/**
 * `UIDocumentPickerViewController` 的 delegate。
 *
 * **这个对象必须由我们持有到回调发生为止**：UIKit 的 `delegate` 属性是 `weak`（ObjC 的
 * assign 语义），picker 根本不持有它。回调发生在 present 之后的某个 runloop turn，
 * 如果持有者只是一个"present 完就不再被读"的局部变量，编译器可以提前结束它的生命周期，
 * 于是 `objc_msgSend` 打在已释放对象上 —— 这是这套 API 最经典的偶发崩溃。
 * 持有者是 [DocumentPickerSession.activeDelegate] 字段，不是局部变量，正是为了排除这种可能。
 */
private class DocumentPickerDelegate(
    private val deferred: CompletableDeferred<NSURL?>,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        // 先完成 deferred 再 dismiss：反过来的话，程序化 dismiss 有可能再触发一次
        // documentPickerWasCancelled，把刚刚这次选择覆盖成 null。`complete` 幂等，
        // 所以顺序换过来就是"选择偶尔丢失"，而且只在真机上偶发。
        deferred.complete(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        // 系统自己也会关掉 picker；这里再关一次是为了覆盖它不关的那些路径，重复调用无害。
        controller.dismissViewControllerAnimated(true, completion = null)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        // 取消路径不必 dismiss：这个回调本身就是"picker 正在关闭"的结果。
        deferred.complete(null)
    }
}

/**
 * 一次"打开选择器并等一个 URL"的会话。
 *
 * 两个 picker 各持一个实例，而不是各写一份：delegate 的持有规则（见
 * [DocumentPickerDelegate]）是**同一条**约束，复制两份的话，将来只修好其中一份就会
 * 留下一个只在某个契约上偶发的崩溃。
 *
 * 之所以记在**实例字段**而不是每次调用一个局部变量：局部变量在挂起之后就不再被读取，
 * 编译器与精确 GC 没有义务留它；而这里的实例被 `IosAppPlatform` 长期持有，
 * [activeDelegate] 因此始终可达。
 */
private class DocumentPickerSession {

    /**
     * 当前这次选择会话的 delegate。类型是 `Any?` 而不是 `DocumentPickerDelegate`：
     * 这里要的只是"一个强引用"，而强引用本身跟具体协议无关。
     */
    private var activeDelegate: Any? = null

    /**
     * 在主线程打开选择器并等待回调。
     *
     * 返回选中项的安全作用域 URL；用户取消、等待超时、拿不到可以 present 的控制器，
     * 都返回 null。
     */
    suspend fun select(contentTypes: List<Any>, asCopy: Boolean): NSURL? =
        withContext(Dispatchers.Main) {
            // UIKit 只能从主线程碰；present 更是必须主线程，所以整段都在 Main 上。
            val root = topMostViewController() ?: return@withContext null

            val deferred = CompletableDeferred<NSURL?>()
            val delegate = DocumentPickerDelegate(deferred)
            // 参数名必须写全：`forOpeningContentTypes:` 与 `forExportingURLs:` 生成出来的
            // 构造器签名都是 (List<*>, Boolean)，位置参数在这里是二义的。
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = contentTypes,
                asCopy = asCopy,
            )
            activeDelegate = delegate
            picker.delegate = delegate

            root.presentViewController(picker, animated = true, completion = null)
            try {
                withTimeoutOrNull(PICK_TIMEOUT_MS) { deferred.await() }
            } finally {
                // 这几件事都要在 finally 里做：正常选完、用户取消、调用方取消（协程被取消时
                // await 会抛 CancellationException）、以及超时。
                // 漏掉最后一种的话，页面上会留下一个永远关不掉的模态框。
                activeDelegate = null
                picker.delegate = null
                picker.dismissViewControllerAnimated(true, completion = null)
            }
        }
}

/** 系统文档选择器 → 一份拷进应用临时目录、可以被传输层反复读的文件。 */
class IosFilePicker : FilePicker {

    private val session = DocumentPickerSession()

    override suspend fun pick(): PickedFile? = guarded {
        // asCopy = true 让系统先把内容复制到我们沙箱里再交给我们：这样即便后面的
        // 拷贝失败，我们也从没从沙箱外的路径上读过字节。
        val url = session.select(FILE_CONTENT_TYPES, asCopy = true) ?: return@guarded null
        // 拷贝可能几百 MB，放主线程会直接冻结界面 —— 与 Android 侧把 SAF 读挪到
        // Dispatchers.IO 是同一条理由。
        withContext(Dispatchers.Default) { copyPickedFile(url) }
    }
}

/** 系统目录选择器 → 一个用户可以往里面写收到文件的绝对路径。 */
class IosSaveDirectoryPicker : SaveDirectoryPicker {

    private val session = DocumentPickerSession()

    override suspend fun pickDirectory(): String? = guarded {
        // asCopy = false：目录没法"先拷一份再交出去"（拷一棵目录树既慢又不是用户要的），
        // 所以回给我们的就是沙箱外那条真实路径的安全作用域授权。
        val url = session.select(FOLDER_CONTENT_TYPES, asCopy = false) ?: return@guarded null

        // 读 `path()` 本身不需要授权，但传输层收完文件要往这个目录**写**，那需要。
        // 这里显式打开作用域，并且**有意不关**：
        //   1. 关掉的话交出去的路径立刻不可写，这个契约等于没实现；
        //   2. 授权是进程级资源，进程退出即释放，上界就是这一次会话；
        //   3. 要跨启动保留只能存 security-scoped bookmark，而契约的返回类型是 String，
        //      没有承载 bookmark 的地方 —— 那是 P5 的事。
        // 返回 false 不代表失败：也可能这个 URL 本来就不是安全作用域的，那条路径直接可写。
        url.startAccessingSecurityScopedResource()
        url.path()
    }
}

/**
 * 把选中项拷进 `NSTemporaryDirectory()/relay-picks/`，返回副本与它的大小。
 *
 * 名字与大小的处理跟 `AndroidFilePicker.copyToCache` 一一对应，包括"同名不覆盖"和
 * "0 字节当作没选"这两条 —— 传输层会照 `sizeBytes` 分片，半个文件比没有文件更糟。
 */
private fun copyPickedFile(url: NSURL): PickedFile? {
    val manager = NSFileManager.defaultManager
    val name = sanitizeFileName(url.lastPathComponent() ?: url.path() ?: return null)
    val directory = NSTemporaryDirectory().trimEnd('/') + "/" + PICKS_DIR
    // 目录已存在时 createDirectoryAtPath 返回 false，那不是失败，所以不检查返回值。
    manager.createDirectoryAtPath(
        directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )

    val targetPath = uniqueTargetPath(manager, directory, name)
    val target = NSURL(fileURLWithPath = targetPath, isDirectory = false)

    // `asCopy = true`（文件选择）时系统已经给了一份沙箱内的副本，这里 start 会返回
    // false —— 没有作用域要开，也就没有要配对的 stop。
    // `asCopy = false`（目录选择）时它才真的是沙箱外的安全作用域，start 返回 true，
    // 于是必须在读完之后 stop，否则每选一次就多消耗一份沙箱扩展额度。
    val scoped = url.startAccessingSecurityScopedResource()
    val copied = try {
        manager.copyItemAtURL(url, toURL = target, error = null)
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
    if (!copied) return null

    val attributes = manager.attributesOfItemAtPath(targetPath, error = null) ?: return null
    // 目录也有属性也有 NSFileSize，但契约说的是文件；不是普通文件就撤销这次拷贝。
    if (attributes[NSFileType] != NSFileTypeRegular) {
        manager.removeItemAtPath(targetPath, error = null)
        return null
    }
    val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return null
    if (size <= 0L) {
        manager.removeItemAtPath(targetPath, error = null)
        return null
    }
    return PickedFile(name = name, sizeBytes = size, path = targetPath)
}

/**
 * 把平台异常折成 null。
 *
 * [FilePicker] / [SaveDirectoryPicker] 的返回类型已经是"可能没有"，UI 也按"没有"来画，
 * 所以 Foundation/UIKit 抛出来的异常不该穿到界面层去。
 *
 * 唯一例外是 [CancellationException]：那是**调用方自己**取消了，吞掉它会让已经离开的
 * 调用方收到一个凭空出现的返回值，也会破坏结构化并发。它在协程里不是"失败"。
 */
private suspend fun <T> guarded(block: suspend () -> T?): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

/**
 * 找到当前可以 present 的控制器。
 *
 * 必须走到最上层已经 present 的那个：从被覆盖的控制器上再 present 会抛
 * `Attempt to present ... which is already presenting ...`，而那个异常在真机上才出现。
 *
 * `keyWindow` 在 iOS 13 起已废弃（多场景下应该问 `UIScene`），但这个应用只有一个 window
 * （`MainViewController` 建的那一个），换成遍历 `connectedScenes` 只会多一段没有收益的代码。
 */
internal fun topMostViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.keyWindow ?: return null
    var top: UIViewController = window.rootViewController ?: return null
    while (true) {
        top = top.presentedViewController ?: return top
    }
}

/**
 * 把名字收敛成一个能当文件名的字符串。
 *
 * 这个值同时是**对端保存时用的文件名**，所以路径分隔符绝不能活着穿过去 —— 否则对端拼
 * 路径时会被写到别的目录里（`../` 那类）。规则与 `AndroidFilePicker.sanitize` 逐字一致。
 */
private fun sanitizeFileName(raw: String): String =
    raw.replace('/', '_').replace('\\', '_').trim()
        .takeIf { it.isNotEmpty() && it != "." && it != ".." }
        ?: "file"

/**
 * 同名不覆盖 —— 与 `AndroidFilePicker.uniqueTarget` 同一套规则。
 *
 * 上一次选中的副本可能还在发送中（传输层正按旧路径读），直接覆盖会让那次传输读到两个
 * 文件混在一起的内容，所以退让出 `-1`、`-2`……
 *
 * 注意退让只影响**落盘的路径**：交出去的 `PickedFile.name` 仍然是原始名字，对端保存时
 * 用的是那个名字，跟 Android 侧一样。
 */
private fun uniqueTargetPath(manager: NSFileManager, directory: String, name: String): String {
    val first = "$directory/$name"
    if (!manager.fileExistsAtPath(first)) return first

    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "")
    val suffix = if (extension.isEmpty()) "" else ".$extension"
    var index = 1
    while (true) {
        val next = "$directory/$base-$index$suffix"
        if (!manager.fileExistsAtPath(next)) return next
        index++
    }
}
