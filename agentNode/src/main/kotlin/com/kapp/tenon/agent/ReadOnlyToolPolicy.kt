// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

/**
 * 一次派单的只读工具白名单（提案 §7.1 第 3 条约束的**执行前**那一半）。
 *
 * ## 为什么要有它
 *
 * `TriggerExecutor` 只能看 `tool.started` 事件 —— 那是「已经开始执行」的通知，
 * 不是「请求批准」。等它判到越界，那个调用**已经落地**了，能做的只有事后判负。
 * 装了 hook 的 CLI 会在工具执行**之前** POST `/v1/agent/hook/approval`，只有那条
 * 路径上存在「拒绝」这个动作。判据挂在派单上（[ControlPlane.readOnlyToolPolicies]）、
 * 由审批路由读，执行前拦截才成立。
 *
 * 判据本体是 `(String?) -> Boolean` 而不是某个类型：白名单住在 `:app` 的
 * `CognitiveFlow`，`:agentNode` 不依赖它，这里只收一个纯 Kotlin 函数。
 *
 * ## 为什么要记下拒绝
 *
 * 判负口径必须和事后那一半一致：**越界即失败**。拦住了不等于没发生 ——
 * 「这条只读通道被试图写盘」是派单方要知道的事实，静默吞掉会让结果卡看起来
 * 一切正常，而任务其实跑在一个已经跑偏的指令上。
 *
 * 记**第一个**而不是最后一个：第一个越界工具就是判负依据，后面的只会稀释结论。
 *
 * ## 生命周期
 *
 * 登记与清除由 `ControlPlane.executeCliTask` 管（起进程**之前**登记、`finally`
 * 里清掉）。本类只管一次派单内的判定与记录，不持有会话身份 —— 拿不到会话就
 * 不会把「只读」泄漏给下一次任务。
 */
internal class ReadOnlyToolPolicy(private val allows: (String?) -> Boolean) {

    private val firstDenial = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /**
     * 放行则 true；不放行则记下第一个越界工具并返回 false。
     *
     * `null` / 空白工具名一律不放行（fail-closed）：没有名字的调用无法证明它是读，
     * 与 `CognitiveFlow.allowsTool` 同一口径 —— 判据来自那边，这里不另立一套。
     */
    fun admit(tool: String?): Boolean {
        if (allows(tool)) return true
        firstDenial.compareAndSet(null, tool?.trim()?.takeIf { it.isNotEmpty() } ?: UNNAMED_TOOL)
        return false
    }

    /** 这次派单被拦下来的第一个工具；null = 一次都没越界。 */
    fun deniedTool(): String? = firstDenial.get()

    private companion object {
        const val UNNAMED_TOOL = "(unnamed tool)"
    }
}
