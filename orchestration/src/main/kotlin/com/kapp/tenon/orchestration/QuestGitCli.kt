// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 支线域共享的 git 子进程执行器（[QuestSerialMergeLock] / [QuestRunner] 同一份）。
 *
 * 为什么必须只有一份：合并/落提交这两类 git 调用的失败形状一模一样——宿主 GIT_*
 * 环境劫持落点、索引锁挂死、超时止损——两处各写一份迟早漂（本仓「多处必须一致」
 * 纪律）。此前 [QuestSerialMergeLock] 私有一份，收编为唯一来源后 QuestRunner 直接复用。
 *
 * 超时分支的 reader 取消与关流是**防御性收敛**，不是实测缺陷的修复：JVM 的进程退出
 * reaper 会在直接子进程死后排干并关闭父端管道（reader 因此拿到 EOF，见
 * ProcessPipeInputStream.processExited），「孙进程握管道 ⇒ reader 永久阻塞」在 JVM
 * 上并不成立（评审时按 POSIX 裸语义推演过界了，本机实测推翻）。保留它的理由是
 * 不赌 reaper 语义的平台/版本细节——关流的代价是零，赌输的代价是全局合流锁。
 */
internal object QuestGitCli {

    data class GitOutput(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * 跑一条 git 命令。超时（[timeoutMs]，墙钟）时：杀直接子进程 → **取消两个
     * stdout/stderr reader 并关闭流**（hook 派生的孙进程仍握着管道写端时，阻塞读
     * 永不 EOF，结构化并发收不了拢——不关流就是挂死调用方）→ 回收进程防僵尸。
     */
    suspend fun run(
        cwd: File,
        timeoutMs: Long = 30_000L,
        vararg args: String,
    ): GitOutput = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(listOf("git") + args.toList())
            .directory(cwd)
        // 环境加固：宿主 shell 里的 GIT_DIR / GIT_WORK_TREE / GIT_INDEX_FILE 会把这里的
        // git 操作重定向到别的仓库 —— 合流/落提交对着错误的仓执行比失败更糟。
        val env = pb.environment()
        env.remove("GIT_DIR")
        env.remove("GIT_WORK_TREE")
        env.remove("GIT_INDEX_FILE")
        env.remove("GIT_OBJECT_DIRECTORY")
        env.remove("GIT_ALTERNATE_OBJECT_DIRECTORIES")
        val proc = pb.start()

        coroutineScope {
            val stdoutJob = async { proc.inputStream.bufferedReader().readText() }
            val stderrJob = async { proc.errorStream.bufferedReader().readText() }

            val finishedInTime = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finishedInTime) {
                proc.destroyForcibly()
                // 防御性收敛（见类 KDoc）：取消 reader 并关流。JVM 的 reaper 通常已在
                // 子进程死后 EOF 父端管道；这两行赌的是「万一没有」时也不挂调用方。
                stdoutJob.cancel()
                stderrJob.cancel()
                runCatching { proc.inputStream.close() }
                runCatching { proc.errorStream.close() }
                proc.waitFor(5, TimeUnit.SECONDS)
                return@coroutineScope GitOutput(-1, "", "Git command timed out after " + timeoutMs + " ms: git ${args.joinToString(" ")}")
            }

            val out = stdoutJob.await()
            val err = stderrJob.await()
            val code = proc.exitValue()
            GitOutput(code, out, err)
        }
    }
}
