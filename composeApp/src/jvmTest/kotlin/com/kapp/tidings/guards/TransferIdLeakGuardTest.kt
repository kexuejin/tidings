// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「内部临时传输键不许漏到界面上」的跨层守卫。
 *
 * ## 为什么这两条住在 `:composeApp` 而不是 `:app`
 *
 * 它们断言的是**一件跨两层的事实**：领域侧生产这个键（`pendingTransferKey`），
 * UI 侧过滤它（`transferIdDetailLabel`）。按依赖方向，`​:composeApp` 依赖 `:app`，
 * 所以能同时读两边的只有上层 —— 放在 `:app` 的测试里，等于让下层去读上层的源码，
 * 那是反向依赖，靠 `File("..")` 的相对路径才侥幸成立。
 *
 * 搬到上层之后，读两边都是"顺着依赖方向"，路径也统一走 [GuardSourceScan]。
 */
class TransferIdLeakGuardTest {

    /**
     * 源码级守卫：详情行必须**经过**这个过滤函数，不能直接渲染 `file.transferId`。
     *
     * 只测 `transferIdDetailLabel` 本身是不够的 —— 那个函数可以存在、而详情行绕开它。
     * 这条钉住调用点；剥掉注释再匹配，避免被解释这件事的注释绊倒（本仓踩过）。
     */
    @Test
    fun theDetailRowGoesThroughTheLabelHelper() {
        val source = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopFilesPane.kt"),
        )
        assertTrue(
            source.contains("transferIdDetailLabel(file.transferId)"),
            "详情行必须经过 transferIdDetailLabel：直接渲染 file.transferId 会把内部临时键漏给用户",
        )
        assertFalse(
            source.contains("file.transferId.take(12)"),
            "详情行不能再直接截断 file.transferId —— 那正是 pending- 泄漏的写法",
        )
    }

    /**
     * 两处前缀必须一致：生产侧的临时键（`pendingTransferKey`）与过滤时用的前缀常量。
     *
     * 这是本仓点名的「多处必须一致」类纪律，类型系统表达不了，只能源码级钉住：
     * 只改一边就会让临时键重新漏到界面上，或者把真实编号也一起藏掉。
     */
    @Test
    fun theInternalPrefixIsTheSameOnBothSides() {
        val producer = GuardSourceScan.stripComments(
            GuardSourceScan.read("app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayControllerTransfersComponent.kt"),
        )
        val consumer = GuardSourceScan.stripComments(
            GuardSourceScan.read("app/src/commonMain/kotlin/com/kapp/tidings/domain/TransferIdLabel.kt"),
        )
        assertTrue(
            producer.contains("\"pending-\" + msgId"),
            "生产侧的临时键前缀变了：过滤前缀要跟着改",
        )
        assertTrue(
            consumer.contains("INTERNAL_TRANSFER_KEY_PREFIX: String = \"pending-\""),
            "过滤前缀变了：内部临时键会漏到界面上",
        )
    }
}
