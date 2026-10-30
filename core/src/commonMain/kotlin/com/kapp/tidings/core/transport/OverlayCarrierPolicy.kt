// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

/**
 * 承载档位（overlay-transport-design.md §2.3 的候选顺序，按接入方式枚举）。
 *
 * 判据按「承载」不按「地址」（§5.2）：同一个 IP 从不同承载进来，安全属性不同——
 * `127.0.0.1` 走 LAN 直连是「本机可达」的兜底，走 OVERLAY 背后却是一条真的出公网的隧道。
 */
enum class Carrier {
    /** 局域网直连（明文，`isLanAddress` 裁决）。 */
    LAN_DIRECT,

    /** 公网直连（TLS + 双向 SPKI pin，fail-closed）。 */
    PUBLIC_DIRECT,

    /** 嵌入式 overlay（用户态 WireGuard 边车 + 回环代理；上层仍 TLS + pin）。 */
    OVERLAY,
}

/**
 * 明文承载的最终裁决（§5.2 修法的纯函数缝）。
 *
 * ## 为什么有它：回环陷阱
 *
 * 今天的 `isPrivateOrLocal` 显式把 `127/8` 判成局域网（K1 明文长连接需要「本机可达」兜底），
 * 而 overlay 的本地代理端点恰好就在 `127.0.0.1`。若 overlay 直接复用 `isLanAddress` 的结论
 * 进明文路径，手机上一切本该被 fail-closed 拒掉的明文长连接都会因「对端是本机回环」被放行
 * —— 而那条回环背后是公网隧道（§5.1，直接违反 AGENTS.md §2.5 的裁决）。
 *
 * ## 用法
 *
 * - 现网：**没有任何调用点**（`isLanAddress` 与全部现有路径行为零变化）——
 *   它是给 M6 O1 的 [Carrier.OVERLAY] 传输预制的最后一问；
 * - 未来 OverlayTransport 拨号前以此裁决明文资格；`lanVerdict` 一律传
 *   `isLanAddress` 的原样结论，本函数负责「承载不是 LAN 直连就一票否决」。
 *
 * 反向验证（§5.2 落地纪律 2）钉在测试里：把回环特权放回去（只看 `lanVerdict`、不看承载）
 * 新测试必须变红。
 */
object OverlayCarrierPolicy {
    fun mayCarryPlaintext(carrier: Carrier, lanVerdict: Boolean): Boolean =
        carrier == Carrier.LAN_DIRECT && lanVerdict
}
