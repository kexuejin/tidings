// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Compose 层与 SAF 之间的桥。
 *
 * `ActivityResultLauncher` 只能在 Activity/Compose 里注册，而 `AppPlatform` 在
 * Application 里构造 —— 两者生命周期不同，所以用一个显式的桥把 launcher 交进来。
 * `pick()` 挂起等结果，超时或取消都返回 null。
 */
class AndroidPickerBridge {

    /** 当前注册的启动器。 */
    private var launcher: ((Array<String>) -> Unit)? = null
    private var multipleLauncher: ((Array<String>) -> Unit)? = null

    /**
     * 当前挂起的那一次选择。
     *
     * 同一时刻只允许一次：系统选择器是前台独占的，第二次放映会把第一次的回调吞掉，
     * 那个挂起的调用方就只能干等到超时，所以第二次直接回 null。
     */
    private var pending: CompletableDeferred<String?>? = null
    private var pendingMultiple: CompletableDeferred<List<String>?>? = null

    /**
     * Compose 层注册进来的启动器；未注册时 pick() 直接返回 null。
     *
     * 交进来的是一个"发一次选择请求"的动作，而不是 `ActivityResultLauncher` 本身：
     * 平台层因此不必认识 activity-result 的类型、也就不会攥着一个 Activity 引用不放。
     */
    fun attach(launch: (Array<String>) -> Unit) {
        launcher = launch
    }

    /** Compose 层注册的多文件启动器。 */
    fun attachMultiple(launch: (Array<String>) -> Unit) {
        multipleLauncher = launch
    }

    /**
     * 宿主离开时断开。
     *
     * 这里必须**主动结束**挂起的那次选择：launcher 已经随 Activity 销毁，回调永远
     * 不会来，不结束的话调用方要白等满 [launch] 的超时才拿得到 null。
     */
    fun detach() {
        launcher = null
        multipleLauncher = null
        pending?.complete(null)
        pending = null
        pendingMultiple?.complete(null)
        pendingMultiple = null
    }

    /** Compose 层在 ActivityResultCallback 里调用，把结果交回挂起的 pick()。 */
    fun deliver(uri: String?) {
        // `complete` 在已完成时返回 false，重复投递天然幂等：系统可能在重建后补投一次。
        pending?.complete(uri)
    }

    /** Compose 层在多选回调里调用，把结果交回挂起的 pickMultiple()。 */
    fun deliverMultiple(uris: List<String>) {
        pendingMultiple?.complete(uris)
    }

    /** 挂起等待；没有桥或用户取消返回 null。 */
    suspend fun launch(mimeTypes: Array<String>, timeoutMs: Long = 5 * 60_000L): String? {
        val start = launcher ?: return null
        if (pending != null || pendingMultiple != null) return null

        val deferred = CompletableDeferred<String?>()
        pending = deferred
        return try {
            start(mimeTypes)
            // 超时和"用户取消"都落到 null —— 对调用方而言两者要做的事完全一样。
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            // 只有自己那一次才清，避免把后来者的 deferred 抹掉。
            if (pending === deferred) pending = null
        }
    }

    /** 挂起等待多选文件；未附着多选桥或用户取消返回空列表。 */
    suspend fun launchMultiple(mimeTypes: Array<String>, timeoutMs: Long = 5 * 60_000L): List<String> {
        val start = multipleLauncher
        if (start == null) {
            return launch(mimeTypes, timeoutMs)?.let { listOf(it) } ?: emptyList()
        }
        if (pending != null || pendingMultiple != null) return emptyList()

        val deferred = CompletableDeferred<List<String>?>()
        pendingMultiple = deferred
        return try {
            start(mimeTypes)
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: emptyList()
        } finally {
            if (pendingMultiple === deferred) pendingMultiple = null
        }
    }
}

/**
 * SAF 选文件 → 拷贝进缓存 → 交出真实路径。
 *
 * 为什么非要拷贝：`PickedFile.path` 要交给传输层用 `RandomAccessFile` 按块读，而 SAF
 * 给的是 `content://` URI —— 它没有文件系统路径，`RandomAccessFile` 打不开。所以选中的
 * 那一刻就把内容复制进应用缓存，之后一切都按普通文件走。
 *
 * 拷贝失败（源被撤权、流中途断、0 字节）一律返回 null 而不是抛错：这是用户点出来的
 * 一次交互，失败应该回到"没选"那条路，不该把异常扔进 UI。
 */
class AndroidFilePicker(
    private val context: Context,
    private val bridge: AndroidPickerBridge,
) : FilePicker {

    override suspend fun pick(): PickedFile? {
        val uriString = bridge.launch(arrayOf("*/*")) ?: return null
        return withContext(Dispatchers.IO) { copyToCache(Uri.parse(uriString)) }
    }

    override suspend fun pickMultiple(): List<PickedFile> {
        val uriStrings = bridge.launchMultiple(arrayOf("*/*"))
        if (uriStrings.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            uriStrings.mapNotNull { copyToCache(Uri.parse(it)) }
        }
    }

    private fun copyToCache(uri: Uri): PickedFile? {
        val name = sanitize(displayName(uri))
        val directory = File(context.cacheDir, PICKS_DIR).apply { mkdirs() }
        if (!directory.isDirectory) return null

        val target = uniqueTarget(directory, name)
        val copied = try {
            val source = context.contentResolver.openInputStream(uri)
            if (source == null) {
                null
            } else {
                source.use { input ->
                    target.outputStream().use { sink -> input.copyTo(sink) }
                }
                target
            }
        } catch (error: Exception) {
            // 半个文件比没有文件更糟：传输层会照 length 发送，对端拿到的是截断内容。
            // 撤权、对端拔盘、磁盘满都在这里汇成同一个结果 —— 当作没选。
            target.delete()
            null
        } ?: return null

        if (copied.length() <= 0L) {
            copied.delete()
            return null
        }
        return PickedFile(name = name, sizeBytes = copied.length(), path = copied.absolutePath)
    }

    /**
     * 取展示名。
     *
     * 三级兜底，因为 provider 什么都能给：正规 provider 报 `DISPLAY_NAME`；报空时用 URI
     * 末段（多数 provider 把它当文件名）；再不行给个固定的 `file`，至少让拷贝有个落点。
     */
    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: "file"
    }

    /**
     * 把名字收敛成一个能当文件名的字符串。
     *
     * 这个值同时是**对端保存时用的文件名**，所以路径分隔符绝不能活着穿过去 —— 否则
     * 对端拼路径时会被写到别的目录里。
     */
    private fun sanitize(raw: String): String =
        raw.replace('/', '_').replace('\\', '_').trim()
            .takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "file"

    /**
     * 同名不覆盖。
     *
     * 上一次选中的文件可能还在发送中（传输层正按旧路径读），直接截断会让那次传输读到
     * 混着两个文件的内容，所以退让出 `-1`、`-2`…… 而不是复用同名文件。
     */
    private fun uniqueTarget(directory: File, name: String): File {
        val candidate = File(directory, name)
        if (!candidate.exists()) return candidate

        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var index = 1
        while (true) {
            val next = File(directory, "$base-$index$suffix")
            if (!next.exists()) return next
            index++
        }
    }

    private companion object {
        const val PICKS_DIR = "relay-picks"
    }
}

/**
 * SAF 选目录 → 真实可写绝对路径。
 *
 * **为什么这件事在 Android 上天生别扭**：`ACTION_OPEN_DOCUMENT_TREE` 回给你的是 tree
 * URI（`content://.../tree/primary%3ADownload`），那是"授权"，不是路径。写它的正规方式
 * 是走 `ContentResolver`/`DocumentFile` 一个文档一个文档地建；但传输层收完文件是要直接
 * `File` 落地，所以这里只能反推路径。
 *
 * 反推方式是拿 tree 的 document id（`primary:Download`）把卷标映射回真实根：
 * `primary` → `/storage/emulated/0`，其余卷标 → `/storage/<卷标>`；映射出来的目录还得
 * `canWrite()` 为真才算数。
 *
 * **诚实的局限**：Android 10 起的分区存储下，SAF 授权**不等于**普通文件系统写权限 ——
 * 用户选的即使是 `Download`，`canWrite()` 也常常是 false。这时不硬着头皮返回那个路径
 * （返回了也是个写就抛异常的路径），而是退回到应用自己的 `filesDir/relay/downloads`：
 * 收到的东西先落到一个**一定写得进去**的地方。代价是它不显示在用户的"下载"目录里，
 * 想改这一点需要 `MANAGE_EXTERNAL_STORAGE` 或 MediaStore，那是另一个决定。
 *
 * 只有用户取消才返回 null。
 */
class AndroidSaveDirectoryPicker(
    private val context: Context,
    private val bridge: AndroidPickerBridge,
) : SaveDirectoryPicker {

    override suspend fun pickDirectory(): String? {
        val treeUri = bridge.launch(arrayOf(DIRECTORY_MIME)) ?: return null
        return withContext(Dispatchers.IO) { resolveDirectory(Uri.parse(treeUri)) }
    }

    private fun resolveDirectory(uri: Uri): String =
        writablePathOf(uri) ?: fallbackDirectory()

    private fun fallbackDirectory(): String =
        File(context.filesDir, FALLBACK_DIR).apply { mkdirs() }.absolutePath

    /**
     * tree URI → 真实路径；返 null 表示"这一条路走不通，去用兜底目录"。
     *
     * 每一步都可能是 null（不是 tree、认不出的卷标、映射出来不可写），所以整条链只回答
     * 一个问题："这个 URI 有没有对应一个现在真能写的目录？"
     */
    private fun writablePathOf(uri: Uri): String? {
        // 少数 provider（含部分文件管理器的 file:// 回投）直接给路径，那就不用映射。
        if (uri.scheme == FILE_SCHEME) {
            return uri.path?.let { File(it) }
                ?.takeIf { it.isDirectory && it.canWrite() }
                ?.absolutePath
        }

        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val volumeId = documentId.substringBefore(':', missingDelimiterValue = "")
        val relative = documentId.substringAfter(':', missingDelimiterValue = "")
        if (volumeId.isEmpty()) return null

        val root = if (volumeId == PRIMARY_VOLUME) externalStorageRoot() else File("/storage", volumeId)
        val directory = if (relative.isEmpty()) root else File(root, relative)
        // 已存在就用现成的；不存在就试着建（SAF 允许在建，普通文件 API 也未必建得动）。
        val usable = directory.isDirectory || directory.mkdirs()
        return directory.takeIf { usable && it.canWrite() }?.absolutePath
    }

    @Suppress("DEPRECATION")
    private fun externalStorageRoot(): File = Environment.getExternalStorageDirectory()

    companion object {
        /**
         * 选目录的 MIME 是 SAF 自己规定的哨兵值，不是真类型。
         *
         * 公开出来是因为 [AndroidPickerBridge] 只有一个 `attach` 回调，宿主必须靠它
         * 分辨"这次要开的是选文件还是选目录"——两边各写一份字面量迟早会写歪。
         */
        const val DIRECTORY_MIME = "vnd.android.document/directory"
        const val FILE_SCHEME = "file"
        const val PRIMARY_VOLUME = "primary"
        const val FALLBACK_DIR = "relay/downloads"
    }
}
