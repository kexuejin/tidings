// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * 编排层的日志门面：**照旧打 stdout，同时留一份进环里**（ARP-8）。
 *
 * ## 为什么需要它
 *
 * 这一层原来有 60 处 `println("…")`，前缀是 `[RELAY-*]` / `[CARD]` / `[CHAIN]` /
 * `[ORCHESTRATOR]` / `[verify]`。它们确实是排障时最好用的东西
 * ——「卡上传输失败」「high-risk lease 拒了」「patch merge 失败」都只在这里留过痕 ——
 * 但 `println` 只到 stdout：桌面版是双击启动的 `.app`，用户看不到控制台，
 * 于是真出问题时唯一的现场在 `/dev/null` 里。诊断包（`relay-diagnostics-*.txt`）
 * 里当时只有设置 / 网络 / 节点三节，「刚才发生了什么」完全缺失。
 *
 * 所以这个门面只做两件事，不引入级别/采样/异步刷盘那些东西：
 * 1. **输出不变**：仍然 `println("[TAG] message")`，逐字与改造前一致
 *    （现有排障习惯、脚本抓取、测试里捕获 stdout 的地方都不用改）；
 * 2. **留最近 [CAPACITY] 行**，给诊断包用。
 *
 * ## tag 的写法
 *
 * 保留改造前那个方括号里的词，**大小写照旧** —— `[CHAIN]` 大写、`[verify]` / `[channel]`
 * 小写。这不是疏忽：排障脚本与肌肉记忆都按这几个字面量抓，统一大小写等于
 * 让「`grep '\[verify\]'`」失效一次。新加日志请沿用已有的 tag，别造近义词。
 *
 * ## 为什么是环形而不是追加文件
 *
 * 追加文件要考虑轮转、权限、磁盘写满、多实例并发 —— 都不是这个门面该管的事，
 * 而它要解决的只是「把刚才那几句话带出来」。500 行 ≈ 几十 KB，整段塞进诊断包
 * 仍然能直接贴进聊天窗口（诊断包的定位是「人可读、能直接发出去」，不是机器归档）。
 * 更早的行**有意滚掉**：真需要长历史时应该去 `agentNode` 的 task.log，而不是这里。
 *
 * ## 线程
 *
 * 编排层的日志来自控制面 HTTP 线程、协程池、CLI 子进程读线程 —— 都会碰这个环，
 * 所以读写都在同一把锁里。锁只包住「入队一行」，`println` 在锁外，
 * 不让一个慢的控制台拖住别的线程。
 */
object RelayLog {

    /** 环的容量（行）。见 KDoc：诊断包只需要「最近发生了什么」。 */
    const val CAPACITY: Int = 500

    private val lock = Any()
    private val ring = ArrayDeque<String>(CAPACITY)

    /**
     * 记一行。
     *
     * @param tag 方括号里的那个词（`RELAY-INVOKE` / `RELAY-PLUGIN` / `CARD` …），
     *   **不带方括号** —— 拼接在这里做，免得每个调用点各写一次括号格式。
     */
    fun info(tag: String, message: String) {
        val line = "[" + tag + "] " + message
        // 先 stdout（保持改造前的行为），再入环。顺序有意：控制台慢不该影响缓冲，
        // 但反过来（先入环再打印）会让崩溃现场的最后一行落后于实际输出。
        println(line)
        synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
    }

    /** 最近若干行，**旧 → 新**。诊断包按这个顺序贴进文件。 */
    fun lines(): List<String> = synchronized(lock) { ring.toList() }

    /** 清空（测试与「用户手动清场」用；正常路径不该调）。 */
    fun clear() {
        synchronized(lock) { ring.clear() }
    }
}
