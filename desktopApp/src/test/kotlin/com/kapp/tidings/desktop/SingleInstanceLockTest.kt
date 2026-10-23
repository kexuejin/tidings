// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 单实例锁的三条语义。
 *
 * ## 为什么这条判据值得单测
 *
 * 它守的是一个**实测发生过的**现场：两个桌面端同时跑，读到同一个 nodeId、写同一个
 * relay.db，而 8788 只有一个能绑上。界面上两边都正常 —— 唯一的线索是控制面时通时不通。
 *
 * ## 三条断言各自能区分什么
 *
 * 1. **第二次抢锁必须拿到 Busy**：把判据改成「锁文件存在 = 已有实例」这条会假绿，
 *    所以要断言**同一目录、两个真实 FileLock** 的关系，而不是文件在不在。
 * 2. **释放后能再次抢到**：这条挡的是「拿不到锁就永久锁死」—— 用户关掉应用再开，
 *    必须能起来。把实现换成"写 pid 文件、从不清理"这条会红。
 * 3. **不可写目录给 Unavailable 而不是 Busy**：这两种失败的含义完全不同。
 *    混成一种的表现是「目录权限有问题」被伪装成「已有实例」，用户看到的是
 *    "点两次都没反应"。把 `Unavailable` 分支改成 `Busy` 这条会红。
 */
class SingleInstanceLockTest {

    private val tempDirs = mutableListOf<File>()

    private fun newDir(): File {
        val dir = Files.createTempDirectory("relay-lock-test").toFile()
        tempDirs += dir
        return dir
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { dir -> dir.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `同一目录第二次抢锁得到 Busy`() {
        val dir = newDir()
        val first = SingleInstanceLock.tryAcquire(dir.absolutePath)
        assertIs<SingleInstanceLock.Acquire.Acquired>(first, "第一次必须拿到锁")

        val second = SingleInstanceLock.tryAcquire(dir.absolutePath)
        assertEquals(
            SingleInstanceLock.Acquire.Busy,
            second,
            "已经有实例持锁时，第二次必须报 Busy；报 Acquired 就是两个进程会写同一份库",
        )

        first.lock.close()
    }

    @Test
    fun `释放之后能再次抢到`() {
        val dir = newDir()
        val first = SingleInstanceLock.tryAcquire(dir.absolutePath)
        assertIs<SingleInstanceLock.Acquire.Acquired>(first)
        first.lock.close()

        val second = SingleInstanceLock.tryAcquire(dir.absolutePath)
        assertIs<SingleInstanceLock.Acquire.Acquired>(
            second,
            "关掉应用再打开必须能起来 —— 按\"锁文件存在\"判会永久锁死用户",
        )
        second.lock.close()
    }

    @Test
    fun `锁文件记账的 pid 是当前进程`() {
        val dir = newDir()
        val acquired = SingleInstanceLock.tryAcquire(dir.absolutePath)
        assertIs<SingleInstanceLock.Acquire.Acquired>(acquired)

        val recorded = File(dir, SingleInstanceLock.LOCK_FILE_NAME).readText().trim()
        assertEquals(
            ProcessHandle.current().pid().toString(),
            recorded,
            "锁文件里写的应当是当前进程的 pid（排查时靠它认人）",
        )

        acquired.lock.close()
    }

    @Test
    fun `不可写目录给 Unavailable 而不是 Busy`() {
        val dir = newDir()
        val blocked = File(dir, "not-a-dir")
        // 用一个**普通文件**当数据目录：建目录必然失败，但这不是"已有实例"。
        blocked.writeText("x")

        val result = SingleInstanceLock.tryAcquire(blocked.absolutePath)
        assertIs<SingleInstanceLock.Acquire.Unavailable>(
            result,
            "目录不可写是另一类故障；当成 Busy 会把真该看见的错伪装成重复启动",
        )
        assertNotEquals<SingleInstanceLock.Acquire>(
            SingleInstanceLock.Acquire.Busy,
            result,
            "两种失败的含义必须可区分",
        )
    }

    @Test
    fun `锁文件落在数据目录里`() {
        val dir = newDir()
        val acquired = SingleInstanceLock.tryAcquire(dir.absolutePath)
        assertIs<SingleInstanceLock.Acquire.Acquired>(acquired)

        assertTrue(
            File(dir, SingleInstanceLock.LOCK_FILE_NAME).isFile,
            "锁文件必须在数据目录下 —— 换一个目录就是换一把锁，两个实例仍会同时跑",
        )
        acquired.lock.close()
    }
}
