// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * **设备接入信任模式** —— 只管「这台设备能不能和本机配对/收发」，与「它能用我的哪些 agent」
 * 是**两套东西**，后者的三档在 [AgentGrantPolicies] 里。
 *
 * ## 为什么必须分成两套（本仓真机踩到过）
 *
 * 界面上一度只有一个「可信设备」页，里面装的全是 agent 授权（ro/rw/admin/owner）：
 * 用户想「让这两台设备能聊天」点进去，看到的却是「谁能调用我的 agent」，
 * 而真正决定能不能聊天的 pin / 配对密钥，那一页一个字都没提。
 * 反过来，把 [AgentGrantPolicies] 的 auto 当成「设备自动互信」也是错的 ——
 * 它只放宽 agent 的可见性，一台**没配对**的设备照样一个字节都发不进来。
 *
 * ## 三档的语义（判据在 `decideSecretlessPairing`，不在界面）
 *
 * - [VERIFY]（**默认，fail-closed**）：没有二维码密钥的入站配对一律**先问人**。
 *   扫码（码里带密钥）这条路不受影响 —— 它本来就是带外证明。
 * - [AUTO]：**同一网段**内、没有二维码的入站配对直接放行（TOFU 首次固定）。
 *   跨网段仍然要问人：公网上「谁先连进来谁可信」是真实风险，不是理论。
 * - [WHITELIST]：只有已被显式信任过的设备能连，其余直接拒，连问都不问。
 *
 * 默认必须是 [VERIFY]：未知取值、配置写坏、旧库没有这个字段，全部归一到这里。
 * 「读不出来当宽松」在本仓是明确禁止的（见 `AgentGrants.failClosed` 的同款教训）。
 */
public object DeviceTrustPolicies {
    /** 需验证：无密钥的入站配对要人点一次（默认）。 */
    public const val VERIFY: String = "verify"

    /** 自动信任：同网段无密钥入站直接放行；跨网段仍要问人。 */
    public const val AUTO: String = "auto"

    /** 白名单：只放已信任的设备，其余直接拒。 */
    public const val WHITELIST: String = "whitelist"

    public val VALUES: List<String> = listOf(VERIFY, AUTO, WHITELIST)

    /**
     * 把任意存量/协议取值归一成三档之一；**认不出就是 [VERIFY]**。
     *
     * 不做小写化之后的模糊匹配：协议取值就是这三个小写串，多一种写法就是多一条
     * 「界面切到了 A、判据读成了 B」的静默（`AgentPromptAdmission` 里那次
     * `byDevice ↔ bydevice` 已经栽过一回）。
     */
    public fun normalize(raw: String?): String {
        val trimmed = raw?.trim()
        return if (trimmed != null && trimmed in VALUES) trimmed else VERIFY
    }
}
