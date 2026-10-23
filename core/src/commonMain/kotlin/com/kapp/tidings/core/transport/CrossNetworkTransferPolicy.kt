// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.IpAddressUtils.isPrivateOrLocal

/** 一次拉取尝试走哪种传输。 */
enum class PullAttemptTransport { Plaintext, Tls }

/**
 * 一条拉取尝试：拨 [host]:[port]，用 [transport]。
 *
 * 顺序由 [CrossNetworkTransferPolicy.plan] 决定，[PullOptions.attempts] 原样照做。
 */
data class PullAttempt(
    val host: String,
    val port: Int,
    val transport: PullAttemptTransport,
)

/**
 * 跨网文件监听端口 = 对端的 `tlsPort + 1`（task-39 裁决）。
 *
 * ## 为什么是 `+1` 而不是新加一个 profile 键
 *
 * 加键要动协议（`Profile` 的字段、codec 的取值域、两端的兼容矩阵），而这一期要的是
 * 「零协议改动」。`tlsPort` 已经在 profile 里、手机也拿得到，派生一个固定偏移量即可：
 * 确定性、可单测、旧端不受影响（旧端根本不会连这个端口）。
 *
 * 端口被占时**不重试别的端口**：宁可这条路径不可用（退回局域网原路径），
 * 也不要让手机去猜一个没人监听的端口。
 */
const val CROSS_NETWORK_FILE_PORT_OFFSET: Int = 1

/** `tlsPort` → 跨网文件端口；越界/缺失返回 null（调用方按「这条路不可用」处理）。 */
fun crossNetworkFilePort(tlsPort: Int?): Int? =
    tlsPort?.takeIf { it in 1..(65535 - CROSS_NETWORK_FILE_PORT_OFFSET) }
        ?.let { it + CROSS_NETWORK_FILE_PORT_OFFSET }

/**
 * F1 跨网文件拉取的**纯策略**：把「直连 / 跨网 / 退回」从 IO 里剥出来，好单测。
 *
 * ## 三条硬规矩（对应 task-39 的三条验收）
 *
 * 1. **局域网原路径永远第一，且开关关着时它是全部** —— 这就是「关掉开关可回滚」；
 * 2. **公网必须 TLS + pin**：`enabled` / `canDialTls` / `hasPin` / `tlsPort` 四个条件
 *    缺一不可，缺任何一个都**不降级明文**（fail-closed，task-13）；
 * 3. **只挑公网候选**：私网地址属于局域网那条路（第一条已经覆盖），不重复拨。
 *
 * ## 为什么不做「把直连地址换成公网地址」
 *
 * 直连地址来自 registry（对端当前那个地址），它可能已经是公网 —— 但那条路径是**今天**
 * 的行为，这一期不动它（局域网不失效）。跨网只**追加**候选，不替换。
 */
/**
 * F1：一次拉取的完整跨网计划（连接顺序 + TLS 拨号口）。
 *
 * 把两者放在一个值里，是因为它们必须**成对**出现：只有 TLS 的尝试却没有拨号口 ⇒ 那条尝试
 * 必然失败；只有拨号口却没有 TLS 尝试 ⇒ 白拿一个连接器。`planWithConnector` 保证一致。
 */
data class CrossNetworkPullPlan(
    val attempts: List<PullAttempt>,
    /** TLS 尝试用的原始字节流拨号口；没有 TLS 尝试时为 null。 */
    val connectRaw: RawPullConnector? = null,
)

/**
 * F1：把「这次拉取该试哪些连接」交给宿主（task-39）。
 *
 * 为什么是这个签名：[TransferManager] 手里只有 `peerAddress(peerId)` 一个回调，
 * 而排计划还要候选池、`tlsPort`、pin —— 那些都在 `NodeRuntime` 手里。所以让宿主把
 * 这几样**闭包进去**，管理器只问一句「这个 peer 该怎么拨」。
 *
 * **null = 今天的行为**（单条明文直连，逐字不变）。
 */
fun interface CrossNetworkTransferPlanner {
    fun plan(peerId: String, direct: PeerEndpoint?): CrossNetworkPullPlan?
}

class CrossNetworkTransferPolicy(
    /** 用户那个「跨网通道」开关（与跨网文本下行共用同一个，见 RelaySettings.linkDownlink）。 */
    val enabled: Boolean,
    /** 本平台能不能拨 TLS（iOS 的 raw 工厂在 task-26 ① 落地前返回 null ⇒ 这里为 false）。 */
    val canDialTls: Boolean,
) {

    /**
     * 按顺序排出这次拉取要试的连接。空表 = 一条都排不出来（调用方按 peer-offline 处理）。
     *
     * @param direct 局域网直连（registry 里对端当前那个地址 + `tcpPort`）；null = 还不知道。
     * @param candidates 对端的候选池（配对码里那一池地址）。
     * @param tlsPort 对端 profile 里的 TLS 监听端口（跨网文件端口由它派生）。
     * @param hasPin 本端有没有存下**对端**的 SPKI pin（没有就不许走公网）。
     */
    /**
     * 排连接顺序，并把 TLS 拨号口**成对**带出来（没有 TLS 尝试时它必为 null）。
     */
    fun planWithConnector(
        direct: PeerEndpoint?,
        candidates: List<PeerEndpoint>,
        tlsPort: Int?,
        hasPin: Boolean,
        connectRaw: RawPullConnector?,
    ): CrossNetworkPullPlan {
        val attempts = plan(direct, candidates, tlsPort, hasPin)
        val needsTls = attempts.any { it.transport == PullAttemptTransport.Tls }
        return CrossNetworkPullPlan(attempts, if (needsTls) connectRaw else null)
    }

    fun plan(
        direct: PeerEndpoint?,
        candidates: List<PeerEndpoint>,
        tlsPort: Int?,
        hasPin: Boolean,
    ): List<PullAttempt> {
        val out = ArrayList<PullAttempt>(2)
        if (direct != null && direct.host.isNotBlank() && direct.port in 1..65535) {
            out += PullAttempt(direct.host, direct.port, PullAttemptTransport.Plaintext)
        }
        if (!enabled || !canDialTls || !hasPin) return out
        val filePort = crossNetworkFilePort(tlsPort) ?: return out
        val seen = HashSet<String>(out.size + candidates.size)
        out.forEach { seen += it.host }
        for (candidate in candidates) {
            val host = candidate.host.trim()
            if (host.isEmpty() || host in seen) continue
            if (isPrivateOrLocal(host)) continue
            seen += host
            out += PullAttempt(host, filePort, PullAttemptTransport.Tls)
        }
        return out
    }
}
