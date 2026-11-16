// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.DeviceTrustPolicies

/**
 * 「设备接入」这一档的读写口 —— **只管能不能配对/连接**，与 agent 授权（谁能用我的 agent）
 * 是两套东西，别把它们并成一个开关。
 *
 * 为什么是一个窄接口而不是直接传 `RelayController`：设置页只要这五项读数与动作，
 * 拿到整个控制器会把「谁在读写信任档」这件事藏进一个万能对象里（本仓
 * `AccessChannelPort` / `GrantReplySender` 都是同一个取舍）。窄口也让预览/测试能塞假实现。
 *
 * 默认实现全部走最严档：宿主没接 luciola 时（或壳还没接线）读出来必须是 `verify`、
 * 写进去必须失败 —— 不认识的实现不该因为默认值宽松而悄悄放宽。
 */
interface DeviceTrustHandle {
    /** `verify` / `auto` / `whitelist`。 */
    fun deviceTrustPolicy(): String = DeviceTrustPolicies.VERIFY

    /** 切档；认不出的取值返回 false（不静默归一）。 */
    fun setDeviceTrustPolicy(raw: String): Boolean = false

    /** 待处理的「无密钥配对申请」：nodeId → `展示名|来源地址`。 */
    fun pendingPairingRequests(): Map<String, String> = emptyMap()

    /** 批准一条申请（只记 nodeId；信任写入发生在对端下一次自证）。 */
    fun approveSecretlessPairing(nodeId: String): Boolean = false

    /** 拒绝一条申请。 */
    fun rejectSecretlessPairing(nodeId: String): Boolean = false
}

/**
 * [DeviceTrustHandle] 的控制器适配器。
 *
 * 为什么单独一个类、而不是在 `App.kt` 里就地写匿名对象：`App.kt` 里有源码级守卫
 * （`TabChromeConsistencyGuardTest` 按「最近一个 `fun` 名」归因整屏背景/surface），
 * 在文件顶层塞几个 `override fun` 会把归因指到 `rejectSecretlessPairing` 上 —— 守卫红得
 * 莫名其妙，而真正的整屏那一段其实没变。适配器住在领域层，界面那边只剩一行 `remember`。
 */
class ControllerDeviceTrust(private val controller: RelayController) : DeviceTrustHandle {
    override fun deviceTrustPolicy(): String = controller.deviceTrustPolicy()

    override fun setDeviceTrustPolicy(raw: String): Boolean = controller.setDeviceTrustPolicy(raw)

    override fun pendingPairingRequests(): Map<String, String> = controller.pendingPairingRequests()

    override fun approveSecretlessPairing(nodeId: String): Boolean =
        controller.approveSecretlessPairing(nodeId)

    override fun rejectSecretlessPairing(nodeId: String): Boolean =
        controller.rejectSecretlessPairing(nodeId)
}
