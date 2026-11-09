// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 驱动键的**唯一归一化来源**（别名口径只许有一份实现）。
 *
 * ## 为什么要有它
 *
 * 同一个 agent 在三处被寻址，而这三处曾经各写一份规则：
 *
 * - 群内派单解析：`ControlPlane.resolveCliDriver`（用 agent 键匹配已注册驱动的 `driverId`）；
 * - 岗位档案：`AgentProfiles.normalizeKey`（把任意写法归一成档案键）；
 * - 登记表：`DriverRegistry` 里各驱动自报的 `driverId`。
 *
 * 规则本身很小（小写、去 `-cli` 后缀、`antigravity → agy`），但**只要有两份实现就会
 * 分叉**：一边加了别名、另一边没加，症状是「同一个 agent 派单跑得到、查档案查不到」，
 * 而且两边都编译通过、谁也不会报错。所以别名表只允许存在于本对象里，其余各口改成调用它；
 * 源码级守卫会扫生产源码，确认没有第三份规则。
 *
 * 三张消费面（全部经本对象）：
 *
 * - **注册**：`cliDriverRegistrations` 与各驱动的 `driverId`（[cliDriverId]）；
 * - **解析 / 在位**：`ControlPlane.resolveCliDriver`（[driverIdAliases]）；
 * - **档案**：`AgentProfiles.normalizeKey`（[canonicalKey] + [stripCliSuffix]）。
 *
 * ## 取值域（逐条对齐既有实现，行为零变更）
 *
 * - 档案键：`codex` / `claude` / `agy` / `opencode` / `dsh`；
 * - CLI 驱动 id：`<键>-cli`（`native` 是宿主本地驱动，不走 CLI 发现）；
 * - 别名：`antigravity` 与 `agy` 是同一厂商的两种写法。
 *
 * **这里只做字符串归一，不做「在不在位」判定**：在位是运行期事实（装没装 CLI、
 * 登录没登录、插件有没有注册），由 `ControlPlane.resolveCliDriver` 拿本对象的别名集
 * 去比对实际注册的驱动；档案表只回答「它是什么」。
 */
object DriverKeys {

    /**
     * 宿主派单时注入子进程的会话对齐键（提案 §9 阶段三）：hook 脚本读到它就把
     * `sessionId` 透传给 hook 路由，审批卡因此落在派单会话里，而不是合成的
     * `agy-*` 池键——hook 的 conversationId 与派单侧 convId 之间从此有一条
     * 可执行的对齐链路。常量住这里：驱动的 env 注入与 hook 路由的解析共用一份。
     */
    const val ENV_HOOK_SESSION_ID = "TENON_HOOK_SESSION_ID"

    /** 归一后的 Antigravity 档案键。 */
    const val AGY = "agy"

    /** `antigravity` 的原始写法（群内派单与插件 hook 仍会带它）。 */
    const val ANTIGRAVITY = "antigravity"

    /** CLI 驱动 id 的后缀：`codex` 的驱动 id 是 `codex-cli`。 */
    const val CLI_SUFFIX = "-cli"

    /**
     * 任意写法 → 小写键；空串 / 纯空白 → null。
     *
     * `antigravity` 归一成 [AGY]；其余只做 trim + lowercase，**不在这里去掉 `-cli`
     * 后缀** —— 去掉后缀是另一件事，由需要它的调用点显式调用 [stripCliSuffix]。
     */
    fun canonicalKey(rawKey: String?): String? {
        val key = rawKey?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return null
        return if (key == ANTIGRAVITY) AGY else key
    }

    /** 去掉 CLI 驱动 id 后缀（幂等：没有后缀时原样返回）。 */
    fun stripCliSuffix(key: String): String = key.removeSuffix(CLI_SUFFIX)

    /**
     * 一个 agent 键可能对应的**驱动 id 集合**，按既有匹配规则展开：
     * `key` / `key + "-cli"` / `去后缀再加后缀`，外加 `antigravity → agy-cli`。
     *
     * 返回集合（而不是逐个 if）：调用方用 `driverId in aliases` 做一次成员判定，
     * 规则增删只改这一处。空键返回空集 —— 调用方据此提前返回 null。
     */
    fun driverIdAliases(rawKey: String?): Set<String> {
        val key = rawKey?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return emptySet()
        val aliases = linkedSetOf(key, key + CLI_SUFFIX, stripCliSuffix(key) + CLI_SUFFIX)
        if (key == ANTIGRAVITY) aliases += AGY + CLI_SUFFIX
        return aliases
    }

    /**
     * CLI 驱动的**注册 id**：档案键 → `<键>-cli`（`antigravity` 归一成 `agy-cli`，
     * 已经带后缀的写法幂等）。
     *
     * 注册侧（`cliDriverRegistrations` 与各驱动的 `driverId`）必须用它，而不是手写
     * `"codex-cli"`：手写与这里的规则只差一个字符时，`resolveCliDriver` 就永远匹配不上，
     * 而两端都能编译 —— 症状是「CLI 装了、发现得到，派单却报 no cli driver」。
     */
    fun cliDriverId(rawKey: String): String {
        val key = canonicalKey(rawKey) ?: rawKey.trim().lowercase()
        return stripCliSuffix(key) + CLI_SUFFIX
    }

    /**
     * `driverId` 是否属于该 agent 键可寻址的集合。
     *
     * 刻意**不对 `driverId` 再做归一**：驱动 id 由各驱动自报、已是规范形态；
     * 这里放宽一点，就会让「注册了一个不符合约定的 id」永远不被发现。
     */
    fun matchesDriverId(rawKey: String?, driverId: String): Boolean =
        driverId in driverIdAliases(rawKey)
}
