// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

/**
 * 一个对端的**拨号诊断**（只读，task-32 ③）。
 *
 * 存在的理由：真机上"长连接没建起来"有两种完全不同的成因，光看 linkConnected 分不清 ——
 *  · **没拨**（策略跳过：对端没声明 lnk1 / 公网但没 pin / 没 tlsPort）⇒ attempts=0 + [skipReason]；
 *  · **拨了连不上**（网络不可达 / TLS 握手失败 / pin 不匹配）⇒ attempts>0 + failures>0 + [lastError]。
 *
 * @param lastTarget 最后一次拨的 `host:port`。
 * @param lastError 最近一次失败原因（`connect-failed` / `connect-timeout` / `tls-handshake-failed` / `pin-mismatch` / `hello-rejected`）。
 * @param skipReason 为什么一个拨号目标都没有（`peer-has-no-lnk1` / `no-pin` / `peer-has-no-tls-port` / `public-address-needs-tls`）。
 */
data class LinkDialStats(
    val attempts: Int = 0,
    val failures: Int = 0,
    val lastTarget: String? = null,
    val lastError: String? = null,
    val skipReason: String? = null,
)

/**
 * 长连接的只读快照（诊断用，task-26 ③）。
 *
 * 刻意做成一个**纯数据**类型：控制面只读它、不触发任何动作，也不进协议。
 *
 * @param carriers nodeId → 承载类型（`"tls"` / `"plaintext"`）。
 * @param total 当前活着的长连接总数（两端各算各的）。
 * @param dial nodeId → 拨号诊断（见 [LinkDialStats]）。
 */
data class LinkSnapshot(
    val carriers: Map<String, String> = emptyMap(),
    val total: Int = 0,
    val dial: Map<String, LinkDialStats> = emptyMap(),
    /**
     * nodeId → **主机侧**最近一次拒绝长连接的原因（task-36 ①）。
     *
     * 与 [dial] 分工：`dial` 是"本端拨出去为什么没成"（发起侧），`denials` 是"本端为什么拒了
     * 对端连进来"（接受侧）。同一台机器上两者都可能非空（K1/K2 两个角色并存）。
     */
    val denials: Map<String, String> = emptyMap(),
    /**
     * nodeId → 最近一次**连接结束**的原因（task-40 ①）：`dead-timeout` / `peer-closed` /
     * `replaced` / `recheck` / `stopped`。真机上"谁先关的"就看它。
     */
    val closes: Map<String, String> = emptyMap(),
    /** nodeId → **本端主动**关掉连接的次数（replaced / recheck / stopped）。 */
    val closeCounts: Map<String, Int> = emptyMap(),
    /** nodeId → 最近一次主动关闭的**判据两元组**（task-40：`isPlaintext=… ip=… local=…`）。 */
    val closeDetails: Map<String, String> = emptyMap(),
    /**
     * nodeId → 对端 pin 的**来源**（task-38）：`pairing` = 配对带来的（可信）；`tofu` = 首次固定
     * 学到的（**降级路径**）；`unknown` = 旧库没记录。让"其实还在 TOFU"看得见。
     */
    val pinSources: Map<String, String> = emptyMap(),
) {
    fun carrierOf(nodeId: String): String? = carriers[nodeId]

    fun dialOf(nodeId: String): LinkDialStats? = dial[nodeId]

    fun denialOf(nodeId: String): String? = denials[nodeId]

    fun closeOf(nodeId: String): String? = closes[nodeId]

    fun closeCountOf(nodeId: String): Int = closeCounts[nodeId] ?: 0

    fun closeDetailOf(nodeId: String): String? = closeDetails[nodeId]

    fun pinSourceOf(nodeId: String): String? = pinSources[nodeId]
}
