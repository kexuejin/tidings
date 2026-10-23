// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 源码级守卫（task-48 F2 的第二半）：`MessageTypes.kt` 里**每一个** `const val CAP_*`
 * 都必须在 [CapabilityInventory] 里有账。
 *
 * 为什么还要这一条：commonTest 的 [CapabilityDeclarationTest] 只能断言"台账里的每个 cap
 * 都有归属"，它**发现不了"新加了一个 cap 但忘了往台账里写"** —— 而那恰恰是今天两次事故
 * （`lnk1` / `att1`）的形状。Kotlin 的 common 代码没有反射能枚举 `const val`，所以在 JVM 上
 * 直接读一眼源码：它是"这份清单完不完整"唯一能被机器钉住的地方。
 *
 * 只读源码、不依赖构建产物、不联网；源码改名/移动会**响亮地失败**，不会静默变成空转。
 */
class CapabilityDeclarationSourceTest {

    @Test
    fun everyCapConstantInTheSourceIsAccountedFor() {
        val source = findMessageTypesSource()
        val names = Regex("""const val (CAP_[A-Z0-9_]+)""")
            .findAll(source.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            names.isNotEmpty(),
            "在 ${source.path} 里一个 CAP_* 常量都没扫到 —— 正则或路径失效了，这条守卫会变成空转",
        )
        assertEquals(
            CapabilityInventory.constants.keys,
            names,
            "MessageTypes.kt 的 CAP_* 与 CapabilityInventory 对不上：新增/删除 cap 必须同步改台账，",
        )
    }

    private fun findMessageTypesSource(): File {
        val relative = "src/commonMain/kotlin/com/kapp/tidings/core/protocol/MessageTypes.kt"
        val starts = listOfNotNull(
            System.getProperty("user.dir")?.let(::File),
            runCatching {
                File(CapabilityDeclarationSourceTest::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull(),
        )
        for (start in starts) {
            var dir: File? = start.absoluteFile
            while (dir != null) {
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
        }
        error("找不到 $relative（起点：${starts.joinToString { it.path }}）")
    }
}
