// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * 进程级单实例锁。
 *
 * ## 为什么必须有它（不是"最好有"）
 *
 * 实测：同一台机器可以同时起两个桌面端，它们**读到同一个 `nodeId`、写同一个
 * `relay.db` 与同一份 `messages.json`**，而 8788 只有一个进程能绑上 —— 另一个
 * 静默失败后继续跑。表现是「控制面时通时不通」与一份被两个写者交替改写的库，
 * 而界面上完全看不出来有两个。
 *
 * ## 判据是"拿到锁"，不是"pid 文件存在"
 *
 * pid 文件在崩溃后会留下残骸，按「文件存在 = 已有实例」判会把用户永久锁在门外。
 * 而 `FileLock` 由内核持有：进程无论怎么死，锁都会释放。
 *
 * ## 三种结果必须分开，不能混成一种
 *
 * - [Acquire.Acquired]：拿到了，正常启动。
 * - [Acquire.Busy]：**别的实例持着锁** ⇒ 转交意图后退出（这是产品行为）。
 * - [Acquire.Unavailable]：连锁文件都建不出来（目录不可写 / 磁盘满）⇒ **不许**
 *   当成"已有实例"退出。那会把一个真正需要用户看见的故障伪装成"重复启动"，
 *   而用户看到的只是"点了没反应"。让它照常启动，由存储层去报真正的错。
 */
internal class SingleInstanceLock private constructor(
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { file.close() }
    }

    /** 抢锁的三种结果。用密封类而不是 `throw`/`null`：两种失败的含义完全不同。 */
    internal sealed interface Acquire {
        class Acquired(val lock: SingleInstanceLock) : Acquire
        data object Busy : Acquire
        class Unavailable(val cause: Throwable) : Acquire
    }

    companion object {

        internal const val LOCK_FILE_NAME: String = "relay.instance.lock"

        fun tryAcquire(dataDirectory: String): Acquire {
            val dir = File(dataDirectory)
            if (!dir.isDirectory && !dir.mkdirs()) {
                return Acquire.Unavailable(
                    IllegalStateException("cannot create data directory: " + dir.absolutePath),
                )
            }
            val raf = try {
                RandomAccessFile(File(dir, LOCK_FILE_NAME), "rw")
            } catch (t: Throwable) {
                return Acquire.Unavailable(t)
            }
            val lock = try {
                raf.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // 同一个 JVM 里重复抢同一把锁（单测会遇到）。语义与跨进程持锁一致。
                null
            } catch (t: Throwable) {
                runCatching { raf.close() }
                return Acquire.Unavailable(t)
            }
            if (lock == null) {
                runCatching { raf.close() }
                return Acquire.Busy
            }
            // 把持有者写进文件，纯粹为了人可读（排查时能知道是谁占着）。
            // 写失败不影响锁的语义 —— 它只是便利信息。
            runCatching {
                raf.setLength(0)
                raf.write((ProcessHandle.current().pid().toString() + "\n").toByteArray())
            }
            return Acquire.Acquired(SingleInstanceLock(raf, lock))
        }
    }
}
