// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * KMP 的 `commonMain` 不许回流 JVM-only API。
 *
 * ## 守的是什么
 *
 * `@Volatile` 与 `java.util.concurrent.*` 写在 `commonMain` 里，在 JVM / Android 目标上
 * 编译得过（前者靠 JVM 的默认 import，后者靠 JDK），到了 Kotlin/Native 就是
 * `Unresolved reference 'java'` / `Unresolved reference 'Volatile'`。因为默认验收链
 * 只跑 JVM/Android，这类回流会**静默积累**：每次单看都是「加个并发容器」，合起来
 * 让整个 iOS 目标编译不过，而所有门禁全绿。
 *
 * 跨平台替代在仓里已有两份现成范式：`kotlin.concurrent.Volatile` 注解，
 * 以及 `kotlin.concurrent.atomics.*` / `kotlinx.atomicfu`（见 `core/build.gradle.kts`
 * 的 commonMain 依赖）。`jvmSharedMain` 不在扫描范围 —— 它按定义就是 JVM/Android
 * 共享源集，用 `java.io` / `java.security` 是正当的。
 *
 * ## 棘轮口径
 *
 * 立守卫时 `:app` / `:composeApp` 的 commonMain 里有 6 处 `ConcurrentHashMap` 以及
 * `Charsets` / `synchronized` / `java.lang.Math` 等 JVM-only 引用（EXT-10b 已清零），
 * 当时用「欠债清单，只许减」记账。现全部还清，守卫直接**禁止任何 `java.` 限定引用**，
 * 棘轮到 0：新增一处即红，不再有例外清单。
 */
class CommonMainJvmOnlyApiTest {

    /** 真正会被 Kotlin/Native 编译的共享源集。 */
    private val sharedRoots = listOf(
        "core/src/commonMain",
        "app/src/commonMain",
        "composeApp/src/commonMain",
    )

    private fun scanner(): Map<String, String> = buildMap {
        for (rel in GuardSourceScan.kotlinFiles(*sharedRoots.toTypedArray())) {
            put(rel, GuardSourceScan.read(rel))
        }
    }

    /** 剥掉注释与字符串里的 `java.` 不算：只认活代码里的限定引用。 */
    private fun javaApiHits(live: String): List<String> =
        live.lineSequence()
            .map { it.trim() }
            .filter { Regex("""\bjava\.[a-zA-Z]""").containsMatchIn(it) }
            .toList()

    @Test
    fun `commonMain 不许出现 JVM-only 的 java 限定引用`() {
        val sources = scanner()
        // 门禁对「不存在的扫描根」是静默跳过的，所以逐根数一遍分母：
        // 少一个根（改名 / 拆模块）会立刻红，而不是显示成「全绿」。
        val perRoot = sharedRoots.associateWith { root ->
            sources.keys.count { it.startsWith("$root/") }
        }
        assertTrue(
            perRoot.values.all { it > 50 },
            "有共享源集没被扫到或文件数明显不对：" + perRoot +
                " —— 扫描根改名/消失时门禁会静默跳过，宁可红也不要假绿。",
        )

        val offenders = mutableListOf<String>()
        for ((rel, text) in sources) {
            val hits = javaApiHits(GuardSourceScan.stripComments(text))
            if (hits.isNotEmpty()) offenders += "$rel ${hits.size} 处: $hits"
        }
        assertTrue(
            offenders.isEmpty(),
            "commonMain 回流了 JVM-only API（iOS 目标会编译不过）：\n" + offenders.joinToString("\n") +
                "\n用 kotlin.concurrent.Volatile / kotlin.concurrent.atomics / kotlinx.atomicfu。",
        )
    }

    @Test
    fun `Volatile 必须显式 import 跨平台版本`() {
        val offenders = mutableListOf<String>()
        for ((rel, text) in scanner()) {
            val live = GuardSourceScan.stripComments(text)
            if (live.contains("@Volatile") && !text.contains("import kotlin.concurrent.Volatile")) {
                offenders += rel
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "这些文件用了 @Volatile 却没有 import kotlin.concurrent.Volatile：" + offenders +
                " —— JVM 上靠默认 import 能过，Kotlin/Native 报 Unresolved reference。",
        )
    }
}
