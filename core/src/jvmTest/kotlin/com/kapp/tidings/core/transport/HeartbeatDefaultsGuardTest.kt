// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-G8：引擎默认节拍与文档对账（§7.10：base 5s ± 1.5s）。默认值是构造参数的
 * 私有缺省 —— JVM 源码级钉住（commonTest 没有 File；有人改默认抖动而不同步
 * 文档/镜像测试，本条红）。
 */
class HeartbeatDefaultsGuardTest {

    @Test
    fun engineDefaultsMatchTheDocumentedJitterSpread() {
        val source = java.io.File("src/commonMain/kotlin/com/kapp/tidings/core/transport/LuciolaDiscoveryEngine.kt")
            .takeIf { it.isFile }?.readText()
            ?: java.io.File("../core/src/commonMain/kotlin/com/kapp/tidings/core/transport/LuciolaDiscoveryEngine.kt").readText()
        assertTrue(
            source.contains("private val heartbeatMs: Long = 5_000,"),
            "心跳基准间隔偏离文档（5s）",
        )
        assertTrue(
            source.contains("private val heartbeatJitterMs: Long = 1_500,"),
            "心跳抖动幅度偏离文档（±1.5s）",
        )
    }
}
