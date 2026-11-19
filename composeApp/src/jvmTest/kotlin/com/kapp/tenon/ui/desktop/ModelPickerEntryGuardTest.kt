// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型输入的**入口收敛**守卫（AGENTS §2.6：一个界面元素只许有一处定义）。
 *
 * ## 守的是什么
 *
 * 「模型」这一格曾经在三个地方各写一遍：团队详情弹窗、团队名册对话框、员工档案对话框。
 * 前两处还各抄了一份**跨厂商混用的写死 chip**（`claude-3-5-sonnet` / `gpt-4o` /
 * `deepseek-chat`），既不区分驱动也会过期。现在三者共用 `DriverModelPicker` 一份，
 * 选项来自各驱动当下的真实目录。
 *
 * 这条守卫钉两件事：
 * 1. 三个入口都**用** `DriverModelPicker`（谁被改回手写输入框就红）；
 * 2. 生产源码里**不许再出现**那三个写死的模型 id（抄回去就红）。
 *
 * ## 为什么只能源码级守
 *
 * Compose 的渲染结果在 Layoutlib 截图里看不到「cell 的数据来源是目录还是常量」——
 * 画出来都是几个 chip。可判定的是「谁是唯一实现」「常量还在不在」，所以守源码。
 *
 * 反向验证：把任一入口的 `DriverModelPicker(` 换回 `OutlinedTextField(` ⇒
 * [everyModelEntryUsesSharedPicker] 红；把 `"gpt-4o"` 塞回任一文件 ⇒
 * [noHardcodedModelPresetChip] 红。
 */
class ModelPickerEntryGuardTest {

    private fun live(path: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * **四个**模型输入入口。
     *
     * 收敛前它们各写一遍：员工建档（MINT）、员工档案编辑、团队详情弹窗、团队名册对话框。
     * 前两处与后两处还是**两种数据形状**（档案 vs 执行档），但「这家有什么模型」是同一个
     * 事实 —— 所以四处读同一份目录、用同一份组件。
     */
    private val entryPoints = listOf(
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/EmployeeCreateDialog.kt",
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/EmployeeEditDialog.kt",
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentTeamDetailView.kt",
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/TeamRosterDialog.kt",
    )

    @Test
    fun everyModelEntryUsesSharedPicker() {
        for (path in entryPoints) {
            val body = live(path)
            assertTrue(
                body.contains("DriverModelPicker("),
                "$path 不再使用共享的 DriverModelPicker —— 模型输入又分叉了（AGENTS §2.6）",
            )
        }
    }

    @Test
    fun noHardcodedModelPresetChip() {
        // 这三个 id 是旧硬编码 preset 的原样值。重新出现意味着又抄了一份与目录无关的选项表。
        val banned = listOf("\"claude-3-5-sonnet\"", "\"gpt-4o\"", "\"deepseek-chat\"")
        val uiRoot = "composeApp/src/commonMain/kotlin/com/kapp/tenon"
        for (path in entryPoints) {
            val body = live(path)
            for (id in banned) {
                assertFalse(
                    body.contains(id),
                    "$path 里又出现写死的模型 id $id —— 选项必须来自驱动目录（driverModelCatalog）",
                )
            }
        }
        // 顺带钉住「三个入口都在 uiRoot 下」这个前提：目录挪了这条守卫才不至于空转。
        assertTrue(
            entryPoints.all { it.startsWith(uiRoot) },
            "入口文件路径与 uiRoot 前提不一致 —— 守卫在扫一个不存在的根",
        )
    }

    @Test
    fun pickerIsTheSingleImplementation() {
        // 「唯一实现」的正面判据：DriverModelPicker 只有一个定义处。
        val picker = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/DriverModelPicker.kt"
        val body = live(picker)
        assertTrue(
            body.contains("internal fun DriverModelPicker("),
            "DriverModelPicker 的定义不在预期位置 —— 守卫的锚点失效了",
        )
    }
}
