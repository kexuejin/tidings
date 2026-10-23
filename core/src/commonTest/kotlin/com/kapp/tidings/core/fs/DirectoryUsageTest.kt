// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.fs

import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [directoryUsageBytes] 的用例。
 *
 * 这里都写**真的**字节数再数回来：设置页上那个数字是给用户看的承诺，
 * 不能靠"调了一次 listDirectory 就算过了"来自我安慰。
 */
class DirectoryUsageTest {

    @Test
    fun aMissingDirectoryCountsAsZero() {
        val root = createTempDir("usage-missing")
        try {
            assertEquals(0L, directoryUsageBytes(pathJoin(root, "nope")))
        } finally {
            removeRecursively(root)
        }
    }

    @Test
    fun anEmptyDirectoryCountsAsZero() {
        val root = createTempDir("usage-empty")
        try {
            assertEquals(0L, directoryUsageBytes(root))
        } finally {
            removeRecursively(root)
        }
    }

    @Test
    fun nestedFilesAddUp() {
        val root = createTempDir("usage-nested")
        try {
            writeBytes(pathJoin(root, "a.bin"), 10)
            writeBytes(pathJoin(root, "sub", "b.bin"), 25)
            writeBytes(pathJoin(root, "sub", "deeper", "c.bin"), 7)
            assertEquals(42L, directoryUsageBytes(root))
        } finally {
            removeRecursively(root)
        }
    }

    @Test
    fun emptySubdirectoriesDoNotBreakTheWalk() {
        val root = createTempDir("usage-hollow")
        try {
            makeDirectories(pathJoin(root, "empty", "also-empty"))
            writeBytes(pathJoin(root, "keep.bin"), 5)
            assertEquals(5L, directoryUsageBytes(root))
        } finally {
            removeRecursively(root)
        }
    }

    /**
     * 深度上限是给软链接成环兜底的，所以它必须真的生效：上限 1 层时，
     * 根目录里的文件要数到，再往里一层的就数不到。
     */
    @Test
    fun theDepthLimitStopsTheWalk() {
        val root = createTempDir("usage-depth")
        try {
            writeBytes(pathJoin(root, "top.bin"), 3)
            writeBytes(pathJoin(root, "sub", "buried.bin"), 100)
            assertEquals(3L, directoryUsageBytes(root, maxDepth = 1))
            assertEquals(103L, directoryUsageBytes(root, maxDepth = 2))
        } finally {
            removeRecursively(root)
        }
    }

    private fun writeBytes(path: String, count: Int) {
        val file = openRandomAccess(path, forWrite = true)
        try {
            file.append(ByteArray(count), count)
            file.flush()
        } finally {
            file.close()
        }
    }
}
