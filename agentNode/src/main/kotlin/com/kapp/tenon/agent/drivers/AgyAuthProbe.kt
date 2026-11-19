// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * agy CLI 的**登录态预检**。
 *
 * ## 为什么要有它
 *
 * agy 的授权是浏览器 OAuth + **print 模式 60s 硬窗口**（cli.log：`Print mode:
 * auth timed out`），token 落 macOS 钥匙串。未登录时每条 quest 都要：建 worktree
 * → 起 CLI → 弹浏览器 → 60s 超时 → 失败——**白烧一整轮**，而且症状与「任务真的
 * 跑挂了」在卡面上无法区分。
 *
 * `agy models` 子命令是最轻的探针：不烧模型 token，已登录 ≈ 6s 返回列表；
 * 未登录时 stderr 带 `You are not logged into Antigravity`。预检命中未登录 →
 * quest 直接快败并给出登录指引，不进沙箱不弹浏览器。
 *
 * 结果**按会话记忆**：钥匙串里的 token 不会中途消失，认证过就不再重复探测
 * （省 6s）；quest 里再次观察到 auth 等待行时 [invalidate]（token 可能被清了）。
 */
class AgyAuthProbe(
    /**
     * 探针命令；默认走发现器解析（见 [resolvedCommand]）。测试可注入假命令。
     *
     * 不再硬编码 `agy`：GUI 启动的宿主只有最小 PATH，裸 `ProcessBuilder("agy")` 在 agy 只装于
     * `~/.local/bin` 或经 `TENON_AGY_CLI` 指定时找不到 ⇒ `IOException → UNKNOWN`（放行），
     * 症状推迟到真派单才显形。发现器返回的是**可执行绝对路径**，用它启动直接绕开 PATH 问题。
     */
    private val command: List<String> = resolvedCommand(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** 探测执行钩子（测试计数用）；生产缺省空实现。 */
    internal var probeHook: () -> Unit = {},
) {
    enum class State { AUTHENTICATED, NOT_AUTHENTICATED, UNKNOWN }

    @Volatile
    private var cached: State? = null

    suspend fun probe(): State {
        cached?.let { return it }
        probeHook()
        val state = withContext(Dispatchers.IO) { runOnce() }
        if (state == State.AUTHENTICATED) cached = state // 仅记忆「已登录」；未登录可被修复后复探
        return state
    }

    /** quest 里再次观察到 auth 等待行 → 已记忆的「已登录」不可信。 */
    fun invalidate() {
        cached = null
    }

    private fun runOnce(): State {
        var proc: Process? = null
        val output = StringBuilder()
        try {
            // start() 必须在 try 内：本机没装该 CLI 时它抛 IOException（error=2,
            // No such file or directory）。曾经放在 try 外，异常冒到调用方——登录态
            // 可见面那一路会弹出模态错误框、把整个配置对话框卡死；quest 预检那一路
            // 则会在快败判断处炸。命令缺失与超时同级，都是 UNKNOWN 放行，不替
            // 正式运行做决定（下方 catch 已按此口径）。
            proc = ProcessBuilder(command).redirectErrorStream(true).start()
            val running = proc
            // 读线程泵 stdout（防管道满阻塞），主线程 waitFor 上超时——读取在
            // waitFor 之前的旧实现，进程不关 stdout（孙进程继承管道）会永久挂死，
            // 20s 超时永不生效、Dispatchers.IO 线程永久丢失（code review #5）。
            // 模式 mirror QuestGitCli.kt:55-64（同款 waitFor+destroy+关流防挂）。
            val reader = Thread {
                runCatching {
                    running.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) {
                            if (output.length < OUTPUT_CAPTURE_CHARS) output.append(line).append('\n')
                        }
                    }
                }
            }.apply { isDaemon = true }
            reader.start()
            val finished = running.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                running.destroyForcibly()
                reader.join(2_000)
                return State.UNKNOWN // 探针超时≠未登录：放行，让正式运行与 auth-wait 检测兜底
            }
            reader.join(2_000)
            return classify(running.exitValue(), synchronized(output) { output.toString() })
        } catch (t: Throwable) {
            runCatching { proc?.destroyForcibly() }
            return State.UNKNOWN // 命令不存在等环境问题：放行，不替正式运行做决定
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 20_000L

        /**
         * 生产装配：走 [CliDiscovery.resolveAgyCli] 拿 agy 的可执行路径（认 `TENON_AGY_CLI`
         * 显式覆盖 → PATH → 应用包约定目录），拼上 `models` 子命令。
         *
         * 与模型目录探针、七家装配共用同一发现器 ⇒ 两者看到的是同一个 agy。找不到时回落裸
         * `agy` 名字：此时 [runOnce] 的 `start()` 抛 IOException → UNKNOWN 放行，与「命令缺失」
         * 同一口径，不替正式运行做决定。测试可注入合成 env 断言这条回落，不碰本机。
         */
        fun resolvedCommand(
            env: Map<String, String> = System.getenv(),
            pathDirs: List<String> = CliDiscovery.pathDirsOf(env),
        ): List<String> {
            // 子命令骨架取自 JSON 投影（argv[0] 必为 ${cliPath}，由 codegen 钉死），
            // 这里只把首项换成发现到的绝对路径，不另写一份 "models"。
            val head = CliDiscovery.resolveAgyCli(env, pathDirs) ?: CliDiscovery.AGY_BIN
            return listOf(head) + spec.argv.drop(1)
        }

        /** 给 FAILED 通知的登录指引（与 QuestAuthWaitDetector 的改写同用户口径）。 */
        const val LOGIN_REMEDIATION: String =
            "agy CLI is not logged in (pre-flight check). Run `agy` once in a terminal on the host " +
                "machine and complete the browser sign-in — one time only, credentials persist in " +
                "the keychain. Then retry this quest."

        /**
         * 纯分类：退出码 + 输出 → 登录态。判据（marker / ignoreCase / exit）从 JSON 投影的 agy
         * `authProbe` 读，机制委托给按 key 取声明的 [DriverAuthProbes.classify] —— 这里只把通用的
         * 三态折成本类的枚举，不另写一份判定顺序。
         *
         * - exit 0 = 已登录（models 列表正常返回）；
         * - 输出带「未登录」签名 = 未登录（CLI 的既定报错文案，真机日志实证）；
         * - 其余（网络问题、命令缺失）= UNKNOWN —— **放行**：预检只拦「确定未登录」，
           不替正式运行做任何其他决定。
         */
        fun classify(exitCode: Int, output: String): State =
            when (DriverAuthProbes.classify(DriverKeys.AGY, exitCode, output)) {
                DriverAuthProbes.State.AUTHENTICATED -> State.AUTHENTICATED
                DriverAuthProbes.State.NOT_AUTHENTICATED -> State.NOT_AUTHENTICATED
                DriverAuthProbes.State.UNKNOWN -> State.UNKNOWN
            }

        /** agy 的未登录签名取自 JSON 投影（`authProbe.notAuthenticatedMarker`），不再有本地 const。 */

        private val spec: CliDriverSpecs.AuthProbeSpec
            get() = CliDriverSpecs.authProbeOf(DriverKeys.AGY)
                ?: error("driver-specs.json 缺 agy 的 authProbe 声明")

        private const val OUTPUT_CAPTURE_CHARS: Int = 8_000
    }
}
