// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

/**
 * 「设备接入」管理面 —— **只服务 `/v1/dev/pairing*` 这两条开发路由**。
 *
 * ## 为什么需要它
 *
 * 「无密钥配对 → 宿主登记申请 → 人批准 → 对端放行」这条链有一半在**人手上**（点批准）。
 * 没有这台管理面时，无鼠标环境（自动化、CI、我的真机排障）只能靠人肉点击，
 * 于是这条链永远「看起来通了但没人验过」。它让「批准」这个动作可以被脚本触发。
 *
 * ## 边界
 *
 * - 只读/只批**接入**，碰不到 agent 授权（那是 `AgentGrantStore`，另一套）。
 * - 路由只在 `TENON_DEV_ROUTES=1` 时注册；生产构建里这两条路径不存在。
 * - 批准只落 nodeId：公钥仍必须来自对端自己那次握手（见 `LuciolaTransportFactory`）。
 */
interface DevicePairingAdmin {
    fun deviceTrustPolicy(): String
    fun pendingPairingRequests(): Map<String, String>
    fun approveSecretlessPairing(nodeId: String): Boolean
    fun rejectSecretlessPairing(nodeId: String): Boolean
}
