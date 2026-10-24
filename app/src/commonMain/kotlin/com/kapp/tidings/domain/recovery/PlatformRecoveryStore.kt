// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

import com.kapp.tidings.core.persist.RecoveryIdentityPayload

/**
 * L0 的平台存活存储接缝：卸载重装后仍幸存的身份数据放在哪、怎么读写。
 *
 * 各平台的实现与它们的存活边界一一对应（方案 §6.1）：
 *  · iOS：钥匙串（本地层 + `kSecAttrSynchronizable` 同步层）；
 *  · Android：公共 Downloads 里的加密身份包（`KDF(ANDROID_ID)` 混淆），读取失败由
 *    设置页的导入入口兜底（A6）；
 *  · 桌面：没有 —— 卸载不动用户目录，身份本来就不会死，用 [NoPlatformRecovery]。
 *
 * **收养规则（Q10）由调用方执行**：本机已有 settings（不管内容是什么）就绝不调用
 * [load] 的结果去覆盖 —— 同步层/公共目录里的身份包可能是账号下另一台设备的。
 * 这个接口只负责存取，不做决策。
 */
interface PlatformRecoveryStore {

    /** 读幸存的身份数据；null = 没有（或这台上没有可读的）。 */
    fun load(): RecoveryIdentityPayload?

    /** 把当前身份写进平台存活存储（幂等；实现自管轮换）。 */
    fun save(payload: RecoveryIdentityPayload)

    /**
     * 最近一次 [load] 的人读诊断（如实、要么不存在 —— 方案 §2.5 的纪律）：
     * null = 没读过或读到了；非 null = 为什么没读到（权限、文件缺失…）。
     * 诊断页/日志可以直接展示，不参与任何决策。
     */
    val readDiagnosis: String? get() = null
}

/** 桌面与测试宿主的空实现：永远没有幸存数据，保存是 no-op。 */
data object NoPlatformRecovery : PlatformRecoveryStore {
    override fun load(): RecoveryIdentityPayload? = null
    override fun save(payload: RecoveryIdentityPayload) = Unit
}
