// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.platform

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.transport.PeerRegistry
import com.kapp.tidings.domain.LuciolaQrInfo
import kotlinx.coroutines.CoroutineScope

/**
 * iOS 的 luciola 宿主 —— **当前是空壳，只证明依赖与接线形态成立**。
 *
 * ## 为什么先有空壳
 *
 * iOS 到现在为止完全走 legacy（`Platform.luciolaHost` 契约默认 `null`）。接 luciola 的
 * 第一步不是"把 Android 那套搬过来"，而是先证明三件事都成立：
 *
 *  1. `iosMain` 能用 **KMP 根模块**（`io.github.kexuejin:luciola`）—— 只有它发布
 *     `iosArm64` / `iosSimulatorArm64` 变体；`luciola-jvm`（桌面 dylib）和
 *     `luciola-android`（AAR）对 iOS 都不可用；
 *  2. 这个接口能在 `composeApp/iosMain` 里实现（`composeApp` 有 `api(project(":app"))`）；
 *  3. **iOS 还能编译通过**。
 *
 * 三件事里任何一件不成立，后面的传输/发现/投屏都无从谈起。
 *
 * ## 空壳为什么是安全的
 *
 * 每个方法都返回 `null`，于是：
 *
 * ```
 * luciolaMode = luciolaTransport != null && luciolaDiscovery != null   // = false
 * ```
 *
 * `RelayController` 会整体退回 legacy —— **与接入前逐字同行为**。
 * 二维码里的 luciola 段也仍是 `null`（码形态不变）。
 *
 * 所以这一步可以单独提交：它不改变任何一个字节的运行行为，只把"能不能接"这个前置问题
 * 变成一个可验证的事实。
 *
 * ## 下一步（按 goal 顺序）
 *
 * (2) `transportFactory` + `discoveryFactory` → 模拟器验 iOS ↔ 桌面互发现；
 * (3)(4)(5) 文件数据面、投屏信道、二维码配对；
 * (6) 以上全绿之后才删 legacy —— 在那之前 legacy 是 iOS 唯一能跑的路径。
 */
class IosLuciolaHost : LuciolaHost {

    override fun pairingInfo(): LuciolaQrInfo? = null

    override fun pairPeer(host: String, port: Int, info: LuciolaQrInfo): Boolean = false

    override fun configure(profile: Profile, udpPort: Int) = Unit

    override fun transportFactory(): ((PeerRegistry) -> com.kapp.tidings.core.transport.DatagramTransport)? = null

    override fun discoveryFactory(): ((PeerRegistry, () -> Profile) -> com.kapp.tidings.core.transport.DiscoveryEngine)? = null

    override fun screenChannelHostFactory(
        scope: CoroutineScope,
        listenPort: Int?,
    ): ((PeerRegistry) -> com.kapp.tidings.core.transport.ScreenChannelHost)? = null
}
