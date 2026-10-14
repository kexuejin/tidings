// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package app.relay.core.fs

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSDate
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForUpdatingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.Foundation.synchronizeFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeData
import platform.posix.memcpy

/**
 * iOS 实现。
 *
 * `NSFileHandle` 与 JVM 的 `RandomAccessFile` 是同一层抽象：游标是句柄自己的状态，
 * `seekToFileOffset` / `seekToEndOfFile` 对 JVM 的 `seek`。差异只有两处：
 * 读要自己把 `NSData` 拷进 ByteArray，写要自己把 ByteArray 包成 `NSData`。
 */
private class IosRandomAccessFile(
    private val path: String,
    private val handle: NSFileHandle,
) : RandomAccessFile {

    /**
     * 走 attributes 而不是 `seekToEndOfFile()`：后者会挪动句柄的游标，
     * 于是"问一次大小"变成了带副作用的操作，调用方在 `read` 之后查 `size` 就会踩到游标变化。
     */
    override val size: Long
        get() {
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
                ?: throw IllegalStateException("读不到文件属性：$path")
            val number = attributes[NSFileSize] as? NSNumber
                ?: throw IllegalStateException("文件属性里没有 NSFileSize：$path")
            return number.longLongValue
        }

    override fun read(buffer: ByteArray, atOffset: Long, count: Int): Int {
        // JVM 的 read 越界会抛 IndexOutOfBounds；native 的 memcpy 会直接写坏堆，
        // 所以这一侧必须自己挡住（协议里的 offset/count 来自对端分片表）。
        require(count in 0..buffer.size) { "count=$count 不在 0..${buffer.size}" }
        handle.seekToFileOffset(atOffset.toULong())
        val data = handle.readDataOfLength(count.toULong())
        val length = data.length.toInt()
        // 越过末尾时 readDataOfLength 返回空 NSData —— 对齐 InputStream.read 的 -1。
        if (length == 0) return -1
        val source = data.bytes ?: return -1
        buffer.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length.toULong()) }
        return length
    }

    override fun append(buffer: ByteArray, count: Int) {
        if (count <= 0) return
        handle.seekToEndOfFile()
        // `NSData.create` 走 initWithBytes:length:，会拷贝一份；unpin 之后仍然有效。
        val data = buffer.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = count.toULong())
        }
        handle.writeData(data)
    }

    /** `synchronizeFile()` 就是 fsync(2)，与 JVM 侧的 `fd.sync()` 同一个语义。 */
    override fun flush() = handle.synchronizeFile()

    override fun close() = handle.closeFile()
}

actual fun openRandomAccess(path: String, forWrite: Boolean): RandomAccessFile {
    val manager = NSFileManager.defaultManager
    val handle: NSFileHandle?
    if (forWrite) {
        parentOf(path)?.let { makeDirectories(it) }
        // `createFileAtPath` 会把已存在的文件清空，而 `.part` 续传必须保住已落盘的字节；
        // 所以只在文件真的不存在时创建，与 JVM 的 "rw"（不截断）保持一致。
        if (!manager.fileExistsAtPath(path)) {
            manager.createFileAtPath(path, contents = null, attributes = null)
        }
        // 用 updating 而不是 writing：接口里的 read 在 forWrite=true 时同样要能用。
        handle = NSFileHandle.fileHandleForUpdatingAtPath(path)
    } else {
        handle = NSFileHandle.fileHandleForReadingAtPath(path)
    }
    return IosRandomAccessFile(path, handle ?: throw IllegalStateException("打开文件失败：$path"))
}

actual fun fileSize(path: String): Long? {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
    // 目录也有属性也有 NSFileSize，但协议里的 size 说的是文件；不是普通文件就当不存在。
    if (attributes[NSFileType] != NSFileTypeRegular) return null
    return (attributes[NSFileSize] as? NSNumber)?.longLongValue
}

actual fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

actual fun deleteFile(path: String): Boolean =
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)

actual fun renameFile(from: String, to: String): Boolean =
    NSFileManager.defaultManager.moveItemAtPath(from, toPath = to, error = null)

/** 目录已存在也算成功 —— 与 JVM 的 `isDirectory || mkdirs()` 保持一致。 */
actual fun makeDirectories(path: String): Boolean {
    val manager = NSFileManager.defaultManager
    if (manager.fileExistsAtPath(path)) return true
    return manager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}

actual fun fileAttributes(path: String): FileAttrs? {
    val manager = NSFileManager.defaultManager
    val attrs = manager.attributesOfItemAtPath(path, error = null) ?: return null
    val isDir = (attrs[NSFileType] as? String) == NSFileTypeDirectory
    val size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
    val modDate = (attrs[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
    return FileAttrs(
        exists = true,
        isDir = isDir,
        size = if (isDir) 0L else size,
        mtime = (modDate * 1000.0).toLong(),
    )
}

/** 只返回名字，不含 path 前缀 —— 与 JVM 的 `File.list()` 对齐。 */
actual fun listDirectory(path: String): List<String> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, error = null)
        ?.filterIsInstance<String>()
        ?: emptyList()

actual fun pathJoin(vararg parts: String): String =
    parts.filter { it.isNotEmpty() }.joinToString("/").replace(DUPLICATE_SEPARATORS, "/")

private val DUPLICATE_SEPARATORS = Regex("/{2,}")

/** 取父目录；没有 '/' 时返回 null。 */
private fun parentOf(path: String): String? {
    val cut = path.lastIndexOf('/')
    if (cut < 0) return null
    return path.substring(0, cut).ifEmpty { "/" }
}
