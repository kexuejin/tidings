// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.ModelCatalogEntry
import com.kapp.tenon.agent.drivers.ModelCatalogState
import com.kapp.tenon.agent.drivers.ModelProbeSpec
import com.kapp.tenon.domain.DriverModelCatalogEntryUi
import com.kapp.tenon.domain.DriverModelCatalogStateUi
import com.kapp.tenon.domain.DriverModelOptionUi

/**
 * 驱动侧目录结论 → 端口侧 UI 形状的**唯一映射**（装配期纯逻辑，可合成输入测试）。
 *
 * ## 为什么映射层要再兜一次「KNOWN 必须非空」
 *
 * 探针侧已经保证「解析不出任何一条 ⇒ UNKNOWN」，但这里是 UI 选项的来源：一旦上游未来
 * 放宽，空 KNOWN 会让界面画出一个空选择行（用户看到"有目录、但一个选项都没有"）。
 * 空集不许冒充结论 —— 降级成 UNKNOWN，让 UI 走手填兜底并如实说"列不出来"。
 *
 * ## 为什么 UNKNOWN 也进 map
 *
 * 「这家读了、结论是读不到」和「这家没探」是两件事：前者要画出处出路，后者只是还没探。
 * 端口只对**被请求过的驱动**调用本映射，所以 map 里出现的键即"探过了"。
 */
internal object DriverModelCatalogMapping {

    fun toUi(entries: Map<String, ModelCatalogEntry>): Map<String, DriverModelCatalogEntryUi> =
        entries.mapValues { (_, entry) -> entry.toUi() }

    private fun ModelCatalogEntry.toUi(): DriverModelCatalogEntryUi {
        val usable = state == ModelCatalogState.KNOWN && models.isNotEmpty()
        return DriverModelCatalogEntryUi(
            driverKey = driverKey,
            state = if (usable) DriverModelCatalogStateUi.KNOWN else DriverModelCatalogStateUi.UNKNOWN,
            models = if (usable) {
                models.map { DriverModelOptionUi(id = it.id, label = it.name.ifBlank { it.id }) }
            } else {
                emptyList()
            },
            note = note,
        )
    }

    /**
     * UI 目录 → 保存校验用的已知 id 表（只收 KNOWN 且非空）。
     *
     * 缺键 = 该驱动不知道（保存期放行）——**不许**用空集表示「知道了但一个都没有」：
     * 那会把所有模型拒掉，而真实原因往往只是解析失败。
     */
    fun knownModelIds(entries: Map<String, DriverModelCatalogEntryUi>): Map<String, Set<String>> =
        entries.values
            .filter { it.state == DriverModelCatalogStateUi.KNOWN && it.models.isNotEmpty() }
            .associate { entry -> entry.driverKey to entry.models.map { it.id }.toSet() }

    /**
     * 目录的**探测规格身份**（诊断/对账用）：哪些驱动有已验证的列模型子命令。
     *
     * 只透出规格是否存在，不把 argv 形状带到 UI —— 那是驱动侧的事，端口层不复制一份。
     */
    fun probedDriverKeys(specs: Map<String, ModelProbeSpec>): Set<String> =
        specs.keys.toSet()
}
