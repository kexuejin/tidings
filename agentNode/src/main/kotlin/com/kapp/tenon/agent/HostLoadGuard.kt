// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.Limits
import java.lang.management.ManagementFactory

/**
 * 宿主自保门（T-G7，§7.12 的接收侧一半）：CPU 超阈值**拒单避让** —— 做懂礼貌的
 * 算力宿主，不透支机主电脑。
 *
 * ## 为什么是接收侧而不是心跳上报
 *
 * §7.12 的另一半（心跳上报遥测）要动 hello/presence 的线上形状（golden vector
 * 与上游对齐的产物，手改被禁止）—— 而拒单语义本来就是**接收侧**的：任务送到
 * 这台机器，这台机器自己最清楚自己多忙。V1 落接收侧（零协议变化），上报侧
 * 入账待协议面裁决。
 *
 * ## 探针
 *
 * JVM 内建 `OperatingSystemMXBean.getCpuLoad()`（0..1；刚启动可能返回负值 =
 * 样本不足，如实按「不知道」处理 —— 不知道 ≠ 拒绝）。
 */
object HostLoadGuard {

    /** @return 0..1 的系统 CPU 负载；拿不到 = null（样本不足 / 平台不支持）。 */
    fun systemCpuLoad(): Double? = runCatching {
        val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            ?: return@runCatching null
        bean.cpuLoad.takeIf { it >= 0.0 }
    }.getOrNull()

    /**
     * 拒单判定：负载超过 [Limits.HOST_CPU_REJECT] = true。null（拿不到）= 不拒
     * —— 自保门不因探针失灵而变成全拒（fail-open 于探针，fail-closed 于阈值）。
     */
    fun shouldRefuse(cpuLoad: Double?, threshold: Double = Limits.HOST_CPU_REJECT): Boolean =
        cpuLoad != null && cpuLoad > threshold
}
