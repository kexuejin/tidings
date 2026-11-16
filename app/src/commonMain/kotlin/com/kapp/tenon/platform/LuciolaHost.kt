// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.platform

import com.kapp.tenon.core.transport.DatagramTransport
import com.kapp.tenon.core.transport.DiscoveryEngine
import com.kapp.tenon.core.transport.PeerRegistry
import com.kapp.tenon.core.transport.ScreenChannelHost
import com.kapp.tenon.core.protocol.DeviceTrustPolicies
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.domain.LuciolaQrInfo
import kotlinx.coroutines.CoroutineScope

/**
 * luciola 传输宿主契约。三端各有实现（桌面 JVM / Android / iOS，见各壳的 *LuciolaHost）；
 * 壳没接（默认 null）即整条走 legacy。
 *
 * 三个都在**宿主侧**：共享层不该知道 luciola 的 UNIFFI 绑定与密钥落盘方式。
 * 传输与发现必须**同时**给：只给一半（有加密信道却没有发现，或反之）比 legacy 还糟，
 * [com.kapp.tenon.domain.RelayController] 会拒绝这种组合。
 */
interface LuciolaHost {
    /**
     * 二维码里要带的 luciola 段（公钥 + 配对密钥）；null = 本机此刻没准备好
     * （未开启 / 身份还没初始化）—— 码退回 legacy 形态，行为不变。
     */
    fun pairingInfo(): LuciolaQrInfo?

    /**
     * 扫码回调：把「对端地址 + 已核对的公钥 + 配对密钥」写进信任库。
     *
     * 返回 false = 本机没开 luciola / 写入失败；调用方应给出可读提示，而不是静默。
     */
    fun pairPeer(host: String, port: Int, info: LuciolaQrInfo): Boolean

    /**
     * 装配前的运行时参数（本机档案 + 发现/信道端口）。控制器在构造 NodeRuntime 之前调用；
     * 宿主据此 lazily 建工厂（身份与信任库在宿主自己的目录里）。
     */
    fun configure(profile: Profile, udpPort: Int)

    /**
     * 设备接入信任模式：`verify`（默认）/ `auto` / `whitelist`。见 `DeviceTrustPolicies`。
     *
     * 它回答的是「**没有二维码密钥的入站配对**该怎么办」，与「这台设备能用我的哪些 agent」
     * （`AgentGrantPolicies`）是两套。默认实现给最严档：没接这一层的宿主不该被默认放宽。
     */
    fun deviceTrustPolicy(): String = DeviceTrustPolicies.VERIFY

    /** 切档。取值不在三档之内返回 false（**不静默归一**：界面点 A、判据读 B 是本仓栽过的静默）。 */
    fun setDeviceTrustPolicy(raw: String): Boolean = false

    /** 待处理的「无密钥配对申请」：nodeId → `展示名|来源地址`。空 = 没人申请。 */
    fun pendingPairingRequests(): Map<String, String> = emptyMap()

    /** 批准一条申请。只记 nodeId —— 公钥必须来自对端自己那次握手，界面不能替它预置。 */
    fun approveSecretlessPairing(nodeId: String): Boolean = false

    /** 拒绝一条申请（对端会在自己的重试窗口里放弃）。 */
    fun rejectSecretlessPairing(nodeId: String): Boolean = false

    /** 传输面装配（NodeRuntime 注入用）。 */
    fun transportFactory(): ((PeerRegistry) -> DatagramTransport)?

    /** 发现面装配（NodeRuntime 注入用）。profile 由运行时给（含运行时才知道的 luciolaPort）。 */
    fun discoveryFactory(): ((PeerRegistry, () -> Profile) -> DiscoveryEngine)?

    /**
     * 画面信道装配（NodeRuntime 注入用）。
     *
     * @param listenPort 画面会话的监听端口；null = 本机只拨不听（手机按平台能力决定）。
     * 返回 null = 本机不提供画面信道 ⇒ 运行时继续走 legacy 的 17879/17882。
     */
    fun screenChannelHostFactory(
        scope: CoroutineScope,
        listenPort: Int?,
    ): ((PeerRegistry) -> ScreenChannelHost)?
}
