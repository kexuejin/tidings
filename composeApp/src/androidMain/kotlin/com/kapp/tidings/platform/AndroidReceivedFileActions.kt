// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Android 侧 [ReceivedFileActions] 的真实实现：`ACTION_VIEW` / `ACTION_SEND` + `FileProvider`。
 *
 * **为什么必须是 FileProvider**：这些文件在 `filesDir/relay/downloads` 里，别的应用读不到。
 * 直接塞 `file://` 给 `ACTION_VIEW` 在 Android 7 起会抛 `FileUriExposedException`（这是系统
 * 在拦目录穿越），所以只能给 `content://` URI，并把读权限按次授予接收方。
 * URI 由 `context.packageName + [AUTHORITY_SUFFIX]` 这个 authority 签发，与
 * `androidApp/src/main/AndroidManifest.xml` 里 `${applicationId}.fileprovider` 必须一致
 * （两边都是 `com.kapp.tidings.fileprovider`）；能签发哪些目录由
 * `androidApp/src/main/res/xml/file_paths.xml` 决定。
 *
 * **只签发那一个目录**：`file_paths.xml` 只声明 `relay/downloads`（= `AndroidFilePicker` 的
 * `FALLBACK_DIR`，收到的东西默认就落在这里）。不为"别的目录也能分享"去加 `<external-path>`：
 * 那等于把 FileProvider 本来要堵的目录穿越洞重新打开。落在声明目录之外的路径（用户在
 * "另存为"里挑了自己的目录时会出现）在这里如实放弃并写日志 —— 宁可少一个按钮能用，
 * 也不放开整棵外部存储。
 *
 * **`open` 失败会退到分享面板**：某些类型系统里没有能打开它的应用（`ActivityNotFoundException`），
 * 此时直接摊开分享面板，用户仍能把它发给别的应用 —— 这条出口比一句"失败"有用。
 *
 * 用的是 `Application` 的 context（平台对象在 `RelayApplication` 里构造），所以每个 Intent
 * 都必须带 `FLAG_ACTIVITY_NEW_TASK`。
 */
public class AndroidReceivedFileActions(private val context: Context) : ReceivedFileActions {

    override fun open(path: String) {
        val file = readableFile(path) ?: return
        val uri = contentUri(file) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeOf(file))
            addFlags(START_FLAGS)
        }
        if (!start(intent)) {
            // 没有应用能"查看"这个类型：换一条路，让用户自己挑接收方。
            Log.w(TAG, "没有应用能打开这个类型，改为分享：${file.name}")
            share(path)
        }
    }

    override fun share(path: String) {
        val file = readableFile(path) ?: return
        val uri = contentUri(file) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeOf(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(START_FLAGS)
        }
        // 选择器标题用文件名：不写死任何语言的字面量，又比留空更说明"在分享什么"。
        start(Intent.createChooser(send, file.name).addFlags(START_FLAGS))
    }

    /** 真的启动一次；返回 false 表示"这次没起来"（已经写过日志，调用方决定要不要换条路）。 */
    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "没有应用能接 ${intent.action}：${e.message}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "启动 ${intent.action} 被拒：${e.message}")
        false
    }

    private fun readableFile(path: String): File? {
        val file = File(path)
        if (!file.isFile) {
            // 用户在系统里删掉/搬走了已经收下的文件：这里没有别的办法，如实留痕。
            Log.w(TAG, "文件不在了，打不开也分享不了：$path")
            return null
        }
        return file
    }

    private fun contentUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "文件不在 file_paths.xml 声明的可分享目录里：${file.absolutePath}")
        null
    }

    /** 认不出类型时给通配类型（见 [FALLBACK_MIME]）：比编一个假类型好，接收方会自己按文件名判断。 */
    private fun mimeTypeOf(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension.isEmpty()) return FALLBACK_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: FALLBACK_MIME
    }

    public companion object {
        /** authority 后缀；`AndroidManifest.xml` 里写的是 `${applicationId}.fileprovider`。 */
        public const val AUTHORITY_SUFFIX: String = ".fileprovider"

        private const val TAG = "AndroidFileActions"
        private const val FALLBACK_MIME = "*/*"
        private const val START_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    }
}
