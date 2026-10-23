// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File

/**
 * 拖放诊断：把 AWT DnD 的每一次回调如实记下来。
 *
 * ## 为什么需要它
 *
 * 「桌面端拖文件没反应」这条症状有一个致命的歧义：
 *
 *   · `dragEnter` 都没触发 ⇒ 事件根本没进这扇窗口（命中测试、窗口状态、平台层）；
 *   · `dragEnter` 有、`drop` 没有 ⇒ 拖动中途事件链断了（`dropAction` 掉回 NONE）；
 *   · `drop` 有、文件却没收下 ⇒ 应用内部逻辑（展开目录、没选会话、上限）。
 *
 * 三种指向完全不同的修法，而**从屏幕上看到的现象一模一样**：什么都没有。
 * 所以把仪器放进产品里 —— 与 [DesktopKeyTrace] 同一立场：用户报问题时截一段就能说清。
 *
 * ## 怎么打开
 *
 * 两种方式任一即可，理由与 KeyTrace 相同（macOS 从程序坞/登录项起的进程拿不到交互
 * shell 的 env）：
 *
 *   · 环境变量 `RELAY_DND_TRACE=1`；
 *   · 数据目录里的标记文件 `dnd-trace.on`。
 *
 * 默认关闭：它会记录文件路径（拖进来的东西），不该默默落盘。
 */
internal object DesktopDndTrace {

    private const val ENABLED_ENV = "RELAY_DND_TRACE"
    private const val FILE_NAME = "dnd-trace.log"
    private const val FLAG_FILE_NAME = "dnd-trace.on"
    private const val MAX_BYTES = 256L * 1024

    @Volatile
    private var file: File? = null

    /**
     * 诊断是否开启。
     *
     * 调用方要拿它守**纯诊断**的定时器（窗口快照、归属自检）：默认关闭时那些定时器
     * 不该在用户机器上空转。功能性的那一条（抢占 Compose 内容面板的 DropTarget）
     * 不受此开关影响 —— 它是修复，不是仪器。
     */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 拖动中 `dragOver` 每秒会来几十次，全记会把日志刷爆、也会让拖动本身变卡。 */
    @Volatile
    private var lastOverAt = 0L

    fun install(dataDirectory: String) {
        if (file != null) return
        val enabledByEnv = System.getenv(ENABLED_ENV) == "1"
        val enabledByFlag = File(dataDirectory, FLAG_FILE_NAME).exists()
        if (!enabledByEnv && !enabledByFlag) return
        val dir = runCatching { File(dataDirectory).also { it.mkdirs() } }.getOrNull() ?: return
        file = File(dir, FILE_NAME)
        enabled = true
        append("dnd trace installed: pid=${ProcessHandle.current().pid()}")
    }

    /** 拖动阶段的汇总行；`throttled` 为真时按 500ms 去重。 */
    fun track(message: String, throttled: Boolean = false) {
        if (throttled) {
            val now = System.currentTimeMillis()
            if (now - lastOverAt < 500L) return
            lastOverAt = now
        }
        append(message)
    }

    private fun append(message: String) {
        val target = file ?: return
        runCatching {
            if (target.length() > MAX_BYTES) {
                target.renameTo(File(target.parentFile, "$FILE_NAME.1"))
            }
            target.appendText("${java.time.Instant.now()} $message\n")
        }
    }
}