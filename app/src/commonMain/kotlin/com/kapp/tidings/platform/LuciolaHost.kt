// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.platform

import com.kapp.tidings.core.transport.DatagramTransport
import com.kapp.tidings.core.transport.DiscoveryEngine
import com.kapp.tidings.core.transport.PeerRegistry
import com.kapp.tidings.core.transport.ScreenChannelHost
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.domain.LuciolaQrInfo
import kotlinx.coroutines.CoroutineScope

/**
 * 实验:luciola 传输宿主（JVM 桌面）。Android/iOS 暂不实现 —— 契约默认 null 即 legacy。
 *
 * 三个都在**宿主侧**：共享层不该知道 luciola 的 UNIFFI 绑定与密钥落盘方式。
 * 传输与发现必须**同时**给：只给一半（有加密信道却没有发现，或反之）比 legacy 还糟，
 * [com.kapp.tidings.domain.RelayController] 会拒绝这种组合。
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
