// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 各 CLI 底座的**只读登录态探针**（v1 状态可见面）。
 *
 * ## 为什么是探针，不是登录入口
 *
 * 登录是各家 CLI 的私有交互（浏览器授权 / TTY `/login` / 应用内选模型），榫卯进程替不了，
 * 也不允许把 token 收进自己的设置里（`CliProviderEnv` 的纪律：值只进子进程环境、不落盘）。
 * 所以这一层只回答「我观察到什么凭证线索」，把「怎么补」交给 UI 的引导文案。
 *
 * ## 三态 + 证据强度（两维，不能压成一维）
 *
 * - [State.AUTHENTICATED] / [State.NOT_AUTHENTICATED] / [State.UNKNOWN]：
 *   语义沿用 `AgyAuthProbe` —— **只报「确定未登录」**，探针超时 / 命令缺失 / 判据不足一律
 *   UNKNOWN，绝不替正式运行做决定（放行）。
 * - [Evidence.STRONG] / [Evidence.HINT]：真跑一次子命令拿到的结论是
 *   STRONG（目前只有 agy 的 `agy models`）；只看凭证文件 / 环境变量在位的是 HINT —— HINT
 *   只说明「有凭证物」，不保证它有效，UI 必须照实标出来，不许把「文件在」画成「已登录」。
 *
 * 两个维度分开，是为了守住本仓「空集假绿」的判据：没有探针结果 ≠ 已登录；HINT 的在位 ≠
 * 认证成功。任何把这两者合并成一个 Boolean 的写法都会把判据抹掉。
 */
object DriverAuthProbes {

    /** CONFIRMED 一档目前仅 agy 真跑探针产出；其余一律 HINT。 */
    enum class State { AUTHENTICATED, NOT_AUTHENTICATED, UNKNOWN }

    enum class Evidence { STRONG, HINT }

    data class Snapshot(
        val driverKey: String,
        val state: State,
        val evidence: Evidence,
    )

    /**
     * 纯分类：退出码 + 输出 → 三态。与 `AgyAuthProbe.classify` 同口径，抽成纯函数便于
     * 合成输入测试（不许依赖本机装没装 CLI）。签名命中即 NOT_AUTHENTICATED；其余
     * （含超时 / 命令缺失的哨兵值）UNKNOWN 放行。
     */
    fun classify(exitCode: Int, output: String): State = when {
        exitCode == 0 -> State.AUTHENTICATED
        output.contains(AgyAuthProbe.NOT_LOGGED_IN_MARKER, ignoreCase = true) -> State.NOT_AUTHENTICATED
        else -> State.UNKNOWN
    }

    /**
     * 由 `agy models` 的真实结果生成 STRONG 快照；null 状态（未接探针 / 本机没有 agy）
     * 原样返回 null，让调用方按「端口读不到」处理，而不是造一条 UNKNOWN 顶数。
     */
    fun agySnapshot(state: State?): Snapshot? = state?.let {
        Snapshot(DriverKeys.AGY, it, Evidence.STRONG)
    }

    /**
     * 文件 / 环境变量类的弱探针：**只判「不在位」**，即确定没有凭证物 ⇒ NOT_AUTHENTICATED；
     * 在位 ⇒ UNKNOWN（不是 AUTHENTICATED —— 文件在不代表 token 还有效）。这条不对称是故意的：
     * 弱证据只能用于「快败提示」，不能用于「放行绿灯」，后者必须留给真跑探针。
     *
     * @param credentialPresent 凭证物是否在位
     */
    fun hintSnapshot(driverKey: String, credentialPresent: Boolean): Snapshot = Snapshot(
        driverKey = driverKey,
        state = if (credentialPresent) State.UNKNOWN else State.NOT_AUTHENTICATED,
        evidence = Evidence.HINT,
    )

    /** CLI 侧 auth session 文件是否存在（只读 exists，不读内容、不解密、不打印）。 */
    fun authSessionExists(path: String): Boolean = runCatching { java.io.File(path).isFile }.getOrDefault(false)

    /** 环境变量里是否至少有一只非空凭证（空白值当没有，与 CliProviderEnv.pick 同口径）。 */
    fun hasCredential(env: Map<String, String>, vararg keys: String): Boolean =
        keys.any { env[it]?.trim()?.isNotEmpty() == true }

    /**
     * zcode 的可靠反向判据：应用 `setting.json` 里是否至少选过一个模型。
     *
     * 为什么这条可靠、而 env/文件在位不可靠：zcode CLI 真的发消息前要求 `modelSelection`，
     * 没选就报 `Select a model before sending a message`（本机 --help 与真跑都验证过）。
     * 这个选择持久化在应用的 `setting.json` → `modelProviderFamilySelectedKeys`，是个对象；
     * **空对象 / 缺键 / 文件不在 ⇒ 确定选不出模型 ⇒ NOT_AUTHENTICATED**（快败提示成立）。
     * 反过来「有一个 key」只说明选过、不保证凭证还有效 ⇒ 仍是 HINT/UNKNOWN，不画绿灯。
     *
     * 路径由调用方传入（装配层按 `ZCODE_STORAGE_DIR` env 覆盖 + `~/.zcode/v2` 默认推导），
     * 这里只读解析：不解密、不打印任何值、不看 token。纯函数 + 合成文件可测。
     *
     * @param settingPath `setting.json` 的绝对路径；不存在/读不了/解析失败一律 false（确定没选）。
     */
    fun zcodeHasModelSelected(settingPath: String): Boolean {
        if (settingPath.isBlank()) return false
        val text = runCatching { java.io.File(settingPath).readText(Charsets.UTF_8) }.getOrNull() ?: return false
        // 只找 `modelProviderFamilySelectedKeys` 这个对象里是否存在非空的值条目；
        // 用极简扫描而非引 JSON 库（探针层不引新依赖，且判据只需「有没有选过」）。
        val marker = "\"modelProviderFamilySelectedKeys\""
        val at = text.indexOf(marker)
        if (at < 0) return false
        val open = text.indexOf('{', at + marker.length)
        if (open < 0) return false
        var depth = 0
        var inString = false
        var escaped = false
        var hasEntry = false
        for (i in open until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    // 一段非空字符串出现在对象体里 = 至少有一个选中键/值
                    if (i > open + 1) hasEntry = true
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return hasEntry
                }
            }
        }
        return false
    }
}
