// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.testing.createTempDir
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 磁盘数据源：按 offset 读、末尾短块、越界返回空、size 反映真实文件长度。 */
class DiskFileChunkSourceTest {

    private fun write(path: String, bytes: ByteArray) {
        makeDirectories(path.substringBeforeLast('/', ""))
        val handle = openRandomAccess(path, forWrite = true)
        handle.append(bytes, bytes.size)
        handle.flush()
        handle.close()
    }

    @Test
    fun readsByOffsetWithShortTailAndEmptyPastEnd() = runTest {
        val dir = createTempDir("relay-disk-source")
        val path = pathJoin(dir, "a.bin")
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        write(path, bytes)

        val source = DiskFileChunkSource(path)
        assertEquals(150_000L, source.size)
        assertContentEquals(bytes.copyOfRange(0, 64 * 1024), source.read(0, 64 * 1024))
        assertContentEquals(bytes.copyOfRange(64 * 1024, 128 * 1024), source.read(64L * 1024, 64 * 1024))
        // 末尾只剩 150000-131072=18928 字节。注意这里**不需要**多余字节被清零,
        // 只要求长度对且内容对。
        assertContentEquals(bytes.copyOfRange(128 * 1024, 150_000), source.read(128L * 1024, 64 * 1024))
        assertEquals(0, source.read(150_000, 1024).size, "越界必须返回空,不报错")
    }

    @Test
    fun missingFileFailsLoudlyInsteadOfReportingZero() = runTest {
        val dir = createTempDir("relay-disk-source-missing")
        val source = DiskFileChunkSource(pathJoin(dir, "nope.bin"))
        // 报 0 会让上层以为"空文件传完了";待传字节不见了必须是一次可归因的失败。
        var threw = false
        try {
            source.size
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "文件消失时 size 必须抛错,不许报 0")
        var readThrew = false
        try {
            source.read(0, 64)
        } catch (e: IllegalStateException) {
            readThrew = true
        }
        assertTrue(readThrew, "读也必须抛错")
    }
}
