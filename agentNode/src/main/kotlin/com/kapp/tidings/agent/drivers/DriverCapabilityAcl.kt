// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

/**
 * 宿主侧的能力 ACL（M2 收编，方案 §2.4）。
 *
 * ## 为什么它住在宿主而不是驱动上
 *
 * 能力白名单是「这台机器允许哪些能力被用」的**宿主治理状态**（手机端远程治理下发后
 * 即时生效）。此前它散在每个驱动的 `activeAcl` 私有集合里 —— 三个驱动三份同构实现，
 * 而进程外插件驱动（ExternalPluginDriver）根本没法把「本地内存里的一个集合」透传给
 * 另一个进程。收编到宿主之后：
 *
 *  - 名单只有这一份，按 driverId 分桶；
 *  - 插件驱动**永远不碰它**（ACL 不出宿主进程）；
 *  - 仍要在自己执行路径里尊重名单的内置驱动（native 的 bash 门），经构造注入的
 *    谓词读这份状态，而不是自己再存一份。
 *
 * ## 缺省语义（有意放行）
 *
 * 某个驱动没有任何覆盖记录时 [allows] 放行它声明的一切 —— 这与收编前各驱动
 * 自带默认名单的行为一致（native 声明的四个能力默认可用）。治理动作只会**收紧**
 * （update 传入的集合就是全部允许项，不在集合里的一律拒绝）。这与
 * AgentExecPolicyStore 的「缺省 = 最严」不冲突：那条管的是「谁能让 agent 干活」
 * （执行范围），这条管的是「agent 干活时能用哪些能力」，覆盖缺席时以驱动声明为准。
 */
class DriverCapabilityAcl {

    /** driverId → 覆盖后的允许集合。缺席 = 该驱动无覆盖（按声明放行）。 */
    private val overrides = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * 写入一个驱动的允许名单（**替换**语义，与原驱动内 `clear + addAll` 一致）：
 * 手机端下发的是完整名单，不是增量。
     */
    fun update(driverId: String, enabledCapabilityIds: Set<String>) {
        overrides[driverId] = enabledCapabilityIds.toSet()
    }

    /** 该能力当前是否被允许。无覆盖记录 = 放行（见类 KDoc 的缺省语义）。 */
    fun allows(driverId: String, capabilityId: String): Boolean {
        val enabled = overrides[driverId] ?: return true
        return capabilityId in enabled
    }
}
