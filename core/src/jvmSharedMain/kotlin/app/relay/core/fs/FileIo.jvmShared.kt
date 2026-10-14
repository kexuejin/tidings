// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.fs

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile as JdkRandomAccessFile

/**
 * JVM 与 Android **共用**的一份实现：`java.io.RandomAccessFile` / `java.io.File`
 * 在 ART 上就是同一套 API。
 *
 * 这一份原来在 `jvmMain/FileIo.jvm.kt` 与 `androidMain/FileIo.android.kt` 下各有一份，
 * 实测**只差一个内部类名**（`JvmRandomAccessFile` vs `AndroidRandomAccessFile`）外加一段
 * 自述「按项目惯例各留一份」的注释。两份都能编译，两份都能跑，漏改其中一份的症状是
 * 「桌面端续传正常、安卓上 resume 从 0 开始重拉」—— 只有真机能暴露。
 *
 * `RandomAccessFile` 的 `seek` + `read(buffer, 0, count)` 正好就是断点续传需要的两件事，
 * 不用自己维护游标。`append` 每次都先 seek 到 `length()` —— 句柄被别处追加过之后仍写在真实末尾，
 * 不会在中间留空洞。
 *
 * 放在 `jvmSharedMain`（声明见 `core/build.gradle.kts`）而不是 `commonMain`：
 * `commonMain` 是跨 iOS 的，而 iOS 那份走 NSFileHandle，API 形状根本不同。
 */
private class JdkRandomAccessFileHandle(private val file: JdkRandomAccessFile) : RandomAccessFile {

    override val size: Long get() = file.length()

    override fun read(buffer: ByteArray, atOffset: Long, count: Int): Int {
        file.seek(atOffset)
        // 直接透传：`RandomAccessFile.read` 到末尾就返回 -1，与 InputStream 语义一致。
        return file.read(buffer, 0, count)
    }

    override fun append(buffer: ByteArray, count: Int) {
        file.seek(file.length())
        file.write(buffer, 0, count)
    }

    /**
     * `RandomAccessFile` 没有用户态缓冲，写就是 write(2)。这里做 `fd.sync()` 是为了
     * 崩溃后 `.part` 的长度可信 —— 对端已经确认收到的字节不能只停在页缓存里。
     * iOS 侧对应的 `synchronizeFile()` 就是同一个语义。
     */
    override fun flush() = file.fd.sync()

    override fun close() = file.close()
}

actual fun openRandomAccess(path: String, forWrite: Boolean): RandomAccessFile {
    val file = File(path)
    if (forWrite) {
        // 只建父目录：文件本身交给 "rw" 创建，省掉一次 createFile 的竞态。
        file.parentFile?.mkdirs()
    } else if (!file.isFile) {
        throw IllegalStateException("文件不存在，无法只读打开：$path")
    }
    val mode = if (forWrite) "rw" else "r"
    return try {
        JdkRandomAccessFileHandle(JdkRandomAccessFile(file, mode))
    } catch (e: IOException) {
        throw IllegalStateException("打开文件失败（mode=$mode）：$path", e)
    }
}

actual fun fileSize(path: String): Long? {
    val file = File(path)
    // 目录也有 length()，但协议里的 size 说的是文件；不是文件就当不存在。
    return if (file.isFile) file.length() else null
}

actual fun fileExists(path: String): Boolean = File(path).exists()

actual fun deleteFile(path: String): Boolean = File(path).delete()

actual fun renameFile(from: String, to: String): Boolean = File(from).renameTo(File(to))

/** 目录已存在也算成功 —— 与 iOS 的 `createDirectoryAtPath` 保持一致。 */
actual fun makeDirectories(path: String): Boolean {
    val dir = File(path)
    return dir.isDirectory || dir.mkdirs()
}

actual fun fileAttributes(path: String): FileAttrs? {
    val file = File(path)
    if (!file.exists()) return null
    return FileAttrs(
        exists = true,
        isDir = file.isDirectory,
        size = if (file.isFile) file.length() else 0L,
        mtime = file.lastModified(),
    )
}

/** 只返回名字，不含 path 前缀 —— 与 iOS 的 `contentsOfDirectoryAtPath` 对齐。 */
actual fun listDirectory(path: String): List<String> = File(path).list()?.toList() ?: emptyList()

actual fun pathJoin(vararg parts: String): String =
    parts.filter { it.isNotEmpty() }.joinToString("/").replace(DUPLICATE_SEPARATORS, "/")

private val DUPLICATE_SEPARATORS = Regex("/{2,}")
